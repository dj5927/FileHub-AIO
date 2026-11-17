using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace NASMount;

internal static class MyBoxWriteLab
{
    private sealed record Item(string Id, string Name, bool Folder, long Size,
        string? Parent);
    private sealed class State
    {
        internal readonly Dictionary<string, Item> Items = [];
        internal string? PendingName;
        internal string? PendingParent;
        internal long PendingSize;
        internal int BytesSeen;
        internal string Add(string name, bool folder, long size, string? parent)
        {
            string id = Guid.NewGuid().ToString("N");
            Items[id] = new Item(id, name, folder, size, parent);
            return id;
        }
        internal string List(string? parent)
        {
            var items = Items.Values.Where(x => x.Parent == parent).Select(x => new {
                name = x.Name, resourceId = x.Id,
                type = x.Folder ? "folder" : "file", size = x.Size
            });
            return JsonSerializer.Serialize(new {
                resources = items, responseMetaData = new { nextCursor = (string?)null }
            });
        }
        internal HttpResponseMessage Response(HttpStatusCode status, object? payload = null) =>
            new(status) {
                Content = new StringContent(payload is string s ? s :
                    JsonSerializer.Serialize(payload ?? new { }), Encoding.UTF8, "application/json")
            };
    }

    private sealed class ApiHandler(State state) : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage req, CancellationToken cancel)
        {
            string path = req.RequestUri?.AbsolutePath ?? "";
            if (req.RequestUri?.Host != "open-api.mybox.naver.com")
                throw new InvalidOperationException("Fake API host changed");
            if (req.Method == HttpMethod.Get && path.EndsWith("/drive/resources"))
                return state.Response(HttpStatusCode.OK, state.List(null));
            if (req.Method == HttpMethod.Get && path.Contains("/drive/folders/") &&
                path.EndsWith("/resources"))
                return state.Response(HttpStatusCode.OK,
                    state.List(path.Split('/')[4]));
            if (req.Method == HttpMethod.Post && path.EndsWith("/drive/files"))
            {
                using var json = JsonDocument.Parse(
                    await req.Content!.ReadAsStringAsync(cancel));
                var root = json.RootElement;
                state.PendingName = root.GetProperty("fileName").GetString();
                state.PendingSize = root.GetProperty("fileSize").GetInt64();
                state.PendingParent = root.TryGetProperty("parentId", out var id) ?
                    id.GetString() : null;
                return state.Response(HttpStatusCode.Created,
                    new { offset = 0, uploadUrl = "https://storage.mybox-test.invalid/upload" });
            }
            if (req.Method == HttpMethod.Post && path.EndsWith("/drive/folders"))
            {
                using var json = JsonDocument.Parse(
                    await req.Content!.ReadAsStringAsync(cancel));
                var root = json.RootElement;
                string name = root.GetProperty("folderName").GetString()!;
                string? parent = root.TryGetProperty("parentId", out var id) ?
                    id.GetString() : null;
                string newId = state.Add(name, true, 0, parent);
                return state.Response(HttpStatusCode.Created,
                    new { name, resourceId = newId });
            }
            if (path.Contains("/drive/resources/"))
            {
                string id = path.Split('/')[4];
                if (!state.Items.TryGetValue(id, out var item))
                    return state.Response(HttpStatusCode.NotFound);
                if (req.Method == HttpMethod.Delete)
                {
                    state.Items.Remove(id);
                    return state.Response(HttpStatusCode.NoContent);
                }
                using var json = JsonDocument.Parse(
                    await req.Content!.ReadAsStringAsync(cancel));
                var body = json.RootElement;
                if (path.EndsWith("/rename"))
                {
                    string name = body.GetProperty("name").GetString()!;
                    state.Items[id] = item with { Name = name };
                    return state.Response(HttpStatusCode.OK, new { name });
                }
                if (path.EndsWith("/copy"))
                {
                    string name = body.GetProperty("name").GetString()!;
                    string? parent = body.TryGetProperty("parentId", out var dest) ?
                        dest.GetString() : null;
                    state.Add(name, item.Folder, item.Size, parent);
                    return state.Response(HttpStatusCode.Created);
                }
                if (path.EndsWith("/move"))
                {
                    string parent = body.GetProperty("parentId").GetString()!;
                    state.Items[id] = item with { Parent = parent };
                    return state.Response(HttpStatusCode.OK);
                }
            }
            throw new InvalidOperationException("Unexpected synthetic MYBOX endpoint " +
                req.Method + " " + path);
        }
    }
    private sealed class UploadHandler(State state) : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage req, CancellationToken cancel)
        {
            if (req.Method != HttpMethod.Post ||
                req.RequestUri?.Host != "storage.mybox-test.invalid" ||
                state.PendingName is null)
                throw new InvalidOperationException("Fake upload URL mismatch");
            var bytes = await req.Content!.ReadAsByteArrayAsync(cancel);
            state.BytesSeen += bytes.Length;
            if (bytes.Length < state.PendingSize)
                throw new InvalidOperationException("Multipart upload bytes missing");
            var prev = state.Items.Values.FirstOrDefault(x =>
                x.Name == state.PendingName && x.Parent == state.PendingParent);
            string id = prev?.Id ?? Guid.NewGuid().ToString("N");
            state.Items[id] = new Item(id, state.PendingName, false,
                state.PendingSize, state.PendingParent);
            return state.Response(HttpStatusCode.OK,
                new { resourceId = id, name = state.PendingName, fileSize = state.PendingSize });
        }
    }

    internal static async Task RunAsync(string rcloneExe)
    {
        string fake = "mbx_pat_" + new string('x', 40);
        var state = new State();
        using var api = new MyBoxBetaGateway.Api(fake,
            new ApiHandler(state), new UploadHandler(state));
        using var gateway = MyBoxBetaGateway.CreateLocalWriteLab(api);
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(35) };
        async Task Ensure(HttpResponseMessage response, HttpStatusCode expected)
        {
            using (response)
                if (response.StatusCode != expected)
                    throw new InvalidOperationException("MYBOX DAV LAB " + expected +
                        " actual " + response.StatusCode + " " +
                        await response.Content.ReadAsStringAsync());
        }
        var data = new ByteArrayContent(Encoding.UTF8.GetBytes("hello_MYBOX_write"));
        await Ensure(await http.PutAsync(gateway.Url + "demo.txt", data),
            HttpStatusCode.Created);
        if (state.BytesSeen == 0)
            throw new InvalidOperationException("Actual multipart was not transmitted");
        await Ensure(await http.PutAsync(gateway.Url + "demo.txt",
            new StringContent("overwritten")), HttpStatusCode.NoContent);
        await Ensure(await http.SendAsync(new HttpRequestMessage(
            new HttpMethod("MKCOL"), gateway.Url + "newfolder")),
            HttpStatusCode.Created);
        using var move = new HttpRequestMessage(new HttpMethod("MOVE"),
            gateway.Url + "demo.txt");
        move.Headers.TryAddWithoutValidation("Destination", gateway.Url + "renamed.txt");
        await Ensure(await http.SendAsync(move), HttpStatusCode.Created);
        using var hostile = new HttpRequestMessage(new HttpMethod("MOVE"),
            gateway.Url + "renamed.txt");
        hostile.Headers.TryAddWithoutValidation("Destination", "http://evil.example/outside");
        await Ensure(await http.SendAsync(hostile), HttpStatusCode.BadRequest);
        using var copy = new HttpRequestMessage(new HttpMethod("COPY"),
            gateway.Url + "renamed.txt");
        copy.Headers.TryAddWithoutValidation("Destination", gateway.Url + "duplicate.txt");
        await Ensure(await http.SendAsync(copy), HttpStatusCode.Created);
        await Ensure(await http.DeleteAsync(gateway.Url + "duplicate.txt"),
            HttpStatusCode.NoContent);
        using var prop = new HttpRequestMessage(new HttpMethod("PROPFIND"), gateway.Url);
        prop.Headers.TryAddWithoutValidation("Depth", "1");
        using var listed = await http.SendAsync(prop);
        string xml = await listed.Content.ReadAsStringAsync();
        if (listed.StatusCode != (HttpStatusCode)207 || !xml.Contains("renamed.txt") ||
            !xml.Contains("newfolder") || xml.Contains("duplicate.txt"))
            throw new InvalidOperationException("Write lab final listings mismatch.");
        Console.WriteLine("MYBOX_MOCK_PUT_OVERWRITE_MKCOL_RENAME_COPY_TRASH_PASS");
        if (!File.Exists(rcloneExe)) throw new FileNotFoundException(rcloneExe);
        // Validate the exact bundled rclone WebDAV PUT protocol, independent
        // of live PAT/account and without mounting any Windows drive letter.
        string file = Path.Combine(Path.GetTempPath(),
            "mybox-write-lab-" + Guid.NewGuid().ToString("N") + ".txt");
        try
        {
            await File.WriteAllTextAsync(file, "write-through-rclone", CancellationToken.None);
            var psi = new ProcessStartInfo(rcloneExe) {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            psi.Environment["RCLONE_CONFIG_NM_TEST_TYPE"] = "webdav";
            psi.Environment["RCLONE_CONFIG_NM_TEST_URL"] = gateway.Url;
            psi.Environment["RCLONE_CONFIG_NM_TEST_VENDOR"] = "other";
            foreach (var arg in new[] { "copyto", file, "nm_test:from-rclone.txt",
                "--config", Path.Combine(AppStore.DirectoryPath, "no-global-rclone.conf"),
                "--retries", "1", "--low-level-retries", "1" })
                psi.ArgumentList.Add(arg);
            using var child = new Process { StartInfo = psi };
            child.Start();
            Task<string> stdout = child.StandardOutput.ReadToEndAsync();
            Task<string> stderr = child.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
            try { await child.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException)
            {
                if (!child.HasExited) child.Kill(entireProcessTree: true);
                throw new TimeoutException("Synthetic rclone copyto timed out");
            }
            string err = await stderr;
            _ = await stdout;
            if (child.ExitCode != 0 ||
                !state.Items.Values.Any(x => x.Name == "from-rclone.txt"))
                throw new InvalidOperationException("Bundled rclone WebDAV PUT failed " +
                    err[..Math.Min(err.Length, 400)]);
            Console.WriteLine("MYBOX_MOCK_REAL_RCLONE_COPYTO_WRITE_PASS");
        }
        finally { try { File.Delete(file); } catch { } }
    }
}
