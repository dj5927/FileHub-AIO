using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal sealed class SynologyShareForm : Form
{
    private readonly ComboBox profileBox = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox dsmBox = new();
    private readonly TextBox userBox = new();
    private readonly TextBox passwordBox = new() { UseSystemPasswordChar = true };
    private readonly TextBox rootBox = new();
    private readonly TextBox relativeBox = new();
    private readonly TextBox otpBox = new() { MaxLength = 6 };
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
        L("DSM 공유 폴더", 214); Input(rootBox, 214);
        L("선택 파일 / 상대 경로", 255); Input(relativeBox, 255);
        L("DSM 2FA 코드 (선택)", 296); Input(otpBox, 296);
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
            rootBox.Text = saved?.ShareRoot ?? (item.Profile.Protocol == "SMB" &&
                !string.IsNullOrEmpty(item.Profile.Share) ?
                "/" + item.Profile.Share.Trim('/','\\') : "");
            resultBox.Clear();
            links.Items.Clear();
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

    private void SaveSettings()
    {
        if (changingProfile) return;
        var current = new SynologyShareSettings {
            DsmUrl = SynologySharing.ValidateDsmUrl(dsmBox.Text).GetLeftPart(UriPartial.Authority),
            ShareRoot = SynologySharing.NormalizeRoot(rootBox.Text),
            User = userBox.Text.Trim()
        };
        if (current.User.Length == 0) throw new ArgumentException("DSM 계정을 입력하세요.");
        if (!string.IsNullOrWhiteSpace(passwordBox.Text))
            WindowsCredentials.Store(SynologySharing.CredentialId(Profile.Id),
                current.User, passwordBox.Text);
        SynologyShareStore.Save(Profile.Id, current);
        passwordBox.Clear();
    }

    private SynologySharingClient Client()
    {
        SaveSettings();
        var settings = SynologyShareStore.Read(Profile.Id)!;
        string password = WindowsCredentials.Read(SynologySharing.CredentialId(Profile.Id)) ?? "";
        return new SynologySharingClient(settings, password, otpBox.Text);
    }

    private string ResolvePath()
    {
        string root = SynologyShareStore.Read(Profile.Id)?.ShareRoot ?? rootBox.Text;
        if (explorerPath is not null)
            return SynologySharing.MapExplorerPath(Profile, root, explorerPath);
        return SynologySharing.MapRelativePath(root, relativeBox.Text);
    }

    private async Task RunAsync(Func<SynologySharingClient, Task> operation)
    {
        if (busy) return;
        busy = true;
        UseWaitCursor = true;
        try
        {
            var client = Client();
            await operation(client);
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
            busy = false;
        }
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
