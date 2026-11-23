using System.Diagnostics;
using System.Net;
using System.Net.Http;

namespace NASMount.LockBridge;

/// <summary>
/// Streams a newly created, sequentially-written file to a private temporary
/// WebDAV object while Windows is still copying its source bytes.
///
/// The caller retains the durable local journal. This class never publishes
/// the final destination: a guarded server MOVE is only attempted after the
/// entire temporary upload succeeds. Non-sequential writes are rejected so
/// the caller can fall back to the existing atomic journal/PUT path.
/// </summary>
internal sealed class DirectWebDavProgressiveUpload : IDisposable
{
    private readonly object _gate = new();
    private readonly CancellationTokenSource _cancel = new();
    private readonly Task _upload;
    private long _written;
    private long _sent;
    private string? _putResponseEtag;
    private bool _disposed;

    internal string StagePath { get; }
    internal long Length { get; }
    internal long Written => Interlocked.Read(ref _written);
    internal long Sent => Interlocked.Read(ref _sent);
    // Some NAS servers return a strong ETag directly with successful PUT.
    // Reusing it avoids an otherwise redundant PROPFIND after all bytes
    // have already been uploaded. Weak/absent ETags still use PROPFIND.
    internal string? PutResponseEtag => _putResponseEtag;

    internal DirectWebDavProgressiveUpload(HttpClient http, Uri stageUri,
        string stagePath, string localPath, long length)
    {
        if (length <= 0) throw new ArgumentOutOfRangeException(nameof(length));
        StagePath = stagePath;
        Length = length;
        _upload = Task.Run(async () =>
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, stageUri);
            request.Headers.TryAddWithoutValidation("If-None-Match", "*");
            request.Content = new FileBody(this, localPath);
            using var response = await http.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, _cancel.Token)
                .ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw new IOException("Progressive stage PUT rejected HTTP " +
                    (int)response.StatusCode);
            _putResponseEtag = response.Headers.ETag?.ToString();
        });
    }

    // A fixed Content-Length avoids Transfer-Encoding: chunked. Several NAS
    // WebDAV servers reject chunked uploads, but accept streaming PUT bodies
    // with a known file length. The length comes from WinFsp SetFileSize.
    private sealed class FileBody(DirectWebDavProgressiveUpload owner,
        string localPath) : HttpContent
    {
        protected override bool TryComputeLength(out long length)
        {
            length = owner.Length;
            return true;
        }

        protected override Task SerializeToStreamAsync(Stream output,
            TransportContext? context) =>
            SerializeToStreamAsync(output, context, owner._cancel.Token);

        protected override async Task SerializeToStreamAsync(Stream output,
            TransportContext? context, CancellationToken cancellationToken)
        {
            using var read = new FileStream(localPath, FileMode.Open,
                FileAccess.Read, FileShare.ReadWrite, 128 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            byte[] buffer = new byte[128 * 1024];
            long offset = 0;
            while (offset < owner.Length)
            {
                long available = await owner.WaitForBytesAsync(offset,
                    cancellationToken).ConfigureAwait(false);
                int count = (int)Math.Min(buffer.Length,
                    Math.Min(owner.Length - offset, available - offset));
                read.Position = offset;
                int received = 0;
                while (received < count)
                {
                    int got = await read.ReadAsync(
                        buffer.AsMemory(received, count - received),
                        cancellationToken).ConfigureAwait(false);
                    if (got == 0)
                        throw new IOException("Progressive stage source truncated");
                    received += got;
                }
                await output.WriteAsync(buffer.AsMemory(0, count), cancellationToken)
                    .ConfigureAwait(false);
                offset += count;
                lock (owner._gate)
                {
                    Interlocked.Exchange(ref owner._sent, offset);
                    Monitor.PulseAll(owner._gate);
                }
            }
        }
    }

    private async Task<long> WaitForBytesAsync(long offset,
        CancellationToken token)
    {
        while (true)
        {
            Task next;
            lock (_gate)
            {
                if (_written > offset) return _written;
                if (_disposed) throw new OperationCanceledException();
                _available ??= NewSignal();
                next = _available.Task;
            }
            await next.WaitAsync(token).ConfigureAwait(false);
        }
    }

    private TaskCompletionSource<bool>? _available;
    private static TaskCompletionSource<bool> NewSignal() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    internal bool Publish(long offset, int length)
    {
        lock (_gate)
        {
            if (_disposed || offset != _written || length < 0 ||
                offset + length > Length)
                return false;
            _written += length;
            _available?.TrySetResult(true);
            _available = null;
            Monitor.PulseAll(_gate);
            return true;
        }
    }

    // WinFsp may only acknowledge bytes after they have moved beyond the
    // local cache into the HTTP socket. This back-pressure lets Explorer's
    // native copy dialog advance across the transfer, not jump straight to
    // 99% as soon as the local journal receives the whole file.
    internal bool WaitForSent(long target, TimeSpan timeout)
    {
        var timer = Stopwatch.StartNew();
        lock (_gate)
        {
            while (_sent < target && !_disposed &&
                   !_upload.IsCompleted && timer.Elapsed < timeout)
            {
                var left = timeout - timer.Elapsed;
                Monitor.Wait(_gate, left > TimeSpan.FromMilliseconds(100)
                    ? TimeSpan.FromMilliseconds(100) : left);
            }
            return _sent >= target;
        }
    }

    internal void Complete()
    {
        if (Written != Length)
            throw new IOException("Progressive stage was not completely written");
        _upload.GetAwaiter().GetResult();
    }

    internal void Cancel()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _cancel.Cancel();
            _available?.TrySetCanceled();
            Monitor.PulseAll(_gate);
        }
        try { _upload.Wait(TimeSpan.FromSeconds(3)); }
        catch (AggregateException) { }
    }

    public void Dispose()
    {
        Cancel();
        _cancel.Dispose();
    }
}
