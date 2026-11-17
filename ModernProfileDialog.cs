using System.Drawing;
using System.Drawing.Drawing2D;
using System.ComponentModel;
using System.Windows.Forms;

namespace NASMount;

/// <summary>
/// Dense, no-scroll connection editor with a fixed 29px field rhythm,
/// a fixed 24px input height and a separate left protocol rail.
/// </summary>
internal sealed class ModernProfileDialog : Form
{
    private readonly TextBox nameBox = new();
    private readonly TextBox hostBox = new();
    private readonly TextBox pathBox = new();
    private readonly TextBox userBox = new();
    private readonly TextBox passwordBox = new() { UseSystemPasswordChar = true };
    private readonly TextBox portBox = new();
    private readonly TextBox fingerprintBox = new();
    private readonly ComboBox ftpCharset = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox ftpSecurity = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox ftpTlsVersion = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly CompactCheck[] modes = new CompactCheck[3];
    private readonly CompactCheck autoBox = new();
    private readonly CompactCheck rememberBox = new();
    private readonly CompactCheck anonymousBox = new();
    private readonly CompactCheck webDavHttpBox = new();
    private readonly CompactCheck nativeDavBox = new();
    private readonly CompactCheck cloudNetworkBox = new();
    private readonly CompactCheck ftpPasv = new();
    private readonly CompactCheck ftpExplicit = new();
    private readonly CompactCheck ftpModeZ = new();
    private readonly Label ftpCharsetLabel;
    private readonly Label ftpTlsLabel;
    private readonly Label ftpVersionLabel;
    private readonly Label sftpLabel;
    private readonly Label smbNote;
    private readonly Label advancedHeading;
    private readonly Label googleInfo;
    private readonly Label googleAccount;
    private readonly Button teraCookieHelp = new();
    private readonly Button teraAutoImport = new();
    private const string TeraCookieInstructions =
        "1. Chrome에서 https://www.terabox.com 에 로그인합니다.\n" +
        "2. F12 → Application(애플리케이션) 탭을 엽니다.\n" +
        "   (탭이 없으면 상단 >> 메뉴에서 찾으세요.)\n" +
        "3. Storage → Cookies → www.terabox.com을 선택합니다.\n" +
        "4. 목록에서 ndus를 찾아 Value(값)만 복사합니다.\n" +
        "5. FileHub AIO의 [쿠키] 입력칸에 붙여넣고 저장하세요.\n\n" +
        "ndus는 비밀번호처럼 중요한 로그인 정보입니다.\n" +
        "다른 사람에게 전송하거나 공개하지 마세요.";
    private const string MyBoxPatInstructions =
        "네이버 MYBOX 개인 액세스 토큰(PAT) 발급 방법\n\n" +
        "1. 네이버 MYBOX에 로그인합니다.\n" +
        "2. 화면 하단의 [설정]을 클릭합니다.\n" +
        "3. [계정 및 개인 액세스 토큰 관리]로 이동합니다.\n" +
        "4. [토큰 생성]을 눌러 새 토큰을 발급합니다.\n" +
        "5. 생성된 토큰을 복사하여 FileHub AIO의 [PAT] 입력칸에 붙여넣습니다.\n\n" +
        "PAT는 비밀번호처럼 중요한 정보입니다. 다른 사람과 공유하지 마세요.";
    private readonly string[] protocols = ["WebDAV", "FTP", "SFTP", "SMB", "GoogleDrive", "OneDrive", "TeraBox", "MyBox"];
    private readonly ProtocolTile[] tiles = new ProtocolTile[8];
    private readonly Panel protocolSidebar = new();
    private readonly PictureBox heroIcon = new();
    private readonly Label heroTitle;
    private readonly Label heroSubtitle;
    private readonly Label heroBadge;
    private readonly Label infoTitle;
    private readonly Label infoText;
    private readonly List<(Label caption, Control input)> fieldPairs = [];
    private readonly Dictionary<Control, Control> framedInputs = [];
    private readonly ToolTip tip = new() { ShowAlways = true };
    private readonly bool existing;
    // Protocol tabs are independent DRAFTS, not four connection profiles.
    // Only the currently selected protocol is saved as ONE NasProfile.
    private sealed record ProtocolDraft(string Name, string Host, string Share,
        string Port, string User, string Password, bool Anonymous,
        bool Remember, bool Auto, int Mode, bool CloudNetwork);
    private readonly Dictionary<string, ProtocolDraft> protocolDrafts = [];
    private bool protocolDraftsEnabled;
    private bool switching;
    private int modeIndex;
    private string currentProtocol = "WebDAV";
    private string selectedFtpCharset = "UTF-8";
    private string selectedSftpCharset = "UTF-8";
    internal event Action<string>? ProtocolChanged;

    internal NasProfile Profile { get; }
    internal string Password => currentProtocol is "TeraBox" or "MyBox" ? passwordBox.Text : anonymousBox.Checked ? "" : passwordBox.Text;
    internal bool PasswordChanged => currentProtocol is "TeraBox" or "MyBox" ? (!existing || passwordBox.TextLength > 0) : anonymousBox.Checked || !existing || passwordBox.TextLength > 0;
    internal bool AnonymousSelected => anonymousBox.Checked;
    internal bool CredentialsDisabled => !userBox.Enabled && !passwordBox.Enabled;
    internal void SetAnonymousForTest(bool enabled) => anonymousBox.Checked = enabled;
    internal int CharsetOptionCount => ftpCharset.Items.Count;
    internal bool CharsetControlVisible => ftpCharset.Visible;
    internal bool ProtocolRowAligned =>
        protocolSidebar.Left == 0 && protocolSidebar.Right == 184 &&
        tiles.All(x => x.Parent == protocolSidebar && x.Left == 9 &&
            x.Width == 166 && x.Height == 45) &&
        tiles.Select((x, i) => x.Top == 55 + i * 52).All(x => x);
    internal int SidebarIconCount => tiles.Count(x => x.Image is not null);
    internal int SelectedMode => modeIndex;
    internal int CheckedModeCount => modes.Count(x => x.Checked);
    internal bool OptionsScrollDisabled => !AutoScroll;
    internal bool HttpTransportSelected => webDavHttpBox.Checked;
    internal string SelectedProtocol => currentProtocol;
    internal string HostText => hostBox.Text;
    internal string NameText => nameBox.Text;
    internal void SetHostTextForTest(string value) => hostBox.Text = value;
    internal void SetNameTextForTest(string value) => nameBox.Text = value;
    internal string PortText => portBox.Text;
    internal bool PortHasSpinner => false; // The port is a plain TextBox.
    internal bool FtpSettingsVisible => ftpCharset.Visible && ftpPasv.Visible && ftpSecurity.Visible;
    internal bool SftpSettingsVisible => fingerprintBox.Visible;
    internal bool WebDavSettingsVisible => webDavHttpBox.Visible;
    internal bool ModeZEnabled => ftpModeZ.Enabled;
    internal bool TeraCookieHelpVisible =>
        currentProtocol == "TeraBox" && teraCookieHelp.Visible;
    internal bool TeraAutoImportVisible =>
        currentProtocol == "TeraBox" && teraAutoImport.Visible;
    internal bool TeraAutoImportAligned =>
        framedInputs.TryGetValue(passwordBox, out var frame) &&
        teraAutoImport.Left >= frame.Right + 4 &&
        teraAutoImport.Right <= teraCookieHelp.Left - 4 &&
        Math.Abs(teraAutoImport.Top - frame.Top) <= 2 &&
        !teraAutoImport.Bounds.IntersectsWith(frame.Bounds);
    internal static string TeraAutoImportLabel => "자동 불러오기";
    internal bool MyBoxPatHelpVisible =>
        currentProtocol == "MyBox" && teraCookieHelp.Visible;
    internal bool MyBoxPatHelpAligned => MyBoxPatHelpVisible && TeraCookieHelpAligned;
    internal static string MyBoxPatHelpText => MyBoxPatInstructions;
    internal bool MyBoxPatFieldVisible =>
        currentProtocol == "MyBox" &&
        framedInputs.TryGetValue(passwordBox, out var frame) &&
        frame.Visible && passwordBox.Visible &&
        !googleAccount.Bounds.IntersectsWith(frame.Bounds);
    internal bool CloudNetworkOptionVisible => cloudNetworkBox.Visible;
    internal bool CloudNetworkSelected => cloudNetworkBox.Checked;
    internal void SetCloudNetworkForTest(bool selected) =>
        cloudNetworkBox.Checked = selected;
    internal bool TeraCookieHelpAligned =>
        framedInputs.TryGetValue(passwordBox, out var frame) &&
        !teraCookieHelp.Bounds.IntersectsWith(frame.Bounds) &&
        teraCookieHelp.Left >= frame.Right &&
        Math.Abs(teraCookieHelp.Top - frame.Top) <= 2 &&
        teraCookieHelp.Right < ClientSize.Width;
    internal static string TeraCookieHelpText => TeraCookieInstructions;

    internal ModernProfileDialog(NasProfile profile, bool isExisting)
    {
        Profile = profile;
        existing = isExisting;
        Text = isExisting ? "NAS 연결 설정" : "새 NAS 연결";
        ClientSize = new Size(1066, 680);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.CenterParent;
        AutoScaleMode = AutoScaleMode.Dpi;
        AutoScroll = false;
        Font = new Font("Malgun Gothic", 9f);
        BackColor = Color.FromArgb(33, 36, 41);
        ForeColor = ModernTheme.Text;
        ModernTheme.EnableDarkTitleBar(this);
        DoubleBuffered = true;
        heroIcon.SetBounds(28, 24, 64, 64);
        heroIcon.SizeMode = PictureBoxSizeMode.Zoom;
        heroIcon.BackColor = Color.Transparent;
        Controls.Add(heroIcon);
        heroTitle = Caption("연결 서비스", 106, 16, 490, 37, 18, Color.White, bold: true);
        heroSubtitle = Caption("NAS · 클라우드 드라이브", 108, 54, 600, 22,
            9.4f, Color.FromArgb(190, 207, 225));
        heroBadge = Caption("", 108, 79, 360, 22,
            8.2f, Color.FromArgb(122, 206, 250), bold: true);
        // Dedicated LEFT menu, right-hand connection details. No decorative
        // frames/rules; this entire screen is an in-window sliding editor.
        protocolSidebar.SetBounds(0, 0, 266, ClientSize.Height);
        protocolSidebar.BackColor = Color.FromArgb(39, 43, 49);
        Controls.Add(protocolSidebar);
        var railHeading = ModernTheme.Label("연결 종류", 13f, true);
        railHeading.SetBounds(19, 23, 228, 31);
        protocolSidebar.Controls.Add(railHeading);
        var railHint = ModernTheme.Label("연결할 서비스를 선택하세요", 8.9f, false,
            Color.FromArgb(157, 172, 189));
        railHint.SetBounds(19, 55, 228, 23);
        protocolSidebar.Controls.Add(railHint);
        for (int i = 0; i < protocols.Length; i++)
        {
            var value = protocols[i];
            var tile = new ProtocolTile(value switch {
                "GoogleDrive" => "Google Drive",
                "MyBox" => "MYBOX",
                _ => value
            });
            tile.Image = ProtocolIconFactory.Create(value);
            tile.SetBounds(14, 92 + i * 60, 236, 54);
            tile.Click += (_, _) => SelectProtocol(value);
            tiles[i] = tile;
            protocolSidebar.Controls.Add(tile);
        }

        Rule(106);
        Caption("연결 정보", 23, 109, 600, 24, 10, bold: true);
        nameBox.Text = profile.DisplayName;
        hostBox.Text = profile.Host;
        pathBox.Text = profile.Share;
        portBox.Text = profile.Port == 0 ? "" : profile.Port.ToString();
        // Section headings begin at X=23. Every subordinate label starts
        // at X=42 (19px indent) and each input starts on the X=132 grid.
        Field("연결 이름", nameBox, 42, 139, 83, 132, 221);
        Field("NAS 주소", hostBox, 42, 170, 83, 132, 399);
        Field("포트", portBox, 543, 170, 35, 583, 79);
        Field("공유 경로", pathBox, 42, 201, 83, 132, 530);
        googleInfo = Caption("Google 계정 인증으로 연결 · NAS용 PC 간 파일잠금은 지원하지 않습니다.",
            132, 171, 550, 28, 9f, ModernTheme.Muted);
        Check(webDavHttpBox, "HTTP 사용", 132, 231);
        webDavHttpBox.Checked = profile.WebDavUseHttp;
        Check(nativeDavBox, "서버 기본 잠금 (폴더 없음)", 132, 368);
        nativeDavBox.Checked = profile.UseNativeWebDavLocks;
        tip.SetToolTip(nativeDavBox,
            "WebDAV LOCK/UNLOCK으로 파일 옆에 .nasmount.lock 폴더를 만들지 않습니다. " +
            "기존 버전의 FileHub AIO와 같은 문서를 동시에 편집하지 마세요.");
        tip.SetToolTip(webDavHttpBox, "HTTP WebDAV는 암호와 파일 내용이 암호화되지 않을 수 있습니다. HTTPS 권장");

        Rule(261);
        Caption("계정", 23, 263, 600, 25, 10, bold: true);
        userBox.Text = profile.Username;
        passwordBox.PlaceholderText = existing ? "변경 시에만 입력" : "암호 입력";
        // The full five-syllable Korean label needs more room than other
        // captions at 125/150% DPI: do not shave off the last syllable.
        Field("사용자 이름", userBox, 42, 289, 83, 132, 218);
        Field("암호", passwordBox, 367, 289, 35, 408, 224);
        teraCookieHelp.Text = "?";
        teraCookieHelp.AccessibleName = "테라박스 ndus 쿠키 확인 방법";
        teraCookieHelp.SetBounds(640, 291, 26, 24);
        teraCookieHelp.FlatStyle = FlatStyle.Flat;
        teraCookieHelp.FlatAppearance.BorderColor = ModernTheme.Border;
        teraCookieHelp.FlatAppearance.BorderSize = 1;
        teraCookieHelp.BackColor = ModernTheme.Field;
        teraCookieHelp.ForeColor = ModernTheme.Text;
        teraCookieHelp.Font = new Font("Malgun Gothic", 9, FontStyle.Bold);
        teraCookieHelp.Cursor = Cursors.Hand;
        teraCookieHelp.Visible = false;
        teraCookieHelp.Click += (_, _) =>
        {
            bool mybox = currentProtocol == "MyBox";
            MessageBox.Show(this, mybox ? MyBoxPatInstructions : TeraCookieInstructions,
                mybox ? "네이버 MYBOX PAT 발급 방법" : "테라박스 ndus 쿠키 확인 방법",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
        };
        Controls.Add(teraCookieHelp);
        tip.SetToolTip(teraCookieHelp, "ndus 쿠키 확인 방법 보기");
        teraAutoImport.Text = TeraAutoImportLabel;
        teraAutoImport.AccessibleName = "TeraBox 웹 로그인으로 ndus 자동 불러오기";
        teraAutoImport.FlatStyle = FlatStyle.Flat;
        teraAutoImport.FlatAppearance.BorderColor = ModernTheme.Border;
        teraAutoImport.FlatAppearance.BorderSize = 1;
        teraAutoImport.BackColor = ModernTheme.Field;
        teraAutoImport.ForeColor = ModernTheme.Text;
        teraAutoImport.Font = new Font("Malgun Gothic", 8, FontStyle.Bold);
        teraAutoImport.Cursor = Cursors.Hand;
        teraAutoImport.Visible = false;
        teraAutoImport.Click += async (_, _) => await ImportTeraBoxCookieAsync();
        Controls.Add(teraAutoImport);
        tip.SetToolTip(teraAutoImport,
            "공식 TeraBox 웹페이지 로그인 후 ndus를 자동으로 가져옵니다. FileHub AIO는 계정 암호를 저장하지 않습니다.");
        Check(anonymousBox, "익명", 647, 290);
        anonymousBox.Checked = profile.IsAnonymous;
        googleAccount = Caption("Google 계정 인증은 저장 후 [연결]에서 진행됩니다.",
            132, 291, 525, 25, 9f, ModernTheme.Muted);
        anonymousBox.CheckedChanged += (_, _) => UpdateAnonymousUi();
        tip.SetToolTip(anonymousBox, "로그인 정보 없이 접근 가능한 서버에서만 연결됩니다. FTP는 anonymous, SMB는 Guest, SFTP는 서버의 무암호 인증이 필요합니다.");
        Check(autoBox, "자동연결", 132, 320);
        Check(rememberBox, "암호저장", 408, 320);
        autoBox.Checked = profile.AutoConnect;
        rememberBox.Checked = profile.RememberPassword;
        UpdateAnonymousUi();

        Rule(351);
        advancedHeading = Caption("고급 설정", 23, 354, 600, 24, 10, bold: true);
        string[] labels = ["파일잠금", "읽기전용", "EXCEL 속도 최적화(읽기전용)"];
        // Keep the mode toggles tightly adjacent, RaiDrive-style, with only
        // a small gap beyond each label's measured width.
        int[] xx = [132, 220, 316];
        for (int i = 0; i < labels.Length; i++)
        {
            int selected = i;
            var mode = new CompactCheck();
            Check(mode, labels[i], xx[i], 388);
            mode.CheckedChanged += (_, _) =>
            {
                if (switching) return;
                if (mode.Checked || selected == modeIndex) SelectMode(selected);
            };
            modes[i] = mode;
        }

        ftpCharsetLabel = Caption("문자셋", 42, 420, 83, 26, 9f);
        ftpCharset.Items.AddRange(CharsetSupport.Names);
        ftpCharset.MaxDropDownItems = 14;
        ftpCharset.DropDownWidth = 398;
        selectedFtpCharset = profile.FtpDisableUtf8 ? "서버 기본" : profile.FtpCharset;
        selectedSftpCharset = profile.SftpCharset;
        if (!CharsetSupport.IsSupported("FTP", selectedFtpCharset)) selectedFtpCharset = "UTF-8";
        if (!CharsetSupport.IsSupported("SFTP", selectedSftpCharset)) selectedSftpCharset = "UTF-8";
        ftpCharset.SelectedItem = selectedFtpCharset;
        ftpCharset.SelectedIndexChanged += (_, _) => CharsetChanged();
        FieldControl(ftpCharset, 132, 421, 159);
        tip.SetToolTip(ftpCharset,
            "UTF-8 연결은 기존 rclone 엔진을 사용합니다. CP949/EUC-KR, Shift-JIS 등 " +
            "레거시 문자셋을 선택하면 전용 FTP/SFTP 변환 게이트웨이가 실제 파일명에 적용됩니다. " +
            "변환할 수 없는 문자는 ?로 대체하지 않고 오류로 차단합니다. " +
            "특수 문자셋은 해당 서버의 설정과 일치해야 합니다.");

        Check(ftpPasv, "PASV 우선", 306, 422);
        ftpPasv.Checked = profile.FtpPreferPasv;
        tip.SetToolTip(ftpPasv, "선택: EPSV를 사용하지 않고 PASV를 우선합니다. 미선택: rclone 기본 EPSV/PASV 동작");
        Check(ftpExplicit, "Explicit", 418, 422);
        Check(ftpModeZ, "Mode Z", 524, 422);
        ftpModeZ.Enabled = false;
        tip.SetToolTip(ftpModeZ, "현재 rclone FTP 엔진은 MODE Z 압축을 지원하지 않습니다.");

        ftpTlsLabel = Caption("TLS/SSL", 42, 451, 83, 26, 9f);
        ftpSecurity.Items.AddRange(["사용 안 함", "Explicit FTPS", "Implicit FTPS"]);
        ftpSecurity.SelectedIndex = profile.FtpImplicitTls ? 2 : profile.ExplicitTls ? 1 : 0;
        FieldControl(ftpSecurity, 132, 452, 159);
        ftpVersionLabel = Caption("TLS 버전", 312, 451, 70, 26, 9f);
        ftpTlsVersion.Items.AddRange(["자동", "TLS 1.2 우선"]);
        ftpTlsVersion.SelectedIndex = profile.FtpTls12Only ? 1 : 0;
        FieldControl(ftpTlsVersion, 393, 452, 161);
        tip.SetToolTip(ftpTlsVersion, "TLS 1.2 우선: TLS 1.3을 제외합니다. TLS 1.2를 완전히 고정하는 기능은 아닙니다.");
        ftpExplicit.Checked = ftpSecurity.SelectedIndex == 1;
        ftpExplicit.CheckedChanged += (_, _) =>
        {
            if (switching || currentProtocol != "FTP") return;
            switching = true;
            ftpSecurity.SelectedIndex = ftpExplicit.Checked ? 1 : 0;
            switching = false;
        };
        ftpSecurity.SelectedIndexChanged += (_, _) =>
        {
            switching = true;
            ftpExplicit.Checked = ftpSecurity.SelectedIndex == 1;
            switching = false;
        };

        sftpLabel = Caption("SSH 키 지문", 42, 451, 83, 26, 9f);
        fingerprintBox.Text = profile.SftpHostFingerprint;
        fingerprintBox.PlaceholderText = "최초 [연결] 시 서버 키 확인 및 자동 등록";
        FieldControl(fingerprintBox, 132, 452, 530);
        tip.SetToolTip(fingerprintBox,
            "최초 SFTP 연결 시 SSH 서버의 SHA256 지문을 확인·승인할 수 있습니다. " +
            "서버 관리자가 알려준 SHA256 지문을 여기에 입력하면 반드시 일치해야 연결됩니다. " +
            "기존 지문을 지우고 저장하면 다음 수동 연결 때 다시 승인할 수 있습니다.");
        smbNote = Caption("SMB 파일 접근 권한은 NAS 공유 폴더의 권한 설정을 따릅니다.",
            132, 422, 530, 26, 9f, ModernTheme.Muted);
        Check(cloudNetworkBox, "네트워크 위치에 표시", 132, 420);
        cloudNetworkBox.Checked = !profile.UseFixedDriveMode;
        tip.SetToolTip(cloudNetworkBox,
            "켜짐: RaiDrive와 같이 '내 PC > 네트워크 위치'에 연결합니다. " +
            "연결/해제 완료 시 Windows 드라이브 추가/제거 알림을 전송합니다.\n" +
            "꺼짐: WinFsp 고정 드라이브로 표시합니다. 클라우드 데이터가 " +
            "로컬 디스크에 모두 다운로드되는 것은 아닙니다.");

        Rule(481);
        var cancel = ModernTheme.Button("취소", 96,
            () => { DialogResult = DialogResult.Cancel; Close(); }, height: 32);
        cancel.SetBounds(443, 551, 96, 36);
        Controls.Add(cancel);
        var close = ModernTheme.Button("닫기", 96,
            () => { DialogResult = DialogResult.Cancel; Close(); }, height: 32);
        close.SetBounds(545, 551, 96, 36);
        Controls.Add(close);
        var save = ModernTheme.Button("저장", 116, SaveProfile, accent: true, height: 36);
        save.SetBounds(627, 623, 116, 36);
        Controls.Add(save);
        cancel.SetBounds(418, 623, 96, 36);
        close.SetBounds(520, 623, 96, 36);
        infoTitle = Caption("안전한 파일 연결", 77, 524, 675, 27,
            10.5f, Color.FromArgb(158, 222, 255), bold: true);
        infoText = Caption("연결 종류와 서버 권한에 따라 지원 기능이 달라집니다.",
            77, 552, 685, 39, 8.9f, Color.FromArgb(205, 221, 236));
        AcceptButton = save;
        CancelButton = cancel;

        modeIndex = profile.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox" ?
            (!existing && profile.Protocol is ("TeraBox" or "MyBox") ||
             profile.AllowUnprotectedWrites ? 0 : 1) :
            profile.OfficeReadTuning && profile.Protocol == "WebDAV" ? 2 :
            profile.UseLockBridge || profile.AllowUnprotectedWrites ? 0 : 1;
        SelectProtocol(protocols.Contains(profile.Protocol) ? profile.Protocol : "WebDAV");
        SelectMode(modeIndex);
        CompactEditorLayout();
        protocolDraftsEnabled = true;
    }

    private const double EditorScaleX = 0.76;
    private const double EditorScaleY = 0.72;

    private void CompactEditorLayout()
    {
        // Reflow the legacy editor once, preserving the original field
        // controls, validation, protocol switching and keyboard shortcuts.
        // All cloud captions use already-translated coordinates afterwards.
        const int railWidth = 184;
        SuspendLayout();
        try
        {
            foreach (Control item in Controls.Cast<Control>()
                         .Where(c => !ReferenceEquals(c, protocolSidebar)).ToArray())
            {
                var old = item.Bounds;
                int x = railWidth + (int)Math.Round(old.Left * EditorScaleX);
                int y = (int)Math.Round(old.Top * EditorScaleY);
                int w = (int)Math.Round(old.Width * EditorScaleX);
                int h = (int)Math.Round(old.Height * EditorScaleY);
                if (item is Label)
                {
                    h = Math.Max(20, h);
                    item.Font = new Font("Malgun Gothic",
                        Math.Max(7.4f, item.Font.Size * .83f), item.Font.Style);
                }
                else if (item is CompactCheck check)
                {
                    check.Font = new Font("Malgun Gothic", 8.0f);
                    w = TextRenderer.MeasureText(check.Text, check.Font,
                        new Size(1000, 100), TextFormatFlags.SingleLine |
                        TextFormatFlags.NoPadding).Width + 25;
                    h = 21;
                }
                else if (item is CenteredTextField) h = 23;
                else if (item is ComboBox) h = 23;
                else if (item is PictureBox) { w = 46; h = 46; }
                else if (item is Button) { h = Math.Max(27, h); }
                item.SetBounds(x, y, w, h);
            }

            ClientSize = new Size(744, 500);
            protocolSidebar.SetBounds(0, 0, railWidth, ClientSize.Height);
            var heading = protocolSidebar.Controls.OfType<Label>()
                .FirstOrDefault(x => x.Text == "연결 종류");
            var hint = protocolSidebar.Controls.OfType<Label>()
                .FirstOrDefault(x => x.Text == "연결할 서비스를 선택하세요");
            heading?.SetBounds(14, 9, 154, 25);
            if (heading is not null) heading.Font = new Font("Malgun Gothic", 10.3f, FontStyle.Bold);
            hint?.SetBounds(14, 33, 154, 18);
            if (hint is not null) hint.Font = new Font("Malgun Gothic", 7.5f);
            for (int i = 0; i < tiles.Length; i++)
            {
                tiles[i].SetBounds(9, 55 + i * 52, 166, 45);
                tiles[i].Font = new Font("Malgun Gothic", 8.8f, FontStyle.Bold);
            }
            heroBadge.Visible = false; // eliminate decorative English caption
            googleInfo.Width = 510;
            googleAccount.Width = 510;
            googleInfo.Font = new Font("Malgun Gothic", 7.8f);
            googleAccount.Font = new Font("Malgun Gothic", 7.8f);
            infoTitle.SetBounds(264, 376, 500, 21);
            infoText.SetBounds(264, 397, 510, 37);
            infoText.Font = new Font("Malgun Gothic", 7.8f);
            // Ensure each label ends before its corresponding editor frame.
            foreach (var (label, input) in fieldPairs)
            {
                int available = input.Left - label.Left - 3;
                var measured = TextRenderer.MeasureText(label.Text, label.Font,
                    new Size(1000, 100), TextFormatFlags.SingleLine |
                    TextFormatFlags.NoPadding).Width + 2;
                if (available > 0) label.Width = Math.Min(available, Math.Max(label.Width, measured));
                if (framedInputs.TryGetValue(passwordBox, out var secret) &&
                    ReferenceEquals(input, secret))
                {
                    label.Left -= 15;
                    label.Width = secret.Left - label.Left - 4;
                }
                else if (framedInputs.TryGetValue(userBox, out var username) &&
                    ReferenceEquals(input, username))
                {
                    label.Left -= 8;
                    label.Width = username.Left - label.Left - 4;
                }
                else if (framedInputs.TryGetValue(portBox, out var port) &&
                    ReferenceEquals(input, port))
                {
                    label.Left -= 8;
                    label.Width = port.Left - label.Left - 4;
                }
            }
            modes[0].Left = 284;
            modes[1].Left = 393;
            modes[2].Left = 495;
            // Spread FTP advanced settings without leaving the frame.
            ftpPasv.Left = 415;
            ftpExplicit.Left = 526;
            ftpModeZ.Left = 632;
            if (framedInputs.TryGetValue(passwordBox, out var secretFrame))
                teraCookieHelp.SetBounds(secretFrame.Right + 6, secretFrame.Top, 23, 23);
            var buttons = Controls.OfType<Button>().Where(x => x.Text is
                "취소" or "닫기" or "저장").ToArray();
            foreach (var button in buttons)
            {
                int x = button.Text switch { "취소" => 542, "닫기" => 617, _ => 698 };
                int w = button.Text == "저장" ? 102 : 69;
                button.SetBounds(x, 457, w, 28);
                button.Font = new Font("Malgun Gothic", 8.2f,
                    button.Text == "저장" ? FontStyle.Bold : FontStyle.Regular);
            }
            // The inherited editor was scaled separately on X and Y.
            // Override the resulting uneven spacing with a single, explicit
            // pixel grid for every protocol rather than scaling each field.
            ApplyUniformEditorGrid();
        }
        finally { ResumeLayout(true); }
    }

    private void ApplyUniformEditorGrid()
    {
        const int left = 216, inputX = 305, right = 726, inputHeight = 24;
        static void Place(Control c, int x, int y, int w, int h) =>
            c.SetBounds(x, y, w, h);

        // A compact RaiDrive-style hierarchy: 40px service mark, 29px rows,
        // restrained section labels and no nested panels or thick dividers.
        Place(heroIcon, 209, 14, 42, 42);
        Place(heroTitle, 261, 11, 460, 28);
        heroTitle.Font = new Font("Malgun Gothic", 14.2f, FontStyle.Bold);
        Place(heroSubtitle, 261, 40, 460, 23);
        heroSubtitle.Font = new Font("Malgun Gothic", 8.4f);
        heroBadge.Visible = false;

        foreach (var item in Controls.OfType<Label>())
        {
            if (item.Text is "연결 정보" or "계정" or "고급 설정")
            {
                int y = item.Text switch
                { "연결 정보" => 77, "계정" => 213, _ => 306 };
                Place(item, left, y, 540, 23);
                item.Font = new Font("Malgun Gothic", 9.4f, FontStyle.Bold);
                item.ForeColor = Color.FromArgb(224, 231, 239);
            }
        }

        void Row(TextBox editor, int y, int x, int width, int labelX, int labelW)
        {
            var frame = framedInputs[editor];
            Place(frame, x, y, width, inputHeight);
            var caption = ReferenceEquals(editor, fingerprintBox)
                ? sftpLabel
                : fieldPairs.First(p => ReferenceEquals(p.input, frame)).caption;
            Place(caption, labelX, y, labelW, inputHeight);
            caption.Font = new Font("Malgun Gothic", 8.4f);
        }

        Row(nameBox, 105, inputX, right - inputX, left, 82);
        Row(hostBox, 134, inputX, 292, left, 82);
        Row(portBox, 134, 651, 75, 607, 36);
        Row(pathBox, 163, inputX, right - inputX, left, 82);
        Row(userBox, 242, inputX, 158, left, 82);
        Row(passwordBox, 242, 518, 132, 473, 40);
        Row(fingerprintBox, 368, inputX, right - inputX, left, 82);

        void Pick(Control c, int x, int y, int w)
        {
            Place(c, x, y, w, inputHeight);
            c.Font = new Font("Malgun Gothic", 8.4f);
        }
        Pick(ftpCharset, inputX, 368, 168);
        Pick(ftpSecurity, inputX, 397, 168);
        Pick(ftpTlsVersion, 566, 397, 160);
        Place(ftpCharsetLabel, left, 368, 82, inputHeight);
        Place(ftpTlsLabel, left, 397, 82, inputHeight);
        Place(ftpVersionLabel, 487, 397, 76, inputHeight);
        Place(sftpLabel, left, 368, 82, inputHeight);
        Place(smbNote, inputX, 368, 415, 22);

        void Toggle(CompactCheck check, int x, int y)
        {
            check.Font = new Font("Malgun Gothic", 8.0f);
            int width = TextRenderer.MeasureText(check.Text, check.Font,
                new Size(1000, 100), TextFormatFlags.SingleLine |
                TextFormatFlags.NoPadding).Width + 27;
            Place(check, x, y, width, 21);
        }
        Toggle(webDavHttpBox, inputX, 192);
        Toggle(anonymousBox, 663, 244);
        Toggle(autoBox, inputX, 274);
        Toggle(rememberBox, 477, 274);
        Toggle(modes[0], inputX, 337);
        Toggle(modes[1], 422, 337);
        Toggle(modes[2], 531, 337);
        Toggle(ftpPasv, 440, 369);
        Toggle(ftpExplicit, 549, 369);
        Toggle(ftpModeZ, 646, 369);
        Toggle(cloudNetworkBox, inputX, 368);
        Toggle(nativeDavBox, inputX, 368);

        Place(googleInfo, inputX, 134, 418, 23);
        Place(googleAccount, inputX, 163, 418, 23);
        googleInfo.Font = googleAccount.Font = new Font("Malgun Gothic", 8.0f);

        Place(infoTitle, left, 423, 500, 18);
        infoTitle.Font = new Font("Malgun Gothic", 8.5f, FontStyle.Bold);
        Place(infoText, left, 441, 502, 21);
        infoText.Font = new Font("Malgun Gothic", 7.7f);
        foreach (var button in Controls.OfType<Button>()
                     .Where(x => x.Text is "취소" or "닫기" or "저장"))
        {
            int x = button.Text switch
            { "취소" => 474, "닫기" => 559, _ => 644 };
            Place(button, x, 466, 81, 27);
            button.Font = new Font("Malgun Gothic", 8.2f,
                button.Text == "저장" ? FontStyle.Bold : FontStyle.Regular);
        }
        Place(teraCookieHelp, 663, 242, 23, inputHeight);
        Place(teraAutoImport, 554, 242, 99, inputHeight);
        UpdateProtocolFieldGrid();
    }

    private void UpdateProtocolFieldGrid()
    {
        bool secretOnly = currentProtocol is "TeraBox" or "MyBox";
        if (framedInputs.TryGetValue(passwordBox, out var frame))
        {
            var caption = fieldPairs.First(p => ReferenceEquals(p.input, frame)).caption;
            if (secretOnly)
            {
                frame.SetBounds(305, 242,
                    currentProtocol == "TeraBox" ? 241 : 345, 24);
                caption.SetBounds(216, 242, 82, 24);
            }
            else
            {
                frame.SetBounds(518, 242, 132, 24);
                caption.SetBounds(473, 242, 40, 24);
            }
        }
        // Cloud captions stay on the same 29px grid as the hidden host/path
        // entries. Microsoft/Google account help occupies the account row.
        googleInfo.SetBounds(305, 134, 418, 23);
        googleAccount.SetBounds(305,
            currentProtocol is "GoogleDrive" or "OneDrive" ? 163 : 192,
            418, 23);
        if (framedInputs.TryGetValue(passwordBox, out var secret))
        {
            teraCookieHelp.SetBounds(
                currentProtocol == "TeraBox" ? 663 : secret.Right + 12,
                secret.Top, 23, 24);
            teraAutoImport.SetBounds(554, secret.Top, 99, 24);
        }
        foreach (int i in Enumerable.Range(0, modes.Length))
        {
            var check = modes[i];
            check.Width = TextRenderer.MeasureText(check.Text, check.Font,
                new Size(1000, 100), TextFormatFlags.SingleLine |
                TextFormatFlags.NoPadding).Width + 27;
        }
    }

    private async Task ImportTeraBoxCookieAsync()
    {
        if (currentProtocol != "TeraBox" || !teraAutoImport.Enabled)
            return;
        teraAutoImport.Enabled = false;
        try
        {
            using var login = new TeraBoxWebLogin();
            // The editor is non-top-level inside the main window. Use the
            // real top-level window as the modal owner.
            Form? owner = Application.OpenForms.Cast<Form>()
                .FirstOrDefault(x => x.TopLevel && x.Visible &&
                    !ReferenceEquals(x, login));
            if (login.ShowDialog(owner) != DialogResult.OK ||
                !TeraBoxBackend.HasCookie(login.ImportedCookie))
                return;
            if (currentProtocol != "TeraBox" || IsDisposed) return;
            passwordBox.Text = login.ImportedCookie!;
            rememberBox.Checked = true;
            tip.SetToolTip(teraAutoImport,
                "ndus 불러오기 완료. [저장] 시 Windows 자격 증명 저장소에 저장합니다.");
            MessageBox.Show(this,
                "TeraBox 로그인 쿠키(ndus)를 자동으로 가져왔습니다.\n\n" +
                "계정 비밀번호는 FileHub AIO에 저장되지 않습니다.\n" +
                "[저장]을 누른 뒤 연결을 확인하세요.",
                "TeraBox 쿠키 가져오기", MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            AppStore.Log("TERABOX_COOKIE_IMPORT_FAILURE " + ex.GetType().Name);
            MessageBox.Show(this,
                "로그인 쿠키를 가져오지 못했습니다.\n" +
                "로그인 창과 WebView2 설치 상태를 확인하세요.\n\n" +
                ex.Message, "TeraBox 쿠키 가져오기",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally { if (!IsDisposed) teraAutoImport.Enabled = true; }
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        e.Graphics.Clear(BackColor);
    }

    private Label Caption(string text, int x, int y, int width, int height,
        float fontSize, Color? color = null, bool bold = false)
    {
        var label = new Label
        {
            Text = text, ForeColor = color ?? ModernTheme.Text,
            BackColor = Color.Transparent,
            Font = new Font("Malgun Gothic", fontSize, bold ? FontStyle.Bold : FontStyle.Regular),
            TextAlign = ContentAlignment.MiddleLeft,
            AutoSize = false, AutoEllipsis = false
        };
        label.SetBounds(x, y, width, height);
        Controls.Add(label);
        return label;
    }

    private void Rule(int y)
    {
        // Deliberately omit separators: section titles and whitespace give
        // clearer hierarchy without stacked lines or nested card borders.
    }

    private void Field(string label, Control input,
        int labelX, int y, int labelWidth, int fieldX, int fieldWidth)
    {
        var caption = Caption(label, labelX, y, labelWidth, 27, 9.1f, ModernTheme.Muted);
        var visualField = FieldControl(input, fieldX, y, fieldWidth);
        fieldPairs.Add((caption, visualField));
    }

    private Control FieldControl(Control input, int x, int y, int width)
    {
        input.ForeColor = ModernTheme.Text;
        input.BackColor = ModernTheme.Field;
        input.Font = new Font("Malgun Gothic", 9.1f);
        if (input is TextBox text)
        {
            // TextBox.AutoSize=false leaves its glyphs at the top of a tall
            // box. Keep the native single-line text height, then CENTER the
            // actual editor inside a compact outer border on every DPI.
            text.AutoSize = true;
            text.BorderStyle = BorderStyle.None;
            var frame = new CenteredTextField(text);
            frame.SetBounds(x, y + 2, width, 24);
            Controls.Add(frame);
            framedInputs[text] = frame;
            return frame;
        }
        if (input is ComboBox combo)
        {
            combo.FlatStyle = FlatStyle.Flat;
            combo.DrawMode = DrawMode.OwnerDrawFixed;
            combo.ItemHeight = 19;
            combo.DrawItem += (_, e) =>
            {
                if (e.Index < 0) return;
                using var background = new SolidBrush((e.State & DrawItemState.Selected) != 0
                    ? ModernTheme.Accent : ModernTheme.Field);
                e.Graphics.FillRectangle(background, e.Bounds);
                string label = combo.GetItemText(combo.Items[e.Index]);
                bool isSupported = combo != ftpCharset ||
                    CharsetSupport.IsSupported(currentProtocol, label);
                if (combo == ftpCharset && !isSupported && e.Bounds.Width > 280)
                    label += "  (변환 미지원)";
                TextRenderer.DrawText(e.Graphics, label,
                    combo.Font, Rectangle.Inflate(e.Bounds, -5, 0),
                    isSupported ? ModernTheme.Text : ModernTheme.Muted,
                    TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine |
                    TextFormatFlags.NoPadding);
            };
        }
        input.SetBounds(x, y + 2, width, 24);
        Controls.Add(input);
        return input;
    }

    private void Check(CompactCheck check, string text, int x, int y)
    {
        check.Text = text;
        check.ForeColor = ModernTheme.Text;
        check.BackColor = BackColor;
        check.Font = new Font("Malgun Gothic", 9f);
        check.SetBounds(x, y,
            Math.Min(ClientSize.Width - x - 10,
                TextRenderer.MeasureText(text, check.Font, new Size(1000, 100),
                    TextFormatFlags.SingleLine | TextFormatFlags.NoPadding).Width + 25), 23);
        Controls.Add(check);
    }

    internal void SelectProtocol(string protocol)
    {
        if (!protocols.Contains(protocol)) throw new ArgumentException("Unknown protocol", nameof(protocol));
        string previous = currentProtocol;
        currentProtocol = protocol;
        if (protocolDraftsEnabled && previous != protocol)
        {
            protocolDrafts[previous] = new ProtocolDraft(nameBox.Text, hostBox.Text,
                pathBox.Text, portBox.Text, userBox.Text, passwordBox.Text,
                anonymousBox.Checked, rememberBox.Checked, autoBox.Checked,
                modeIndex, cloudNetworkBox.Checked);
            if (protocolDrafts.TryGetValue(protocol, out var saved))
            {
                nameBox.Text = saved.Name;
                hostBox.Text = saved.Host;
                pathBox.Text = saved.Share;
                portBox.Text = saved.Port;
                userBox.Text = saved.User;
                passwordBox.Text = saved.Password;
                anonymousBox.Checked = saved.Anonymous;
                rememberBox.Checked = !saved.Anonymous && saved.Remember;
                autoBox.Checked = saved.Auto;
                cloudNetworkBox.Checked = saved.CloudNetwork;
                SelectMode(saved.Mode);
            }
            else
            {
                // No silent propagation of the WebDAV host/password into
                // FTP, SFTP, or SMB: user explicitly fills this new draft.
                nameBox.Text = protocol == "GoogleDrive" ? "Google Drive" :
                    protocol == "OneDrive" ? "OneDrive" :
                    protocol == "MyBox" ? "네이버 MYBOX" :
                    protocol == "TeraBox" ? "TeraBox" : "새 NAS";
                hostBox.Clear();
                pathBox.Clear();
                portBox.Text = protocol switch
                { "WebDAV" => "5006", "FTP" => "21", "SFTP" => "22", _ => "" };
                userBox.Clear();
                passwordBox.Clear();
                anonymousBox.Checked = false;
                rememberBox.Checked = protocol is "TeraBox" or "MyBox";
                autoBox.Checked = true;
                if (protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox")
                    cloudNetworkBox.Checked = true;
            }
            UpdateAnonymousUi();
        }
        if (previous == "FTP") selectedFtpCharset = ftpCharset.SelectedItem?.ToString() ?? "UTF-8";
        if (previous == "SFTP") selectedSftpCharset = ftpCharset.SelectedItem?.ToString() ?? "UTF-8";
        switching = true;
        try { ftpCharset.SelectedItem = protocol == "FTP" ? selectedFtpCharset : selectedSftpCharset; }
        finally { switching = false; }
        for (int i = 0; i < tiles.Length; i++) tiles[i].Selected = protocols[i] == protocol;
        heroIcon.Image?.Dispose();
        heroIcon.Image = ProtocolIconFactory.Create(protocol);
        heroTitle.Text = protocol switch {
            "MyBox" => "네이버 MYBOX",
            "GoogleDrive" => "Google Drive",
            "OneDrive" => "Microsoft OneDrive",
            "TeraBox" => "TeraBox",
            "SMB" => "Windows 공유 폴더",
            _ => protocol
        };
        heroSubtitle.Text = protocol switch {
            "MyBox" => "네이버 MYBOX Open API로 내 파일에 연결합니다",
            "GoogleDrive" => "Google 계정으로 클라우드 파일에 연결합니다",
            "OneDrive" => "Microsoft 계정의 개인·회사 드라이브 연결",
            "TeraBox" => "TeraBox 클라우드 저장소 연결",
            "SMB" => "사내 네트워크 파일 공유 연결",
            _ => "NAS 및 원격 서버를 Windows 드라이브로 연결합니다"
        };
        heroBadge.Text = (existing ? "CONNECTION SETTINGS" : "NEW CONNECTION") +
            "  /  NAS MOUNT";
        infoTitle.Text = protocol == "MyBox" ? "MYBOX 기능 안내" :
            protocol is "GoogleDrive" or "OneDrive" or "TeraBox" ?
            "클라우드 드라이브 안내" : "파일 접근 및 잠금 안내";
        infoText.Text = protocol == "MyBox" ?
            "업로드/수정 지원 · Excel 직접 편집과 PC 간 파일잠금 미검증" :
            protocol is "GoogleDrive" or "OneDrive" or "TeraBox" ?
            "클라우드 문서 잠금 미지원 · 중요 파일은 백업 후 편집" :
            "FileHub AIO 전용 잠금 · 저장 후 업로드 완료를 확인하세요.";
        if (!protocolDraftsEnabled && previous != protocol)
        {
            // Only auto-update a port that still holds a standard default.
            if (string.IsNullOrWhiteSpace(portBox.Text) ||
                portBox.Text == (previous switch { "SFTP" => "22", "FTP" => "21", "WebDAV" => "5006", _ => "0" }))
                portBox.Text = protocol switch { "WebDAV" => "5006", "SFTP" => "22", "FTP" => "21", _ => "" };
        }
        bool dav = protocol == "WebDAV", ftp = protocol == "FTP", sftp = protocol == "SFTP";
        bool google = protocol == "GoogleDrive";
        bool tera = protocol == "TeraBox";
        bool oneDrive = protocol == "OneDrive";
        bool mybox = protocol == "MyBox";
        bool cloud = google || tera || oneDrive || mybox;
        foreach (var field in new Control[] { hostBox, portBox, pathBox, userBox, passwordBox })
        {
            if (framedInputs.TryGetValue(field, out var frame))
            {
                bool fieldEnabled = !cloud ||
                    (tera || mybox) && ReferenceEquals(field, passwordBox);
                frame.Visible = fieldEnabled;
                foreach (var pair in fieldPairs)
                    if (ReferenceEquals(pair.input, frame))
                    {
                        // Control.Visible reflects the whole parent chain.
                        // During construction the Form is still hidden, so
                        // querying frame.Visible here incorrectly hides the
                        // labels on the very first open (embedded or modal).
                        pair.caption.Visible = fieldEnabled;
                        if (ReferenceEquals(field, passwordBox))
                            pair.caption.Text = tera ? "쿠키" : mybox ? "PAT" : "암호";
                    }
            }
        }
        googleInfo.Visible = cloud;
        googleInfo.Text = mybox ? "MYBOX · 업로드/수정 지원 · 파일잠금 미지원" :
            tera ? "TeraBox 비공식 연동 · ndus 쿠키 필요 · 파일잠금 미지원" :
            oneDrive ? "Microsoft 계정 연결 · PC 간 파일잠금 미지원" :
            "Google 계정 연결 · PC 간 파일잠금 미지원";
        googleAccount.Visible = cloud;
        googleAccount.Text = mybox ? "PAT 발급 방법은 입력칸 옆 [?] 버튼에서 확인하세요." :
            tera ? "[자동 불러오기]로 로그인하거나 ndus를 직접 입력하세요." :
            oneDrive ? "Microsoft 계정 인증은 저장 후 [연결]에서 진행됩니다." :
            "Google 계정 인증은 저장 후 [연결]에서 진행됩니다.";
        // MYBOX PAT and TeraBox ndus occupy the password row at y=289.
        // Their help text belongs on the available cloud-information row.
        // The entire editor was translated to the right of the protocol rail.
        // Previously SetBounds(132, ...) reverted this ONE caption to the old
        // x-coordinate on every cloud switch, hiding text behind the menu.
        // Anchor to the already-positioned cloud info label instead.
        if (protocolDraftsEnabled)
            UpdateProtocolFieldGrid();
        else
            googleAccount.SetBounds(googleInfo.Left, tera || mybox ? 211 : 291, 525, 25);
        anonymousBox.Visible = !cloud;
        rememberBox.Visible = !google && !oneDrive;
        rememberBox.Text = mybox ? "토큰 저장" : tera ? "쿠키저장" : "암호저장";
        if (tera || mybox) { anonymousBox.Checked = false; passwordBox.Enabled = true; rememberBox.Enabled = true; }
        passwordBox.PlaceholderText = mybox ? (existing ? "변경할 때만 새 PAT 입력" : "mbx_pat_... 개인 액세스 토큰") :
            tera ? (existing ? "변경할 때만 ndus 입력" : "ndus 값 또는 ndus=...") :
            existing ? "변경 시에만 입력" : "암호 입력";
        teraCookieHelp.Visible = tera || mybox;
        teraAutoImport.Visible = tera;
        teraCookieHelp.AccessibleName = mybox ? "MYBOX 개인 액세스 토큰 발급 방법" :
            "테라박스 ndus 쿠키 확인 방법";
        tip.SetToolTip(teraCookieHelp,
            mybox ? "MYBOX PAT 발급 방법 보기" : "ndus 쿠키 확인 방법 보기");
        webDavHttpBox.Visible = dav;
        nativeDavBox.Visible = dav;
        nativeDavBox.Enabled = dav && modeIndex == 0;
        ftpCharsetLabel.Visible = ftp || sftp;
        ftpCharset.Visible = ftp || sftp;
        ftpPasv.Visible = ftp;
        ftpExplicit.Visible = ftp;
        ftpModeZ.Visible = ftp;
        ftpTlsLabel.Visible = ftp;
        ftpSecurity.Visible = ftp;
        ftpVersionLabel.Visible = ftp;
        ftpTlsVersion.Visible = ftp;
        sftpLabel.Visible = sftp;
        // The TextBox lives inside a border frame. Hide the ENTIRE frame;
        // hiding only the inner editor leaves a blank bar in other protocols.
        SetFieldVisible(fingerprintBox, sftp);
        smbNote.Visible = protocol == "SMB";
        cloudNetworkBox.Visible = cloud;
        modes[0].Visible = protocol != "SMB";
        // Match WebDAV's compact toggles. Their actual enabled/disabled
        // semantics are explained by the tooltips below; longer strings
        // would overrun the fixed, adjacent radio/checkbox positions.
        modes[0].Text = cloud ? "쓰기 허용" : "파일잠금";
        modes[1].Text = "읽기전용";
        if (ftp || sftp)
        {
            tip.SetToolTip(modes[0],
                "FileHub AIO끼리 협력 잠금 사용. 다른 PC는 편집 차단, 사용 중 표시 파일 생성. " +
                "FTP/SFTP 표준 잠금 기능이 아닌 FileHub AIO 전용 서버 잠금 폴더를 사용합니다.");
            tip.SetToolTip(modes[1],
                "협력 잠금 없이 읽기 전용으로 마운트합니다. 파일 수정/업로드는 할 수 없습니다.");
        }
        modes[2].Visible = dav;
        if (cloud && !mybox && previous != protocol && !protocolDrafts.ContainsKey(protocol) &&
            (!existing || Profile.Protocol != protocol))
            SelectMode(0);
        if (mybox && previous != protocol && !protocolDrafts.ContainsKey(protocol) &&
            (!existing || Profile.Protocol != protocol))
            SelectMode(0); // New MYBOX writable; existing saved read-only mode is preserved.
        if (protocol == "SMB" && modeIndex == 0 || !dav && modeIndex == 2)
            SelectMode(1);
        if (protocol == "SMB")
            tip.SetToolTip(modes[1], "SMB 읽기 전용은 서버측 권한 설정이 필요합니다.");
        portBox.Enabled = protocol != "SMB";
        pathBox.PlaceholderText = protocol == "SMB" ? "공유 폴더 이름" :
            protocol == "SFTP" ? "/volume1/공유폴더" : "기본 경로 /";
        advancedHeading.Text = "고급 설정";
        ProtocolChanged?.Invoke(protocol);
    }

    private void CharsetChanged()
    {
        if (switching || currentProtocol is not ("FTP" or "SFTP")) return;
        string chosen = ftpCharset.SelectedItem?.ToString() ?? "UTF-8";
        if (!CharsetSupport.IsSupported(currentProtocol, chosen))
        {
            MessageBox.Show(this, CharsetSupport.DescribeUnsupported(currentProtocol, chosen),
                "문자셋 엔진 미지원", MessageBoxButtons.OK, MessageBoxIcon.Information);
            switching = true;
            try { ftpCharset.SelectedItem = currentProtocol == "FTP" ? selectedFtpCharset : selectedSftpCharset; }
            finally { switching = false; }
            return;
        }
        if (currentProtocol == "FTP") selectedFtpCharset = chosen;
        else selectedSftpCharset = chosen;
    }

    private void UpdateAnonymousUi()
    {
        bool enabled = !anonymousBox.Checked;
        userBox.Enabled = enabled;
        passwordBox.Enabled = enabled;
        rememberBox.Enabled = enabled;
        if (!enabled) rememberBox.Checked = false;
    }

    private void SetFieldVisible(Control field, bool visible)
    {
        if (framedInputs.TryGetValue(field, out var frame)) frame.Visible = visible;
        else field.Visible = visible;
    }

    internal void SelectMode(int index)
    {
        if (index is < 0 or > 2) throw new ArgumentOutOfRangeException(nameof(index));
        if (index == 2 && currentProtocol != "WebDAV" ||
            index == 0 && currentProtocol == "SMB") index = 1;
        switching = true;
        try
        {
            modeIndex = index;
            for (int i = 0; i < modes.Length; i++) modes[i].Checked = i == index;
            nativeDavBox.Enabled = currentProtocol == "WebDAV" && index == 0;
        }
        finally { switching = false; }
    }

    internal static void ApplyPerformanceMode(NasProfile profile, int selected)
    {
        profile.AllowUnprotectedWrites = false;
        profile.UseLockBridge = profile.Protocol is not ("SMB" or "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox") && selected == 0;
        if (!profile.UseLockBridge) profile.UseNativeWebDavLocks = false;
        profile.AllowUnprotectedWrites = profile.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox" && selected == 0;
        // The old fast-read mode is removed. Existing profiles are migrated
        // to the safer standard read-only mode on Save, never faster caching.
        profile.FastReadOnly = false;
        profile.OfficeReadTuning = profile.Protocol == "WebDAV" && selected == 2;
    }

    private void SaveProfile()
    {
        if (!int.TryParse(portBox.Text.Trim().Length == 0 ? "0" : portBox.Text.Trim(), out var port) ||
            port is < 0 or > 65535)
        {
            MessageBox.Show(this, "포트는 0~65535 사이의 숫자를 입력하세요.", "포트 확인");
            portBox.Focus(); return;
        }
        var next = Profile.Clone();
        next.DisplayName = nameBox.Text.Trim();
        next.Protocol = currentProtocol;
        next.Host = hostBox.Text.Trim();
        next.Share = pathBox.Text.Trim();
        next.Port = port;
        if (currentProtocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox")
        {
            next.Host = "";
            next.Share = "";
            next.Port = 0;
        }
        next.IsAnonymous = currentProtocol is "GoogleDrive" or "OneDrive" ||
            currentProtocol != "TeraBox" && anonymousBox.Checked;
        next.Username = next.IsAnonymous ? "" : userBox.Text.Trim();
        if (currentProtocol == "TeraBox") next.Username = "";
        next.RememberPassword = (currentProtocol == "TeraBox" || !next.IsAnonymous) && rememberBox.Checked;
        next.AutoConnect = autoBox.Checked;
        if (next.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox")
            next.UseFixedDriveMode = !cloudNetworkBox.Checked;
        next.WebDavUseHttp = webDavHttpBox.Checked;
        next.SftpHostFingerprint = fingerprintBox.Text.Trim();
        bool sftpEndpointChanged = next.Protocol == "SFTP" &&
            (!next.Host.Equals(Profile.Host, StringComparison.OrdinalIgnoreCase) ||
             (next.Port == 0 ? 22 : next.Port) !=
                (Profile.Port == 0 ? 22 : Profile.Port));
        if (sftpEndpointChanged && string.Equals(next.SftpHostFingerprint,
                Profile.SftpHostFingerprint, StringComparison.Ordinal))
            next.SftpHostFingerprint = ""; // old server's pin is not this endpoint
        if (next.Protocol != "SFTP" || sftpEndpointChanged ||
            !string.Equals(next.SftpHostFingerprint, Profile.SftpHostFingerprint,
                StringComparison.Ordinal))
        {
            next.SftpPinnedHostKey = "";
            next.SftpTrustedHost = "";
            next.SftpTrustedPort = 0;
        }
        next.FtpCharset = selectedFtpCharset;
        next.SftpCharset = selectedSftpCharset;
        next.FtpDisableUtf8 = next.FtpCharset == "서버 기본";
        next.FtpPreferPasv = ftpPasv.Checked;
        next.FtpImplicitTls = ftpSecurity.SelectedIndex == 2;
        next.ExplicitTls = ftpSecurity.SelectedIndex == 1;
        next.FtpTls12Only = ftpTlsVersion.SelectedIndex == 1;
        ApplyPerformanceMode(next, modeIndex);
        next.UseNativeWebDavLocks = next.Protocol == "WebDAV" &&
            next.UseLockBridge && nativeDavBox.Checked;
        if (next.UseNativeWebDavLocks &&
            MessageBox.Show(this,
                "서버 기본 잠금은 .nasmount.lock 폴더를 만들지 않습니다. " +
                "서버가 WebDAV LOCK 토큰을 지원하지 않으면 Excel 저장이 차단될 수 있습니다. " +
                "구 버전 FileHub AIO와 같은 문서를 동시에 열지 마세요. 사용하시겠습니까?",
                "서버 기본 잠금 호환성 확인", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
        if (next.Protocol == "MyBox")
        {
            if ((!existing || passwordBox.TextLength > 0) &&
                !MyBoxBetaGateway.Api.ValidToken(passwordBox.Text))
            {
                MessageBox.Show(this,
                    "MYBOX에서 발급한 mbx_pat_... 개인 액세스 토큰을 입력하세요.",
                    "MYBOX 토큰 확인");
                return;
            }
            if (!next.RememberPassword)
            {
                MessageBox.Show(this, "MYBOX는 자동 재연결을 위해 PAT 토큰 저장이 필요합니다.");
                return;
            }
            if (next.AllowUnprotectedWrites && (!existing || !Profile.AllowUnprotectedWrites) &&
                MessageBox.Show(this,
                    "MYBOX 쓰기는 공식 Open API 기반 시험 기능입니다. 일반 파일 업로드/삭제/이름 변경에만 사용하고, Excel 등 중요한 문서의 직접 편집은 충분한 검증 전까지 피하세요. 계속할까요?",
                    "MYBOX 쓰기 주의", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Warning) != DialogResult.Yes) return;
        }
        if (next.Protocol == "TeraBox")
        {
            if ((!existing || passwordBox.TextLength > 0) && !TeraBoxBackend.HasCookie(passwordBox.Text))
            { MessageBox.Show(this, "테라박스 로그인 쿠키(ndus)를 확인하세요."); return; }
            if (!existing && !next.RememberPassword)
            { MessageBox.Show(this, "자동 재연결을 위해 쿠키저장을 선택하세요."); return; }
            // Explicit '쓰기 허용' selection is sufficient. Do not show
            // another beta/experimental-write confirmation dialog on Save.
            // Cookie validation and existing backend read/write policies stay
            // unchanged.
        }

        if (string.IsNullOrWhiteSpace(next.DisplayName))
        { MessageBox.Show(this, "연결 이름을 입력하세요."); nameBox.Focus(); return; }
        if (!RemoteMount.TryValidate(next, out string reason))
        { MessageBox.Show(this, reason, "입력 정보 확인"); return; }
        if (next.IsAnonymous && next.Protocol is ("SFTP" or "SMB") &&
            MessageBox.Show(this,
                next.Protocol == "SFTP" ?
                "SFTP에는 공통 익명 로그인 표준이 없습니다. 서버가 anonymous 계정의 무암호 SSH 인증을 허용하는 경우에만 연결됩니다.\n계속 저장할까요?" :
                "SMB 게스트 접속은 NAS 공유 권한과 Windows 보안 정책 양쪽에서 허용해야 합니다.\n계속 저장할까요?",
                "익명 연결 안내", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        if (next.Protocol == "WebDAV" && next.WebDavUseHttp &&
            MessageBox.Show(this, "HTTP 연결은 비밀번호와 파일 내용이 암호화되지 않을 수 있습니다.\n" +
                "가능하면 HTTPS를 사용하세요. 계속 저장할까요?", "HTTP 전송 주의",
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;
        if (!existing && next.RememberPassword && next.Username.Length > 0 && Password.Length == 0)
        { MessageBox.Show(this, "암호저장을 선택했다면 NAS 암호를 입력하세요."); passwordBox.Focus(); return; }
        if (next.AutoConnect && next.Username.Length > 0 && !next.RememberPassword &&
            MessageBox.Show(this, "암호저장이 꺼져 있으면 자동연결이 실패할 수 있습니다. 계속할까요?",
                "자동연결", MessageBoxButtons.YesNo) != DialogResult.Yes) return;

        Profile.DisplayName = next.DisplayName;
        Profile.Protocol = next.Protocol;
        Profile.Host = next.Host;
        Profile.Share = next.Share;
        Profile.Port = next.Port;
        Profile.Username = next.Username;
        Profile.IsAnonymous = next.IsAnonymous;
        Profile.RememberPassword = next.RememberPassword;
        Profile.AutoConnect = next.AutoConnect;
        Profile.WebDavUseHttp = next.WebDavUseHttp;
        Profile.SftpHostFingerprint = next.SftpHostFingerprint;
        Profile.SftpPinnedHostKey = next.SftpPinnedHostKey;
        Profile.SftpTrustedHost = next.SftpTrustedHost;
        Profile.SftpTrustedPort = next.SftpTrustedPort;
        Profile.FtpDisableUtf8 = next.FtpDisableUtf8;
        Profile.FtpCharset = next.FtpCharset;
        Profile.SftpCharset = next.SftpCharset;
        Profile.FtpPreferPasv = next.FtpPreferPasv;
        Profile.FtpImplicitTls = next.FtpImplicitTls;
        Profile.ExplicitTls = next.ExplicitTls;
        Profile.FtpTls12Only = next.FtpTls12Only;
        Profile.AllowUnprotectedWrites = next.AllowUnprotectedWrites;
        Profile.UseLockBridge = next.UseLockBridge;
        Profile.UseNativeWebDavLocks = next.UseNativeWebDavLocks;
        Profile.FastReadOnly = next.FastReadOnly;
        Profile.OfficeReadTuning = next.OfficeReadTuning;
        DialogResult = DialogResult.OK;
        Close();
    }

    internal void VerifyFieldLayout()
    {
        // Regression: all four cloud providers must show their account/help
        // captions entirely within the right-side editor, not under the rail.
        if (googleAccount.Visible &&
            (googleAccount.Left != googleInfo.Left ||
             googleAccount.Left < protocolSidebar.Right + 24 ||
             googleAccount.Right > ClientSize.Width - 10 ||
             googleInfo.Left < protocolSidebar.Right + 24))
            throw new InvalidOperationException("Cloud account help clipped by protocol sidebar: " +
                currentProtocol + " caption=" + googleAccount.Bounds +
                " info=" + googleInfo.Bounds);
        foreach (var (label, input) in fieldPairs)
        {
            if (label.Bounds.IntersectsWith(input.Bounds))
                throw new InvalidOperationException("Label overlaps input: " + label.Text);
            Size measured = TextRenderer.MeasureText(label.Text, label.Font, new Size(1000, 100),
                TextFormatFlags.SingleLine | TextFormatFlags.NoPadding);
            if (measured.Width > label.Width - 2 || measured.Height > label.Height - 2)
                throw new InvalidOperationException("Label clipped: " + label.Text + ", " + measured);
            if (input.Right >= ClientSize.Width || input.Bottom > 482)
                throw new InvalidOperationException("Input outside viewport: " + label.Text);
        }
        foreach (var c in Controls.OfType<CompactCheck>().Where(x => x.Visible))
        {
            var m = TextRenderer.MeasureText(c.Text, c.Font, new Size(1000, 100),
                TextFormatFlags.SingleLine | TextFormatFlags.NoPadding);
            if (m.Width + 23 > c.Width + 2 || c.Right >= ClientSize.Width)
                throw new InvalidOperationException("Checkbox clipped: " + c.Text);
        }
        var visibleChecks = Controls.OfType<CompactCheck>().Where(x => x.Visible).ToArray();
        for (int i = 0; i < visibleChecks.Length; i++)
            for (int j = i + 1; j < visibleChecks.Length; j++)
                if (visibleChecks[i].Bounds.IntersectsWith(visibleChecks[j].Bounds))
                    throw new InvalidOperationException("Checkbox overlap: " +
                        visibleChecks[i].Text + " / " + visibleChecks[j].Text);
        if (modes.Count(x => x.Checked) != 1 || AutoScroll ||
            Controls.OfType<ScrollBar>().Any())
            throw new InvalidOperationException("Mode selection or scrollbar invalid");
        if (portBox.Height != hostBox.Height || portBox.Height != pathBox.Height ||
            nameBox.Height != userBox.Height || nameBox.Height != passwordBox.Height)
            throw new InvalidOperationException("Input field height mismatch");
        foreach (var (label, displayed) in fieldPairs)
        {
            if (label.Left <= 39)
                throw new InvalidOperationException("Subcategory not indented: " + label.Text);
            if (displayed is CenteredTextField frame)
            {
                int freeTop = frame.Inner.Top;
                int freeBottom = frame.Height - frame.Inner.Bottom;
                if (Math.Abs(freeTop - freeBottom) > 2)
                    throw new InvalidOperationException("Input text not centered: " +
                        label.Text + " top=" + freeTop + " bottom=" + freeBottom);
                if (frame.Height > 25)
                    throw new InvalidOperationException("Oversized entry field: " + label.Text);
            }
        }
        if (framedInputs.TryGetValue(fingerprintBox, out var sshFrame) &&
            sshFrame.Visible != (currentProtocol == "SFTP"))
            throw new InvalidOperationException("SFTP input frame leaked into another protocol");
        if (teraCookieHelp.Visible !=
                (currentProtocol is "TeraBox" or "MyBox") ||
            (currentProtocol is "TeraBox" or "MyBox") && !TeraCookieHelpAligned ||
            currentProtocol == "MyBox" && !MyBoxPatHelpVisible)
            throw new InvalidOperationException("Cloud token help hidden or overlaps token input: "
                + $"form={Visible}, button={teraCookieHelp.Visible}, protocol={currentProtocol}, "
                + $"bounds={teraCookieHelp.Bounds}, frame="
                + (framedInputs.TryGetValue(passwordBox, out var cookieFrame) ? cookieFrame.Bounds.ToString() : "missing"));
        if (TeraAutoImportVisible != (currentProtocol == "TeraBox") ||
            currentProtocol == "TeraBox" && !TeraAutoImportAligned)
            throw new InvalidOperationException(
                "TeraBox ndus automatic import button hidden/misaligned");
    }
}

/// <summary>
/// Thin 24px field with a REAL editable TextBox on the vertical center line.
/// The native editor is kept at PreferredSize.Height; no fake text painting.
/// This also centers password glyphs and placeholder text inside the border.
/// </summary>
internal sealed class CenteredTextField : Panel
{
    internal TextBox Inner { get; }

    internal CenteredTextField(TextBox inner)
    {
        Inner = inner;
        TabStop = false;
        BackColor = ModernTheme.Field;
        ForeColor = ModernTheme.Text;
        SetStyle(ControlStyles.ResizeRedraw | ControlStyles.OptimizedDoubleBuffer, true);
        Controls.Add(Inner);
        Inner.Margin = Padding.Empty;
        Inner.AutoSize = true;
        Inner.BorderStyle = BorderStyle.None;
        Inner.BackColor = BackColor;
        Inner.ForeColor = ForeColor;
        Inner.FontChanged += (_, _) => CenterEditor();
        Click += (_, _) => Inner.Focus();
    }

    protected override void OnLayout(LayoutEventArgs levent)
    {
        base.OnLayout(levent);
        CenterEditor();
    }

    private void CenterEditor()
    {
        if (Inner.IsDisposed || Width < 12 || Height < 10) return;
        int natural = Math.Min(Height - 2, Inner.PreferredSize.Height);
        int top = Math.Max(1, (Height - natural) / 2);
        var bounds = new Rectangle(5, top, Math.Max(1, Width - 10), natural);
        if (Inner.Bounds != bounds) Inner.Bounds = bounds;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        using var border = new Pen(ModernTheme.Border);
        e.Graphics.DrawRectangle(border, 0, 0, Width - 1, Height - 1);
    }
}

internal sealed class CompactCheck : CheckBox
{
    internal CompactCheck()
    {
        AutoSize = false;
        SetStyle(ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.AllPaintingInWmPaint | ControlStyles.ResizeRedraw, true);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(BackColor);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        int side = 15;
        var rect = new Rectangle(1, (Height - side) / 2, side, side);
        using (var fill = new SolidBrush(Checked && Enabled ? ModernTheme.Accent : ModernTheme.Field))
            e.Graphics.FillRectangle(fill, rect);
        using (var stroke = new Pen(Enabled ? ModernTheme.Muted : ModernTheme.Border))
            e.Graphics.DrawRectangle(stroke, rect);
        if (Checked)
        {
            using var mark = new Pen(Color.White, 1.8f);
            e.Graphics.DrawLines(mark,
            [
                new Point(rect.X + 3, rect.Y + 7),
                new Point(rect.X + 6, rect.Y + 11),
                new Point(rect.X + 12, rect.Y + 4)
            ]);
        }
        TextRenderer.DrawText(e.Graphics, Text, Font,
            new Rectangle(22, 0, Math.Max(0, Width - 22), Height),
            Enabled ? ForeColor : ModernTheme.Muted,
            TextFormatFlags.SingleLine | TextFormatFlags.NoPadding | TextFormatFlags.VerticalCenter);
    }
}

internal sealed class ProtocolTile : Button
{
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    internal bool Selected { get => selected; set { selected = value; Invalidate(); } }
    private bool selected;
    private bool hovered;
    internal ProtocolTile(string value)
    {
        Text = value;
        Font = new Font("Malgun Gothic", 10.2f, FontStyle.Bold);
        ForeColor = ModernTheme.Text;
        BackColor = ModernTheme.Surface;
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        Cursor = Cursors.Hand;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint |
            ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw, true);
    }
    protected override void OnMouseEnter(EventArgs e)
    {
        hovered = true; Invalidate(); base.OnMouseEnter(e);
    }
    protected override void OnMouseLeave(EventArgs e)
    {
        hovered = false; Invalidate(); base.OnMouseLeave(e);
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.Clear(Parent?.BackColor ?? Color.FromArgb(39, 43, 49));
        var border = new Rectangle(1, 1, Width - 3, Height - 3);
        using var fill = new SolidBrush(selected ? Color.FromArgb(37, 97, 151) :
            hovered ? Color.FromArgb(50, 57, 66) : Color.FromArgb(39, 43, 49));
        g.FillRectangle(fill, border);
        if (selected)
            using (var accent = new SolidBrush(Color.FromArgb(66, 176, 245)))
                g.FillRectangle(accent, new Rectangle(1, 2, 3, Height - 4));
        int iconSide = Math.Min(36, Height - 7);
        if (Image is not null)
            g.DrawImage(Image, new Rectangle(9, (Height - iconSide) / 2,
                iconSide, iconSide));
        TextRenderer.DrawText(g, Text, Font,
            new Rectangle(49, 2, Width - 53, Height - 4), ForeColor,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter |
            TextFormatFlags.SingleLine | TextFormatFlags.NoPadding | TextFormatFlags.EndEllipsis);
    }
    protected override void Dispose(bool disposing)
    {
        if (disposing) Image?.Dispose();
        base.Dispose(disposing);
    }
}
