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
            ClientSize = new Size(565, 438),
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
        var startup = new CheckBox
        {
            Text = "Windows 로그인 시 FileHub AIO 자동 실행",
            Checked = AppStore.IsAutoStartEnabled(),
            ForeColor = ModernTheme.Text,
            BackColor = ModernTheme.Background,
            FlatStyle = FlatStyle.Flat,
            AutoSize = false
        };
        startup.SetBounds(29, 53, 492, 27);
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
        line.SetBounds(27, 94, 510, 1);
        window.Controls.Add(line);
        var section = ModernTheme.Label("연결 도구", 10, true);
        section.SetBounds(28, 103, 450, 26);
        window.Controls.Add(section);

        var diagnostics = ModernTheme.Button("연결 진단", 243,
            () => _ = DiagnoseSelected(), height: 37);
        diagnostics.SetBounds(28, 139, 243, 33);
        window.Controls.Add(diagnostics);
        var logs = ModernTheme.Button("로그 폴더 열기", 244, OpenLogs, height: 37);
        logs.SetBounds(282, 139, 244, 33);
        window.Controls.Add(logs);
        var driver = ModernTheme.Button("WinFsp 확인 / 설치", 243,
            () => _ = InstallWinFsp(), height: 37);
        driver.SetBounds(28, 180, 243, 33);
        window.Controls.Add(driver);
        var retry = ModernTheme.Button("전체 연결 재시도", 244,
            () => _ = ConnectAll(true), height: 37);
        retry.SetBounds(282, 180, 244, 33);
        window.Controls.Add(retry);
        // Keep the RFC4918 test but present it as a full-size standard button.
        var nativeLockProbe = ModernTheme.Button(
            "WebDAV 서버 LOCK 지원 검사", 243,
            () => _ = CheckWebDavStandardLock(), height: 37);
        nativeLockProbe.SetBounds(28, 221, 243, 33);
        window.Controls.Add(nativeLockProbe);
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
                        "FileHub AIO v1.3.88 · 네트워크 위치 정밀 진단",
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
        diagnoseGhost.SetBounds(282, 221, 244, 33);
        window.Controls.Add(diagnoseGhost);
        var backup = ModernTheme.Button("설정 내보내기", 243,
            () => ExportSettings(window), height: 37);
        backup.SetBounds(28, 262, 243, 33);
        backup.AccessibleName = "모든 연결 설정과 저장된 암호를 암호화 백업 파일로 내보내기";
        window.Controls.Add(backup);
        var restore = ModernTheme.Button("설정 가져오기", 244,
            () => ImportSettings(window), height: 37);
        restore.SetBounds(282, 262, 244, 33);
        restore.AccessibleName = "암호화된 FileHub AIO 백업 파일을 가져오기";
        window.Controls.Add(restore);

        // Permanent, self-contained reference for future maintenance:
        // versions, binary roles, upstream repositories and real on-disk
        // rclone versions. Opens independently of current connections.
        var components = ModernTheme.Button("엔진 · 버전 · README", 243,
            () =>
            {
                using var detail = new ComponentInventoryDialog();
                detail.ShowDialog(this); // MainForm is the top-level owner.
            }, height: 34);
        components.SetBounds(28, 385, 243, 33);
        components.AccessibleName = "사용 엔진, 라이브러리 및 확장 백엔드 버전 README";
        window.Controls.Add(components);

        var close = ModernTheme.Button("닫기", 109,
            () => { window.DialogResult = DialogResult.OK; window.Close(); },
            accent: true, height: 34);
        close.SetBounds(417, 385, 109, 33);
        window.Controls.Add(close);
        var share = ModernTheme.Button("Synology 공유 설정", 498,
            OpenSynologyShare, height: 34);
        share.SetBounds(28, 303, 498, 33);
        window.Controls.Add(share);
        var checkPcUpdate = ModernTheme.Button("PC 업데이트 확인", 498,
            () => _ = CheckForPcUpdateAsync(window), height: 34);
        checkPcUpdate.SetBounds(28, 344, 498, 33);
        checkPcUpdate.AccessibleName =
            "Windows 전용 GitHub pc-v 릴리즈 업데이트 확인";
        window.Controls.Add(checkPcUpdate);
        window.AcceptButton = close;
        return window;
    }

    private void OpenSynologyShare()
    {
        using var form = new SynologySettingsForm(cfg);
        form.ShowDialog(this);
    }
}
