using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal sealed partial class MainForm
{
    private void ShowCompactSettings()
    {
        OpenSlidingView(CreateCompactSettingsForm(), "환경 설정");
    }

    internal Form CreateCompactSettingsForm()
    {
        var window = new Form
        {
            Text = "FileHub AIO · 환경 설정",
            ClientSize = new Size(565, 460),
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            ShowInTaskbar = false,
            AutoScroll = false,
            AutoScaleMode = AutoScaleMode.Dpi,
            BackColor = ModernTheme.Background,
            ForeColor = ModernTheme.Text,
            Font = new Font("Malgun Gothic", 9f)
        };
        ModernTheme.EnableDarkTitleBar(window);
        var heading = ModernTheme.Label("환경 설정", 17, true);
        heading.SetBounds(27, 13, 450, 35);
        window.Controls.Add(heading);
        var googleStartupDiag = new CheckBox
        {
            Text = "Google 연결 초기화 진단 (민감정보 제외 · 다음 연결부터)",
            Checked = cfg.GoogleStartupDiagnostics,
            ForeColor = ModernTheme.Muted,
            BackColor = ModernTheme.Background,
            FlatStyle = FlatStyle.Flat,
            AutoSize = false
        };
        googleStartupDiag.SetBounds(29, 53, 500, 27);
        googleStartupDiag.CheckedChanged += (_, _) =>
        {
            bool previous = cfg.GoogleStartupDiagnostics;
            try
            {
                cfg.GoogleStartupDiagnostics = googleStartupDiag.Checked;
                AppStore.Save(cfg);
                GoogleStartupTrace.Enabled = googleStartupDiag.Checked;
                AppStore.Log("GOOGLE_STARTUP_DIAG_OPT_IN enabled=" +
                    googleStartupDiag.Checked);
            }
            catch (Exception ex)
            {
                cfg.GoogleStartupDiagnostics = previous;
                GoogleStartupTrace.Enabled = previous;
                MessageBox.Show(window, "Google 진단 설정을 저장하지 못했습니다.\n" +
                    ex.Message);
            }
        };
        window.Controls.Add(googleStartupDiag);

        var startup = new CheckBox
        {
            Text = "Windows 로그인 시 FileHub AIO 자동 실행",
            Checked = AppStore.IsAutoStartEnabled(),
            ForeColor = ModernTheme.Text,
            BackColor = ModernTheme.Background,
            FlatStyle = FlatStyle.Flat,
            AutoSize = false
        };
        startup.SetBounds(29, 88, 492, 27);
        startup.CheckedChanged += (_, _) =>
        {
            try
            {
                AppStore.SetAutoStart(startup.Checked);
                cfg.StartWithWindows = startup.Checked;
                AppStore.Save(cfg);
            }
            catch (Exception ex)
            {
                MessageBox.Show("자동 실행 설정 실패: " + ex.Message, "FileHub AIO",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        };
        window.Controls.Add(startup);

        var line = new Panel { BackColor = ModernTheme.Border };
        line.SetBounds(27, 126, 510, 1);
        window.Controls.Add(line);
        var section = ModernTheme.Label("연결 도구", 10, true);
        section.SetBounds(28, 136, 450, 26);
        window.Controls.Add(section);
        // Surface the existing real-server RFC4918 capability probe.
        // It creates only a disposable test document after confirmation,
        // never changes a production workbook or switches modes silently.
        var nativeLockProbe = new LinkLabel
        {
            Text = "WebDAV 서버 LOCK 지원 검사",
            AutoSize = false, TextAlign = ContentAlignment.MiddleRight,
            LinkColor = Color.FromArgb(86, 177, 240),
            ActiveLinkColor = Color.White,
            BackColor = ModernTheme.Background
        };
        nativeLockProbe.SetBounds(291, 137, 235, 24);
        nativeLockProbe.LinkClicked += (_, _) => _ = CheckWebDavStandardLock();
        window.Controls.Add(nativeLockProbe);

        var diagnostics = ModernTheme.Button("연결 진단", 243,
            () => _ = DiagnoseSelected(), height: 37);
        diagnostics.SetBounds(28, 170, 243, 33);
        window.Controls.Add(diagnostics);
        var logs = ModernTheme.Button("로그 폴더 열기", 244, OpenLogs, height: 37);
        logs.SetBounds(282, 170, 244, 33);
        window.Controls.Add(logs);
        var driver = ModernTheme.Button("WinFsp 확인 / 설치", 243,
            () => _ = InstallWinFsp(), height: 37);
        driver.SetBounds(28, 211, 243, 33);
        window.Controls.Add(driver);
        var retry = ModernTheme.Button("전체 연결 재시도", 244,
            () => _ = ConnectAll(true), height: 37);
        retry.SetBounds(282, 211, 244, 33);
        window.Controls.Add(retry);
        var clean = ModernTheme.Button("끊어진 연결 기록 정리", 243,
            () =>
            {
                try
                {
                    var result = LegacyNetworkGhostCleaner.Cleanup(cfg.Profiles);
                    ExplorerLiveViewRefresh.RefreshThisPc("MANUAL_RETIRED_GHOST_CLEANUP");
                    MessageBox.Show(window, result.Korean,
                        "FileHub AIO v1.3.83 · 이전 연결 기록 정리",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                catch (Exception ex)
                {
                    AppStore.Log("MANUAL_LEGACY_GHOST_CLEANUP_ERROR " + ex);
                    MessageBox.Show(window, "정리에 실패했습니다.\n" + ex.Message +
                        "\n로그 폴더를 확인하세요.", "네트워크 위치 정리",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                }
            }, height: 37);
        clean.SetBounds(28, 252, 243, 33);
        window.Controls.Add(clean);
        var diagnoseGhost = ModernTheme.Button(
            "빨간 X 원인 정밀 진단 저장", 244,
            () =>
            {
                try
                {
                    string path = GhostSystemDiagnostics.SaveReport();
                    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                    {
                        FileName = path, UseShellExecute = true
                    });
                    MessageBox.Show(window,
                        "현재 PC의 Windows 연결 상태와 WinFsp 드라이버 볼륨을" +
                        " 읽기 전용으로 검사해 진단 파일을 만들었습니다.\n\n" +
                        path + "\n\n" +
                        "빨간 X가 그대로라면 이 파일을 보내 주세요.\n" +
                        "실제 볼륨이 남아 있는 경우 다른 연결 보호를 위해" +
                        " WinFsp 전체 재시작은 자동 실행하지 않습니다.",
                        "FileHub AIO v1.3.83 · 네트워크 위치 정밀 진단",
                        MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                catch (Exception ex)
                {
                    AppStore.Log("GHOST_DIAGNOSTICS_ERROR " + ex);
                    MessageBox.Show(window, "진단 저장 실패: " + ex.Message,
                        "정밀 진단", MessageBoxButtons.OK,
                        MessageBoxIcon.Warning);
                }
            }, height: 37);
        diagnoseGhost.SetBounds(282, 252, 244, 33);
        window.Controls.Add(diagnoseGhost);
        var refreshExplorer = ModernTheme.Button(
            "탐색기만 다시 시작 (빨간 X 표시 캐시 초기화)", 498,
            () => ExplorerShellRestart.AskUserAndRestart(window), height: 30);
        var backup = ModernTheme.Button("설정 내보내기", 243,
            () => ExportSettings(window), height: 37);
        backup.SetBounds(28, 293, 243, 33);
        backup.AccessibleName = "모든 연결 설정과 저장된 암호를 암호화 백업 파일로 내보내기";
        window.Controls.Add(backup);
        var restore = ModernTheme.Button("설정 가져오기", 244,
            () => ImportSettings(window), height: 37);
        restore.SetBounds(282, 293, 244, 33);
        restore.AccessibleName = "암호화된 FileHub AIO 백업 파일을 가져오기";
        window.Controls.Add(restore);
        refreshExplorer.SetBounds(28, 334, 498, 30);
        window.Controls.Add(refreshExplorer);

        // Permanent, self-contained reference for future maintenance:
        // versions, binary roles, upstream repositories and real on-disk
        // rclone versions. Opens independently of current connections.
        var components = ModernTheme.Button("엔진 · 버전 · README", 243,
            () =>
            {
                using var detail = new ComponentInventoryDialog();
                detail.ShowDialog(this); // MainForm is the top-level owner.
            }, height: 34);
        components.SetBounds(28, 415, 243, 33);
        components.AccessibleName = "사용 엔진, 라이브러리 및 확장 백엔드 버전 README";
        window.Controls.Add(components);

        var close = ModernTheme.Button("닫기", 109,
            () => { window.DialogResult = DialogResult.OK; window.Close(); },
            accent: true, height: 34);
        close.SetBounds(417, 415, 109, 33);
        window.Controls.Add(close);
        var checkPcUpdate = ModernTheme.Button("PC 업데이트 확인", 498,
            () => _ = CheckForPcUpdateAsync(window), height: 34);
        checkPcUpdate.SetBounds(28, 374, 498, 33);
        checkPcUpdate.AccessibleName =
            "Windows 전용 GitHub pc-v 릴리즈 업데이트 확인";
        window.Controls.Add(checkPcUpdate);
        window.AcceptButton = close;
        return window;
    }
}
