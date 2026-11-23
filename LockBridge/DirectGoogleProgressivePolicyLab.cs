using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount.LockBridge;

/// <summary>Slow Google resumable-upload transport, with no real credentials.
/// Verifies bytes are sent across multiple Windows writes, not at Cleanup.</summary>
internal static class DirectGoogleProgressivePolicyLab
{
    private sealed class SlowWriteStream(Stream inner, int delayMs) : Stream
    {
        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }
        public override void Flush() => inner.Flush();
        public override Task FlushAsync(CancellationToken cancel) =>
            inner.FlushAsync(cancel);
        public override void Write(byte[] buffer, int offset, int count) =>
            inner.Write(buffer, offset, count);
        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer,
            CancellationToken cancellationToken = default)
        {
            await Task.Delay(delayMs, cancellationToken).ConfigureAwait(false);
            await inner.WriteAsync(buffer, cancellationToken).ConfigureAwait(false);
        }
        public override Task WriteAsync(byte[] buffer, int offset, int count,
            CancellationToken cancellationToken) =>
            WriteAsync(buffer.AsMemory(offset, count), cancellationToken).AsTask();
        public override int Read(byte[] buffer, int offset, int count) =>
            throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) =>
            throw new NotSupportedException();
        public override void SetLength(long value) =>
            throw new NotSupportedException();
    }

    private sealed class MockGoogle : HttpMessageHandler
    {
        internal int Started, Completed;
        internal byte[]? UploadedBytes;
        internal int DelayMs = 85;
        private MemoryStream _session = new();

        private static HttpResponseMessage Reply(object value,
            HttpStatusCode status = HttpStatusCode.OK) =>
            new(status)
            {
                Content = new StringContent(JsonSerializer.Serialize(value),
                    Encoding.UTF8, "application/json")
            };

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var url = request.RequestUri!;
            if (url.AbsolutePath.EndsWith("/files/generateIds") &&
                request.Method == HttpMethod.Get)
                return Reply(new { ids = new[] { "newprogress_01" } });
            if (url.AbsolutePath.EndsWith("/drive/v3/files") &&
                request.Method == HttpMethod.Get)
                return Reply(new { files = Array.Empty<object>() });
            if (url.AbsolutePath.EndsWith("/files/newprogress_01") &&
                request.Method == HttpMethod.Get)
                return UploadedBytes is null
                    ? Reply(new { error = new { code = 404 } },
                        HttpStatusCode.NotFound)
                    : FileMeta();
            if (url.AbsolutePath.EndsWith("/upload/drive/v3/files") &&
                request.Method == HttpMethod.Post)
            {
                Interlocked.Increment(ref Started);
                _session.Dispose();
                _session = new MemoryStream();
                var started = Reply(new { });
                started.Headers.Location =
                    new Uri("http://127.0.0.1:19492/session/progress");
                return started;
            }
            if (url.AbsolutePath == "/session/progress" &&
                request.Method == HttpMethod.Put)
            {
                var range = request.Content!.Headers.ContentRange ??
                    throw new IOException("Progressive test lacked Content-Range");
                if (range.From != _session.Length)
                    throw new IOException("Unexpected Google chunk offset");
                using var slow = new SlowWriteStream(_session, DelayMs);
                await request.Content!.CopyToAsync(slow, null,
                    cancellationToken).ConfigureAwait(false);
                if (!range.HasLength)
                {
                    var incomplete = Reply(new { }, (HttpStatusCode)308);
                    incomplete.Headers.TryAddWithoutValidation("Range",
                        "bytes=0-" + (_session.Length - 1));
                    return incomplete;
                }
                if (range.Length != _session.Length)
                    throw new IOException("Progressive final size mismatch");
                UploadedBytes = _session.ToArray();
                Interlocked.Increment(ref Completed);
                return FileMeta();
            }
            throw new IOException("Unexpected Google progressive mock operation");
        }

        private HttpResponseMessage FileMeta() =>
            Reply(new
            {
                id = "newprogress_01", name = "progress-6mb.zip",
                mimeType = "application/octet-stream",
                size = UploadedBytes!.Length.ToString(),
                md5Checksum = Convert.ToHexString(MD5.HashData(UploadedBytes))
                    .ToLowerInvariant(), version = "1"
            });
    }

    internal static void Run()
    {
        string root = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleProgressive_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            using var fake = new MockGoogle();
            using var http = new HttpClient(fake);
            var api = new GoogleDirectApiClient(http,
                _ => Task.FromResult("TEST_TOKEN_ONLY"),
                new Uri("http://127.0.0.1:19492/drive/v3/"));
            const int size = 6 * 1024 * 1024 + 123;
            byte[] source = new byte[size];
            new Random(20261123).NextBytes(source);
            var timer = Stopwatch.StartNew();
            using (var stage = new DirectGoogleWriteStage(api, root,
                "root", "progress-6mb.zip"))
            {
                stage.PlanCopy(size);
                const int chunk = 128 * 1024;
                for (int offset = 0; offset < source.Length; offset += chunk)
                {
                    int bytes = Math.Min(chunk, source.Length - offset);
                    stage.Write((ulong)offset, source.AsSpan(offset, bytes).ToArray(),
                        append: false);
                    if (fake.Completed != 0)
                        throw new IOException(
                            "Google upload completed before WinFsp Cleanup");
                    if (offset > 2 * 1024 * 1024 && fake.Started == 0)
                        throw new IOException(
                            "Google upload did not start during CopyFile writes");
                }
                stage.FlushLocal();
                if (fake.Completed != 0)
                    throw new IOException("Google Flush prematurely completed upload");
                if (timer.ElapsedMilliseconds < 100)
                    throw new IOException(
                        "Google copy writes returned before network backpressure");
                Console.WriteLine("GOOGLE_PROGRESSIVE_COPY_DURING_WRITE_PASS ms=" +
                    timer.ElapsedMilliseconds);
                stage.Commit();
                if (!stage.Uploaded || fake.Completed != 1 ||
                    fake.Started != 1 || fake.UploadedBytes is null ||
                    !fake.UploadedBytes.SequenceEqual(source))
                    throw new IOException(
                        "Google progressive copy did not commit exact bytes once");
                Console.WriteLine("GOOGLE_PROGRESSIVE_FINAL_COMMIT_MD5_PASS");
            }
            if (Directory.GetFiles(root, "*.json").Length != 0)
                throw new IOException("Committed Google stage journal was not released");

            // A cancelled, partially written file must NEVER be published.
            using (var abandoned = new DirectGoogleWriteStage(api, root,
                "root", "abandoned.zip"))
            {
                abandoned.PlanCopy(size);
                abandoned.Write(0, source.AsSpan(0, 256 * 1024).ToArray(),
                    append: false);
            }
            if (fake.Completed != 1 ||
                Directory.GetFiles(root, "*.json").Length != 1 ||
                Directory.GetFiles(root, "*.data").Length != 1)
                throw new IOException(
                    "Interrupted Google copy was published or lost local recovery");
            Console.WriteLine("GOOGLE_PROGRESSIVE_CANCEL_LOCAL_RECOVERY_PASS");
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
