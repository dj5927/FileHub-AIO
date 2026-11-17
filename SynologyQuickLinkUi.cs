using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal static class SynologyOtpPrompt
{
    internal readonly record struct Result(string Code, bool Trust);

    internal static Result? Ask(IWin32Window owner, string message, bool trustDefault)
    {
        using var window = new Form {
            Text = "DSM 2단계 인증", ClientSize = new Size(420, 209),
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MaximizeBox = false, MinimizeBox = false,
            AutoScaleMode = AutoScaleMode.Dpi,
            BackColor = ModernTheme.Background, ForeColor = ModernTheme.Text,
            Font = new Font("Malgun Gothic", 9f)
        };
        ModernTheme.EnableDarkTitleBar(window);
        window.Controls.Add(new Label {
            Text = message, Location = new Point(19, 15),
            Size = new Size(385, 54), AutoEllipsis = true,
            ForeColor = ModernTheme.Text
        });
        var code = new TextBox {
            Location = new Point(19, 72), Size = new Size(383, 30),
            MaxLength = 6, UseSystemPasswordChar = true,
            BackColor = ModernTheme.Surface, ForeColor = ModernTheme.Text
        };
        window.Controls.Add(code);
        var trust = new CheckBox {
            Text = "이 PC 신뢰 (다음부터 2FA 입력 생략)",
            Checked = trustDefault, Location = new Point(19, 113),
            Size = new Size(383, 25), ForeColor = ModernTheme.Text,
            BackColor = ModernTheme.Background
        };
        window.Controls.Add(trust);
        var ok = ModernTheme.Button("인증하고 계속", 187, () => {
            if (code.Text.Length != 6 || code.Text.Any(c => c < '0' || c > '9'))
            {
                MessageBox.Show(window, "OTP 숫자 6자리를 입력하세요.");
                return;
            }
            window.DialogResult = DialogResult.OK;
            window.Close();
        }, accent: true);
        ok.SetBounds(19, 156, 187, 34);
        window.Controls.Add(ok);
        var cancel = ModernTheme.Button("취소", 187, () => {
            window.DialogResult = DialogResult.Cancel;
            window.Close();
        });
        cancel.SetBounds(215, 156, 187, 34);
        window.Controls.Add(cancel);
        window.AcceptButton = ok; window.CancelButton = cancel;
        window.Shown += (_, _) => code.Focus();
        return window.ShowDialog(owner) == DialogResult.OK ?
            new Result(code.Text, trust.Checked) : null;
    }
}

/// <summary>
/// Explorer shell-only quick action: maps the selected Windows path to the
/// configured DSM share, creates one link automatically and shows only the
/// URL/copy controls. Never opens the full settings form on success.
/// </summary>
internal sealed class SynologyQuickLinkForm : Form
{
    private readonly AppConfig config;
    private readonly string explorerPath;
    private readonly bool preview;
    private readonly Label filename = new();
    private readonly Label status = new();
    private readonly TextBox result = new() { ReadOnly = true };
    private readonly Button copyButton;
    private readonly Button openSettingsButton;
    private bool finished;
    internal bool HasLink => result.Text.Length > 0;

    internal SynologyQuickLinkForm(AppConfig config, string explorerPath,
        bool previewForUiTest = false)
    {
        this.config = config;
        this.explorerPath = explorerPath;
        preview = previewForUiTest;
        Text = "FileHub AIO - 공유 링크";
        ClientSize = new Size(554, 223);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false; MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 9f);
        ModernTheme.EnableDarkTitleBar(this);
        try { Icon = new Icon(ExplorerDriveIcon.IconPath); } catch { }
        Controls.Add(new Label {
            Text = "Synology 공유 링크",
            Font = new Font("Malgun Gothic", 13.5f, FontStyle.Bold),
            Location = new Point(20, 14), Size = new Size(510, 29),
            ForeColor = ModernTheme.Text
        });
        filename.SetBounds(21, 48, 511, 24);
        filename.ForeColor = ModernTheme.Muted;
        filename.AutoEllipsis = true;
        filename.Text = "대상: " + Path.GetFileName(explorerPath.TrimEnd('\\'));
        Controls.Add(filename);
        status.SetBounds(21, 78, 511, 29);
        status.ForeColor = ModernTheme.Muted;
        status.Text = "설정된 DSM 계정으로 공유 링크를 생성하고 있습니다...";
        Controls.Add(status);
        result.SetBounds(21, 112, 510, 30);
        result.ForeColor = ModernTheme.Text;
        result.BackColor = ModernTheme.Surface;
        Controls.Add(result);
        copyButton = ModernTheme.Button("링크 복사", 180, () => {
            if (result.Text.Length == 0) return;
            try {
                Clipboard.SetText(result.Text);
                status.Text = "공유 URL을 클립보드에 복사했습니다.";
            }
            catch (Exception ex) {
                MessageBox.Show(this, "복사 실패: " + ex.Message);
            }
        }, accent: true);
        copyButton.SetBounds(21, 163, 180, 35);
        copyButton.Enabled = false;
        Controls.Add(copyButton);
        openSettingsButton = ModernTheme.Button("연결 설정", 166, () => {
            // Only necessary if the initial automatic action failed.
            using var settings = new SynologySettingsForm(config);
            settings.ShowDialog(this);
        });
        openSettingsButton.SetBounds(210, 163, 166, 35);
        openSettingsButton.Visible = false;
        Controls.Add(openSettingsButton);
        var close = ModernTheme.Button("닫기", 146, Close);
        close.SetBounds(385, 163, 146, 35);
        Controls.Add(close);
        CancelButton = close;
        Shown += async (_, _) => {
            if (preview) {
                Complete("https://gofile.me/example/preview");
                return;
            }
            await CreateQuickLinkAsync();
        };
    }

    private void Complete(string url)
    {
        result.Text = url;
        copyButton.Enabled = true;
        status.Text = "링크 생성 완료 · 복사 버튼을 눌러 전달하세요.";
        finished = true;
    }

    private async Task CreateQuickLinkAsync()
    {
        if (finished) return;
        try
        {
            if (explorerPath.Length < 3 || explorerPath[1] != ':' ||
                !Path.IsPathFullyQualified(explorerPath))
                throw new ArgumentException("FileHub AIO 드라이브에서 파일을 선택하세요.");
            var matches = config.Profiles.Where(p => SynologySharing.IsNas(p) &&
                p.Letter.Length == 1 &&
                char.ToUpperInvariant(p.Letter[0]) ==
                char.ToUpperInvariant(explorerPath[0])).ToArray();
            if (matches.Length != 1)
                throw new ArgumentException(
                    "이 드라이브의 FileHub AIO 연결 설정을 정확히 찾지 못했습니다.");
            var profile = matches[0];
            var settings = SynologyShareStore.Read(profile.Id) ??
                throw new ArgumentException(
                    "이 연결의 DSM 공유 설정이 없습니다. 환경 설정에서 먼저 등록하세요.");
            string dsmPath = SynologySharing.MapExplorerPath(profile,
                settings, explorerPath);
            if (dsmPath.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase) ||
                Path.GetFileName(dsmPath).StartsWith("~$", StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("잠금 표시 파일은 공유 링크를 만들 수 없습니다.");
            string password = WindowsCredentials.Read(
                SynologySharing.CredentialId(profile.Id)) ?? "";
            if (password.Length == 0)
                throw new ArgumentException(
                    "저장된 DSM 암호가 없습니다. Synology 공유 설정에서 저장하세요.");
            string linkPassword = settings.DefaultLinkPasswordEnabled ?
                WindowsCredentials.Read(
                    SynologySharing.LinkPasswordCredentialId(profile.Id)) ?? "" : "";
            if (settings.DefaultLinkPasswordEnabled && linkPassword.Length == 0)
                throw new ArgumentException("설정된 링크 비밀번호를 찾지 못했습니다.");
            Guid tokenKey = SynologySharing.TrustedDeviceCredentialId(
                profile.Id, settings.DsmUrl, settings.User);
            string deviceId = WindowsCredentials.Read(tokenKey) ?? "";
            bool trust = deviceId.Length > 0;
            string otp = "";
            async Task<SynologySharedLink> Create()
            {
                var client = new SynologySharingClient(settings, password, otp,
                    trustThisPc: trust, savedDeviceId: deviceId,
                    onTrustedDeviceId: did => WindowsCredentials.Store(
                        tokenKey, settings.User, did),
                    onRejectedDeviceId: () => WindowsCredentials.Delete(tokenKey));
                return await client.CreateAsync(
                    dsmPath, settings.DefaultExpiryDays, linkPassword);
            }
            SynologySharedLink link;
            try { link = await Create(); }
            catch (SynologyOtpRequiredException ex)
            {
                var entered = SynologyOtpPrompt.Ask(this, ex.Message, trustDefault: true);
                if (entered is null)
                {
                    status.Text = "2단계 인증 취소 · 링크를 생성하지 않았습니다.";
                    finished = true;
                    return;
                }
                otp = entered.Value.Code;
                trust = entered.Value.Trust;
                deviceId = "";
                link = await Create();
            }
            Complete(link.Url);
        }
        catch (Exception ex)
        {
            status.Text = "링크 생성 실패: " + ex.Message;
            openSettingsButton.Visible = true;
            openSettingsButton.BringToFront();
            result.Clear();
            finished = true;
            AppStore.Log("SYNOLOGY_QUICK_LINK_FAILED " + ex.GetType().Name);
            MessageBox.Show(this, ex.Message, "Synology 공유 링크",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
