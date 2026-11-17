using System.Diagnostics;
using System.Net.Http.Headers;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Microsoft OneDrive (personal and work/school) delegated OAuth. The user
/// explicitly starts the browser login; no user consent window on startup.
/// rclone refreshes its own token from an ACL-restricted per-profile config.
/// Microsoft Graph /me/drive discovers the selected account's default drive
/// ID and drive type, which the OneDrive rclone backend requires.
/// </summary>
internal static class OneDriveAuth
{
    internal static string DirectoryPath => Path.Combine(AppStore.DirectoryPath, "onedrive-auth");
    internal static string RemoteName(NasProfile p) => "nm_" + p.Id.ToString("N");
    internal static string ConfigPath(NasProfile p) =>
        Path.Combine(DirectoryPath, p.Id.ToString("N") + ".conf");

    internal static bool IsAuthorized(NasProfile p)
    {
        if (p.Protocol != "OneDrive") return false;
        try
        {
            var path = ConfigPath(p);
            if (!File.Exists(path)) return false;
            var text = File.ReadAllText(path, Encoding.UTF8);
            return text.Contains("[" + RemoteName(p) + "]", StringComparison.Ordinal) &&
                text.Contains("type = onedrive", StringComparison.Ordinal) &&
                text.Contains("refresh_token", StringComparison.Ordinal) &&
                text.Contains("drive_id = ", StringComparison.Ordinal) &&
                text.Contains("drive_type = ", StringComparison.Ordinal);
        }
        catch { return false; }
    }

    private static JsonElement ValidateToken(string raw)
    {
        using var document = JsonDocument.Parse(raw);
        var token = document.RootElement;
        if (token.ValueKind != JsonValueKind.Object ||
            !token.TryGetProperty("access_token", out var access) ||
            string.IsNullOrWhiteSpace(access.GetString()) ||
            !token.TryGetProperty("refresh_token", out var refresh) ||
            string.IsNullOrWhiteSpace(refresh.GetString()))
            throw new InvalidDataException("Microsoft OAuth access/refresh token missing");
        return token.Clone();
    }

    internal static (string Id, string Type) ParseDriveInfo(string json)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        string id = root.TryGetProperty("id", out var driveId)
            ? driveId.GetString() ?? "" : "";
        string kind = root.TryGetProperty("driveType", out var driveType)
            ? driveType.GetString() ?? "" : "";
        if (id.Length is < 2 or > 500 || id.IndexOfAny(['\r','\n','[',']']) >= 0)
            throw new InvalidDataException("Microsoft Graph did not return a valid drive ID");
        if (kind is not ("personal" or "business" or "documentLibrary"))
            throw new InvalidDataException("Unsupported Microsoft drive type: " + kind);
        return (id, kind);
    }

    internal static string BuildConfig(NasProfile profile, string token,
        string driveId, string driveType, string clientId = "", string clientSecret = "")
    {
        if (profile.Protocol != "OneDrive")
            throw new ArgumentException("Not a Microsoft OneDrive profile");
        var jsonToken = ValidateToken(token);
        var validatedDrive = ParseDriveInfo(JsonSerializer.Serialize(
            new { id = driveId, driveType }));
        if (clientId.IndexOfAny(['\r', '\n', '[', ']']) >= 0 ||
            clientSecret.IndexOfAny(['\r', '\n', '[', ']']) >= 0)
            throw new ArgumentException("OAuth client values contain invalid characters");
        var text = new StringBuilder()
            .Append('[').Append(RemoteName(profile)).AppendLine("]")
            .AppendLine("type = onedrive")
            .AppendLine("region = global")
            .Append("drive_id = ").AppendLine(validatedDrive.Id)
            .Append("drive_type = ").AppendLine(validatedDrive.Type);
        if (!string.IsNullOrWhiteSpace(clientId))
            text.Append("client_id = ").AppendLine(clientId.Trim());
        if (!string.IsNullOrWhiteSpace(clientSecret))
            text.Append("client_secret = ").AppendLine(clientSecret.Trim());
        text.Append("token = ").AppendLine(jsonToken.GetRawText());
        return text.ToString();
    }

    private static void SecureDirectory()
    {
        Directory.CreateDirectory(DirectoryPath);
        var user = WindowsIdentity.GetCurrent().User ??
            throw new UnauthorizedAccessException("Windows identity not available");
        var acl = new DirectorySecurity();
        acl.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        acl.AddAccessRule(new FileSystemAccessRule(user, FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        acl.AddAccessRule(new FileSystemAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            FileSystemRights.FullControl,
            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
            PropagationFlags.None, AccessControlType.Allow));
        new DirectoryInfo(DirectoryPath).SetAccessControl(acl);
    }

    internal static void SaveAuthorized(NasProfile profile, string token,
        string driveId, string driveType, string clientId, string clientSecret)
    {
        string config = BuildConfig(profile, token, driveId, driveType,
            clientId, clientSecret);
        SecureDirectory();
        string destination = ConfigPath(profile);
        string temporary = destination + "." + Guid.NewGuid().ToString("N") + ".tmp";
        File.WriteAllText(temporary, config, new UTF8Encoding(false));
        File.Move(temporary, destination, overwrite: true);
    }

    internal static void ImportAuthorizedConfig(NasProfile profile, string content)
    {
        if (profile.Protocol != "OneDrive" ||
            !content.Contains("[" + RemoteName(profile) + "]", StringComparison.Ordinal) ||
            !content.Contains("type = onedrive", StringComparison.Ordinal) ||
            !content.Contains("refresh_token", StringComparison.Ordinal) ||
            !content.Contains("drive_id = ", StringComparison.Ordinal))
            throw new InvalidDataException("OneDrive 백업 인증 파일이 올바르지 않습니다.");
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

    private static string ParseToken(string output)
    {
        foreach (Match match in Regex.Matches(output, @"\{[^{}]+\}"))
            try
            {
                ValidateToken(match.Value);
                return match.Value;
            }
            catch (Exception e) when (e is JsonException or InvalidDataException) { }
        throw new InvalidDataException(
            "Microsoft login did not return an access and refresh token.");
    }

    private static async Task<(string Id, string Type)> DiscoverDriveAsync(
        string token, CancellationToken cancel)
    {
        var json = ValidateToken(token);
        string bearer = json.GetProperty("access_token").GetString()!;
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(28) };
        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", bearer);
        using var response = await client.GetAsync(
            "https://graph.microsoft.com/v1.0/me/drive?$select=id,driveType",
            cancel);
        if (!response.IsSuccessStatusCode)
            throw new IOException("Microsoft Graph drive discovery failed (HTTP " +
                (int)response.StatusCode +
                "). Check Microsoft account consent, OneDrive provisioning and " +
                "organization policy. Your OAuth token was not saved.");
        return ParseDriveInfo(await response.Content.ReadAsStringAsync(cancel));
    }

    internal static async Task AuthorizeAsync(NasProfile profile, string clientId,
        string clientSecret, Action<string> progress, Action<string> openUrl,
        CancellationToken cancel)
    {
        string exe = RemoteMount.RclonePath ??
            throw new FileNotFoundException("Bundled tools\\rclone.exe missing");
        var inspected = await RcloneExecutableInspector.VerifyAsync(exe);
        if (!inspected.Success) throw new IOException(inspected.Message);
        clientId = clientId.Trim();
        clientSecret = clientSecret.Trim();
        if ((clientId.Length == 0) != (clientSecret.Length == 0))
            throw new ArgumentException(
                "Microsoft OAuth Client ID and Secret must both be supplied.");
        var psi = new ProcessStartInfo(exe)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        psi.ArgumentList.Add("authorize");
        psi.ArgumentList.Add("onedrive");
        if (clientId.Length > 0)
        {
            psi.ArgumentList.Add(clientId);
            psi.ArgumentList.Add(clientSecret);
        }
        psi.ArgumentList.Add("--auth-no-open-browser");
        psi.ArgumentList.Add("--config");
        psi.ArgumentList.Add(Path.Combine(
            AppStore.DirectoryPath, "no-global-rclone.conf"));
        using var process = new Process { StartInfo = psi };
        process.Start();
        using var cancellation = cancel.Register(() =>
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
            catch { }
        });
        var stdout = process.StandardOutput.ReadToEndAsync(cancel);
        var stderr = Task.Run(async () =>
        {
            bool opened = false;
            while (await process.StandardError.ReadLineAsync(cancel) is { } line)
            {
                if (!opened && GoogleDriveAuth.TryParseAuthUrl(line, out var url))
                {
                    opened = true;
                    openUrl(url);
                    progress("Microsoft 계정 로그인 및 OneDrive 접근 승인을 완료하세요.");
                }
                else if (line.Contains("Waiting for code", StringComparison.OrdinalIgnoreCase))
                    progress("Microsoft 로그인 승인을 기다리고 있습니다...");
                // Never log raw stderr/stdout: it can contain OAuth tokens.
            }
            return opened;
        }, cancel);
        await process.WaitForExitAsync(cancel);
        bool opened = await stderr;
        string output = await stdout;
        cancel.ThrowIfCancellationRequested();
        if (process.ExitCode != 0 || !opened)
            throw new IOException(
                "Microsoft OAuth did not complete. Check browser login, " +
                "account permissions and custom app registration.");
        string token = ParseToken(output);
        progress("OneDrive 정보를 확인하고 있습니다...");
        var drive = await DiscoverDriveAsync(token, cancel);
        SaveAuthorized(profile, token, drive.Id, drive.Type, clientId, clientSecret);
        AppStore.Log("ONEDRIVE_OAUTH_COMPLETED profile=" + profile.Id +
            " driveType=" + drive.Type + " token_redacted=true");
        progress("OneDrive 인증 완료. 다음 실행부터 자동 연결할 수 있습니다.");
    }

    internal static void OfflinePolicyTest()
    {
        var profile = new NasProfile {
            Protocol = "OneDrive", Host = "", Share = "", Letter = "X",
            IsAnonymous = true, UseLockBridge = false
        };
        const string fake = "{\"access_token\":\"test-only\",\"refresh_token\":\"test-refresh\",\"token_type\":\"Bearer\"}";
        var personal = ParseDriveInfo("{\"id\":\"112233!123\",\"driveType\":\"personal\"}");
        var business = ParseDriveInfo("{\"id\":\"b!abc123\",\"driveType\":\"business\"}");
        string config = BuildConfig(profile, fake, business.Id, business.Type,
            "fake-app-id", "fake-secret");
        if (personal.Type != "personal" || business.Type != "business" ||
            !config.Contains("type = onedrive") ||
            !config.Contains("drive_id = b!abc123") ||
            !config.Contains("drive_type = business") ||
            !config.Contains("refresh_token") ||
            !config.Contains("[nm_" + profile.Id.ToString("N") + "]") ||
            ParseToken("MARKER\n" + fake + "\nFINISHED") != fake)
            throw new InvalidOperationException("OneDrive config or OAuth policy invalid");
        if (GoogleDrivePreflightPolicy.AttemptCount("OneDrive") != 3 ||
            GoogleDrivePreflightPolicy.ExplorerBrowseTimeout("OneDrive").TotalSeconds < 30 ||
            !GoogleDrivePreflightPolicy.IsTransient("OneDrive", false, "HTTP 503") ||
            GoogleDrivePreflightPolicy.IsTransient("OneDrive", false, "401 Unauthorized") ||
            MountResponsivenessPolicy.DirectoryCacheTime("OneDrive", false, false) != "20s" ||
            OneDriveAuth.ConfigPath(profile) == GoogleDriveAuth.ConfigPath(profile) ||
            !RemoteMount.UsesNetworkDriveMode(profile))
            throw new InvalidOperationException("OneDrive backend lifecycle policy invalid");
        try { ParseDriveInfo("{\"id\":\"bad\\r\\nvalue\",\"driveType\":\"personal\"}"); 
            throw new InvalidOperationException("Unsafe drive ID accepted"); }
        catch (InvalidDataException) { }
        try { BuildConfig(profile, fake, "id", "notOneDrive", "", "");
            throw new InvalidOperationException("Unsafe drive type accepted"); }
        catch (InvalidDataException) { }
        if (!RemoteMount.TryValidate(profile, out var reason))
            throw new InvalidOperationException("OneDrive validation: " + reason);
        ModernProfileDialog.ApplyPerformanceMode(profile, 0);
        if (profile.UseLockBridge || !profile.AllowUnprotectedWrites)
            throw new InvalidOperationException("OneDrive must not use NAS lock bridge");
        ModernProfileDialog.ApplyPerformanceMode(profile, 1);
        if (profile.UseLockBridge || profile.AllowUnprotectedWrites)
            throw new InvalidOperationException("OneDrive readonly mode broken");
        Console.WriteLine("ONEDRIVE_OAUTH_OFFLINE_DRIVE_CONFIG_POLICY_PASS");
    }
}

internal sealed class OneDriveLoginDialog : Form
{
    private readonly NasProfile profile;
    private readonly TextBox clientId = new();
    private readonly TextBox clientSecret = new() { UseSystemPasswordChar = true };
    private readonly Label state = new();
    private readonly Button login;
    private readonly CancellationTokenSource cancel = new();
    private bool busy;

    internal OneDriveLoginDialog(NasProfile profile)
    {
        this.profile = profile;
        Text = "Microsoft OneDrive 계정 연결";
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
        void Caption(string s, int top, int height, bool bold = false)
        {
            var label = ModernTheme.Label(s, bold ? 11f : 9f, bold);
            label.SetBounds(24, top, 625, height);
            Controls.Add(label);
        }
        Caption("OneDrive 최초 연결", 18, 30, true);
        Caption("브라우저에서 Microsoft 개인 계정 또는 회사/학교 계정으로 로그인합니다.", 55, 27);
        Caption("허용된 계정의 기본 OneDrive를 선택해 이후 자동 연결합니다.", 82, 24);
        Caption("회사·학교 계정은 조직의 Microsoft 관리자 승인 정책에 따라 제한될 수 있습니다.", 119, 28);
        Caption("Microsoft OAuth Client ID (선택)", 157, 24);
        clientId.SetBounds(24, 184, 618, 27);
        clientId.BackColor = ModernTheme.Field; clientId.ForeColor = ModernTheme.Text;
        Controls.Add(clientId);
        Caption("Client Secret (선택 · ID와 함께 입력)", 213, 24);
        clientSecret.SetBounds(24, 238, 618, 27);
        clientSecret.BackColor = ModernTheme.Field;
        clientSecret.ForeColor = ModernTheme.Text;
        Controls.Add(clientSecret);
        state.ForeColor = ModernTheme.Muted;
        state.Text = "자체 OAuth 앱이 없으면 rclone 기본 Microsoft 로그인을 시도합니다.";
        state.SetBounds(24, 270, 618, 27);
        Controls.Add(state);
        login = ModernTheme.Button("Microsoft 로그인", 166,
            () => _ = BeginAuthAsync(), accent: true, height: 34);
        login.SetBounds(346, 307, 166, 34);
        Controls.Add(login);
        var close = ModernTheme.Button("취소", 114, () =>
        {
            cancel.Cancel();
            if (!busy) { DialogResult = DialogResult.Cancel; Close(); }
        }, height: 34);
        close.SetBounds(520, 307, 114, 34);
        Controls.Add(close);
        FormClosing += (_, e) =>
        {
            if (busy) { cancel.Cancel(); e.Cancel = true; }
        };
    }

    private async Task BeginAuthAsync()
    {
        if (busy) return;
        busy = true;
        login.Enabled = false;
        try
        {
            state.Text = "Microsoft 인증 주소를 준비 중입니다...";
            await OneDriveAuth.AuthorizeAsync(profile, clientId.Text,
                clientSecret.Text,
                message => { if (!IsDisposed) BeginInvoke(() => state.Text = message); },
                url =>
                {
                    if (IsDisposed) return;
                    BeginInvoke(() =>
                    {
                        try
                        {
                            Process.Start(new ProcessStartInfo(url)
                                { UseShellExecute = true });
                        }
                        catch (Exception ex)
                        {
                            state.Text = "브라우저 실행 실패: " + ex.Message;
                        }
                    });
                }, cancel.Token);
            busy = false;
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (OperationCanceledException) { state.Text = "인증이 취소되었습니다."; }
        catch (Exception ex)
        {
            state.Text = "OneDrive 인증이 실패했습니다.";
            MessageBox.Show(this, ex.Message, "Microsoft 로그인 오류",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            busy = false;
            login.Enabled = !cancel.IsCancellationRequested;
            if (cancel.IsCancellationRequested)
            {
                DialogResult = DialogResult.Cancel;
                Close();
            }
        }
    }
}
