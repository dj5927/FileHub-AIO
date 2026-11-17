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
    // v1.3.85 legacy field: retained for backward-compatible migration.
    public string ShareRoot { get; set; } = "";
    public List<string> ShareRoots { get; set; } = [];
}

internal sealed record SynologySharedLink(string Id, string Path, string Url,
    string Owner, string Expires);
internal sealed record SynologyFileEntry(string Name, string Path, bool IsDirectory);

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

    // Trust tokens must not leak from one DSM endpoint/account to another.
    internal static Guid TrustedDeviceCredentialId(Guid profileId, string dsmUrl, string username)
    {
        string scoped = "FileHubAIO:DSM:TrustedDevice:v1:" + profileId.ToString("D") +
            ":" + ValidateDsmUrl(dsmUrl).GetLeftPart(UriPartial.Authority)
                .ToLowerInvariant() + ":" + username.Trim().ToLowerInvariant();
        return new Guid(SHA256.HashData(Encoding.UTF8.GetBytes(scoped)).AsSpan(0, 16));
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

    internal static List<string> NormalizeRoots(IEnumerable<string>? roots)
    {
        if (roots is null) return [];
        return roots.Select(NormalizeRoot).Distinct(StringComparer.Ordinal).ToList();
    }

    internal static List<string> Roots(SynologyShareSettings settings) =>
        settings.ShareRoots is { Count: > 0 }
            ? NormalizeRoots(settings.ShareRoots)
            : string.IsNullOrWhiteSpace(settings.ShareRoot)
                ? []
                : [NormalizeRoot(settings.ShareRoot)];

    internal static bool IsAllowed(SynologyShareSettings settings, string path) =>
        Roots(settings).Any(root => InRoot(root, path));

    // A mounted drive may expose all DSM shares or start inside one share.
    // Never guess the first selected share when multiple shares are allowed.
    internal static string MapExplorerPath(NasProfile profile,
        SynologyShareSettings settings, string windowsPath)
    {
        var roots = Roots(settings);
        if (roots.Count == 0)
            throw new ArgumentException("DSM 공유 폴더를 하나 이상 선택하세요.");
        if (!Regex.IsMatch(profile.Letter ?? "", "^[A-Za-z]$"))
            throw new ArgumentException("드라이브 문자를 확인하세요.");
        string drive = char.ToUpperInvariant(profile.Letter[0]) + @":\";
        if (windowsPath.Split('\\').Any(s => s is "." or ".."))
            throw new ArgumentException("상대 이동 구문은 허용되지 않습니다.");
        string full = Path.GetFullPath(windowsPath);
        if (!full.StartsWith(drive, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("선택한 파일은 해당 FileHub AIO 드라이브에 없습니다.");
        string relative = full[drive.Length..].Replace('\\', '/').Trim('/');
        string direct = "/" + relative;
        if (IsAllowed(settings, direct)) return direct;
        string[] parts = (profile.Share ?? "").Replace('\\', '/').Trim('/')
            .Split('/', StringSplitOptions.RemoveEmptyEntries);
        foreach (string root in roots)
        {
            string name = root[1..];
            int i = Array.FindIndex(parts, part =>
                string.Equals(part, name, StringComparison.Ordinal));
            if (i < 0) continue;
            string prefix = root + (i + 1 < parts.Length
                ? "/" + string.Join("/", parts[(i + 1)..]) : "");
            string mapped = prefix + (relative.Length == 0 ? "" : "/" + relative);
            if (IsAllowed(settings, mapped)) return mapped;
        }
        if (roots.Count == 1) return MapExplorerPath(profile, roots[0], windowsPath);
        throw new ArgumentException(
            "드라이브 경로가 어느 DSM 공유 폴더에 속하는지 특정할 수 없습니다. " +
            "NAS 파일/폴더 탐색에서 직접 선택하세요.");
    }

    internal static string MapRelativePath(SynologyShareSettings settings, string relative)
    {
        var roots = Roots(settings);
        if (roots.Count == 0)
            throw new ArgumentException("DSM 공유 폴더를 하나 이상 선택하세요.");
        string clean = relative.Trim().Replace('\\', '/').Trim('/');
        if (clean.Split('/').Any(s => s is "." or ".." || s.Contains(':')))
            throw new ArgumentException("공유 경로를 다시 확인하세요.");
        string direct = "/" + clean;
        if (IsAllowed(settings, direct)) return direct;
        if (roots.Count == 1) return MapRelativePath(roots[0], relative);
        throw new ArgumentException(
            "여러 공유 폴더를 선택했다면 폴더명부터 경로를 입력하거나 " +
            "NAS 파일/폴더 탐색에서 선택하세요.");
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
        var legacy = new SynologyShareSettings { ShareRoot = "/video" };
        var multi = new SynologyShareSettings {
            ShareRoot = "/video", ShareRoots = ["/video", "/Public", "/video"]
        };
        if (!Roots(legacy).SequenceEqual(["/video"]) ||
            !Roots(multi).SequenceEqual(["/video", "/Public"]) ||
            !IsAllowed(multi, "/Public/movie.mp4") ||
            IsAllowed(multi, "/private/movie.mp4") ||
            IsAllowed(multi, "/videos/movie.mp4") ||
            MapRelativePath(multi, "Public/folder/movie.mp4") != "/Public/folder/movie.mp4" ||
            MapExplorerPath(p, multi, @"X:\Public\movie.mp4") != "/Public/movie.mp4")
            throw new Exception("Multi-share/legacy selection policy failed.");
        try {
            MapExplorerPath(p, multi, @"X:\movie.mp4");
            throw new Exception("Ambiguous multi-share path accepted.");
        } catch (ArgumentException) { }
        var mounted = new NasProfile { Protocol = "WebDAV", Letter = "X",
            Share = "/remote.php/dav/files/testuser/Public/subfolder" };
        if (MapExplorerPath(mounted, multi, @"X:\file.mp4") !=
            "/Public/subfolder/file.mp4")
            throw new Exception("Mapped transport path ignored share folder.");
        var roundTrip = JsonSerializer.Deserialize<SynologyShareSettings>(
            JsonSerializer.Serialize(multi))!;
        if (!Roots(roundTrip).SequenceEqual(["/video", "/Public"]))
            throw new Exception("Multi-share JSON compatibility failed.");
        if (TrustedDeviceCredentialId(p.Id, "https://nas.example.com:5001", "abc") ==
            TrustedDeviceCredentialId(p.Id, "https://nas.example.com:5001", "xyz") ||
            TrustedDeviceCredentialId(p.Id, "https://nas.example.com:5001", "abc") ==
            TrustedDeviceCredentialId(p.Id, "https://other.example.com:5001", "abc"))
            throw new Exception("DSM trusted-device token scope failed.");
        SynologySharingClient.TrustedDevicePolicyTest();
        using (var sampleShares = JsonDocument.Parse(
            """{"shares":[{"name":"01자료","path":"/01자료"},{"name":"video","path":"/video"},{"name":"invalid","path":"/video/nested"}]}"""))
        {
            var entries = SynologySharingClient.ParseShareEntries(sampleShares.RootElement);
            if (entries.Count != 2 || entries.All(e => e.Path != "/01자료") ||
                entries.Any(e => e.Path == "/video/nested"))
                throw new Exception("DSM list_share parsing failed.");
        }
        using (var sampleFolder = JsonDocument.Parse(
            """{"files":[{"name":"ok.mp4","path":"/Public/ok.mp4","isdir":false},{"name":"secret.txt","path":"/private/secret.txt","isdir":false},{"name":"sub","path":"/Public/sub","isdir":true}]}"""))
        {
            var entries = SynologySharingClient.ParseFolderEntries(multi, sampleFolder.RootElement);
            if (entries.Count != 2 || entries.Any(e => e.Path.StartsWith("/private/")) ||
                entries[0].Path != "/Public/sub")
                throw new Exception("Multi-share File Station list scope failed.");
        }
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
    private readonly bool trustThisPc;
    private readonly string savedDeviceId;
    private readonly Action<string>? onTrustedDeviceId;
    private readonly Action? onRejectedDeviceId;
    private readonly Uri baseUri;
    private static readonly HttpClient Http = new(new HttpClientHandler {
        AllowAutoRedirect = false, UseCookies = false
    }) { Timeout = TimeSpan.FromSeconds(22) };

    internal SynologySharingClient(SynologyShareSettings settings, string password, string otp,
        bool trustThisPc = false, string savedDeviceId = "",
        Action<string>? onTrustedDeviceId = null, Action? onRejectedDeviceId = null)
    {
        this.settings = settings;
        this.password = password;
        this.otp = otp.Trim();
        this.trustThisPc = trustThisPc;
        this.savedDeviceId = savedDeviceId;
        this.onTrustedDeviceId = onTrustedDeviceId;
        this.onRejectedDeviceId = onRejectedDeviceId;
        baseUri = SynologySharing.ValidateDsmUrl(settings.DsmUrl);
        // Listing shares must work before the first share is selected.
        SynologySharing.Roots(settings);
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
        if (!response.IsSuccessStatusCode)
            throw new IOException("DSM 관리 API HTTP " +
                (int)response.StatusCode + " 오류입니다. HTTPS 관리 주소·역방향 프록시 및 " +
                "File Station 권한을 확인하세요.");
        string raw = await response.Content.ReadAsStringAsync();
        if (raw.Length > 2_000_000)
            throw new IOException("DSM 응답이 너무 큽니다.");
        if (raw.TrimStart().StartsWith("<", StringComparison.Ordinal))
            throw new IOException("DSM이 API JSON 대신 웹 로그인 페이지를 반환했습니다. " +
                "DSM 관리 HTTPS 주소와 역방향 프록시 경로를 확인하세요.");
        JsonDocument parsed;
        try { parsed = JsonDocument.Parse(raw); }
        catch (JsonException)
        {
            throw new IOException("DSM API 응답이 JSON 형식이 아닙니다. 관리 주소와 " +
                "File Station API 지원 여부를 확인하세요.");
        }
        using var json = parsed;
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
            string apiName = parameters is not null &&
                parameters.TryGetValue("api", out var name) ? name : "API";
            string methodName = parameters is not null &&
                parameters.TryGetValue("method", out var m) ? m : "unknown";
            string advice = apiName == "SYNO.FileStation.Sharing" &&
                methodName == "create" ?
                "NAS 파일/폴더 탐색에서 실제 파일을 다시 선택하고 DSM File Station의 " +
                "공유 권한을 확인하세요." :
                apiName == "SYNO.FileStation.List" ?
                "DSM File Station 권한과 선택한 공유 폴더 접근 권한을 확인하세요." :
                "DSM 계정 권한과 NAS 관리 주소를 확인하세요.";
            throw new IOException("DSM " + apiName + " / " + methodName +
                " 요청 거부 (코드 " + code + "). " + advice);
        }
        return obj.TryGetProperty("data", out var data) ? data.Clone() : default;
    }

    private sealed record ApiSpec(string Path, int Version);

    internal static Dictionary<string, string> BuildLoginFields(
        string user, string password, int version, string otp,
        bool trustThisPc, string savedDeviceId)
    {
        var fields = new Dictionary<string, string> {
            ["api"] = "SYNO.API.Auth", ["version"] = version.ToString(),
            ["method"] = "login", ["account"] = user, ["passwd"] = password,
            ["session"] = "FileStation", ["format"] = "sid",
            ["enable_syno_token"] = "yes"
        };
        if (otp.Length == 6)
        {
            fields["otp_code"] = otp;
            if (trustThisPc)
            {
                fields["enable_device_token"] = "yes";
                fields["device_name"] = "FileHub AIO Windows";
            }
        }
        else if (!string.IsNullOrWhiteSpace(savedDeviceId))
        {
            fields["device_name"] = "FileHub AIO Windows";
            fields["device_id"] = savedDeviceId;
        }
        return fields;
    }

    internal static void TrustedDevicePolicyTest()
    {
        var first = BuildLoginFields("user", "secret", 6, "123456", true, "");
        var followup = BuildLoginFields("user", "secret", 6, "", false, "did-example");
        var notTrusted = BuildLoginFields("user", "secret", 6, "123456", false, "");
        if (first.GetValueOrDefault("otp_code") != "123456" ||
            first.GetValueOrDefault("enable_device_token") != "yes" ||
            first.GetValueOrDefault("device_name") != "FileHub AIO Windows" ||
            first.ContainsKey("device_id") ||
            followup.GetValueOrDefault("device_id") != "did-example" ||
            followup.ContainsKey("otp_code") ||
            notTrusted.ContainsKey("enable_device_token"))
            throw new Exception("Synology trusted-device / 2FA policy failed.");
    }

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
        Task<JsonElement>>,Task<T>> operation, bool useFileList = false)
    {
        var auth = await Discover("SYNO.API.Auth", 6);
        string targetApi = useFileList ? "SYNO.FileStation.List" :
            "SYNO.FileStation.Sharing";
        var share = await Discover(targetApi, useFileList ? 2 : 3);
        var login = BuildLoginFields(settings.User, password, auth.Version,
            otp, trustThisPc, savedDeviceId);
        JsonElement loginData;
        try { loginData = await Request(auth.Path, login); }
        catch (SynologyOtpRequiredException)
        {
            if (otp.Length == 0 && !string.IsNullOrEmpty(savedDeviceId))
                onRejectedDeviceId?.Invoke();
            throw;
        }
        if (otp.Length == 6 && trustThisPc &&
            loginData.TryGetProperty("did", out var didValue) &&
            didValue.ValueKind == JsonValueKind.String)
        {
            string did = didValue.GetString() ?? "";
            if (did.Length is >= 4 and <= 512 && !did.Any(char.IsControl))
                onTrustedDeviceId?.Invoke(did);
        }
        string sid = loginData.TryGetProperty("sid", out var sn) ? sn.GetString() ?? "" : "";
        string token = loginData.TryGetProperty("synotoken", out var tn) ? tn.GetString() ?? "" :
            loginData.TryGetProperty("SynoToken", out var alt) ? alt.GetString() ?? "" : "";
        if (sid.Length < 4)
            throw new IOException("DSM 로그인 세션이 반환되지 않았습니다.");
        async Task<JsonElement> Invoke(string method, IDictionary<string,string> fields)
        {
            var values = new Dictionary<string,string>(fields) {
                ["api"] = targetApi, ["version"] = share.Version.ToString(),
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

    internal static List<SynologyFileEntry> ParseShareEntries(JsonElement data)
    {
        if (!data.TryGetProperty("shares", out var values) ||
            values.ValueKind != JsonValueKind.Array)
            throw new IOException("DSM이 공유 폴더 목록을 반환하지 않았습니다.");
        var result = new List<SynologyFileEntry>();
        foreach (var item in values.EnumerateArray())
        {
            string path = item.TryGetProperty("path", out var p) ? p.GetString() ?? "" : "";
            string name = item.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
            try
            {
                path = SynologySharing.NormalizeRoot(path);
                if (result.Any(existing => existing.Path == path)) continue;
                result.Add(new SynologyFileEntry(
                    string.IsNullOrWhiteSpace(name) ? path[1..] : name, path, true));
            }
            catch (ArgumentException) { /* Ignore non-share nested paths. */ }
        }
        return result.OrderBy(e => e.Name, StringComparer.CurrentCulture).ToList();
    }

    internal async Task<List<SynologyFileEntry>> ListSharesAsync() =>
        await WithSession(async invoke => {
            var data = await invoke("list_share", new Dictionary<string,string>());
            return ParseShareEntries(data);
        }, useFileList: true);

    internal static List<SynologyFileEntry> ParseFolderEntries(
        SynologyShareSettings settings, JsonElement data)
    {
        if (!data.TryGetProperty("files", out var values) ||
            values.ValueKind != JsonValueKind.Array)
            throw new IOException("DSM이 폴더 내용을 반환하지 않았습니다.");
        var result = new List<SynologyFileEntry>();
        foreach (var item in values.EnumerateArray())
        {
            string path = item.TryGetProperty("path", out var p) ? p.GetString() ?? "" : "";
            string name = item.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
            if (!SynologySharing.IsAllowed(settings, path) ||
                string.IsNullOrWhiteSpace(name) || name is "." or ".." ||
                name.Contains('/') || name.Contains('\\')) continue;
            bool directory = item.TryGetProperty("isdir", out var d) &&
                d.ValueKind == JsonValueKind.True;
            result.Add(new(name, path, directory));
        }
        return result.OrderByDescending(e => e.IsDirectory)
            .ThenBy(e => e.Name, StringComparer.CurrentCulture).ToList();
    }

    // Keep one DSM login alive for a full browsing dialog, avoiding OTP
    // re-prompts for every folder expansion.
    internal async Task<string?> BrowseAsync(
        Func<Func<string, Task<List<SynologyFileEntry>>>, Task<string?>> browse)
    {
        if (SynologySharing.Roots(settings).Count == 0)
            throw new ArgumentException("공유 폴더를 하나 이상 선택하세요.");
        return await WithSession(async invoke =>
            await browse(async folder => {
                if (!SynologySharing.IsAllowed(settings, folder))
                    throw new ArgumentException("선택하지 않은 DSM 공유 폴더입니다.");
                var data = await invoke("list", new Dictionary<string,string> {
                    ["folder_path"] = JsonSerializer.Serialize(folder),
                    ["offset"] = "0", ["limit"] = "500"
                });
                return ParseFolderEntries(settings, data);
            }), useFileList: true);
    }

    internal async Task<SynologySharedLink> CreateAsync(string filePath,
        int expiryDays, string linkPassword)
    {
        if (!SynologySharing.IsAllowed(settings, filePath))
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
                    if (!SynologySharing.IsAllowed(settings, path) ||
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
            target.Any(x => !SynologySharing.IsAllowed(settings, x.Path) ||
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
