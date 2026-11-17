using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

// Keep DSM credentials separate from the existing SMB/WebDAV/rclone credentials.
// No password, OTP, session ID or CSRF token is stored in config.json.
public sealed class SynologyShareSettings
{
    public string DsmUrl { get; set; } = "";
    public string User { get; set; } = "";
    public string ShareRoot { get; set; } = "";
}

internal sealed record SynologySharedLink(string Id, string Path, string Url,
    string Owner, string Expires);

internal static class SynologyShareStore
{
    private static readonly string ConfigPath =
        Path.Combine(AppStore.DirectoryPath, "synology-sharing.json");

    internal static SynologyShareSettings? Read(Guid id)
    {
        if (!File.Exists(ConfigPath)) return null;
        var entries = JsonSerializer.Deserialize<Dictionary<Guid, SynologyShareSettings>>(
            File.ReadAllText(ConfigPath));
        return entries is not null && entries.TryGetValue(id, out var item) ? item : null;
    }

    internal static void Save(Guid id, SynologyShareSettings settings)
    {
        Directory.CreateDirectory(AppStore.DirectoryPath);
        Dictionary<Guid, SynologyShareSettings> entries =
            File.Exists(ConfigPath) ?
                JsonSerializer.Deserialize<Dictionary<Guid,SynologyShareSettings>>(
                    File.ReadAllText(ConfigPath)) ?? new() : new();
        entries[id] = settings;
        string temp = ConfigPath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(entries, new JsonSerializerOptions {
            WriteIndented = true
        }));
        File.Move(temp, ConfigPath, overwrite: true);
    }
}

internal static class SynologySharing
{
    internal static bool IsNas(NasProfile profile) =>
        profile.Protocol is "SMB" or "WebDAV" or "SFTP" or "FTP";

    internal static Guid CredentialId(Guid profileId)
    {
        byte[] input = Encoding.UTF8.GetBytes("FileHubAIO:DSM:Sharing:v1:" + profileId.ToString("D"));
        return new Guid(SHA256.HashData(input).AsSpan(0, 16));
    }

    internal static Uri ValidateDsmUrl(string url)
    {
        if (!Uri.TryCreate(url.Trim(), UriKind.Absolute, out var uri) ||
            uri.Scheme != Uri.UriSchemeHttps || uri.UserInfo.Length != 0 ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0 ||
            uri.AbsolutePath.Trim('/') != "" || uri.Host.Length == 0)
            throw new ArgumentException("DSM 주소는 https://NAS주소:5001 형식으로 입력하세요. 경로와 계정은 넣지 마세요.");
        return uri;
    }

    internal static string NormalizeRoot(string root)
    {
        string value = root.Replace('\\', '/').Trim().TrimEnd('/');
        if (value.Length < 2 || !value.StartsWith('/') ||
            value.Contains("//") || value.Contains(':') ||
            value.Split('/').Skip(1).Any(s => s is "" or "." or ".."))
            throw new ArgumentException("DSM 공유 폴더는 /video 같은 최상위 공유 폴더 경로여야 합니다.");
        // Require one true File Station share: do not grant a guessed nested root.
        if (value.IndexOf('/', 1) >= 0)
            throw new ArgumentException("DSM 공유 폴더는 /video 처럼 최상위 폴더 1개만 지정하세요.");
        return value;
    }

    internal static string MapExplorerPath(NasProfile profile, string shareRoot, string windowsPath)
    {
        string root = NormalizeRoot(shareRoot);
        if (!Regex.IsMatch(profile.Letter ?? "", "^[A-Za-z]$"))
            throw new ArgumentException("드라이브 문자를 확인하세요.");
        string drive = char.ToUpperInvariant(profile.Letter[0]) + @":\";
        if (windowsPath.Split('\\').Any(segment => segment is "." or ".."))
            throw new ArgumentException("상대 이동 구문은 허용되지 않습니다.");
        string full = Path.GetFullPath(windowsPath);
        if (!full.StartsWith(drive, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("선택한 파일은 해당 FileHub AIO 드라이브에 없습니다.");
        string relative = full[drive.Length..];
        string[] parts = relative.Split('\\', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Any(s => s is "." or ".." || s.Contains(':')))
            throw new ArgumentException("유효하지 않은 경로입니다.");
        return root + (parts.Length > 0 ? "/" + string.Join("/", parts) : "");
    }

    internal static string MapRelativePath(string shareRoot, string relative)
    {
        string root = NormalizeRoot(shareRoot);
        string clean = relative.Trim().Replace('\\', '/').Trim('/');
        if (clean.Length == 0 || clean == ".") return root;
        if (clean.Split('/').Any(p => p is "" or "." or ".." || p.Contains(':')))
            throw new ArgumentException("공유할 상대 경로를 다시 확인하세요.");
        return root + "/" + clean;
    }

    internal static bool InRoot(string shareRoot, string path)
    {
        string root = NormalizeRoot(shareRoot);
        return string.Equals(path, root, StringComparison.Ordinal) ||
            path.StartsWith(root + "/", StringComparison.Ordinal);
    }

    internal static void PolicyTest()
    {
        var p = new NasProfile { Protocol = "WebDAV", Letter = "X" };
        if (MapExplorerPath(p, "/video", @"X:\movie\가족.mp4") != "/video/movie/가족.mp4" ||
            MapRelativePath("/video", @"sub\movie.mp4") != "/video/sub/movie.mp4" ||
            !InRoot("/video", "/video/file") || InRoot("/video", "/video2/file") ||
            CredentialId(p.Id) == p.Id)
            throw new Exception("Synology path/credential mapping failed.");
        foreach (string path in new[] { @"C:\other.mp4", @"X:\..\Windows\file", @"\\server\other\file" })
        {
            try { MapExplorerPath(p, "/video", path); throw new Exception("Unsafe path accepted"); }
            catch (ArgumentException) { }
        }
        foreach (string url in new[] { "http://nas:5000", "https://user:pw@nas:5001",
                    "https://nas:5001/webapi", "https://nas:5001?sid=123" })
        {
            try { ValidateDsmUrl(url); throw new Exception("Unsafe DSM URL accepted"); }
            catch (ArgumentException) { }
        }
        if (ValidateDsmUrl("https://nas.example.com:5001").Scheme != "https")
            throw new Exception("Valid DSM URL rejected.");
        if (!SynologyExplorerMenu.BuildCommand(@"C:\Apps\FileHubAIO.exe")
            .Contains("--synology-share \"%1\"", StringComparison.Ordinal))
            throw new Exception("Explorer shell argument quoting broken.");
        Console.WriteLine("SYNOLOGY_SHARING_PATH_URL_REGISTRY_POLICY_PASS");
    }
}

internal sealed class SynologyOtpRequiredException(bool rejected)
    : IOException(rejected ? "DSM 2단계 인증코드 오류입니다. 새 OTP를 입력하세요." :
        "DSM 2단계 인증코드가 필요합니다. OTP 6자리를 입력하세요.");

internal sealed class SynologySharingClient
{
    private readonly SynologyShareSettings settings;
    private readonly string password;
    private readonly string otp;
    private readonly Uri baseUri;
    private static readonly HttpClient Http = new(new HttpClientHandler {
        AllowAutoRedirect = false, UseCookies = false
    }) { Timeout = TimeSpan.FromSeconds(22) };

    internal SynologySharingClient(SynologyShareSettings settings, string password, string otp)
    {
        this.settings = settings;
        this.password = password;
        this.otp = otp.Trim();
        baseUri = SynologySharing.ValidateDsmUrl(settings.DsmUrl);
        SynologySharing.NormalizeRoot(settings.ShareRoot);
        if (string.IsNullOrWhiteSpace(settings.User))
            throw new ArgumentException("DSM 계정을 입력하세요.");
        if (string.IsNullOrEmpty(password))
            throw new ArgumentException("DSM 비밀번호가 필요합니다.");
        if (this.otp.Length > 0 && !Regex.IsMatch(this.otp, @"^\d{6}$"))
            throw new ArgumentException("OTP는 숫자 6자리여야 합니다.");
    }

    private Uri Endpoint(string path, string token = "")
    {
        if (!Regex.IsMatch(path, @"^[A-Za-z0-9/_-]+\.cgi$") || path.Contains(".."))
            throw new InvalidDataException("DSM API 경로가 유효하지 않습니다.");
        Uri endpoint = new(baseUri, "webapi/" + path);
        if (!string.IsNullOrEmpty(token))
            return new Uri(endpoint + "?SynoToken=" + Uri.EscapeDataString(token));
        return endpoint;
    }

    private async Task<JsonElement> Request(string path, IDictionary<string,string>? parameters,
        string token = "")
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, Endpoint(path, token));
        request.Headers.TryAddWithoutValidation("Cache-Control", "no-store");
        request.Content = new FormUrlEncodedContent(parameters ?? new Dictionary<string,string>());
        using var response = await Http.SendAsync(request);
        response.EnsureSuccessStatusCode();
        string raw = await response.Content.ReadAsStringAsync();
        if (raw.Length > 2_000_000)
            throw new IOException("DSM 응답이 너무 큽니다.");
        using var json = JsonDocument.Parse(raw);
        JsonElement obj = json.RootElement;
        if (!obj.TryGetProperty("success", out var success) || !success.GetBoolean())
        {
            int code = obj.TryGetProperty("error", out var err) &&
                err.TryGetProperty("code", out var c) ? c.GetInt32() : -1;
            if (parameters is not null &&
                parameters.TryGetValue("api", out var api) && api == "SYNO.API.Auth" &&
                parameters.TryGetValue("method", out var method) && method == "login" &&
                (code == 403 || code == 404))
                throw new SynologyOtpRequiredException(code == 404);
            throw new IOException("DSM " + (parameters is not null &&
                parameters.TryGetValue("api", out var name) ? name : "API") +
                " 요청 거부 (코드 " + code + "). 권한과 DSM 설정을 확인하세요.");
        }
        return obj.TryGetProperty("data", out var data) ? data.Clone() : default;
    }

    private sealed record ApiSpec(string Path, int Version);

    private async Task<ApiSpec> Discover(string api, int requestedVersion)
    {
        var map = await Request("query.cgi", new Dictionary<string, string> {
            ["api"] = "SYNO.API.Info", ["version"] = "1",
            ["method"] = "query", ["query"] = api
        });
        if (!map.TryGetProperty(api, out var spec))
            throw new IOException("DSM에서 " + api + " API를 찾을 수 없습니다.");
        int max = spec.GetProperty("maxVersion").GetInt32();
        int min = spec.GetProperty("minVersion").GetInt32();
        int version = Math.Min(requestedVersion, max);
        if (version < min || (api == "SYNO.FileStation.Sharing" && version < 3))
            throw new IOException("공유 링크 생성에 필요한 DSM API 버전을 지원하지 않습니다.");
        return new ApiSpec(spec.GetProperty("path").GetString() ?? "", version);
    }

    private async Task<T> WithSession<T>(Func<Func<string,IDictionary<string,string>,
        Task<JsonElement>>,Task<T>> operation)
    {
        var auth = await Discover("SYNO.API.Auth", 6);
        var share = await Discover("SYNO.FileStation.Sharing", 3);
        var login = new Dictionary<string,string> {
            ["api"] = "SYNO.API.Auth", ["version"] = auth.Version.ToString(),
            ["method"] = "login", ["account"] = settings.User,
            ["passwd"] = password, ["session"] = "FileStation",
            ["format"] = "sid", ["enable_syno_token"] = "yes"
        };
        if (otp.Length == 6) login["otp_code"] = otp;
        var loginData = await Request(auth.Path, login);
        string sid = loginData.TryGetProperty("sid", out var sn) ? sn.GetString() ?? "" : "";
        string token = loginData.TryGetProperty("synotoken", out var tn) ? tn.GetString() ?? "" :
            loginData.TryGetProperty("SynoToken", out var alt) ? alt.GetString() ?? "" : "";
        if (sid.Length < 4)
            throw new IOException("DSM 로그인 세션이 반환되지 않았습니다.");
        async Task<JsonElement> Invoke(string method, IDictionary<string,string> fields)
        {
            var values = new Dictionary<string,string>(fields) {
                ["api"] = "SYNO.FileStation.Sharing", ["version"] = share.Version.ToString(),
                ["method"] = method, ["_sid"] = sid
            };
            return await Request(share.Path, values, token);
        }
        try { return await operation(Invoke); }
        finally
        {
            try
            {
                await Request(auth.Path, new Dictionary<string,string> {
                    ["api"] = "SYNO.API.Auth", ["version"] = auth.Version.ToString(),
                    ["method"] = "logout", ["session"] = "FileStation", ["_sid"] = sid
                }, token);
            }
            catch { /* Do not mask primary operation failures. */ }
        }
    }

    internal async Task TestAsync()
    {
        await WithSession(async call => {
            await call("list", new Dictionary<string,string> {
                ["offset"] = "0", ["limit"] = "1"
            });
            return true;
        });
    }

    internal async Task<SynologySharedLink> CreateAsync(string filePath,
        int expiryDays, string linkPassword)
    {
        if (!SynologySharing.InRoot(settings.ShareRoot, filePath))
            throw new ArgumentException("설정된 공유 폴더 밖의 경로는 링크를 만들 수 없습니다.");
        if (linkPassword.Length > 16)
            throw new ArgumentException("시놀로지 링크 암호는 16자 이내여야 합니다.");
        if (expiryDays is not (0 or 1 or 3 or 7 or 30))
            throw new ArgumentException("유효 기간을 다시 선택하세요.");
        return await WithSession(async call => {
            var fields = new Dictionary<string,string> {
                ["path"] = JsonSerializer.Serialize(filePath),
                ["date_expired"] = expiryDays == 0 ? "0" :
                    JsonSerializer.Serialize(DateTime.Today.AddDays(expiryDays).ToString("yyyy-MM-dd"))
            };
            if (linkPassword.Length > 0)
                fields["password"] = JsonSerializer.Serialize(linkPassword);
            var data = await call("create", fields);
            if (!data.TryGetProperty("links", out var links) ||
                links.ValueKind != JsonValueKind.Array || links.GetArrayLength() == 0)
                throw new IOException("DSM이 생성된 공유 링크를 반환하지 않았습니다.");
            var link = links[0];
            int error = link.TryGetProperty("error", out var e) ? e.GetInt32() : 0;
            if (error != 0) throw new IOException("DSM 링크 생성 실패 (코드 " + error + ").");
            string url = link.TryGetProperty("url", out var u) ? u.GetString() ?? "" : "";
            if (!Uri.TryCreate(url, UriKind.Absolute, out var parsed) ||
                parsed.Scheme is not ("http" or "https"))
                throw new IOException("DSM이 유효한 링크 URL을 반환하지 않았습니다.");
            return new SynologySharedLink(
                link.TryGetProperty("id", out var id) ? id.GetString() ?? "" : "",
                filePath, url, settings.User,
                expiryDays == 0 ? "무제한" : fields["date_expired"].Trim('"'));
        });
    }

    internal async Task<List<SynologySharedLink>> ListAsync()
    {
        return await WithSession(async call => {
            var list = new List<SynologySharedLink>();
            int offset = 0;
            for (int page = 0; page < 10; page++)
            {
                var data = await call("list", new Dictionary<string,string> {
                    ["offset"] = offset.ToString(), ["limit"] = "100"
                });
                if (!data.TryGetProperty("links", out var links) ||
                    links.ValueKind != JsonValueKind.Array) break;
                foreach (var item in links.EnumerateArray())
                {
                    string path = item.TryGetProperty("path", out var p) ? p.GetString() ?? "" : "";
                    string owner = item.TryGetProperty("link_owner", out var o) ? o.GetString() ?? "" : "";
                    if (!SynologySharing.InRoot(settings.ShareRoot, path) ||
                        (owner.Length > 0 &&
                         !string.Equals(owner, settings.User, StringComparison.OrdinalIgnoreCase)))
                        continue;
                    string id = item.TryGetProperty("id", out var i) ? i.GetString() ?? "" : "";
                    string url = item.TryGetProperty("url", out var u) ? u.GetString() ?? "" : "";
                    if (id.Length > 0) list.Add(new(id, path, url, owner,
                        item.TryGetProperty("date_expired", out var d) ? d.GetString() ?? "" : ""));
                }
                int found = links.GetArrayLength();
                offset += found;
                int total = data.TryGetProperty("total", out var t) ? t.GetInt32() : offset;
                if (found == 0 || offset >= total) break;
            }
            return list;
        });
    }

    internal async Task DeleteAsync(IEnumerable<SynologySharedLink> links)
    {
        var target = links.ToArray();
        if (target.Length == 0 || target.Length > 100 ||
            target.Any(x => !SynologySharing.InRoot(settings.ShareRoot, x.Path) ||
                string.IsNullOrWhiteSpace(x.Id) || x.Id.Length > 128 ||
                x.Id.Any(char.IsControl)))
            throw new ArgumentException("선택한 공유 링크를 다시 확인하세요.");
        await WithSession(async call => {
            // One ID per call follows the known-good Android File Station API
            // contract and avoids undocumented multi-ID encodings.
            foreach (var item in target.DistinctBy(x => x.Id))
                await call("delete", new Dictionary<string,string> {
                    ["id"] = JsonSerializer.Serialize(item.Id)
                });
            return true;
        });
    }
}
