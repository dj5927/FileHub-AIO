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
                url.AbsolutePath.EndsWith("/files"))
            {
                bool nested = Uri.UnescapeDataString(url.Query).Contains(
                    "'folder_01' in parents", StringComparison.Ordinal);
                string result = System.Text.Json.JsonSerializer.Serialize(new
                {
                    files = nested ? Array.Empty<object>() :
                        FolderName.Length == 0 ? new object[]
                        {
                            new { id = "file_01", name = "hello.txt", mimeType = "text/plain",
                                size = "15", version = "1", modifiedTime = "2026-01-01T00:00:00Z" }
                        } : new object[]
                        {
                            new { id = "file_01", name = "hello.txt", mimeType = "text/plain",
                                size = "15", version = "1", modifiedTime = "2026-01-01T00:00:00Z" },
                            new { id = "folder_01", name = FolderName,
                                mimeType = GoogleDirectApiClient.FolderMime, version = "1" }
                        }
                });
                return Task.FromResult(Json(result));
            }
            if (request.Method == HttpMethod.Get &&
                url.AbsolutePath.EndsWith("/files/file_01"))
            {
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
        using var fs = new DirectGoogleWinFsp(api, Path.GetTempPath(), true);
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
        Directory.CreateDirectory(Path.Combine(root, "새폴더"));
        if (fake.FolderName != "새폴더")
            throw new IOException("Direct folder create did not reach Google API");
        Directory.Move(Path.Combine(root, "새폴더"),
            Path.Combine(root, "새이름"));
        if (fake.FolderName != "새이름")
            throw new IOException("Direct folder rename did not reach Google API");
        Console.WriteLine("GOOGLE_DIRECT_WINFSP_REAL_WINDOWS_FOLDER_CREATE_RENAME_PASS");
    }
}
