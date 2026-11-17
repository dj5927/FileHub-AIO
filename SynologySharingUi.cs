using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal sealed class SynologyShareForm : Form
{
    private readonly ComboBox profileBox = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox dsmBox = new();
    private readonly TextBox userBox = new();
    private readonly TextBox passwordBox = new() { UseSystemPasswordChar = true };
    private readonly TextBox rootBox = new() { ReadOnly = true };
    private readonly TextBox relativeBox = new();
    private readonly TextBox otpBox = new() { MaxLength = 6 };
    private readonly CheckBox rememberDeviceBox = new() {
        Text = "이 PC 신뢰 (다음부터 2FA 생략)",
        AutoSize = false, ForeColor = ModernTheme.Text,
        BackColor = ModernTheme.Background
    };
    private readonly ComboBox daysBox = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox sharePasswordBox = new() { UseSystemPasswordChar = true, MaxLength = 16 };
    private readonly TextBox resultBox = new() { ReadOnly = true };
    private readonly ListView links = new() {
        View = View.Details, FullRowSelect = true, MultiSelect = true,
        HideSelection = false
    };
    private readonly Button toggleMenu = new();
    private readonly Label status = new() { AutoEllipsis = true };
    private readonly string? explorerPath;
    private readonly List<string> selectedRoots = [];
    private string? browserPickedPath;
    private bool changingProfile;
    private bool busy;

    private sealed record ProfileItem(NasProfile Profile)
    {
        public override string ToString() =>
            Profile.DisplayName + " (" + Profile.Letter.ToUpperInvariant() + ": / " +
            Profile.Protocol + ")";
    }

    private NasProfile Profile => ((ProfileItem?)profileBox.SelectedItem)?.Profile ??
        throw new ArgumentException("먼저 NAS 연결을 선택하세요.");

    internal SynologyShareForm(AppConfig config, string? explorerPath = null,
        Guid? preferredProfileId = null)
    {
        this.explorerPath = explorerPath;
        Text = "FileHub AIO - Synology 공유 링크";
        ClientSize = new Size(794, 705);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 9f);
        try { Icon = new Icon(ExplorerDriveIcon.IconPath); } catch {}
        ModernTheme.EnableDarkTitleBar(this);

        var title = new Label { Text = "Synology 공유 링크 생성 · 관리",
            Font = new Font("Malgun Gothic", 15f, FontStyle.Bold),
            ForeColor = ModernTheme.Text, Location = new Point(22, 12),
            Size = new Size(700, 32) };
        Controls.Add(title);
        Label L(string text, int y)
        {
            var label = new Label { Text = text, Location = new Point(23, y + 5),
                Size = new Size(165, 24), ForeColor = ModernTheme.Muted };
            Controls.Add(label);
            return label;
        }
        void Input(Control control, int y, int width = 578)
        {
            control.SetBounds(187, y, width, 29);
            if (control is TextBox tb) {
                tb.BackColor = ModernTheme.Surface;
                tb.ForeColor = ModernTheme.Text;
                tb.BorderStyle = BorderStyle.FixedSingle;
            }
            Controls.Add(control);
        }
        L("연결 드라이브", 50); Input(profileBox, 50);
        L("DSM HTTPS 주소", 91); Input(dsmBox, 91);
        L("DSM 로그인 계정", 132); Input(userBox, 132);
        L("DSM 로그인 암호", 173); Input(passwordBox, 173);
        L("DSM 공유 폴더 (복수)", 214); Input(rootBox, 214, 333);
        B("목록 불러오기 · 다중선택", 528, 214, 237,
            () => _ = LoadAvailableSharesAsync());
        L("파일 / DSM 경로", 255); Input(relativeBox, 255, 333);
        B("NAS 파일 / 폴더 탐색", 528, 255, 237,
            () => _ = BrowseFilesAsync());
        L("DSM 2FA 코드 (선택)", 296); Input(otpBox, 296, 159);
        rememberDeviceBox.SetBounds(361, 298, 404, 27);
        rememberDeviceBox.CheckedChanged += (_, _) => {
            if (!changingProfile && !rememberDeviceBox.Checked) {
                try {
                    var key = SynologySharing.TrustedDeviceCredentialId(
                        Profile.Id, dsmBox.Text, userBox.Text);
                    WindowsCredentials.Delete(key);
                    SetStatus("저장된 PC 신뢰 인증을 해제했습니다. 다음 연결부터 OTP가 필요할 수 있습니다.");
                }
                catch (Exception ex) {
                    AppStore.Log("DSM_TRUST_DEVICE_REVOKE_FAILED " + ex.GetType().Name);
                    SetStatus("PC 신뢰 인증 해제 실패: " + ex.Message);
                }
            }
        };
        Controls.Add(rememberDeviceBox);
        L("링크 유효기간", 337); Input(daysBox, 337, 160);
        var pwdLabel = new Label { Text = "링크 비밀번호 (선택)",
            Location = new Point(379, 343), Size = new Size(150, 24),
            ForeColor = ModernTheme.Muted };
        Controls.Add(pwdLabel);
        sharePasswordBox.SetBounds(537, 337, 228, 29);
        sharePasswordBox.BackColor = ModernTheme.Surface;
        sharePasswordBox.ForeColor = ModernTheme.Text;
        Controls.Add(sharePasswordBox);

        daysBox.Items.AddRange(["1일", "3일", "7일", "30일", "무제한"]);
        daysBox.SelectedIndex = 2;
        void B(string text, int x, int y, int w, Action click)
        {
            var b = ModernTheme.Button(text, w, click, height: 34);
            b.SetBounds(x, y, w, 33);
            Controls.Add(b);
        }
        B("설정 저장", 23, 382, 170, () => ShowAction(() => {
            SaveSettings(); SetStatus("DSM 설정을 저장했습니다.");
        }));
        B("DSM 연결 확인", 204, 382, 182, () => _ = RunAsync(async client => {
            await client.TestAsync();
            SetStatus("DSM 인증 및 File Station API 확인 성공");
        }));
        B("공유 링크 생성", 397, 382, 368, () => _ = RunAsync(async client => {
            string full = ResolvePath();
            int days = daysBox.SelectedIndex switch {
                0 => 1, 1 => 3, 2 => 7, 3 => 30, _ => 0
            };
            var link = await client.CreateAsync(full, days, sharePasswordBox.Text);
            resultBox.Text = link.Url;
            SetStatus("공유 링크가 생성되었습니다. 링크 복사 버튼으로 전달하세요.");
        }));
        L("생성된 URL", 426);
        resultBox.SetBounds(187, 426, 578, 29);
        resultBox.BackColor = ModernTheme.Surface;
        resultBox.ForeColor = ModernTheme.Text;
        Controls.Add(resultBox);

        var listLabel = new Label { Text = "이 계정의 공유 링크 (설정된 공유 폴더 안에서만 표시)",
            Location = new Point(23, 470), Size = new Size(740, 22),
            ForeColor = ModernTheme.Muted };
        Controls.Add(listLabel);
        links.SetBounds(23, 495, 742, 120);
        links.BackColor = ModernTheme.Surface;
        links.ForeColor = ModernTheme.Text;
        links.Columns.Add("DSM 경로", 285);
        links.Columns.Add("만료", 105);
        links.Columns.Add("공유 URL", 325);
        Controls.Add(links);

        B("링크 목록 새로고침", 23, 623, 206, () => _ = RunAsync(async client => {
            var items = await client.ListAsync();
            links.Items.Clear();
            foreach (var item in items)
            {
                var row = new ListViewItem(item.Path) { Tag = item };
                row.SubItems.Add(item.Expires == "0" ? "무제한" : item.Expires);
                row.SubItems.Add(item.Url);
                links.Items.Add(row);
            }
            SetStatus("총 " + items.Count + "개 링크를 조회했습니다.");
        }));
        B("링크 복사", 238, 623, 125, () => ShowAction(() => {
            string url = links.SelectedItems.Count > 0 &&
                links.SelectedItems[0].Tag is SynologySharedLink link ?
                link.Url : resultBox.Text;
            if (string.IsNullOrEmpty(url)) throw new ArgumentException("복사할 링크를 선택하세요.");
            Clipboard.SetText(url);
            SetStatus("클립보드에 링크 복사 완료");
        }));
        B("선택 링크 해제", 372, 623, 170, () => _ = RevokeAsync());
        toggleMenu.SetBounds(550, 623, 215, 33);
        toggleMenu.BackColor = ModernTheme.Surface;
        toggleMenu.ForeColor = ModernTheme.Text;
        toggleMenu.FlatStyle = FlatStyle.Flat;
        toggleMenu.Click += (_, _) => ShowAction(ToggleExplorerMenu);
        Controls.Add(toggleMenu);

        status.SetBounds(23, 669, 740, 22);
        status.ForeColor = ModernTheme.Muted;
        status.Text = "Windows 11 우클릭: 추가 옵션 표시(Shift+F10)에서 사용";
        Controls.Add(status);

        foreach (var p in config.Profiles.Where(SynologySharing.IsNas))
            profileBox.Items.Add(new ProfileItem(p));
        profileBox.SelectedIndexChanged += (_, _) => LoadProfile();
        if (profileBox.Items.Count > 0)
        {
            int selected = 0;
            if (preferredProfileId is Guid preferred)
            {
                for (int i = 0; i < profileBox.Items.Count; i++)
                    if (((ProfileItem)profileBox.Items[i]!).Profile.Id == preferred)
                        { selected = i; break; }
            }
            else if (!string.IsNullOrWhiteSpace(explorerPath) &&
                explorerPath.Length > 1 && explorerPath[1] == ':')
            {
                for (int i = 0; i < profileBox.Items.Count; i++)
                    if (char.ToUpperInvariant(((ProfileItem)profileBox.Items[i]!).Profile
                        .Letter.FirstOrDefault()) ==
                        char.ToUpperInvariant(explorerPath[0]))
                        { selected = i; break; }
            }
            profileBox.SelectedIndex = selected;
        }
        else SetStatus("등록된 SMB/WebDAV/SFTP/FTP 드라이브가 없습니다. 먼저 NAS를 연결하세요.");
        RefreshMenuLabel();
    }

    private void SetStatus(string text) => status.Text = text;

    private void LoadProfile()
    {
        if (profileBox.SelectedItem is not ProfileItem item) return;
        changingProfile = true;
        try
        {
            var saved = SynologyShareStore.Read(item.Profile.Id);
            dsmBox.Text = saved?.DsmUrl ?? "";
            userBox.Text = saved?.User ?? "";
            passwordBox.Clear();
            rememberDeviceBox.Checked = saved is not null &&
                !string.IsNullOrWhiteSpace(saved.DsmUrl) &&
                !string.IsNullOrWhiteSpace(saved.User) &&
                !string.IsNullOrWhiteSpace(WindowsCredentials.Read(
                    SynologySharing.TrustedDeviceCredentialId(item.Profile.Id,
                        saved.DsmUrl, saved.User)));
            selectedRoots.Clear();
            if (saved is not null)
                selectedRoots.AddRange(SynologySharing.Roots(saved));
            UpdateShareRootSummary();
            resultBox.Clear();
            links.Items.Clear();
            browserPickedPath = null;
            relativeBox.Text = !string.IsNullOrEmpty(explorerPath) &&
                explorerPath.Length > 2 &&
                char.ToUpperInvariant(explorerPath[0]) ==
                char.ToUpperInvariant(item.Profile.Letter.FirstOrDefault()) &&
                explorerPath[1] == ':' ?
                explorerPath[3..].Replace('\\', '/') : "";
            if (explorerPath is not null) relativeBox.ReadOnly = true;
        }
        finally { changingProfile = false; }
    }

    private void UpdateShareRootSummary()
    {
        rootBox.Text = selectedRoots.Count == 0 ? "(미선택 · 목록 불러오기)" :
            string.Join(", ", selectedRoots);
        rootBox.AccessibleDescription = $"{selectedRoots.Count}개 공유 폴더 선택됨";
    }

    private SynologyShareSettings DraftSettings(bool requireRoots)
    {
        var roots = SynologySharing.NormalizeRoots(selectedRoots);
        if (requireRoots && roots.Count == 0)
            throw new ArgumentException("공유 폴더 목록을 불러와 하나 이상 체크하세요.");
        var settings = new SynologyShareSettings {
            DsmUrl = SynologySharing.ValidateDsmUrl(dsmBox.Text)
                .GetLeftPart(UriPartial.Authority),
            ShareRoot = roots.FirstOrDefault() ?? "",
            ShareRoots = roots,
            User = userBox.Text.Trim()
        };
        if (settings.User.Length == 0)
            throw new ArgumentException("DSM 계정을 입력하세요.");
        return settings;
    }

    private void SaveSettings()
    {
        if (changingProfile) return;
        var current = DraftSettings(requireRoots: true);
        if (!string.IsNullOrWhiteSpace(passwordBox.Text))
            WindowsCredentials.Store(SynologySharing.CredentialId(Profile.Id),
                current.User, passwordBox.Text);
        SynologyShareStore.Save(Profile.Id, current);
        passwordBox.Clear();
    }

    private SynologySharingClient Client(bool shareDiscovery = false)
    {
        if (!shareDiscovery) SaveSettings();
        var settings = shareDiscovery ? DraftSettings(requireRoots: false) :
            SynologyShareStore.Read(Profile.Id)!;
        string password = !string.IsNullOrWhiteSpace(passwordBox.Text) ?
            passwordBox.Text :
            WindowsCredentials.Read(SynologySharing.CredentialId(Profile.Id)) ?? "";
        Guid tokenKey = SynologySharing.TrustedDeviceCredentialId(
            Profile.Id, settings.DsmUrl, settings.User);
        return new SynologySharingClient(settings, password, otpBox.Text,
            trustThisPc: rememberDeviceBox.Checked,
            savedDeviceId: rememberDeviceBox.Checked ?
                WindowsCredentials.Read(tokenKey) ?? "" : "",
            onTrustedDeviceId: did => {
                WindowsCredentials.Store(tokenKey, settings.User, did);
                AppStore.Log("DSM_TRUSTED_DEVICE_REGISTERED profile=" + Profile.Id);
            },
            onRejectedDeviceId: () => {
                WindowsCredentials.Delete(tokenKey);
                AppStore.Log("DSM_TRUSTED_DEVICE_EXPIRED profile=" + Profile.Id);
            });
    }

    private string ResolvePath()
    {
        var settings = SynologyShareStore.Read(Profile.Id) ??
            DraftSettings(requireRoots: true);
        if (browserPickedPath is not null &&
            string.Equals(relativeBox.Text, browserPickedPath,
                StringComparison.Ordinal) &&
            SynologySharing.IsAllowed(settings, browserPickedPath))
            return browserPickedPath;
        if (explorerPath is not null)
            return SynologySharing.MapExplorerPath(Profile, settings, explorerPath);
        if (string.IsNullOrWhiteSpace(relativeBox.Text))
            throw new ArgumentException(
                "공유할 대상이 선택되지 않았습니다. NAS 파일 / 폴더 탐색에서 " +
                "실제 파일이나 폴더를 먼저 선택하세요.");
        return SynologySharing.MapRelativePath(settings, relativeBox.Text);
    }

    private async Task LoadAvailableSharesAsync()
    {
        await RunAsync(async client => {
            var shares = await client.ListSharesAsync();
            if (shares.Count == 0) {
                throw new IOException(
                    "DSM File Station에서 접근 가능한 공유 폴더가 없습니다. 계정 권한을 확인하세요.");
            }
            using var chooser = new SynologyShareSelectorDialog(
                shares, selectedRoots);
            if (chooser.ShowDialog(this) != DialogResult.OK) return;
            selectedRoots.Clear();
            selectedRoots.AddRange(chooser.SelectedRoots);
            UpdateShareRootSummary();
            if (browserPickedPath is not null &&
                !SynologySharing.IsAllowed(DraftSettings(true), browserPickedPath))
            {
                browserPickedPath = null;
                if (explorerPath is null) relativeBox.Clear();
            }
            SaveSettings();
            SetStatus($"DSM 공유 폴더 {selectedRoots.Count}개 선택·저장 완료");
        }, shareDiscovery: true);
    }

    private async Task BrowseFilesAsync()
    {
        await RunAsync(async client => {
            var roots = selectedRoots.ToArray();
            string? path = await client.BrowseAsync(async folderList => {
                using var browser = new SynologyFileBrowserDialog(roots, folderList);
                return await browser.ChooseAsync(this);
            });
            if (path is null) return;
            browserPickedPath = path;
            relativeBox.Text = path;
            SetStatus("DSM에서 파일/폴더 선택: " + path);
        });
    }

    private async Task RunAsync(Func<SynologySharingClient, Task> operation,
        bool shareDiscovery = false)
    {
        if (busy) return;
        busy = true;
        profileBox.Enabled = false;
        UseWaitCursor = true;
        try
        {
            var client = Client(shareDiscovery);
            try
            {
                await operation(client);
            }
            catch (SynologyOtpRequiredException ex) when
                (string.IsNullOrWhiteSpace(otpBox.Text))
            {
                // DSM login rejected before the requested operation ran.
                // One retry after an OTP prompt is safe.
                if (!PromptForOtp(ex.Message)) return;
                await operation(Client(shareDiscovery));
            }
        }
        catch (Exception ex)
        {
            SetStatus("오류: " + ex.Message);
            MessageBox.Show(this, ex.Message, "Synology 공유 링크",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            otpBox.Clear();
            sharePasswordBox.Clear();
            UseWaitCursor = false;
            profileBox.Enabled = true;
            busy = false;
        }
    }

    private bool PromptForOtp(string reason)
    {
        using var dialog = new Form {
            Text = "DSM 2단계 인증", ClientSize = new Size(420, 215),
            Font = new Font("Malgun Gothic", 9f), MaximizeBox = false,
            MinimizeBox = false, FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            BackColor = ModernTheme.Background, ForeColor = ModernTheme.Text
        };
        ModernTheme.EnableDarkTitleBar(dialog);
        var prompt = new Label {
            Text = reason, ForeColor = ModernTheme.Text,
            Location = new Point(18, 17), Size = new Size(387, 48),
            AutoEllipsis = true
        };
        dialog.Controls.Add(prompt);
        var code = new TextBox {
            Location = new Point(18, 72), Width = 384,
            MaxLength = 6, UseSystemPasswordChar = true,
            BackColor = ModernTheme.Surface, ForeColor = ModernTheme.Text
        };
        dialog.Controls.Add(code);
        var trust = new CheckBox {
            Text = "이 PC를 신뢰하고 다음 DSM 로그인부터 OTP 생략",
            Checked = rememberDeviceBox.Checked, AutoSize = false,
            Location = new Point(18, 111), Size = new Size(384, 26),
            ForeColor = ModernTheme.Text, BackColor = ModernTheme.Background
        };
        dialog.Controls.Add(trust);
        var confirm = ModernTheme.Button("인증하고 계속", 188, () => {
            if (!System.Text.RegularExpressions.Regex.IsMatch(code.Text, @"^[0-9]{6}$"))
            {
                MessageBox.Show(dialog, "OTP 6자리 숫자를 입력하세요.",
                    "DSM 인증", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            otpBox.Text = code.Text;
            rememberDeviceBox.Checked = trust.Checked;
            dialog.DialogResult = DialogResult.OK;
            dialog.Close();
        }, accent: true);
        confirm.SetBounds(18, 159, 188, 35);
        dialog.Controls.Add(confirm);
        var cancel = ModernTheme.Button("취소", 188, () => {
            dialog.DialogResult = DialogResult.Cancel;
            dialog.Close();
        });
        cancel.SetBounds(214, 159, 188, 35);
        dialog.Controls.Add(cancel);
        dialog.AcceptButton = confirm;
        dialog.CancelButton = cancel;
        dialog.Shown += (_, _) => code.Focus();
        return dialog.ShowDialog(this) == DialogResult.OK;
    }

    private async Task RevokeAsync()
    {
        var selected = links.SelectedItems.Cast<ListViewItem>()
            .Select(x => x.Tag).OfType<SynologySharedLink>().ToArray();
        if (selected.Length == 0)
        {
            SetStatus("삭제할 링크를 선택하세요.");
            return;
        }
        if (MessageBox.Show(this, "선택한 공유 링크 " + selected.Length +
            "개를 해제할까요?\n원본 파일은 삭제하지 않습니다.",
            "공유 링크 해제", MessageBoxButtons.YesNo,
            MessageBoxIcon.Question) != DialogResult.Yes) return;
        await RunAsync(async client => {
            await client.DeleteAsync(selected);
            foreach (var item in links.SelectedItems.Cast<ListViewItem>().ToArray())
                links.Items.Remove(item);
            SetStatus(selected.Length + "개 공유 링크 해제 완료");
        });
    }

    private void ShowAction(Action action)
    {
        try { action(); }
        catch (Exception ex) {
            SetStatus("오류: " + ex.Message);
            MessageBox.Show(this, ex.Message, "FileHub AIO",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ToggleExplorerMenu()
    {
        if (SynologyExplorerMenu.IsRegistered())
        {
            SynologyExplorerMenu.Unregister();
            SetStatus("탐색기 메뉴를 해제했습니다.");
        }
        else
        {
            SynologyExplorerMenu.Register();
            SetStatus("탐색기 메뉴 등록 완료. Windows 11은 추가 옵션 표시에서 이용하세요.");
        }
        RefreshMenuLabel();
    }

    private void RefreshMenuLabel() =>
        toggleMenu.Text = SynologyExplorerMenu.IsRegistered() ?
            "탐색기 메뉴 등록 해제" : "탐색기 메뉴 등록";
}
