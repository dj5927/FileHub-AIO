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
        SaveAuthorized(p, token, clientId, clientSecret);
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
    private readonly Label state = new();
    private readonly Button login;
    private readonly CancellationTokenSource cts = new();
    private bool busy;

    internal GoogleDriveLoginDialog(NasProfile p)
    {
        profile = p;
        Text = "Google Drive 계정 연결";
        ClientSize = new Size(670, 365);
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
        Caption("Google Drive 최초 연결", 18, 30, true);
        Caption("[Google 계정 연결]을 누르면 브라우저에서 구글 계정 승인 화면이 열립니다.", 55, 27);
        Caption("승인이 완료되면 다음부터는 로그인 없이 자동으로 연결됩니다.", 82, 24);
        Caption("2026년 rclone 공용 Google OAuth 종료 예정. 본인 OAuth Client ID 사용 권장", 119, 28);
        Caption("Google OAuth Client ID (선택)", 157, 24);
        idBox.SetBounds(24, 184, 618, 27);
        idBox.BackColor = ModernTheme.Field; idBox.ForeColor = ModernTheme.Text;
        Controls.Add(idBox);
        Caption("Client Secret (선택 · ID와 함께 입력)", 213, 24);
        secretBox.SetBounds(24, 238, 618, 27);
        secretBox.BackColor = ModernTheme.Field; secretBox.ForeColor = ModernTheme.Text;
        Controls.Add(secretBox);
        state.ForeColor = ModernTheme.Muted;
        state.Text = "자체 ID가 없으면 rclone 공용 인증을 시도할 수 있으나 지원이 종료될 수 있습니다.";
        state.SetBounds(24, 270, 617, 25);
        Controls.Add(state);
        login = ModernTheme.Button("Google 계정 연결", 166, () => _ = BeginAuthAsync(), accent: true, height: 34);
        login.SetBounds(346, 307, 166, 34);
        Controls.Add(login);
        var cancel = ModernTheme.Button("취소", 114, () => {
            cts.Cancel();
            if (!busy) { DialogResult = DialogResult.Cancel; Close(); }
        }, height: 34);
        cancel.SetBounds(520, 307, 114, 34);
        Controls.Add(cancel);
        FormClosing += (_, e) => { if (busy) { cts.Cancel(); e.Cancel = true; } };
    }

    private async Task BeginAuthAsync()
    {
        if (busy) return;
        busy = true;
        login.Enabled = false;
        try
        {
            state.Text = "구글 인증 주소를 준비 중입니다...";
            await GoogleDriveAuth.AuthorizeAsync(profile, idBox.Text, secretBox.Text,
                message => { if (!IsDisposed) BeginInvoke(() => state.Text = message); },
                url => {
                    if (IsDisposed) return;
                    BeginInvoke(() => {
                        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
                        catch (Exception ex) { state.Text = "브라우저를 열지 못했습니다: " + ex.Message; }
                    });
                }, cts.Token);
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
            login.Enabled = !cts.IsCancellationRequested;
            if (cts.IsCancellationRequested) { DialogResult = DialogResult.Cancel; Close(); }
        }
    }
}
