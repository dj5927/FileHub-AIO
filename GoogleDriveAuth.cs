using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Google OAuth via the bundled rclone authorize command. The temporary OAuth
/// listener is bound only to 127.0.0.1. Refresh tokens are persisted in a
/// per-Windows-user, ACL-restricted rclone config, so rclone itself can refresh
/// tokens after relaunch. Never log, print or show OAuth tokens.
/// </summary>
internal static class GoogleDriveAuth
{
    internal static string DirectoryPath => Path.Combine(AppStore.DirectoryPath, "google-auth");
    internal static string RemoteName(NasProfile p) => "nm_" + p.Id.ToString("N");
    internal static string ConfigPath(NasProfile p) =>
        Path.Combine(DirectoryPath, p.Id.ToString("N") + ".conf");

    private static void AtomicReplaceWithBackup(string temporary,
        string destination, string backup)
    {
        if (File.Exists(destination))
            File.Replace(temporary, destination, backup,
                ignoreMetadataErrors: true);
        else
            File.Move(temporary, destination);
    }

    internal static string PreviousAuthorizationPath(NasProfile p) =>
        ConfigPath(p) + ".previous-auth.bak";
    internal static bool HasPreviousAuthorization(NasProfile p) =>
        p.Protocol == "GoogleDrive" &&
        (GoogleDirectCredentialStore.HasBackup(p) ||
         File.Exists(PreviousAuthorizationPath(p)));

    internal static void RestorePreviousAuthorization(NasProfile p)
    {
        if (p.Protocol != "GoogleDrive")
            throw new InvalidOperationException("Google Drive 프로필이 아닙니다.");
        if (GoogleDirectCredentialStore.HasBackup(p))
        {
            GoogleDirectCredentialStore.RestoreBackup(p);
            AppStore.Log("GOOGLE_DIRECT_DPAPI_PREVIOUS_AUTH_RESTORED profile=" + p.Id);
            return;
        }
        SecureDirectory();
        string original = ConfigPath(p);
        string backup = PreviousAuthorizationPath(p);
        if (!File.Exists(backup))
            throw new FileNotFoundException("복구할 이전 Google 인증이 없습니다.");
        string content = File.ReadAllText(backup, Encoding.UTF8);
        if (!content.Contains("[" + RemoteName(p) + "]", StringComparison.Ordinal) ||
            !content.Contains("type = drive", StringComparison.Ordinal) ||
            !content.Contains("refresh_token", StringComparison.Ordinal))
            throw new InvalidDataException(
                "이전 Google 인증 파일이 현재 프로필과 일치하지 않습니다.");
        string temporary = original + "." + Guid.NewGuid().ToString("N") + ".restore";
        try
        {
            File.WriteAllText(temporary, content, new UTF8Encoding(false));
            AtomicReplaceWithBackup(temporary, original,
                original + ".before-restore.bak");
            AppStore.Log("GOOGLE_OAUTH_PREVIOUS_AUTH_RESTORED profile=" + p.Id);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    // Only report whether the access token is currently expired. Never log
    // tokens, refresh secrets, Google IDs, file paths or full config content.
    // This distinguishes a cold OAuth refresh from root-folder/API pacing.
    internal static bool? IsStoredAccessTokenExpired(NasProfile p)
    {
        if (p.Protocol != "GoogleDrive") return null;
        try
        {
            string config = File.ReadAllText(ConfigPath(p), Encoding.UTF8);
            var expiry = Regex.Match(config,
                @"""expiry""\s*:\s*""([^""]+)""").Groups[1].Value;
            if (DateTimeOffset.TryParse(expiry, out var timestamp))
                return timestamp <= DateTimeOffset.UtcNow.AddMinutes(2);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
        return null;
    }

    internal static bool IsAuthorized(NasProfile p)
    {
        if (p.Protocol != "GoogleDrive") return false;
        if (GoogleDirectCredentialStore.HasNative(p)) return true;
        try
        {
            string path = ConfigPath(p);
            if (!File.Exists(path)) return false;
            string contents = File.ReadAllText(path, Encoding.UTF8);
            if (!contents.Contains("[" + RemoteName(p) + "]", StringComparison.Ordinal)) return false;
            return contents.Contains("type = drive", StringComparison.Ordinal) &&
                   contents.Contains("refresh_token", StringComparison.Ordinal);
        }
        catch { return false; }
    }

    // Expose only the OAuth client mode to UI/logs; never the credentials.
    internal static string ClientMode(NasProfile p)
    {
        if (!IsAuthorized(p)) return "not-authorized";
        if (GoogleDirectCredentialStore.HasNative(p)) return "custom";
        try
        {
            string contents = File.ReadAllText(ConfigPath(p), Encoding.UTF8);
            var client = Regex.Match(contents,
                @"(?m)^\s*client_id\s*=\s*([^\r\n]+?)\s*$");
            return client.Success &&
                !string.IsNullOrWhiteSpace(client.Groups[1].Value)
                ? "custom" : "shared";
        }
        catch (IOException) { return "unknown"; }
        catch (UnauthorizedAccessException) { return "unknown"; }
    }

    internal static string BuildConfig(NasProfile p, string jsonToken,
        string clientId, string clientSecret)
    {
        if (p.Protocol != "GoogleDrive") throw new ArgumentException("Not Google Drive");
        using var doc = JsonDocument.Parse(jsonToken);
        var token = doc.RootElement;
        if (token.ValueKind != JsonValueKind.Object ||
            !token.TryGetProperty("access_token", out var access) ||
            string.IsNullOrWhiteSpace(access.GetString()) ||
            !token.TryGetProperty("refresh_token", out var refresh) ||
            string.IsNullOrWhiteSpace(refresh.GetString()))
            throw new InvalidDataException("Google authorization did not provide a refresh token");
        if (clientId.IndexOfAny(['\r', '\n']) >= 0 ||
            clientSecret.IndexOfAny(['\r', '\n']) >= 0)
            throw new ArgumentException("Invalid client credentials");
        var config = new StringBuilder()
            .Append('[').Append(RemoteName(p)).AppendLine("]")
            .AppendLine("type = drive")
            .AppendLine("scope = drive");
        if (!string.IsNullOrWhiteSpace(clientId))
            config.Append("client_id = ").AppendLine(clientId.Trim());
        if (!string.IsNullOrWhiteSpace(clientSecret))
            config.Append("client_secret = ").AppendLine(clientSecret.Trim());
        config.Append("token = ").AppendLine(token.GetRawText());
        return config.ToString();
    }

    private static void SecureDirectory()
    {
        Directory.CreateDirectory(DirectoryPath);
        var user = WindowsIdentity.GetCurrent().User ??
            throw new UnauthorizedAccessException("Windows user identity unavailable");
        var acl = new DirectorySecurity();
        acl.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(DirectoryPath).SetAccessControl(acl);
    }

    internal static void SaveAuthorized(NasProfile p, string jsonToken,
        string clientId = "", string clientSecret = "")
    {
        // Verify token before touching persistent config or the user's other remotes.
        string contents = BuildConfig(p, jsonToken, clientId, clientSecret);
        SecureDirectory();
        string destination = ConfigPath(p);
        string temporary = destination + "." + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temporary, contents, new UTF8Encoding(false));
        File.Move(temporary, destination, overwrite: true);
    }

    // Browser PKCE can validate and preserve an account without executing
    // rclone. Only swap the previous credential after a successful read-only
    // Google Drive REST request.
    internal static async Task SaveNativeBrowserAuthorizationAsync(
        NasProfile profile, string jsonToken, string clientId, string clientSecret,
        CancellationToken cancellationToken)
    {
        using var json = JsonDocument.Parse(jsonToken);
        string? access = json.RootElement.GetProperty("access_token").GetString();
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        using var request = new HttpRequestMessage(HttpMethod.Get,
            "https://www.googleapis.com/drive/v3/about?fields=user(permissionId)");
        request.Headers.Authorization =
            new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", access);
        using var response = await http.SendAsync(request, cancellationToken)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
            throw new IOException("Google direct OAuth validation HTTP " +
                (int)response.StatusCode + ". Previous login is unchanged.");
        // Separate DPAPI store: native Google auth never writes a rclone
        // config. Previous legacy credentials remain untouched for rollback.
        GoogleDirectCredentialStore.SaveBrowserGrant(
            profile, jsonToken, clientId, clientSecret);
        AppStore.Log("GOOGLE_NATIVE_BROWSER_DPAPI_AUTH_SAVED profile=" +
            profile.Id + " rcloneAuth=false previousPreserved=true");
    }

    // A token acquired for a new OAuth app must be tested before replacing
    // the user's existing working account. Validate a disposable rclone
    // configuration and atomically save it with a rollback backup.
    internal static async Task ValidateAndSaveAuthorizedAsync(
        NasProfile profile, string token, string clientId, string clientSecret,
        string rcloneExe, Action<string> progress,
        CancellationToken cancellationToken)
    {
        string candidate = BuildConfig(profile, token, clientId, clientSecret);
        SecureDirectory();
        string destination = ConfigPath(profile);
        string temporary = destination + "." + Guid.NewGuid().ToString("N") + ".verify";
        string backup = destination + ".previous-auth.bak";
        try
        {
            File.WriteAllText(temporary, candidate, new UTF8Encoding(false));
            // Validate access with one read-only Drive About call, not root
            // listing. Listing can trigger repeated 403 throttling on the
            // shared OAuth client even when the newly issued token is valid.
            progress("새 Google 인증으로 Drive API 접근 확인 중...");
            var start = new ProcessStartInfo(rcloneExe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            foreach (string argument in ValidationArguments(profile, temporary))
                start.ArgumentList.Add(argument);
            using var process = new Process { StartInfo = start };
            process.Start();
            // Always drain both pipes; never log filenames or raw API errors.
            Task<string> stdout = process.StandardOutput.ReadToEndAsync();
            Task<string> stderr = process.StandardError.ReadToEndAsync();
            using var limit = CancellationTokenSource.CreateLinkedTokenSource(
                cancellationToken);
            limit.CancelAfter(TimeSpan.FromSeconds(45));
            try
            {
                await process.WaitForExitAsync(limit.Token);
                _ = await stdout;
                _ = await stderr;
            }
            catch (OperationCanceledException)
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
                catch { }
                throw cancellationToken.IsCancellationRequested
                    ? new OperationCanceledException(cancellationToken)
                    : new IOException(
                        "새 Google 인증 확인이 45초를 초과했습니다. 이전 인증은 유지됩니다.");
            }
            if (process.ExitCode != 0)
                throw new IOException(
                    "새 Google 인증으로 Drive API에 접근하지 못했습니다. 기존 인증은 유지됩니다.");

            cancellationToken.ThrowIfCancellationRequested();
            AtomicReplaceWithBackup(temporary, destination, backup);
            AppStore.Log("GOOGLE_OAUTH_VALIDATED_AND_SWAPPED profile=" +
                profile.Id + " previousAuthBackup=" + File.Exists(backup) +
                " clientMode=" + (clientId.Length > 0 ? "custom" : "shared"));
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    internal static string[] ValidationArguments(
        NasProfile profile, string temporaryConfig) =>
    [
        "about", RemoteName(profile) + ":",
        "--config", temporaryConfig,
        "--json", "--log-level", "ERROR",
        "--drive-pacer-min-sleep", GoogleMountPacingPolicy.MinSleep,
        "--drive-pacer-burst", GoogleMountPacingPolicy.Burst
    ];

    internal static void ValidationPolicyTest()
    {
        var profile = new NasProfile
        {
            Id = Guid.Parse("12345678-1111-2222-3333-444455556666"),
            Protocol = "GoogleDrive"
        };
        const string json = "{\"access_token\":\"safe_access\",\"refresh_token\":\"safe_refresh\"}";
        string text = BuildConfig(profile, json,
            "test.apps.googleusercontent.com", "test_secret");
        if (!text.Contains("client_id = test.apps.googleusercontent.com") ||
            !text.Contains("client_secret = test_secret") ||
            !text.Contains("refresh_token") ||
            !text.Contains("scope = drive"))
            throw new InvalidOperationException("OAuth candidate config invalid");
        try
        {
            BuildConfig(profile, json, "invalid\nclient", "secret");
            throw new InvalidOperationException("OAuth injection test failed");
        }
        catch (ArgumentException) { }
        GoogleOAuthClientFile.SelfTest();
        string[] verification = ValidationArguments(
            profile, @"C:\fake-validation-config.conf");
        if (verification[0] != "about" ||
            !verification.Contains("--json") ||
            verification.Contains("lsf") ||
            verification.Contains("--max-depth"))
            throw new InvalidOperationException(
                "OAuth validation must not enumerate root folders");
        Console.WriteLine("GOOGLE_OAUTH_ABOUT_ONLY_VALIDATION_POLICY_PASS");
        string scratch = Path.Combine(Path.GetTempPath(),
            "FileHub_GoogleAuth_Swap_QA_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        try
        {
            string current = Path.Combine(scratch, "current.conf");
            string next = Path.Combine(scratch, "candidate.conf");
            string old = Path.Combine(scratch, "previous.conf");
            File.WriteAllText(current, "original-sentinel");
            File.WriteAllText(next, "validated-new-sentinel");
            AtomicReplaceWithBackup(next, current, old);
            if (File.ReadAllText(current) != "validated-new-sentinel" ||
                File.ReadAllText(old) != "original-sentinel" ||
                File.Exists(next))
                throw new InvalidOperationException(
                    "OAuth validated swap did not preserve original state");
        }
        finally { Directory.Delete(scratch, recursive: true); }
        Console.WriteLine("GOOGLE_OAUTH_VALIDATED_SWAP_AND_ROLLBACK_POLICY_PASS");
    }

    internal static void ImportAuthorizedConfig(NasProfile profile, string content)
    {
        if (profile.Protocol != "GoogleDrive" ||
            !content.Contains("[" + RemoteName(profile) + "]", StringComparison.Ordinal) ||
            !content.Contains("type = drive", StringComparison.Ordinal) ||
            !content.Contains("refresh_token", StringComparison.Ordinal))
            throw new InvalidDataException("Google Drive 백업 인증 파일이 올바르지 않습니다.");
        SecureDirectory();
        string destination = ConfigPath(profile);
        string temporary = destination + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllText(temporary, content, new UTF8Encoding(false));
            File.Move(temporary, destination, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    internal static bool TryParseAuthUrl(string line, out string url)
    {
        url = "";
        var match = Regex.Match(line, @"http://127\.0\.0\.1:\d+/auth\?[^\s\""']+");
        if (!match.Success || !Uri.TryCreate(match.Value, UriKind.Absolute, out var uri) ||
            uri.Host != "127.0.0.1" || uri.Scheme != "http" || uri.AbsolutePath != "/auth" ||
            !uri.Query.Contains("state=", StringComparison.Ordinal)) return false;
        url = uri.AbsoluteUri;
        return true;
    }

    internal static string ParseAuthorizedToken(string output)
    {
        // Authorize prints a JSON OAuth token between marker lines. Ignore
        // all other output. No successful token should be logged anywhere.
        foreach (Match match in Regex.Matches(output, @"\{[^{}]+\}"))
        {
            try
            {
                using var doc = JsonDocument.Parse(match.Value);
                if (doc.RootElement.TryGetProperty("access_token", out _) &&
                    doc.RootElement.TryGetProperty("refresh_token", out _))
                    return match.Value;
            }
            catch (JsonException) { }
        }
        throw new InvalidDataException("Google OAuth did not return an access and refresh token. Retry with a verified Google OAuth client.");
    }

    internal static async Task AuthorizeAsync(NasProfile p, string clientId,
        string clientSecret, Action<string> progress, Action<string> openUrl,
        CancellationToken cancellationToken)
    {
        string exe = RemoteMount.RclonePath ??
            throw new FileNotFoundException("Bundled rclone.exe is unavailable");
        var binary = await RcloneExecutableInspector.VerifyAsync(exe);
        if (!binary.Success) throw new IOException(binary.Message);
        clientId = clientId.Trim();
        clientSecret = clientSecret.Trim();
        if ((clientId.Length == 0) != (clientSecret.Length == 0))
            throw new ArgumentException("Google OAuth Client ID and Client Secret must both be supplied");
        if (clientId.Length > 0)
            GoogleOAuthClientFile.ValidateDesktopCredentials(
                clientId, clientSecret);

        var psi = new ProcessStartInfo(exe) {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        psi.ArgumentList.Add("authorize");
        psi.ArgumentList.Add("drive");
        if (clientId.Length > 0) {
            psi.ArgumentList.Add(clientId);
            psi.ArgumentList.Add(clientSecret);
        }
        psi.ArgumentList.Add("--auth-no-open-browser");
        psi.ArgumentList.Add("--config");
        psi.ArgumentList.Add(Path.Combine(AppStore.DirectoryPath, "no-global-rclone.conf"));
        using var process = new Process { StartInfo = psi };
        process.Start();
        using var cancellation = cancellationToken.Register(() => {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
            catch { }
        });
        var stdout = process.StandardOutput.ReadToEndAsync(cancellationToken);
        var stderr = Task.Run(async () => {
            bool browserOpened = false;
            while (await process.StandardError.ReadLineAsync(cancellationToken) is { } line) {
                if (!browserOpened && TryParseAuthUrl(line, out var url)) {
                    browserOpened = true;
                    openUrl(url);
                    progress("브라우저에서 Google 계정 선택 후 접근을 승인하세요.");
                }
                else if (line.Contains("Waiting for code", StringComparison.OrdinalIgnoreCase))
                    progress("Google 인증 승인을 기다리고 있습니다...");
            }
            return browserOpened;
        }, cancellationToken);
        await process.WaitForExitAsync(cancellationToken);
        bool opened = await stderr;
        string result = await stdout;
        cancellationToken.ThrowIfCancellationRequested();
        if (process.ExitCode != 0 || !opened)
            throw new IOException("Google 로그인 프로세스가 정상 완료되지 않았습니다. 브라우저 로그인 및 OAuth Client ID를 확인하세요.");
        string token = ParseAuthorizedToken(result);
        await ValidateAndSaveAuthorizedAsync(p, token, clientId,
            clientSecret, exe, progress, cancellationToken);
        AppStore.Log("GOOGLE_OAUTH_COMPLETED profile=" + p.Id + " (token redacted)");
        progress("Google 계정 인증 완료. 자동 재연결 준비가 끝났습니다.");
    }
}

/// <summary>First-time user-controlled OAuth consent. No sign-in on app startup.</summary>
internal sealed class GoogleDriveLoginDialog : Form
{
    private readonly NasProfile profile;
    private readonly TextBox idBox = new();
    private readonly TextBox secretBox = new() { UseSystemPasswordChar = true };
    private readonly CheckBox sharedClientConsent = new();
    private readonly Label state = new();
    private readonly Button login;
    private readonly CancellationTokenSource cts = new();
    private bool busy;

    internal GoogleDriveLoginDialog(NasProfile p)
    {
        profile = p;
        Text = "Google Drive 계정 연결";
        ClientSize = new Size(670, 474);
        MinimumSize = MaximumSize = Size;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = MinimizeBox = false;
        StartPosition = FormStartPosition.CenterParent;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 9f);
        ModernTheme.EnableDarkTitleBar(this);
        void Caption(string value, int top, int height, bool bold = false) {
            var label = ModernTheme.Label(value, bold ? 11f : 9f, bold);
            label.SetBounds(24, top, 620, height);
            Controls.Add(label);
        }
        Caption("Google Drive 전용 OAuth 연결", 18, 30, true);
        string mode = GoogleDriveAuth.ClientMode(p);
        Caption(mode switch
        {
            "custom" => "현재 인증: 전용 Google OAuth Client ID 사용 중",
            "shared" => "기존 rclone 공용 인증 발견 · 직접 API 연결을 위해 새 Chrome 승인이 필요합니다.",
            _ => "현재 인증: 계정 미연결"
        }, 53, 28);
        Caption("Google Cloud에서 '데스크톱 앱'용 OAuth JSON을 발급받아 가져오세요.", 83, 27);
        Caption("전용 인증은 API 요청 제한을 줄일 수 있으나 연결 속도를 보장하지는 않습니다.", 111, 27);
        Caption("Google OAuth Client ID", 167, 24);
        idBox.SetBounds(24, 193, 618, 27);
        idBox.BackColor = ModernTheme.Field; idBox.ForeColor = ModernTheme.Text;
        Controls.Add(idBox);
        Caption("Client Secret (직접 입력 또는 JSON 가져오기)", 225, 24);
        secretBox.SetBounds(24, 252, 618, 27);
        secretBox.BackColor = ModernTheme.Field; secretBox.ForeColor = ModernTheme.Text;
        Controls.Add(secretBox);
        var import = ModernTheme.Button("OAuth JSON 가져오기", 178,
            ImportOAuthJson, height: 33);
        import.SetBounds(24, 291, 178, 33);
        Controls.Add(import);
        var guide = ModernTheme.Button("Google 발급 안내", 150,
            ShowOAuthInstructions, height: 33);
        guide.SetBounds(211, 291, 150, 33);
        Controls.Add(guide);
        sharedClientConsent.Text = "공용 rclone 인증은 직접 엔진에서 지원하지 않습니다.";
        sharedClientConsent.AutoSize = false;
        sharedClientConsent.SetBounds(24, 334, 615, 28);
        sharedClientConsent.ForeColor = ModernTheme.Text;
        sharedClientConsent.BackColor = ModernTheme.Background;
        sharedClientConsent.Visible = false;
        Controls.Add(sharedClientConsent);
        state.ForeColor = ModernTheme.Muted;
        state.Text = "FileHub 전용 데스크톱 OAuth JSON을 가져오면 Chrome 계정 승인만 진행합니다.";
        state.SetBounds(24, 367, 617, 45);
        Controls.Add(state);
        login = ModernTheme.Button("Google 계정 연결", 166, () => _ = BeginAuthAsync(), accent: true, height: 34);
        login.SetBounds(346, 423, 166, 34);
        Controls.Add(login);
        var cancel = ModernTheme.Button("취소", 114, () => {
            cts.Cancel();
            if (!busy) { DialogResult = DialogResult.Cancel; Close(); }
        }, height: 34);
        cancel.SetBounds(520, 423, 114, 34);
        Controls.Add(cancel);
        // Publisher-owned OAuth credentials are configured once when the
        // application is built. End users only click the browser login.
        try
        {
            if (GoogleOAuthClientFile.TryReadPackagedClient(
                out string bundledId, out string bundledSecret))
            {
                idBox.Text = bundledId;
                secretBox.Text = bundledSecret;
                sharedClientConsent.Checked = false;
                idBox.Visible = false;
                secretBox.Visible = false;
                sharedClientConsent.Visible = false;
                var cover = new Panel { BackColor = ModernTheme.Background };
                cover.SetBounds(20, 78, 630, 280);
                Controls.Add(cover);
                var heading = ModernTheme.Label("Google 계정 연결", 13f, true);
                heading.SetBounds(10, 42, 600, 40);
                cover.Controls.Add(heading);
                var instructions = ModernTheme.Label(
                    "아래 [Google 계정 연결] 버튼을 누르면 기본 브라우저가 열립니다." +
                    "\nGoogle 계정 선택 → 접근 승인으로 인증이 완료됩니다." +
                    "\n별도의 API 키나 Client ID 입력은 필요하지 않습니다.",
                    10f, false);
                instructions.SetBounds(10, 110, 605, 112);
                cover.Controls.Add(instructions);
                cover.BringToFront();
                state.Text = "Google 계정 연결을 누르면 브라우저 인증이 시작됩니다.";
            }
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        {
            AppStore.Log("GOOGLE_PACKAGED_OAUTH_CONFIG_INVALID type=" +
                ex.GetType().Name);
            state.Text = "앱에 포함된 Google 인증 설정을 확인해야 합니다.";
        }
        idBox.TextChanged += (_, _) => RefreshLoginAvailability();
        secretBox.TextChanged += (_, _) => RefreshLoginAvailability();
        sharedClientConsent.CheckedChanged += (_, _) => RefreshLoginAvailability();
        RefreshLoginAvailability();
        FormClosing += (_, e) => { if (busy) { cts.Cancel(); e.Cancel = true; } };
    }

    private void RefreshLoginAvailability()
    {
        if (login is null) return;
        bool hasId = !string.IsNullOrWhiteSpace(idBox.Text);
        bool hasSecret = !string.IsNullOrWhiteSpace(secretBox.Text);
        login.Enabled = !busy && hasId && hasSecret;
    }

    private void ImportOAuthJson()
    {
        using var picker = new OpenFileDialog
        {
            Title = "Google Cloud 데스크톱 OAuth JSON 선택",
            Filter = "Google OAuth JSON (*.json)|*.json",
            CheckFileExists = true,
            Multiselect = false,
            RestoreDirectory = true
        };
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        try
        {
            var (clientId, clientSecret) =
                GoogleOAuthClientFile.ReadDesktopFile(picker.FileName);
            idBox.Text = clientId;
            secretBox.Text = clientSecret;
            sharedClientConsent.Checked = false;
            state.Text = "전용 OAuth 설정을 가져왔습니다. 계정 연결을 누르면 브라우저에서 승인합니다.";
        }
        catch (Exception ex) when (ex is IOException or ArgumentException or
                                  UnauthorizedAccessException)
        {
            state.Text = "Google Cloud에서 발급한 데스크톱 앱 JSON을 선택하세요.";
            MessageBox.Show(this, ex.Message, "Google OAuth JSON 오류",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ShowOAuthInstructions()
    {
        try
        {
            Process.Start(new ProcessStartInfo(
                GoogleOAuthClientFile.GoogleSetupUrl)
            { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Google OAuth 발급 안내",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private async Task BeginAuthAsync()
    {
        if (busy) return;
        busy = true;
        login.Enabled = false;
        try
        {
            state.Text = "구글 인증 주소를 준비 중입니다...";
            Action<string> update = message => {
                if (!IsDisposed) BeginInvoke(() => state.Text = message);
            };
            Action<string> browser = url => {
                    if (IsDisposed) return;
                    BeginInvoke(() => {
                        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
                        catch (Exception ex) { state.Text = "브라우저를 열지 못했습니다: " + ex.Message; }
                    });
            };
            string token = await GoogleDirectBrowserAuth.AuthorizeAsync(
                idBox.Text.Trim(), secretBox.Text.Trim(),
                update, browser, cts.Token);
            await GoogleDriveAuth.SaveNativeBrowserAuthorizationAsync(
                profile, token, idBox.Text.Trim(), secretBox.Text.Trim(), cts.Token);
            // Clear busy BEFORE Close(): FormClosing cancels a close while
            // OAuth is still running. The token is already persisted here.
            busy = false;
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (OperationCanceledException) { state.Text = "인증이 취소되었습니다."; }
        catch (Exception ex) {
            state.Text = "인증에 실패했습니다. OAuth 설정이나 로그인 승인을 확인하세요.";
            MessageBox.Show(this, ex.Message, "Google Drive 인증 오류",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally {
            busy = false;
            RefreshLoginAvailability();
            if (cts.IsCancellationRequested) { DialogResult = DialogResult.Cancel; Close(); }
        }
    }
}
