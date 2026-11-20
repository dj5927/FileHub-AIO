using System.Net;
using System.Net.Http.Headers;
using System.Text;
using Fsp;

namespace NASMount.LockBridge;

/// <summary>Actual Windows/WinFsp integration with a fake Google REST backend.
/// No real account, rclone process, user files or credentials are used.</summary>
internal static class DirectGoogleWinFspPolicyLab
{
    private sealed class MockDriveHandler : HttpMessageHandler
    {
        internal string FolderName = "";
        internal string SourceName = "hello.txt";
        internal bool SourceTrashed;
        internal int MediaRequests;
        internal byte[]? UploadedContent;
        internal int UploadInitiations;
        internal int UploadCompletions;
        internal bool FailGenerateId;
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
                url.AbsolutePath.EndsWith("/files/generateIds"))
                return Task.FromResult(FailGenerateId
                    ? Json("""{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}""",
                        HttpStatusCode.Forbidden)
                    : Json("""{"ids":["new_drive_01"]}"""));
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
                var response = Json("{}", HttpStatusCode.OK);
                response.Headers.Location = new Uri("http://127.0.0.1:19490/session/new_drive_01");
                return Task.FromResult(response);
            }
            if (request.Method == HttpMethod.Put &&
                url.AbsolutePath == "/session/new_drive_01")
            {
                UploadedContent = request.Content!.ReadAsByteArrayAsync(
                    cancellationToken).GetAwaiter().GetResult();
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
                            new { id = "file_01", name = SourceName, mimeType = "text/plain",
                                size = "15", version = "1", modifiedTime = "2026-01-01T00:00:00Z" }
                        }.Where(_ => !SourceTrashed).Concat(UploadedContent is null ? Array.Empty<object>() :
                            [new { id = "new_drive_01", name = "upload-from-pc.txt",
                                mimeType = "application/octet-stream",
                                size = UploadedContent.Length.ToString(), version = "1" }]).ToArray()
                        : new object[]
                        {
                            new { id = "file_01", name = SourceName, mimeType = "text/plain",
                                size = "15", version = "1", modifiedTime = "2026-01-01T00:00:00Z" },
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
                        new { id = "file_01", name = SourceName,
                            mimeType = "text/plain", size = "15", version = "1" })));
                Interlocked.Increment(ref MediaRequests);
                if (request.Headers.Range is not { } range)
                    throw new InvalidOperationException("Google WinFsp media read lacked range");
                byte[] data = Encoding.ASCII.GetBytes("Hello from API!");
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
        char free = Enumerable.Range('T', 7).Select(n => (char)n)
            .FirstOrDefault(letter =>
                !Environment.GetLogicalDrives().Any(d => d.StartsWith(letter + ":",
                    StringComparison.OrdinalIgnoreCase)));
        if (free == default)
            throw new IOException("No isolated drive letter for Google pilot test");
        string letterPath = free + ":";
        using var host = new FileSystemHost(fs);
        host.FileSystemName = "NTFS";
        if (host.Mount(letterPath, null, true) < 0)
            throw new IOException("Google direct WinFsp mock could not mount");
        string root = letterPath + "\\";
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
        File.WriteAllText(newFile, payload, Encoding.UTF8);
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
