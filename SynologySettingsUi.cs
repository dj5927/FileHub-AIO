using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

/// <summary>
/// One-time DSM connection/preset setup. No share-link creation or file
/// browser belongs here: Explorer right-click opens the small quick-link UI.
/// </summary>
internal sealed class SynologySettingsForm : Form
{
    private sealed record ProfileChoice(NasProfile Value)
    {
        public override string ToString() =>
            $"{Value.DisplayName} ({Value.Letter.ToUpperInvariant()}: / {Value.Protocol})";
    }

    private readonly ComboBox drives = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox dsm = new();
    private readonly TextBox account = new();
    private readonly TextBox secret = new() { UseSystemPasswordChar = true };
    private readonly TextBox roots = new() { ReadOnly = true };
    private readonly TextBox otp = new() { MaxLength = 6 };
    private readonly CheckBox trust = new() { Text = "이 PC 신뢰 (2FA 유지)",
        BackColor = ModernTheme.Background, ForeColor = ModernTheme.Text };
    private readonly ComboBox expiry = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly CheckBox useLinkPassword = new() { Text = "링크 암호 사용",
        BackColor = ModernTheme.Background, ForeColor = ModernTheme.Text };
    private readonly TextBox linkPassword = new() {
        UseSystemPasswordChar = true, MaxLength = 16
    };
    private readonly Label status = new() { AutoEllipsis = true };
    private readonly Button shell = new();
    private readonly List<string> selection = [];
    private bool loading, busy;

    private NasProfile Profile =>
        (drives.SelectedItem as ProfileChoice)?.Value ??
        throw new ArgumentException("설정할 NAS 드라이브를 선택하세요.");

    internal SynologySettingsForm(AppConfig config, Guid? preferredProfileId = null)
    {
        Text = "FileHub AIO - Synology 공유 설정";
        ClientSize = new Size(690, 540);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        MaximizeBox = false; MinimizeBox = false;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 9f);
        ModernTheme.EnableDarkTitleBar(this);
        try { Icon = new Icon(ExplorerDriveIcon.IconPath); } catch { }
        Controls.Add(new Label {
            Text = "Synology 공유 설정",
            Font = new Font("Malgun Gothic", 15f, FontStyle.Bold),
            Location = new Point(23, 14), Size = new Size(640, 34),
            ForeColor = ModernTheme.Text
        });
        void Line(string name, Control field, int y, int width = 460)
        {
            Controls.Add(new Label {
                Text = name, Location = new Point(23, y + 4),
                Size = new Size(160, 25), ForeColor = ModernTheme.Muted
            });
            field.SetBounds(182, y, width, 30);
            if (field is TextBox t)
            {
                t.BackColor = ModernTheme.Surface;
                t.ForeColor = ModernTheme.Text;
                t.BorderStyle = BorderStyle.FixedSingle;
            }
            Controls.Add(field);
        }
        Button Action(string label, int x, int y, int width, Action handler,
            bool accent = false)
        {
            var button = ModernTheme.Button(label, width, handler, height: 34,
                accent: accent);
            button.SetBounds(x, y, width, 34);
            Controls.Add(button);
            return button;
        }
        Line("연결 드라이브", drives, 58);
        Line("DSM HTTPS 주소", dsm, 100);
        Line("DSM 계정", account, 142);
        Line("DSM 비밀번호", secret, 184);
        Line("DSM 공유 폴더", roots, 226, 272);
        Action("목록 불러오기 · 다중선택", 464, 224, 178,
            () => _ = LoadSharesAsync());
        Line("2FA 코드 (선택)", otp, 268, 155);
        trust.SetBounds(354, 270, 277, 24);
        trust.CheckedChanged += (_, _) => {
            if (loading || trust.Checked) return;
            try {
                var existing = SynologyShareStore.Read(Profile.Id);
                if (existing is not null && existing.DsmUrl.Length > 0 &&
                    existing.User.Length > 0)
                {
                    WindowsCredentials.Delete(
                        SynologySharing.TrustedDeviceCredentialId(Profile.Id,
                            existing.DsmUrl, existing.User));
                    SetStatus("이 PC의 기존 DSM 신뢰 기기 인증을 해제했습니다.");
                }
            } catch (Exception ex) { SetStatus(ex.Message); }
        };
        Controls.Add(trust);
        Line("링크 기본 만료", expiry, 310, 175);
        expiry.Items.AddRange(["1일", "3일", "7일", "30일", "무제한"]);
        useLinkPassword.SetBounds(370, 313, 132, 24);
        useLinkPassword.CheckedChanged += (_, _) =>
            linkPassword.Enabled = useLinkPassword.Checked;
        Controls.Add(useLinkPassword);
        linkPassword.SetBounds(503, 309, 139, 30);
        linkPassword.BackColor = ModernTheme.Surface;
        linkPassword.ForeColor = ModernTheme.Text;
        linkPassword.Enabled = false;
        Controls.Add(linkPassword);
        Controls.Add(new Label {
            Text = "설정 저장 후 탐색기에서 파일을 우클릭하면 링크가 바로 생성됩니다.",
            ForeColor = ModernTheme.Muted, Location = new Point(24, 353),
            Size = new Size(637, 26)
        });
        Action("설정 저장", 23, 394, 175, () => RunAction(SaveSettings), accent: true);
        Action("DSM 연결 확인", 208, 394, 175, () => _ = TestDsmAsync());
        shell.SetBounds(393, 394, 249, 34);
        shell.ForeColor = ModernTheme.Text;
        shell.BackColor = ModernTheme.Surface;
        shell.FlatStyle = FlatStyle.Flat;
        shell.Click += (_, _) => RunAction(() => {
            if (SynologyExplorerMenu.IsRegistered())
                SynologyExplorerMenu.Unregister();
            else SynologyExplorerMenu.Register();
            RefreshShell();
        });
        Controls.Add(shell);
        status.SetBounds(23, 453, 640, 53);
        status.ForeColor = ModernTheme.Muted;
        status.Text = "Windows 11: 우클릭 → 추가 옵션 표시 (Shift+F10)";
        Controls.Add(status);

        foreach (var item in config.Profiles.Where(SynologySharing.IsNas))
            drives.Items.Add(new ProfileChoice(item));
        drives.SelectedIndexChanged += (_, _) => LoadProfile();
        if (drives.Items.Count > 0)
        {
            int index = 0;
            if (preferredProfileId is Guid target)
                for (int i = 0; i < drives.Items.Count; i++)
                    if (((ProfileChoice)drives.Items[i]!).Value.Id == target)
                        { index = i; break; }
            drives.SelectedIndex = index;
        }
        else SetStatus("등록된 NAS 드라이브가 없습니다. 먼저 연결을 추가하세요.");
        RefreshShell();
    }

    private void SetStatus(string message) => status.Text = message;
    private static int ExpiryAt(int index) => index switch {
        0 => 1, 1 => 3, 2 => 7, 3 => 30, _ => 0
    };
    private static int IndexOfExpiry(int days) => days switch {
        1 => 0, 3 => 1, 7 => 2, 30 => 3, 0 => 4, _ => 2
    };
    private void RefreshShell() =>
        shell.Text = SynologyExplorerMenu.IsRegistered() ?
            "탐색기 우클릭 메뉴 등록 해제" : "탐색기 우클릭 메뉴 등록";

    private void LoadProfile()
    {
        if (drives.SelectedItem is not ProfileChoice choice) return;
        loading = true;
        try
        {
            var saved = SynologyShareStore.Read(choice.Value.Id);
            dsm.Text = saved?.DsmUrl ?? "";
            account.Text = saved?.User ?? "";
            secret.Clear();
            otp.Clear();
            selection.Clear();
            if (saved is not null)
                selection.AddRange(SynologySharing.Roots(saved));
            UpdateRoots();
            expiry.SelectedIndex = IndexOfExpiry(saved?.DefaultExpiryDays ?? 7);
            useLinkPassword.Checked = saved?.DefaultLinkPasswordEnabled ?? false;
            linkPassword.Clear();
            linkPassword.PlaceholderText = useLinkPassword.Checked ?
                "저장된 암호 유지" : "선택 시 입력";
            trust.Checked = saved is not null &&
                !string.IsNullOrWhiteSpace(saved.DsmUrl) &&
                !string.IsNullOrWhiteSpace(saved.User) &&
                !string.IsNullOrWhiteSpace(WindowsCredentials.Read(
                    SynologySharing.TrustedDeviceCredentialId(choice.Value.Id,
                        saved.DsmUrl, saved.User)));
        }
        finally { loading = false; }
    }

    private void UpdateRoots() => roots.Text =
        selection.Count == 0 ? "(미선택)" : string.Join(", ", selection);

    private SynologyShareSettings Draft(bool requireSelection = true)
    {
        var chosen = SynologySharing.NormalizeRoots(selection);
        if (requireSelection && chosen.Count == 0)
            throw new ArgumentException("DSM 공유 폴더를 하나 이상 선택하세요.");
        string user = account.Text.Trim();
        if (user.Length == 0) throw new ArgumentException("DSM 계정을 입력하세요.");
        return new SynologyShareSettings {
            DsmUrl = SynologySharing.ValidateDsmUrl(dsm.Text)
                .GetLeftPart(UriPartial.Authority),
            User = user, ShareRoot = chosen.FirstOrDefault() ?? "",
            ShareRoots = chosen, DefaultExpiryDays = ExpiryAt(expiry.SelectedIndex),
            DefaultLinkPasswordEnabled = useLinkPassword.Checked
        };
    }

    private void SaveSettings()
    {
        var settings = Draft();
        if (secret.Text.Length > 0)
            WindowsCredentials.Store(SynologySharing.CredentialId(Profile.Id),
                settings.User, secret.Text);
        var key = SynologySharing.LinkPasswordCredentialId(Profile.Id);
        if (settings.DefaultLinkPasswordEnabled)
        {
            string existing = WindowsCredentials.Read(key) ?? "";
            if (linkPassword.Text.Length == 0 && existing.Length == 0)
                throw new ArgumentException(
                    "링크 암호 사용을 선택했다면 암호를 입력하세요.");
            if (linkPassword.Text.Length > 0)
                WindowsCredentials.Store(key, settings.User, linkPassword.Text);
        }
        else WindowsCredentials.Delete(key);
        SynologyShareStore.Save(Profile.Id, settings);
        secret.Clear(); linkPassword.Clear();
        SetStatus($"설정 저장됨 · 폴더 {settings.ShareRoots.Count}개 · " +
            (settings.DefaultExpiryDays == 0 ? "무제한" :
                settings.DefaultExpiryDays + "일") +
            " · 탐색기에서 바로 링크 생성 가능");
    }

    private SynologySharingClient BuildClient(bool requireSelection)
    {
        var settings = Draft(requireSelection);
        string password = secret.Text.Length > 0 ? secret.Text :
            WindowsCredentials.Read(SynologySharing.CredentialId(Profile.Id)) ?? "";
        Guid key = SynologySharing.TrustedDeviceCredentialId(
            Profile.Id, settings.DsmUrl, settings.User);
        return new SynologySharingClient(settings, password, otp.Text,
            trustThisPc: trust.Checked,
            savedDeviceId: trust.Checked ? WindowsCredentials.Read(key) ?? "" : "",
            onTrustedDeviceId: did => WindowsCredentials.Store(key, settings.User, did),
            onRejectedDeviceId: () => WindowsCredentials.Delete(key));
    }

    private async Task LoadSharesAsync()
    {
        await ExecuteAsync(async () => {
            var items = await BuildClient(false).ListSharesAsync();
            using var chooser = new SynologyShareSelectorDialog(items, selection);
            if (chooser.ShowDialog(this) != DialogResult.OK) return;
            selection.Clear();
            selection.AddRange(chooser.SelectedRoots);
            UpdateRoots();
            SetStatus($"공유 폴더 {selection.Count}개 체크됨. 설정 저장을 눌러 적용하세요.");
        });
    }

    private async Task TestDsmAsync() =>
        await ExecuteAsync(async () => {
            var items = await BuildClient(false).ListSharesAsync();
            SetStatus($"DSM 인증·공유 폴더 조회 성공 ({items.Count}개).");
        });

    private async Task ExecuteAsync(Func<Task> callback)
    {
        if (busy) return;
        busy = true; drives.Enabled = false; UseWaitCursor = true;
        try
        {
            try { await callback(); }
            catch (SynologyOtpRequiredException ex) when (otp.Text.Length == 0)
            {
                var result = SynologyOtpPrompt.Ask(this, ex.Message, trust.Checked);
                if (result is null) return;
                otp.Text = result.Value.Code;
                trust.Checked = result.Value.Trust;
                await callback();
            }
        }
        catch (Exception ex)
        {
            SetStatus(ex.Message);
            MessageBox.Show(this, ex.Message, "Synology 설정",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            otp.Clear();
            busy = false; drives.Enabled = true; UseWaitCursor = false;
        }
    }

    private void RunAction(Action action)
    {
        try { action(); }
        catch (Exception ex)
        {
            SetStatus(ex.Message);
            MessageBox.Show(this, ex.Message, "Synology 설정",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }
}
