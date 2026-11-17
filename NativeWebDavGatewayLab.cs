using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Diagnostics;

namespace NASMount;

// A real HTTP loopback gateway tested against a fake in-memory NAS.
internal static class NativeWebDavGatewayLab
{
    private static int checks;
    private static void Check(bool result, string label)
    {
        if (!result) throw new IOException("NATIVE_DAV_GATEWAY_FAIL " + label);
        checks++;
    }

    private static async Task<(bool Ok, string Id)> Control(
        HttpClient client, NativeWebDavGateway gateway, string command,
        string path, string id = "")
    {
        using var request = new HttpRequestMessage(HttpMethod.Post,
            gateway.ControlUrl + command);
        request.Headers.TryAddWithoutValidation("X-NASMount-Control",
            gateway.ControlSecret);
        request.Content = new StringContent(
            JsonSerializer.Serialize(new { Path = path, Id = id }),
            Encoding.UTF8, "application/json");
        using var response = await client.SendAsync(request);
        string text = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(text);
        return (response.IsSuccessStatusCode,
            doc.RootElement.TryGetProperty("Id", out var idValue) ?
            idValue.GetString() ?? "" : "");
    }

    internal static async Task RunAsync()
    {
        checks = 0;
        using var fake = new FakeBackend();
        var upstream = new Uri("https://nas.example.test:5443/");
        using var first = NativeWebDavGateway.StartLab(upstream, "share", fake);
        using var second = NativeWebDavGateway.StartLab(upstream, "share", fake);
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(45) };
        Uri docA = new(first.Url + "share/document.xlsx");
        Uri docB = new(second.Url + "share/document.xlsx");
        Uri stageA = new(first.Url + "share/stage.tmp");

        using (var put = new HttpRequestMessage(HttpMethod.Put, docA))
        {
            put.Content = new ByteArrayContent("unsafe"u8.ToArray());
            using var denied = await client.SendAsync(put);
            Check((int)denied.StatusCode == 423, "unlocked document blocked");
        }
        Check(Encoding.UTF8.GetString(fake.Document) == "Original",
            "original preserved when unlocked");
        var lockA = await Control(client, first, "acquire", "document.xlsx");
        Check(lockA.Ok && lockA.Id.Length == 32, "first client obtains native token");
        var lockB = await Control(client, second, "acquire", "document.xlsx");
        Check(!lockB.Ok, "second client conflict");
        Check(fake.LegacyChecks >= 2, "legacy marker checked without creating folder");

        byte[] bytes = new byte[2 * 1024 * 1024];
        Random.Shared.NextBytes(bytes);
        using (var put = new HttpRequestMessage(HttpMethod.Put, docA))
        {
            put.Content = new ByteArrayContent(bytes);
            using var result = await client.SendAsync(put);
            Check(result.IsSuccessStatusCode, "locked 2MiB binary PUT");
        }
        Check(fake.Document.AsSpan().SequenceEqual(bytes),
            "large body forwarded byte-identically");
        Check(fake.LastIf.Contains("<opaquelocktoken:", StringComparison.Ordinal),
            "locked PUT bears native token");
        await RcloneRealBackendWriteAsync(first, fake);

        // The original Office workbook is renamed to backup.tmp during save.
        // Native WebDAV MOVE would release the existing source lock. Preserve
        // its original URI by issuing an upstream COPY in that specific case.
        byte[] beforeBackup = fake.Document.ToArray();
        using (var backup = new HttpRequestMessage(new HttpMethod("MOVE"), docA))
        {
            backup.Headers.TryAddWithoutValidation("Destination",
                new Uri(first.Url + "share/backup.tmp").AbsoluteUri);
            using var response = await client.SendAsync(backup);
            Check(response.IsSuccessStatusCode, "Excel original backup accepted");
        }
        Check(fake.Document.AsSpan().SequenceEqual(beforeBackup),
            "backup never removes locked original");
        Check(fake.BackupCopies == 1 && fake.BackupBytes.AsSpan().SequenceEqual(
            beforeBackup), "backup is a token-protected COPY, not MOVE");
        Check(fake.LockActive, "original remains locked until final commit");
        // Alternate Excel backup spelling: eight hex digits, no .tmp suffix.
        using (var backup = new HttpRequestMessage(new HttpMethod("MOVE"), docA))
        {
            backup.Headers.TryAddWithoutValidation("Destination",
                new Uri(first.Url + "share/AB12CD34").AbsoluteUri);
            using var response = await client.SendAsync(backup);
            Check(response.IsSuccessStatusCode,
                "eight-character Excel backup name accepted");
        }
        Check(fake.BackupCopies == 2 && fake.LockActive,
            "native token preserved after hex-only backup");

        using (var put = new HttpRequestMessage(HttpMethod.Put, stageA))
        {
            put.Content = new ByteArrayContent("Saved workbook"u8.ToArray());
            using var response = await client.SendAsync(put);
            Check(response.IsSuccessStatusCode, "unlocked temporary-file PUT");
        }
        using (var other = new HttpRequestMessage(new HttpMethod("MOVE"),
            new Uri(second.Url + "share/stage.tmp")))
        {
            other.Headers.TryAddWithoutValidation("Destination", docB.AbsoluteUri);
            using var response = await client.SendAsync(other);
            Check((int)response.StatusCode == 423,
                "other PC cannot replace locked destination");
        }
        using (var own = new HttpRequestMessage(new HttpMethod("MOVE"), stageA))
        {
            own.Headers.TryAddWithoutValidation("Destination", docA.AbsoluteUri);
            own.Headers.TryAddWithoutValidation("Overwrite", "T");
            using var response = await client.SendAsync(own);
            Check(response.IsSuccessStatusCode, "native tagged If on Excel MOVE");
        }
        Check(Encoding.UTF8.GetString(fake.Document) == "Saved workbook",
            "Excel final document contents");
        Check(fake.LastIf.Contains(
            "<https://nas.example.test:5443/share/document.xlsx>",
            StringComparison.Ordinal), "destination tag targets true server URI");

        using (var list = new HttpRequestMessage(new HttpMethod("PROPFIND"),
            new Uri(first.Url + "share/")))
        {
            list.Headers.TryAddWithoutValidation("Depth", "1");
            using var response = await client.SendAsync(list);
            string xml = await response.Content.ReadAsStringAsync();
            Check((int)response.StatusCode == 207, "WebDAV PROPFIND forwarded");
            Check(xml.Contains(new Uri(first.Url).AbsolutePath + "share/document.xlsx",
                StringComparison.Ordinal), "PROPFIND href matches local rclone URL");
        }
        using (var marker = new HttpRequestMessage(new HttpMethod("MKCOL"),
            new Uri(first.Url + "share/document.xlsx.nasmount.lock")))
        {
            using var response = await client.SendAsync(marker);
            Check((int)response.StatusCode == 403, "old lock folder creation blocked");
        }
        var released = await Control(client, first, "release",
            "document.xlsx", lockA.Id);
        Check(released.Ok && fake.Releases == 1, "UNLOCK after all uploads");
        lockB = await Control(client, second, "acquire", "document.xlsx");
        Check(lockB.Ok, "second PC can acquire after owner releases");
        Check((await Control(client, second, "release",
            "document.xlsx", lockB.Id)).Ok, "second PC unlock");
        // Real-world Korean Office filenames must resolve to the SAME
        // percent-encoded resource key in the bridge control and rclone path.
        const string koreanName = "11월매출.xlsx";
        var koreanLease = await Control(client, first, "acquire", koreanName);
        Check(koreanLease.Ok, "Korean Excel name obtains native lock");
        using (var koreanPut = new HttpRequestMessage(HttpMethod.Put,
            new Uri(first.Url + "share/" + Uri.EscapeDataString(koreanName))))
        {
            koreanPut.Content = new ByteArrayContent("Korean workbook"u8.ToArray());
            using var koreanResult = await client.SendAsync(koreanPut);
            Check(koreanResult.IsSuccessStatusCode,
                "percent-encoded Korean path resolves active lock token");
        }
        Check(Encoding.UTF8.GetString(fake.Document) == "Korean workbook",
            "Korean Excel document reaches upstream unchanged");
        Check((await Control(client, first, "release",
            koreanName, koreanLease.Id)).Ok, "Korean document native unlock");
        Check(fake.SidecarWrites == 0, "no remote sidecar files");
        Console.WriteLine("NATIVE_WEBDAV_GATEWAY_LAB_PASS checks=" + checks +
            " real_loopback_http=true dual_clients=true zero_sidecars=true");
    }

    private static async Task RcloneRealBackendWriteAsync(
        NativeWebDavGateway gateway, FakeBackend fake)
    {
        string rclone = Path.Combine(AppContext.BaseDirectory, "tools", "rclone.exe");
        Check(File.Exists(rclone), "actual rclone binary available");
        string fixtureDir = Path.Combine(AppContext.BaseDirectory,
            "native-webdav-rclone-qa-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(fixtureDir);
        string input = Path.Combine(fixtureDir, "source.xlsx");
        byte[] payload = new byte[256 * 1024];
        Random.Shared.NextBytes(payload);
        File.WriteAllBytes(input, payload);
        var psi = new ProcessStartInfo(rclone)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true
        };
        psi.Environment["RCLONE_CONFIG_NATIVEPROBE_TYPE"] = "webdav";
        psi.Environment["RCLONE_CONFIG_NATIVEPROBE_URL"] = gateway.Url;
        psi.Environment["RCLONE_CONFIG_NATIVEPROBE_VENDOR"] = "other";
        foreach (string arg in new[] {
            "copyto", input, "nativeprobe:share/document.xlsx",
            "--config", Path.Combine(fixtureDir, "no-global-rclone.conf"),
            "--ignore-times", "--retries", "1", "--low-level-retries", "1",
            "--timeout", "12s", "--log-level", "ERROR"
        }) psi.ArgumentList.Add(arg);
        using var process = new Process { StartInfo = psi };
        process.Start();
        Task<string> stdout = process.StandardOutput.ReadToEndAsync();
        Task<string> stderr = process.StandardError.ReadToEndAsync();
        try { await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(50)); }
        catch (TimeoutException) {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
            throw new IOException("Actual rclone WebDAV upload timed out");
        }
        string error = await stderr;
        _ = await stdout;
        if (process.ExitCode != 0)
            throw new IOException("Actual rclone WebDAV upload failed, rc=" +
                process.ExitCode + " " +
                (error.Length > 2800 ? error[^2800..] : error));
        Check(fake.Document.AsSpan().SequenceEqual(payload),
            "ACTUAL rclone v1.76.0 -> gateway -> fake NAS byte-for-byte upload");
        Console.WriteLine("NATIVE_GATEWAY_RCLONE_REAL_BINARY_UPLOAD_PASS bytes=" +
            payload.Length);
    }

    internal sealed class FakeBackend : HttpMessageHandler
    {
        internal byte[] Document = "Original"u8.ToArray();
        internal string LastIf = "";
        internal int LegacyChecks;
        internal int SidecarWrites;
        internal int Releases;
        internal int BackupCopies;
        internal byte[] BackupBytes = [];
        internal bool LockActive => token is not null;
        private string? token;
        private byte[] stage = [];

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken ct)
        {
            string method = request.Method.Method.ToUpperInvariant();
            string path = request.RequestUri!.AbsolutePath;
            bool isWorkbook = path.EndsWith("document.xlsx", StringComparison.Ordinal) ||
                Uri.UnescapeDataString(path).EndsWith("11월매출.xlsx",
                    StringComparison.Ordinal);
            if (path.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase) &&
                method == "HEAD")
            { LegacyChecks++; return new HttpResponseMessage(HttpStatusCode.NotFound); }
            if (path.Contains(".nasmount.lock", StringComparison.OrdinalIgnoreCase))
            { SidecarWrites++; return new HttpResponseMessage(HttpStatusCode.Forbidden); }
            LastIf = request.Headers.TryGetValues("If", out var headers) ?
                string.Join(" ", headers) : "";
            bool owned = token != null &&
                LastIf.Contains(token, StringComparison.Ordinal);
            if (method == "LOCK")
            {
                if (request.Content == null)
                    return new HttpResponseMessage(HttpStatusCode.PreconditionFailed);
                if (token != null) return new HttpResponseMessage(HttpStatusCode.Locked);
                token = "<opaquelocktoken:" + Guid.NewGuid().ToString("N") + ">";
                var ok = new HttpResponseMessage(HttpStatusCode.OK);
                ok.Headers.TryAddWithoutValidation("Lock-Token", token);
                ok.Headers.TryAddWithoutValidation("Timeout", "Second-600");
                return ok;
            }
            if (method == "UNLOCK")
            {
                string requested = request.Headers.TryGetValues("Lock-Token", out var values) ?
                    values.FirstOrDefault() ?? "" : "";
                if (requested != token)
                    return new HttpResponseMessage(HttpStatusCode.Conflict);
                token = null;
                Releases++;
                return new HttpResponseMessage(HttpStatusCode.NoContent);
            }
            if (method == "PUT" && isWorkbook)
            {
                if (!owned) return new HttpResponseMessage(HttpStatusCode.Locked);
                Document = await request.Content!.ReadAsByteArrayAsync(ct);
                return new HttpResponseMessage(HttpStatusCode.NoContent);
            }
            if (method == "PUT" && path.EndsWith("stage.tmp"))
            {
                stage = await request.Content!.ReadAsByteArrayAsync(ct);
                return new HttpResponseMessage(HttpStatusCode.Created);
            }
            if (method == "MOVE" && path.EndsWith("stage.tmp"))
            {
                if (!owned) return new HttpResponseMessage(HttpStatusCode.Locked);
                Document = stage.ToArray();
                stage = [];
                return new HttpResponseMessage(HttpStatusCode.NoContent);
            }
            if (method == "COPY" && path.EndsWith("document.xlsx"))
            {
                string destination = request.Headers.TryGetValues("Destination",
                    out var destinations) ? destinations.FirstOrDefault() ?? "" : "";
                if (!owned || !(destination.EndsWith("/share/backup.tmp",
                    StringComparison.Ordinal) ||
                    destination.EndsWith("/share/AB12CD34", StringComparison.Ordinal)))
                    return new HttpResponseMessage(HttpStatusCode.Locked);
                BackupBytes = Document.ToArray();
                BackupCopies++;
                return new HttpResponseMessage(HttpStatusCode.Created);
            }
            if (method == "OPTIONS")
            {
                var result = new HttpResponseMessage(HttpStatusCode.OK);
                result.Headers.TryAddWithoutValidation("DAV", "1,2");
                result.Headers.TryAddWithoutValidation("Allow",
                    "OPTIONS, PROPFIND, GET, HEAD, PUT, MOVE, PROPPATCH, LOCK, UNLOCK");
                return result;
            }
            if (method == "HEAD" && isWorkbook)
            {
                var result = new HttpResponseMessage(HttpStatusCode.OK);
                result.Content = new ByteArrayContent([]);
                result.Content.Headers.ContentLength = Document.LongLength;
                result.Content.Headers.LastModified =
                    new DateTimeOffset(2026, 11, 12, 3, 0, 0, TimeSpan.Zero);
                return result;
            }
            if (method == "GET" && isWorkbook)
                return new HttpResponseMessage(HttpStatusCode.OK) {
                    Content = new ByteArrayContent(Document)
                };
            if (method == "PROPPATCH")
                return new HttpResponseMessage(HttpStatusCode.NoContent);
            if (method == "PROPFIND")
            {
                const string props =
                    "<d:getlastmodified>Thu, 12 Nov 2026 03:00:00 GMT</d:getlastmodified>" +
                    "<d:creationdate>2026-11-12T03:00:00Z</d:creationdate>";
                string folder = "<d:response><d:href>/share/</d:href>" +
                    "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>" +
                    props + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "</d:response>";
                string document = "<d:response><d:href>/share/document.xlsx</d:href>" +
                    "<d:propstat><d:prop><d:resourcetype/>" +
                    "<d:getcontentlength>" + Document.LongLength + "</d:getcontentlength>" +
                    props + "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                    "</d:response>";
                string entries = path.EndsWith("document.xlsx") ?
                    document : folder + document;
                return new HttpResponseMessage((HttpStatusCode)207) {
                    Content = new StringContent(
                        "<d:multistatus xmlns:d=\"DAV:\">" + entries +
                        "</d:multistatus>", Encoding.UTF8, "application/xml")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotImplemented);
        }
    }
}
