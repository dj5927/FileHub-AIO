using System.Net;
using System.Net.Http.Headers;
using System.Text;

namespace NASMount;

internal static class LegacyCharsetLab
{
    private sealed class FakeStore : ILegacyCharsetStore
    {
        private readonly Dictionary<string, byte[]> files = new(StringComparer.Ordinal);
        private readonly HashSet<string> directories = new(StringComparer.Ordinal) { "" };
        internal FakeStore() => files["한글 파일.txt"] = Encoding.UTF8.GetBytes("ABCDEF");
        public IReadOnlyList<LegacyEntry> List(string path)
        {
            string prefix = path.Length == 0 ? "" : path.TrimEnd('/') + "/";
            return directories.Where(x => x.StartsWith(prefix, StringComparison.Ordinal) &&
                    x.Length > prefix.Length && !x[prefix.Length..].Contains('/'))
                .Select(x => new LegacyEntry(x[prefix.Length..], true, 0,
                    DateTimeOffset.UnixEpoch))
                .Concat(files.Where(x => x.Key.StartsWith(prefix, StringComparison.Ordinal) &&
                    !x.Key[prefix.Length..].Contains('/'))
                    .Select(x => new LegacyEntry(x.Key[prefix.Length..], false,
                        x.Value.LongLength, DateTimeOffset.UnixEpoch)))
                .ToArray();
        }
        public LegacyEntry? Stat(string path) =>
            directories.Contains(path) ? new LegacyEntry(
                path.Split('/')[^1], true, 0, DateTimeOffset.UnixEpoch) :
            files.TryGetValue(path, out var bytes) ? new LegacyEntry(
                path.Split('/')[^1], false, bytes.Length, DateTimeOffset.UnixEpoch) : null;
        public Stream OpenRead(string path, long offset)
        {
            var stream = new MemoryStream(files[path], writable: false);
            stream.Seek(offset, SeekOrigin.Begin);
            return stream;
        }
        public void Upload(string path, string localFile) => files[path] = File.ReadAllBytes(localFile);
        public void Rename(string from, string to)
        {
            if (files.Remove(from, out var data)) { files[to] = data; return; }
            if (directories.Remove(from)) { directories.Add(to); return; }
            throw new FileNotFoundException(from);
        }
        public void Delete(string path, bool directory)
        {
            if (directory) directories.Remove(path);
            else files.Remove(path);
        }
        public void CreateDirectory(string path) => directories.Add(path);
        internal string ReadText(string path) => Encoding.UTF8.GetString(files[path]);
        public void Dispose() { }
    }

    internal static async Task RunAsync(string? rcloneExe = null)
    {
        CharsetSupport.SelfTest();
        if (Environment.GetEnvironmentVariable("NASMOUNT_CHARSET_FTP_LAB") == "1")
        {
            RunLiveFtpLab();
            return;
        }
        if (Environment.GetEnvironmentVariable("NASMOUNT_CHARSET_SFTP_LAB") == "1")
        {
            await RunLiveSftpLabAsync();
            return;
        }
        foreach (string item in new[] {
            "CP949 / EUC-KR (한국어)", "Shift-JIS / CP932 (일본어)",
            "Big5 / CP950 (중국어 번체)" })
            Console.WriteLine("CHARSET_AVAILABLE " + item + "=" +
                CharsetSupport.IsSupported("FTP", item));
        if (!CharsetSupport.IsSupported("FTP", "CP949 / EUC-KR (한국어)") ||
            !CharsetSupport.IsSupported("SFTP", "Shift-JIS / CP932 (일본어)") ||
            !CharsetSupport.IsSupported("FTP", "Big5 / CP950 (중국어 번체)"))
            throw new InvalidOperationException("Primary legacy code page not supported");
        var fake = new FakeStore();
        using var gateway = LegacyCharsetGateway.StartLab(fake);
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        using (var readOnly = LegacyCharsetGateway.StartLab(fake, writable: false))
        using (var forbidden = await http.PutAsync(
            readOnly.Url + Uri.EscapeDataString("must-not-write.txt"),
            new StringContent("denied")))
            if (forbidden.StatusCode != HttpStatusCode.Forbidden)
                throw new InvalidOperationException("Readonly gateway accepted a write");

        using (var propfind = new HttpRequestMessage(new HttpMethod("PROPFIND"), gateway.Url))
        {
            propfind.Headers.TryAddWithoutValidation("Depth", "1");
            using var response = await http.SendAsync(propfind);
            string xml = await response.Content.ReadAsStringAsync();
            if ((int)response.StatusCode != 207 || !xml.Contains("한글 파일.txt"))
                throw new InvalidOperationException("Legacy charset PROPFIND listing failed");
        }

        using (var request = new HttpRequestMessage(HttpMethod.Get,
            gateway.Url + Uri.EscapeDataString("한글 파일.txt")))
        {
            request.Headers.Range = new RangeHeaderValue(2, 4);
            using var response = await http.SendAsync(request);
            if (response.StatusCode != HttpStatusCode.PartialContent ||
                await response.Content.ReadAsStringAsync() != "CDE")
                throw new InvalidOperationException("Legacy charset ranged GET failed");
        }

        using (var put = new HttpRequestMessage(HttpMethod.Put,
            gateway.Url + Uri.EscapeDataString("새 문서.txt")))
        {
            put.Content = new ByteArrayContent(Encoding.UTF8.GetBytes("TEST CONTENT"));
            using var response = await http.SendAsync(put);
            if (response.StatusCode != HttpStatusCode.Created ||
                fake.ReadText("새 문서.txt") != "TEST CONTENT")
                throw new InvalidOperationException("Legacy charset PUT staging failed");
        }

        // Exercise streaming/chunked upload independently from Content-Length.
        using (var put = new HttpRequestMessage(HttpMethod.Put,
            gateway.Url + Uri.EscapeDataString("새 문서.txt")))
        {
            put.Content = new StreamContent(new MemoryStream(Encoding.UTF8.GetBytes("UPDATED")));
            put.Headers.TransferEncodingChunked = true;
            using var response = await http.SendAsync(put);
            if (response.StatusCode != HttpStatusCode.NoContent ||
                fake.ReadText("새 문서.txt") != "UPDATED")
                throw new InvalidOperationException("Legacy charset chunked overwrite failed");
        }

        using (var create = new HttpRequestMessage(new HttpMethod("MKCOL"),
            gateway.Url + Uri.EscapeDataString("사진")))
        {
            using var response = await http.SendAsync(create);
            if (response.StatusCode != HttpStatusCode.Created || fake.Stat("사진") is null)
                throw new InvalidOperationException("Legacy charset folder create failed");
        }
        using (var rename = new HttpRequestMessage(new HttpMethod("MOVE"),
            gateway.Url + Uri.EscapeDataString("새 문서.txt")))
        {
            rename.Headers.TryAddWithoutValidation("Destination",
                gateway.Url + Uri.EscapeDataString("변경 이름.txt"));
            using var response = await http.SendAsync(rename);
            if (response.StatusCode != HttpStatusCode.Created ||
                fake.ReadText("변경 이름.txt") != "UPDATED")
                throw new InvalidOperationException("Legacy charset MOVE failed");
        }
        using (var collision = new HttpRequestMessage(new HttpMethod("MOVE"),
            gateway.Url + Uri.EscapeDataString("변경 이름.txt")))
        {
            collision.Headers.TryAddWithoutValidation("Destination",
                gateway.Url + Uri.EscapeDataString("한글 파일.txt"));
            using var response = await http.SendAsync(collision);
            if (response.StatusCode != HttpStatusCode.PreconditionFailed ||
                fake.ReadText("한글 파일.txt") != "ABCDEF")
                throw new InvalidOperationException("Legacy charset safe overwrite guard failed");
        }
        using (var copy = new HttpRequestMessage(new HttpMethod("COPY"),
            gateway.Url + Uri.EscapeDataString("변경 이름.txt")))
        {
            copy.Headers.TryAddWithoutValidation("Destination",
                gateway.Url + Uri.EscapeDataString("복사본.txt"));
            using var response = await http.SendAsync(copy);
            if (response.StatusCode != HttpStatusCode.Created ||
                fake.ReadText("복사본.txt") != "UPDATED")
                throw new InvalidOperationException("Legacy charset COPY failed");
        }
        using (var remove = new HttpRequestMessage(HttpMethod.Delete,
            gateway.Url + Uri.EscapeDataString("복사본.txt")))
        {
            using var response = await http.SendAsync(remove);
            if (response.StatusCode != HttpStatusCode.NoContent ||
                fake.Stat("복사본.txt") is not null)
                throw new InvalidOperationException("Legacy charset DELETE failed");
        }
        using (var foreign = new HttpRequestMessage(new HttpMethod("MOVE"),
            gateway.Url + Uri.EscapeDataString("변경 이름.txt")))
        {
            foreign.Headers.TryAddWithoutValidation("Destination",
                "http://127.0.0.1:12501/not-owned/foreign.txt");
            using var response = await http.SendAsync(foreign);
            if (response.StatusCode != HttpStatusCode.Forbidden)
                throw new InvalidOperationException("Legacy charset origin guard failed");
        }
        Console.WriteLine("LEGACY_CHARSET_DAV_GET_RANGE_PUT_CHUNKED_MKCOL_MOVE_COPY_DELETE_PASS");
        if (rcloneExe is not null && File.Exists(rcloneExe))
        {
            async Task<string> RcloneAsync(params string[] arguments)
            {
                var psi = new System.Diagnostics.ProcessStartInfo(rcloneExe)
                {
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardOutput = true, RedirectStandardError = true,
                    StandardOutputEncoding = new UTF8Encoding(false, true),
                    StandardErrorEncoding = new UTF8Encoding(false, true)
                };
                psi.Environment["RCLONE_CONFIG_NM_CHARSET_LAB_TYPE"] = "webdav";
                psi.Environment["RCLONE_CONFIG_NM_CHARSET_LAB_URL"] = gateway.Url;
                psi.Environment["RCLONE_CONFIG_NM_CHARSET_LAB_VENDOR"] = "other";
                foreach (var a in arguments) psi.ArgumentList.Add(a);
                psi.ArgumentList.Add("--config");
                psi.ArgumentList.Add(Path.Combine(AppStore.DirectoryPath,
                    "no-global-rclone.conf"));
                using var process = new System.Diagnostics.Process { StartInfo = psi };
                process.Start();
                var stdout = process.StandardOutput.ReadToEndAsync();
                var stderr = process.StandardError.ReadToEndAsync();
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(25));
                try { await process.WaitForExitAsync(cts.Token); }
                catch (OperationCanceledException)
                {
                    if (!process.HasExited) process.Kill(entireProcessTree: true);
                    throw new TimeoutException("Legacy charset simulated rclone timed out");
                }
                string result = await stdout, error = await stderr;
                if (process.ExitCode != 0)
                    throw new InvalidOperationException("rclone legacy gateway lab failed: " +
                        string.Join(" ", arguments) + " " +
                        error[..Math.Min(error.Length, 900)]);
                return result;
            }
            string listed = await RcloneAsync("lsf", "nm_charset_lab:",
                "--max-depth", "1");
            Console.WriteLine("RCLONE_LIST_LAB_QUOTED=" +
                System.Text.Json.JsonSerializer.Serialize(listed));
            if (!listed.Contains("한글 파일.txt") ||
                !listed.Contains("변경 이름.txt"))
                throw new InvalidOperationException("Real rclone failed to list Korean filename");
            string tmp = Path.Combine(Path.GetTempPath(),
                "NASMount_charset_" + Guid.NewGuid().ToString("N") + ".txt");
            try
            {
                await File.WriteAllTextAsync(tmp, "RCLONE-UNICODE-PASS");
                await RcloneAsync("copyto", tmp, "nm_charset_lab:한글 업로드.txt");
                if (fake.ReadText("한글 업로드.txt") != "RCLONE-UNICODE-PASS")
                    throw new InvalidOperationException("Real rclone PUT content mismatch");
                await RcloneAsync("moveto", "nm_charset_lab:한글 업로드.txt",
                    "nm_charset_lab:이름 변경.txt");
                if (fake.ReadText("이름 변경.txt") != "RCLONE-UNICODE-PASS")
                    throw new InvalidOperationException("Real rclone MOVE content mismatch");
            }
            finally { if (File.Exists(tmp)) File.Delete(tmp); }
            Console.WriteLine("LEGACY_CHARSET_REAL_RCLONE_LSF_COPYTO_MOVETO_PASS");
        }
    }

    private static void RunLiveFtpLab()
    {
        // Must be an opt-in local fixture; never connect to a user's NAS.
        var profile = new NasProfile
        {
            Protocol = "FTP", Host = "127.0.0.1", Port = 22944,
            Username = "naslab", DisplayName = "QA charset fixture",
            Letter = "Q", FtpCharset = "CP949 / EUC-KR (한국어)",
            ExplicitTls = false
        };
        using var adapter = new LegacyCharsetStorage(profile, "naslab-pass",
            CharsetSupport.GetEncoding(profile.FtpCharset)!);
        string sample = Path.Combine(Path.GetTempPath(),
            "NASMount_charset_ftp_" + Guid.NewGuid().ToString("N") + ".txt");
        try
        {
            File.WriteAllText(sample, "LOCAL-CP949-WIRE-ROUNDTRIP");
            adapter.Upload("한글 이름.txt", sample);
            if (!adapter.List("").Any(x => x.Name == "한글 이름.txt"))
                throw new InvalidOperationException("Actual FTP CP949 filename listing failed");
            using (var input = adapter.OpenRead("한글 이름.txt", 0))
            using (var reader = new StreamReader(input, Encoding.UTF8))
                if (reader.ReadToEnd() != "LOCAL-CP949-WIRE-ROUNDTRIP")
                    throw new InvalidOperationException("Actual FTP CP949 file read failed");
            adapter.Rename("한글 이름.txt", "변경 성공.txt");
            if (adapter.Stat("변경 성공.txt")?.Size !=
                Encoding.UTF8.GetByteCount("LOCAL-CP949-WIRE-ROUNDTRIP"))
                throw new InvalidOperationException("Actual FTP CP949 rename failed");
            adapter.Delete("변경 성공.txt", false);
            if (adapter.Stat("변경 성공.txt") is not null)
                throw new InvalidOperationException("Actual FTP CP949 delete failed");
        }
        finally { if (File.Exists(sample)) File.Delete(sample); }
        Console.WriteLine("LEGACY_CHARSET_REAL_FTP_CP949_UPLOAD_LIST_GET_RENAME_DELETE_PASS");
    }

    private static async Task RunLiveSftpLabAsync()
    {
        var profile = new NasProfile
        {
            Protocol = "SFTP", Host = "127.0.0.1", Port = 22945,
            Username = "naslab", DisplayName = "QA charset fixture",
            Letter = "Q", SftpCharset = "CP949 / EUC-KR (한국어)"
        };
        var observed = await SftpHostKeyTrust.ObserveAsync(profile);
        SftpHostKeyTrust.Approve(profile, observed);
        if (!SftpHostKeyTrust.HasTrustedKey(profile))
            throw new InvalidOperationException("SFTP lab host key approval failed");
        using var adapter = new LegacyCharsetStorage(profile, "naslab-pass",
            CharsetSupport.GetEncoding(profile.SftpCharset)!);
        string sample = Path.Combine(Path.GetTempPath(),
            "NASMount_charset_sftp_" + Guid.NewGuid().ToString("N") + ".txt");
        try
        {
            File.WriteAllText(sample, "LOCAL-SFTP-CP949-WIRE-ROUNDTRIP");
            adapter.Upload("한글 이름.txt", sample);
            if (!adapter.List("").Any(x => x.Name == "한글 이름.txt"))
                throw new InvalidOperationException("Actual SFTP CP949 filename listing failed");
            using (var input = adapter.OpenRead("한글 이름.txt", 0))
            using (var reader = new StreamReader(input, Encoding.UTF8))
                if (reader.ReadToEnd() != "LOCAL-SFTP-CP949-WIRE-ROUNDTRIP")
                    throw new InvalidOperationException("Actual SFTP CP949 file read failed");
            adapter.Rename("한글 이름.txt", "변경 성공.txt");
            if (adapter.Stat("변경 성공.txt")?.Size !=
                Encoding.UTF8.GetByteCount("LOCAL-SFTP-CP949-WIRE-ROUNDTRIP"))
                throw new InvalidOperationException("Actual SFTP CP949 rename failed");
            adapter.Delete("변경 성공.txt", false);
            if (adapter.Stat("변경 성공.txt") is not null)
                throw new InvalidOperationException("Actual SFTP CP949 delete failed");
        }
        finally { if (File.Exists(sample)) File.Delete(sample); }
        Console.WriteLine("LEGACY_CHARSET_REAL_SFTP_CP949_HOSTKEY_UPLOAD_LIST_GET_RENAME_DELETE_PASS");
    }
}
