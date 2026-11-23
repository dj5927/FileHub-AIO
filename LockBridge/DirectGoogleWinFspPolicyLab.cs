using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Fsp;

namespace NASMount.LockBridge;

/// <summary>Actual Windows/WinFsp integration with a fake Google REST backend.
/// No real account, rclone process, user files or credentials are used.</summary>
internal static class DirectGoogleWinFspPolicyLab
{
    private sealed class MockDriveHandler : HttpMessageHandler
    {
        internal int SlowUploadDelayMs;
        private MemoryStream _newUploadParts = new();
        private sealed class DelayedSink(Stream inner, int delayMs) : Stream
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
            public override int Read(byte[] b, int offset, int count) =>
                throw new NotSupportedException();
            public override long Seek(long offset, SeekOrigin origin) =>
                throw new NotSupportedException();
            public override void SetLength(long size) =>
                throw new NotSupportedException();
            public override void Write(byte[] b, int offset, int count) =>
                inner.Write(b, offset, count);
            public override async ValueTask WriteAsync(
                ReadOnlyMemory<byte> b, CancellationToken token = default)
            {
                await Task.Delay(delayMs, token).ConfigureAwait(false);
                await inner.WriteAsync(b, token).ConfigureAwait(false);
            }
            public override Task WriteAsync(byte[] b, int offset,
                int count, CancellationToken token) =>
                WriteAsync(b.AsMemory(offset, count), token).AsTask();
        }
        internal string FolderName = "";
        internal string SourceName = "hello.txt";
        internal bool SourceTrashed;
        internal int MediaRequests;
        internal byte[]? UploadedContent;
        internal int UploadInitiations;
        internal int UploadCompletions;
        internal bool FailGenerateId;
        internal byte[] SourceContent = Encoding.ASCII.GetBytes("Hello from API!");
        internal string SourceRevision = "rev_01";
        internal bool SourcePinned;
        internal int PinnedRevisions;
        internal bool FailPin;
        internal int ExistingCompletions;
        internal int QuotaRequests;
        internal bool UnlimitedQuota;
        private object SourceMeta() => new
        {
            id = "file_01", name = SourceName, mimeType = "text/plain",
            size = SourceContent.Length.ToString(),
            version = SourceRevision,
            headRevisionId = SourceRevision,
            md5Checksum = Convert.ToHexString(
                System.Security.Cryptography.MD5.HashData(SourceContent)).ToLowerInvariant(),
            modifiedTime = "2026-01-01T00:00:00Z"
        };
        private static HttpResponseMessage Json(string body, HttpStatusCode code = HttpStatusCode.OK)
            => new(code) { Content = new StringContent(body, Encoding.UTF8, "application/json") };

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            if (request.RequestUri?.Host != "127.0.0.1" ||
                request.Headers.Authorization?.Parameter != "OFFLINE_GOOGLE_TOKEN")
                throw new InvalidOperationException("Google WinFsp pilot escaped mock API");
            var url = request.RequestUri;
            if (request.Method == HttpMethod.Get &&
                url.AbsolutePath.EndsWith("/about", StringComparison.Ordinal))
            {
                Interlocked.Increment(ref QuotaRequests);
                if (!Uri.UnescapeDataString(url.Query).Contains(
                        "storageQuota(limit,usage)", StringComparison.Ordinal))
                    throw new IOException("Google quota request omitted exact About fields");
                return Task.FromResult(Json(UnlimitedQuota
                    ? """{"storageQuota":{"usage":"68000000000"}}"""
                    : """{"storageQuota":{"limit":"5000000000000","usage":"68000000000"}}"""));
            }
            if (request.Method == HttpMethod.Get &&
                url.AbsolutePath.EndsWith("/files/generateIds"))
                return Task.FromResult(FailGenerateId
                    ? Json("""{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""",
                        HttpStatusCode.Forbidden)
                    : Json("""{"ids":["new_drive_01"]}"""));
            if (request.Method == HttpMethod.Patch &&
                url.AbsolutePath.Contains("/files/file_01/revisions/", StringComparison.Ordinal))
            {
                if (FailPin)
                    return Task.FromResult(Json(
                        """{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""",
                        HttpStatusCode.Forbidden));
                if (!url.AbsolutePath.EndsWith("/" + SourceRevision,
                    StringComparison.Ordinal))
                    return Task.FromResult(Json("{}", HttpStatusCode.Conflict));
                SourcePinned = true;
                Interlocked.Increment(ref PinnedRevisions);
                return Task.FromResult(Json(System.Text.Json.JsonSerializer.Serialize(
                    new { id = SourceRevision, keepForever = true })));
            }
            if (request.Method == HttpMethod.Patch &&
                url.AbsolutePath.EndsWith("/upload/drive/v3/files/file_01"))
            {
                if (!SourcePinned)
                    throw new InvalidOperationException("Existing Google upload was not backed up");
                var started = Json("{}");
                started.Headers.Location = new Uri("http://127.0.0.1:19490/session/existing_file_01");
                return Task.FromResult(started);
            }
            if (request.Method == HttpMethod.Put &&
                url.AbsolutePath == "/session/existing_file_01")
            {
                SourceContent = request.Content!.ReadAsByteArrayAsync(cancellationToken)
                    .GetAwaiter().GetResult();
                int next = Interlocked.Increment(ref ExistingCompletions) + 1;
                SourceRevision = "rev_" + next.ToString("D2");
                SourcePinned = false;
                return Task.FromResult(Json(System.Text.Json.JsonSerializer.Serialize(SourceMeta())));
            }
            if (request.Method == HttpMethod.Get &&
                url.AbsolutePath.EndsWith("/files/new_drive_01"))
            {
                if (UploadedContent is null)
                    return Task.FromResult(Json("""{"error":{"code":404}}""", HttpStatusCode.NotFound));
                string md5 = Convert.ToHexString(
                    System.Security.Cryptography.MD5.HashData(UploadedContent))
                    .ToLowerInvariant();
                return Task.FromResult(Json(System.Text.Json.JsonSerializer.Serialize(
                    new { id = "new_drive_01", name = "upload-from-pc.txt",
                        mimeType = "application/octet-stream", size = UploadedContent.Length.ToString(),
                        md5Checksum = md5, version = "1" })));
            }
            if (request.Method == HttpMethod.Post &&
                url.AbsolutePath.EndsWith("/upload/drive/v3/files"))
            {
                Interlocked.Increment(ref UploadInitiations);
                _newUploadParts.Dispose();
                _newUploadParts = new MemoryStream();
                var response = Json("{}", HttpStatusCode.OK);
                response.Headers.Location = new Uri("http://127.0.0.1:19490/session/new_drive_01");
                return Task.FromResult(response);
            }
            if (request.Method == HttpMethod.Put &&
                url.AbsolutePath == "/session/new_drive_01")
            {
                var range = request.Content!.Headers.ContentRange ??
                    throw new IOException("Google mock missing Content-Range");
                if (range.From != _newUploadParts.Length)
                    throw new IOException("Google mock chunk offset mismatch");
                if (SlowUploadDelayMs > 0)
                {
                    using var slow = new DelayedSink(_newUploadParts, SlowUploadDelayMs);
                    request.Content!.CopyToAsync(slow, null,
                        cancellationToken).GetAwaiter().GetResult();
                }
                else
                    request.Content!.CopyToAsync(_newUploadParts, null,
                        cancellationToken).GetAwaiter().GetResult();
                if (!range.HasLength || range.To + 1 < range.Length)
                {
                    var pending = Json("{}", (HttpStatusCode)308);
                    pending.Headers.TryAddWithoutValidation("Range",
                        "bytes=0-" + (_newUploadParts.Length - 1));
                    return Task.FromResult(pending);
                }
                if (range.Length != _newUploadParts.Length)
                    throw new IOException("Google mock final size mismatch");
                UploadedContent = _newUploadParts.ToArray();
                Interlocked.Increment(ref UploadCompletions);
                string md5 = Convert.ToHexString(
                    System.Security.Cryptography.MD5.HashData(UploadedContent))
                    .ToLowerInvariant();
                return Task.FromResult(Json(System.Text.Json.JsonSerializer.Serialize(
                    new { id = "new_drive_01", name = "upload-from-pc.txt",
                        mimeType = "application/octet-stream", size = UploadedContent.Length.ToString(),
                        md5Checksum = md5, version = "1" })));
            }
            if (request.Method == HttpMethod.Get &&
                url.AbsolutePath.EndsWith("/files"))
            {
                bool nested = Uri.UnescapeDataString(url.Query).Contains(
                    "'folder_01' in parents", StringComparison.Ordinal);
                string result = System.Text.Json.JsonSerializer.Serialize(new
                {
                    files = nested ? Array.Empty<object>() :
                        FolderName.Length == 0 ? new object[]
                        {
                            SourceMeta()
                        }.Where(_ => !SourceTrashed).Concat(UploadedContent is null ? Array.Empty<object>() :
                            [new { id = "new_drive_01", name = "upload-from-pc.txt",
                                mimeType = "application/octet-stream",
                                size = UploadedContent.Length.ToString(), version = "1" }]).ToArray()
                        : new object[]
                        {
                            SourceMeta(),
                            new { id = "folder_01", name = FolderName,
                                mimeType = GoogleDirectApiClient.FolderMime, version = "1" }
                        }.Where(item => !SourceTrashed ||
                            !System.Text.Json.JsonSerializer.Serialize(item).Contains("\"file_01\""))
                          .Concat(UploadedContent is null ? Array.Empty<object>() :
                            [new { id = "new_drive_01", name = "upload-from-pc.txt",
                                mimeType = "application/octet-stream",
                                size = UploadedContent.Length.ToString(), version = "1" }]).ToArray()
                });
                return Task.FromResult(Json(result));
            }
            if (request.Method == HttpMethod.Get &&
                url.AbsolutePath.EndsWith("/files/file_01"))
            {
                if (!url.Query.Contains("alt=media"))
                    return Task.FromResult(Json(System.Text.Json.JsonSerializer.Serialize(
                        SourceMeta())));
                Interlocked.Increment(ref MediaRequests);
                if (request.Headers.Range is not { } range)
                    throw new InvalidOperationException("Google WinFsp media read lacked range");
                byte[] data = SourceContent;
                var bounds = range.Ranges.First();
                int first = checked((int)(bounds.From ?? 0));
                int last = checked((int)Math.Min(bounds.To ?? (data.Length - 1),
                    data.Length - 1));
                var slice = data.Skip(first).Take(Math.Max(0, last - first + 1))
                    .ToArray();
                var reply = new HttpResponseMessage(HttpStatusCode.PartialContent)
                { Content = new ByteArrayContent(slice) };
                reply.Content.Headers.ContentRange =
                    new ContentRangeHeaderValue(first, last, data.Length);
                return Task.FromResult(reply);
            }
            if (request.Method == HttpMethod.Patch &&
                url.AbsolutePath.EndsWith("/files/file_01"))
            {
                using var body = System.Text.Json.JsonDocument.Parse(
                    request.Content!.ReadAsStringAsync(cancellationToken)
                        .GetAwaiter().GetResult());
                if (body.RootElement.TryGetProperty("name", out var rename))
                    SourceName = rename.GetString() ?? SourceName;
                if (body.RootElement.TryGetProperty("trashed", out var trash) &&
                    trash.GetBoolean())
                    SourceTrashed = true;
                return Task.FromResult(Json(System.Text.Json.JsonSerializer.Serialize(
                    new { id = "file_01", name = SourceName,
                        mimeType = "text/plain", version = "1" })));
            }
            if (request.Method == HttpMethod.Post &&
                url.AbsolutePath.EndsWith("/files"))
            {
                FolderName = "새폴더";
                return Task.FromResult(Json(
                    """{"id":"folder_01","name":"새폴더","mimeType":"application/vnd.google-apps.folder","version":"1"}"""));
            }
            if (request.Method == HttpMethod.Patch &&
                url.AbsolutePath.EndsWith("/files/folder_01"))
            {
                FolderName = "새이름";
                return Task.FromResult(Json(
                    """{"id":"folder_01","name":"새이름","mimeType":"application/vnd.google-apps.folder","version":"2"}"""));
            }
            throw new InvalidOperationException("Unknown Google pilot mock request");
        }
    }

    private delegate uint CopyProgress(long totalBytes, long transferred,
        long streamBytes, long streamTransferred, uint streamIndex,
        uint callbackReason, IntPtr source, IntPtr target, IntPtr data);

    [System.Runtime.InteropServices.DllImport("kernel32.dll",
        CharSet = System.Runtime.InteropServices.CharSet.Unicode,
        SetLastError = true)]
    private static extern bool CopyFileEx(string source, string target,
        CopyProgress callback, IntPtr data, ref int cancel, uint flags);

    internal static void RunCopyProgress()
    {
        using var fake = new MockDriveHandler { SlowUploadDelayMs = 80 };
        using var http = new HttpClient(fake);
        var api = new GoogleDirectApiClient(http,
            _ => Task.FromResult("OFFLINE_GOOGLE_TOKEN"),
            new Uri("http://127.0.0.1:19490/drive/v3/"));
        string isolated = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleWinFspProgress_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(isolated);
        try
        {
            char letter = Enumerable.Range('T', 7).Select(n => (char)n)
                .FirstOrDefault(value => !Environment.GetLogicalDrives()
                    .Any(path => path.StartsWith(value + ":",
                        StringComparison.OrdinalIgnoreCase)));
            if (letter == default)
                throw new IOException("No free drive letter for Google progress");
            string root = letter + ":\\";
            using var filesystem = new DirectGoogleWinFsp(api,
                Path.GetTempPath(), true, isolated);
            using var host = new FileSystemHost(filesystem);
            host.FileSystemName = "NTFS";
            host.Prefix = @"\FileHubGoogleProgress\" +
                Guid.NewGuid().ToString("N");
            if (host.Mount(letter + ":", null, true) < 0)
                throw new IOException("Google progressive WinFsp mount failed");
            if (!SpinWait.SpinUntil(() =>
            {
                filesystem.GetVolumeInfo(out var quota);
                return quota.FreeSize > 100_000_000UL;
            }, TimeSpan.FromSeconds(5)))
                throw new IOException("Google progressive WinFsp quota unavailable");

            const int bytes = 8 * 1024 * 1024 + 123;
            byte[] source = new byte[bytes];
            new Random(20261124).NextBytes(source);
            string local = Path.Combine(isolated, "local-source.zip");
            File.WriteAllBytes(local, source);
            string remote = Path.Combine(root, "upload-from-pc.txt");
            var progressed = new List<(long ByteCount, long Milliseconds)>();
            var elapsed = System.Diagnostics.Stopwatch.StartNew();
            CopyProgress callback = (all, sent, _, _, _, _, _, _, _) =>
            {
                lock (progressed)
                {
                    if (progressed.Count == 0 ||
                        progressed[^1].ByteCount < sent)
                        progressed.Add((sent, elapsed.ElapsedMilliseconds));
                }
                return 0;
            };
            int cancel = 0;
            if (!CopyFileEx(local, remote, callback, IntPtr.Zero,
                ref cancel, 0))
            {
                int win32 = System.Runtime.InteropServices.Marshal.GetLastWin32Error();
                Console.Error.WriteLine(
                    "GOOGLE_PROGRESS_TEST_COPY_FAILED starts=" +
                    fake.UploadInitiations + " completes=" +
                    fake.UploadCompletions + " samples=" +
                    progressed.Count + " stageFiles=" +
                    Directory.GetFiles(isolated, "*.json").Length +
                    " rootExists=" + Directory.Exists(root) +
                    " free=" + new DriveInfo(root).AvailableFreeSpace);
                throw new IOException("Windows CopyFileEx Google progress failed " +
                    win32);
            }
            elapsed.Stop();
            if (fake.UploadInitiations != 1 || fake.UploadCompletions != 1 ||
                fake.UploadedContent is null ||
                !source.AsSpan().SequenceEqual(fake.UploadedContent))
            {
                Console.Error.WriteLine(
                    "GOOGLE_PROGRESS_LAB_MISMATCH starts=" + fake.UploadInitiations +
                    " completions=" + fake.UploadCompletions +
                    " remoteBytes=" + (fake.UploadedContent?.Length ?? 0) +
                    " expectedBytes=" + source.Length +
                    " stageCount=" + Directory.GetFiles(isolated, "*.json").Length);
                foreach (string manifest in Directory.GetFiles(isolated, "*.json"))
                {
                    using var doc = JsonDocument.Parse(File.ReadAllText(manifest));
                    Console.Error.WriteLine("GOOGLE_PROGRESS_LAB_JOURNAL status=" +
                        doc.RootElement.GetProperty("Status").GetString() +
                        " size=" + doc.RootElement.GetProperty("Size").GetInt64());
                }
                throw new IOException("Google WinFsp progressive output mismatch");
            }
            long span = progressed.Count < 2 ? 0 :
                progressed[^1].Milliseconds - progressed[0].Milliseconds;
            if (progressed.Count < 8 || span < 300 ||
                progressed[0].ByteCount >= bytes)
                throw new IOException("Windows copy progress reached 99% too soon");
            Console.WriteLine("GOOGLE_DIRECT_WINFSP_NATIVE_COPY_PROGRESS_PASS" +
                " samples=" + progressed.Count +
                " spanMs=" + span +
                " fullMs=" + elapsed.ElapsedMilliseconds);
            Console.WriteLine("GOOGLE_DIRECT_WINFSP_SHA256_COPY_PASS bytes=" +
                bytes);
        }
        finally
        {
            if (Directory.Exists(isolated))
                Directory.Delete(isolated, true);
        }
    }

    internal static void Run()
    {
        using var fake = new MockDriveHandler();
        using var http = new HttpClient(fake);
        var api = new GoogleDirectApiClient(http,
            _ => Task.FromResult("OFFLINE_GOOGLE_TOKEN"),
            new Uri("http://127.0.0.1:19490/drive/v3/"));
        string isolated = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleStageLab_" + Guid.NewGuid().ToString("N"));
        using var fs = new DirectGoogleWinFsp(api, Path.GetTempPath(), true, isolated);
        int quotaUpdates = 0;
        fs.QuotaUpdated += (total, free) =>
        {
            if (total == 5_000_000_000_000L &&
                free == 4_932_000_000_000L)
                Interlocked.Increment(ref quotaUpdates);
        };
        var networkProfile = new NasProfile
        {
            Id = Guid.NewGuid(), Protocol = "GoogleDrive",
            DisplayName = "Google 검증용 드라이브"
        };
        MountPresentation.BeginNetworkMountAttempt(networkProfile);
        string ownedPrefix = MountPresentation.WinFspPrefix(networkProfile);
        if (DirectGoogleWinFspHost.ValidateNetworkPrefix(networkProfile,
                ownedPrefix) != ownedPrefix)
            throw new IOException("Direct Google owned network prefix was not accepted");
        bool foreignDenied = false;
        try { DirectGoogleWinFspHost.ValidateNetworkPrefix(networkProfile,
            @"\ThirdParty\Google Drive"); }
        catch (ArgumentException) { foreignDenied = true; }
        if (!foreignDenied)
            throw new IOException("Direct Google accepted a foreign network prefix");
        char free = Enumerable.Range('T', 7).Select(n => (char)n)
            .FirstOrDefault(letter =>
                !Environment.GetLogicalDrives().Any(d => d.StartsWith(letter + ":",
                    StringComparison.OrdinalIgnoreCase)));
        if (free == default)
            throw new IOException("No isolated drive letter for Google pilot test");
        string letterPath = free + ":";
        using var host = new FileSystemHost(fs);
        host.FileSystemName = "NTFS";
        host.Prefix = ownedPrefix;
        if (host.Mount(letterPath, null, true) < 0)
            throw new IOException("Google direct WinFsp mock could not mount");
        string root = letterPath + "\\";
        if (new DriveInfo(letterPath).DriveType != DriveType.Network)
            throw new IOException("Google direct WinFsp should appear as a network drive");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_OWNED_NETWORK_DRIVE_PASS");
        // The first volume callback must never borrow capacity from C: and
        // must return without waiting for a real network round-trip. Google
        // About is fetched once in the background and then held in cache.
        var sw = System.Diagnostics.Stopwatch.StartNew();
        if (fs.GetVolumeInfo(out var firstQuota) != 0 ||
            sw.Elapsed > TimeSpan.FromSeconds(1) ||
            (firstQuota.TotalSize != 0 &&
                firstQuota.TotalSize != 5_000_000_000_000UL))
            throw new IOException("Google WinFsp returned host-disk or blocking capacity");
        if (!SpinWait.SpinUntil(() =>
        {
            fs.GetVolumeInfo(out var quota);
            return quota.TotalSize == 5_000_000_000_000UL &&
                quota.FreeSize == 4_932_000_000_000UL;
        }, TimeSpan.FromSeconds(5)))
            throw new IOException("Google WinFsp did not present real About quota");
        if (!SpinWait.SpinUntil(() => Volatile.Read(ref quotaUpdates) == 1,
                TimeSpan.FromSeconds(2)))
            throw new IOException("Google volume update callback was not delivered");
        for (int i = 0; i < 100; i++) fs.GetVolumeInfo(out _);
        var visibleQuota = new DriveInfo(letterPath);
        if (fake.QuotaRequests != 1 || quotaUpdates != 1 ||
            visibleQuota.TotalSize != 5_000_000_000_000L ||
            // GetDiskFreeSpaceEx rounds WinFsp free bytes down to a sector.
            visibleQuota.AvailableFreeSpace > 4_932_000_000_000L ||
            visibleQuota.AvailableFreeSpace < 4_932_000_000_000L - 4095)
            throw new IOException("Windows quota mismatch requests=" +
                fake.QuotaRequests + " notices=" + quotaUpdates +
                " total=" + visibleQuota.TotalSize +
                " free=" + visibleQuota.AvailableFreeSpace);
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_REAL_REMOTE_QUOTA_NONBLOCKING_PASS");
        fake.UnlimitedQuota = true;
        if (api.GetStorageQuotaAsync().GetAwaiter().GetResult() is not null)
            throw new IOException("Unlimited Google quota was assigned an invented limit");
        fake.UnlimitedQuota = false;
        Console.WriteLine("GOOGLE_DIRECT_UNLIMITED_QUOTA_REMAINS_UNKNOWN_PASS");
        if (!Directory.EnumerateFileSystemEntries(root)
            .Any(path => Path.GetFileName(path) == "hello.txt"))
            throw new IOException("Google direct root listing missing content");
        string value = File.ReadAllText(Path.Combine(root, "hello.txt"));
        if (value != "Hello from API!")
            throw new IOException("Google direct range read content mismatch");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_REAL_WINDOWS_READ_PASS rclone=false");
        var directEntry = fs.Find("/hello.txt") ??
            throw new IOException("Direct cached-read file entry missing");
        IntPtr scratch = System.Runtime.InteropServices.Marshal.AllocHGlobal(4);
        try
        {
            int result = fs.Read(directEntry, directEntry, scratch, 1, 2, out var count1);
            int second = fs.Read(directEntry, directEntry, scratch, 4, 2, out var count2);
            if (result != 0 || second != 0 || count1 != 2 || count2 != 2 ||
                fake.MediaRequests != 1)
                throw new IOException("Two small WinFsp reads were not coalesced into one API range");
        }
        finally { System.Runtime.InteropServices.Marshal.FreeHGlobal(scratch); }
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_COALESCED_RANGE_READ_CACHE_PASS");
        Directory.CreateDirectory(Path.Combine(root, "새폴더"));
        if (fake.FolderName != "새폴더")
            throw new IOException("Direct folder create did not reach Google API");
        Directory.Move(Path.Combine(root, "새폴더"),
            Path.Combine(root, "새이름"));
        if (fake.FolderName != "새이름")
            throw new IOException("Direct folder rename did not reach Google API");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_REAL_WINDOWS_FOLDER_CREATE_RENAME_PASS");
        const string payload = "Google FileHub native WinFsp upload!";
        string newFile = Path.Combine(root, "upload-from-pc.txt");
        byte[] payloadBytes = Encoding.UTF8.GetBytes(payload);
        using (var first = new FileStream(newFile, FileMode.CreateNew,
            FileAccess.ReadWrite, FileShare.ReadWrite))
        {
            int midpoint = payloadBytes.Length / 2;
            first.Write(payloadBytes, 0, midpoint);
            first.Flush(flushToDisk: true);
            if (fake.UploadCompletions != 0)
                throw new IOException("Google Flush uploaded unfinished new file");
            first.Write(payloadBytes, midpoint,
                payloadBytes.Length - midpoint);
            first.Flush(flushToDisk: true);
            if (fake.UploadCompletions != 0)
                throw new IOException("Google second Flush prematurely uploaded file");
        }
        string uploadedText = fake.UploadedContent is null ? "" :
            Encoding.UTF8.GetString(fake.UploadedContent).TrimStart('\uFEFF');
        if (fake.UploadCompletions != 1 || fake.UploadInitiations != 1 ||
            uploadedText != payload)
            throw new IOException("Windows File.WriteAllText failed direct Google new upload" +
                " starts=" + fake.UploadInitiations + " completed=" +
                fake.UploadCompletions + " bytes=" +
                (fake.UploadedContent?.Length ?? 0));
        // WinFsp can delay Close beyond Windows Cleanup while the cache
        // manager releases references. The stage is removed at Close, not
        // before the callback has actually finished.
        for (int retry = 0; retry < 50 &&
            (Directory.GetFiles(isolated, "*.data").Length != 0 ||
             Directory.GetFiles(isolated, "*.json").Length != 0); retry++)
            Thread.Sleep(100);
        if (Directory.GetFiles(isolated, "*.data").Length != 0 ||
            Directory.GetFiles(isolated, "*.json").Length != 0)
            throw new IOException("Committed Google stage not released after Close");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_REAL_WINDOWS_NEW_FILE_UPLOAD_PASS");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_MULTIFLUSH_BEFORE_COMMIT_PASS");
        // Existing files require explicit opt-in and must first download
        // a verified base before replacing content. The production mount
        // intentionally leaves this switch off until real multi-PC checks.
        char editLetter = Enumerable.Range('P', 4).Select(n => (char)n)
            .FirstOrDefault(letter => letter != free &&
                !Environment.GetLogicalDrives().Any(d => d.StartsWith(letter + ":",
                    StringComparison.OrdinalIgnoreCase)));
        if (editLetter == default)
            throw new IOException("No second free drive for existing-file edit lab");
        string editDirectory = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleExistingLab_" + Guid.NewGuid().ToString("N"));
        using (var editFs = new DirectGoogleWinFsp(api, Path.GetTempPath(),
            true, editDirectory, allowExistingBinaryWrites: true))
        using (var editHost = new FileSystemHost(editFs))
        {
            editHost.FileSystemName = "NTFS";
            editHost.Prefix = @"\FileHubGoogle\" + Guid.NewGuid().ToString("N");
            if (editHost.Mount(editLetter + ":", null, true) < 0)
                throw new IOException("Existing-file Google WinFsp test could not mount");
            string existingPath = editLetter + ":\\hello.txt";
            const string changed = "Edited Google binary from WinFsp";
            File.WriteAllText(existingPath, changed, Encoding.UTF8);
            if (fake.ExistingCompletions != 1 || fake.PinnedRevisions != 1 ||
                Encoding.UTF8.GetString(fake.SourceContent).TrimStart('\uFEFF') != changed)
                throw new IOException("Existing binary edit did not pin and upload correctly" +
                    " completion=" + fake.ExistingCompletions +
                    " pins=" + fake.PinnedRevisions +
                    " bytes=" + fake.SourceContent.Length +
                    " stage=" + Directory.GetFiles(editDirectory, "*.json").Length);
            for (int retry = 0; retry < 50 &&
                Directory.GetFiles(editDirectory, "*.json").Length != 0; retry++)
                Thread.Sleep(100);
            if (Directory.GetFiles(editDirectory, "*.json").Length != 0)
                throw new IOException("Verified existing upload left stale recovery stage");
            const string savedAgain = "Second save to the same Google Drive file";
            File.WriteAllText(existingPath, savedAgain, Encoding.UTF8);
            if (fake.ExistingCompletions != 2 || fake.PinnedRevisions != 2 ||
                Encoding.UTF8.GetString(fake.SourceContent).TrimStart('\uFEFF') != savedAgain)
                throw new IOException("Repeated Google file overwrite failed revision preservation");
        }
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_EXISTING_BINARY_EDIT_PIN_VERIFY_PASS");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_EXISTING_BINARY_REPEAT_SAVE_PASS");

        // A second client changing the file after stage creation must make
        // Commit fail WITHOUT overwriting that client's content.
        string conflictDirectory = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleConflictLab_" + Guid.NewGuid().ToString("N"));
        try
        {
            var original = api.GetFileAsync("file_01").GetAwaiter().GetResult()!;
            using (var conflict = new DirectGoogleWriteStage(api,
                conflictDirectory, "root", "hello.txt", original))
            {
                conflict.SetLength(0);
                conflict.Write(0, Encoding.UTF8.GetBytes("MY LOCAL UNSAVED EDIT"), false);
                fake.SourceContent = Encoding.UTF8.GetBytes("SECOND PC SAVED");
                fake.SourceRevision = "rev_other_pc";
                try
                {
                    conflict.Commit();
                    throw new IOException("Stale Google existing edit overwrote second PC");
                }
                catch (IOException ex) when (ex.Message.Contains(
                    "local recovery retained", StringComparison.Ordinal)) { }
            }
            if (fake.ExistingCompletions != 2 ||
                Encoding.UTF8.GetString(fake.SourceContent) != "SECOND PC SAVED" ||
                Directory.GetFiles(conflictDirectory, "*.data").Length != 1 ||
                File.ReadAllText(Directory.GetFiles(conflictDirectory, "*.data").Single())
                    != "MY LOCAL UNSAVED EDIT")
                throw new IOException("Google conflict lost local or second-PC contents");
            Console.WriteLine("GOOGLE_DIRECT_TWO_CLIENT_STALE_WRITE_RECOVERY_PASS");
        }
        finally
        {
            if (Directory.Exists(conflictDirectory))
                Directory.Delete(conflictDirectory, recursive: true);
        }
        string pinFailureDirectory = Path.Combine(Path.GetTempPath(),
            "FileHubGooglePinFailureLab_" + Guid.NewGuid().ToString("N"));
        try
        {
            var baseline = api.GetFileAsync("file_01").GetAwaiter().GetResult()!;
            using (var failure = new DirectGoogleWriteStage(api,
                pinFailureDirectory, "root", "hello.txt", baseline))
            {
                failure.SetLength(0);
                failure.Write(0, Encoding.UTF8.GetBytes("SAVE AFTER LIMIT ERROR"), false);
                fake.FailPin = true;
                try
                {
                    failure.Commit();
                    throw new IOException("Google 403 during revision backup was ignored");
                }
                catch (IOException ex) when (ex.Message.Contains(
                    "local recovery retained", StringComparison.Ordinal)) { }
            }
            if (fake.ExistingCompletions != 2 ||
                Encoding.UTF8.GetString(fake.SourceContent) != "SECOND PC SAVED" ||
                Directory.GetFiles(pinFailureDirectory, "*.data").Length != 1 ||
                File.ReadAllText(Directory.GetFiles(pinFailureDirectory, "*.data").Single())
                    != "SAVE AFTER LIMIT ERROR")
                throw new IOException("Backup 403 failure lost the existing edit or source");
            Console.WriteLine("GOOGLE_DIRECT_EXISTING_PIN_403_LOCAL_RECOVERY_PASS");
        }
        finally
        {
            fake.FailPin = false;
            if (Directory.Exists(pinFailureDirectory))
                Directory.Delete(pinFailureDirectory, recursive: true);
        }
        File.Move(Path.Combine(root, "hello.txt"),
            Path.Combine(root, "hello-renamed.txt"));
        if (fake.SourceName != "hello-renamed.txt")
            throw new IOException("Google native file rename did not reach the metadata API");
        File.Delete(Path.Combine(root, "hello-renamed.txt"));
        if (!fake.SourceTrashed)
            throw new IOException("Google native File.Delete did not call reversible Trash");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_REAL_WINDOWS_FILE_RENAME_TRASH_PASS");

        // A rate-limit failure must not delete the original local bytes.
        // This is a separate stage: it never writes to the real user Drive.
        string recoveryFolder = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleFailureLab_" + Guid.NewGuid().ToString("N"));
        fake.FailGenerateId = true;
        try
        {
            using (var failed = new DirectGoogleWriteStage(
                api, recoveryFolder, "root", "must-not-lose.txt"))
            {
                failed.Write(0, Encoding.UTF8.GetBytes("RECOVER ME"), false);
                try
                {
                    failed.Commit();
                    throw new IOException("Google 403 quota failure was ignored");
                }
                catch (GoogleDirectApiClient.GoogleDirectApiException rejected)
                    when (rejected.HttpStatus == 403 && rejected.IsRateLimited)
                { }
            }
            if (Directory.GetFiles(recoveryFolder, "*.data").Length != 1 ||
                Directory.GetFiles(recoveryFolder, "*.json").Length != 1)
                throw new IOException("Failed Google upload lost recovery journal");
            string saved = File.ReadAllText(
                Directory.GetFiles(recoveryFolder, "*.data").Single());
            if (saved != "RECOVER ME")
                throw new IOException("Failed Google upload lost original content");
            Console.WriteLine(
                "GOOGLE_DIRECT_UPLOAD_403_FAILURE_PRESERVES_LOCAL_RECOVERY_PASS");
        }
        finally
        {
            fake.FailGenerateId = false;
            if (Directory.Exists(recoveryFolder))
                Directory.Delete(recoveryFolder, recursive: true);
        }
    }
}
