using System.Diagnostics;

namespace NASMount.LockBridge;

/// <summary>
/// Google Drive resumable chunk uploader. Windows rounds WinFsp allocation
/// size up to 4K, so total size remains unknown until final Cleanup. Send
/// acknowledged 1MiB non-final chunks with Content-Range */*, and retain
/// the last chunk until Cleanup supplies the true total length.
/// The separate local .data + JSON recovery journal remains authoritative.
/// </summary>
internal sealed class DirectGoogleProgressiveUpload : IDisposable
{
    private readonly int _chunk;
    private readonly object _gate = new();
    private readonly CancellationTokenSource _cancel = new();
    private TaskCompletionSource<bool>? _next;
    private readonly Task<GoogleDirectApiClient.Item> _task;
    private readonly GoogleDirectApiClient _api;
    private readonly string _parent, _name, _driveId, _path;
    private bool _finish;
    private bool _disposed;
    private long _written;
    private long _acknowledged;

    internal long Capacity { get; }
    internal long Written => Interlocked.Read(ref _written);
    internal long Acknowledged => Interlocked.Read(ref _acknowledged);
    internal bool Failed => _task.IsFaulted || _task.IsCanceled;
    internal string? FailureCategory =>
        _task.Exception?.GetBaseException().GetType().Name;

    internal DirectGoogleProgressiveUpload(GoogleDirectApiClient api,
        string parentId, string name, string generatedId,
        string localPath, long allocation)
    {
        if (allocation < 2 * 1024 * 1024)
            throw new ArgumentOutOfRangeException(nameof(allocation));
        _api = api;
        _parent = parentId;
        _name = name;
        _driveId = generatedId;
        _path = localPath;
        Capacity = allocation;
        _chunk = allocation >= 1024L * 1024 * 1024
            ? 8 * 1024 * 1024
            : allocation >= 64L * 1024 * 1024
                ? 4 * 1024 * 1024
                : 1024 * 1024;
        _task = Task.Run(() => RunAsync(_cancel.Token));
    }

    private async Task<GoogleDirectApiClient.Item> RunAsync(
        CancellationToken cancel)
    {
        Uri session = await _api.BeginProgressiveSessionAsync(
            _parent, _name, _driveId, cancel).ConfigureAwait(false);
        using var input = new FileStream(_path, FileMode.Open, FileAccess.Read,
            FileShare.ReadWrite, 128 * 1024,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        long position = 0;
        while (true)
        {
            var (count, finalLength) =
                await WaitReadyAsync(position, cancel).ConfigureAwait(false);
            byte[] bytes = new byte[count];
            input.Position = position;
            int read = 0;
            while (read < count)
            {
                int got = await input.ReadAsync(bytes.AsMemory(read, count - read),
                    cancel).ConfigureAwait(false);
                if (got == 0)
                    throw new IOException("Google streamed source file was truncated");
                read += got;
            }
            var (acknowledged, result) = await _api.SendProgressiveChunkAsync(
                session, _driveId, position, bytes, finalLength, cancel)
                .ConfigureAwait(false);
            if (acknowledged != position + count)
                throw new IOException("Google progressive chunk was not fully confirmed");
            position = acknowledged;
            lock (_gate)
            {
                Interlocked.Exchange(ref _acknowledged, position);
                Monitor.PulseAll(_gate);
            }
            if (result is not null) return result;
        }
    }

    private async Task<(int Count, long? FinalLength)> WaitReadyAsync(
        long position, CancellationToken token)
    {
        while (true)
        {
            Task pulse;
            lock (_gate)
            {
                if (_disposed) throw new OperationCanceledException();
                long available = _written - position;
                if (available < 0)
                    throw new IOException("Google progressive byte position invalid");
                // An extra byte after a whole chunk proves it is NOT the
                // final chunk; never complete the remote object early.
                if (!_finish && available > _chunk)
                    return (_chunk, null);
                if (_finish && available > 0)
                    return ((int)Math.Min(_chunk, available),
                        available <= _chunk ? _written : null);
                _next ??= new TaskCompletionSource<bool>(
                    TaskCreationOptions.RunContinuationsAsynchronously);
                pulse = _next.Task;
            }
            await pulse.WaitAsync(token).ConfigureAwait(false);
        }
    }

    internal bool Publish(long position, int length)
    {
        lock (_gate)
        {
            if (_disposed || position != _written || length < 0 ||
                position + length > Capacity)
                return false;
            _written += length;
            _next?.TrySetResult(true);
            _next = null;
            return true;
        }
    }

    // Explorer's native CopyFileEx progress must follow confirmed Google
    // resumable chunk ACKs. The final ≤1MiB chunk is withheld until Close.
    internal bool WaitForSend(long endOfWrite, TimeSpan timeout)
    {
        long target = Math.Max(0, endOfWrite - _chunk);
        var watch = Stopwatch.StartNew();
        lock (_gate)
        {
            while (_acknowledged < target && !_disposed &&
                   !_task.IsCompleted && watch.Elapsed < timeout)
            {
                TimeSpan remaining = timeout - watch.Elapsed;
                Monitor.Wait(_gate, remaining > TimeSpan.FromMilliseconds(100)
                    ? TimeSpan.FromMilliseconds(100) : remaining);
            }
            return _acknowledged >= target;
        }
    }

    internal GoogleDirectApiClient.Item Complete(long exactLength)
    {
        lock (_gate)
        {
            if (_written != exactLength || exactLength < 2 * 1024 * 1024)
                throw new IOException("Google progressive copy length was incomplete");
            _finish = true;
            _next?.TrySetResult(true);
            _next = null;
        }
        return _task.WaitAsync(TimeSpan.FromMinutes(5))
            .GetAwaiter().GetResult();
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            _cancel.Cancel();
            _next?.TrySetCanceled();
            Monitor.PulseAll(_gate);
        }
        try { _task.Wait(TimeSpan.FromSeconds(2)); }
        catch (AggregateException) { }
        _cancel.Dispose();
    }
}
