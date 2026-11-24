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
            ClientSize = new Size(565, 332),
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

        void Section(string title, int y)
        {
            var label = ModernTheme.Label(title, 10, true, ModernTheme.Muted);
            label.SetBounds(28, y, 250, 24);
            window.Controls.Add(label);
            var rule = new Panel { BackColor = ModernTheme.Border };
            rule.SetBounds(282, y + 12, 244, 1);
            window.Controls.Add(rule);
        }

        ModernButton ActionButton(string title, string icon, int x, int y,
            Action action, int width = 243, bool accent = false)
        {
            var button = (ModernButton)ModernTheme.Button(title, width, action,
                accent: accent, height: 40);
            button.Glyph = icon;
            button.SetBounds(x, y, width, 40);
            button.AccessibleName = title;
            window.Controls.Add(button);
            return button;
        }

        Section("설정 백업", 92);
        var backup = ActionButton("설정 내보내기", "export", 28, 119,
            () => ExportSettings(window));
        backup.AccessibleName = "모든 연결 설정과 저장된 암호를 암호화 백업 파일로 내보내기";
        var restore = ActionButton("설정 가져오기", "import", 282, 119,
            () => ImportSettings(window), width: 244);
        restore.AccessibleName = "암호화된 FileHub AIO 백업 파일을 가져오기";

        Section("프로그램 관리", 174);
        ActionButton("로그 폴더 열기", "folder", 28, 201, OpenLogs);
        ActionButton("WinFsp 확인 / 설치", "driver", 282, 201,
            () => _ = InstallWinFsp(), width: 244);
        var update = ActionButton("PC 업데이트 확인", "update", 28, 249,
            () => _ = CheckForPcUpdateAsync(window));
        update.AccessibleName = "Windows 전용 GitHub pc-v 릴리즈 업데이트 확인";
        var components = ActionButton("엔진 · 버전 · README", "info", 282, 249, () =>
        {
            using var detail = new ComponentInventoryDialog();
            detail.ShowDialog(this);
        }, width: 244);
        components.AccessibleName = "사용 엔진, 라이브러리 및 확장 백엔드 버전 README";

        var cache = (ModernButton)ModernTheme.Button("캐시 관리", 243, () =>
        {
            using var detail = CreateCacheSettingsForm();
            detail.ShowDialog(this);
        }, height: 32);
        cache.Glyph = "folder";
        cache.SetBounds(28, 297, 243, 32);
        window.Controls.Add(cache);

        var close = (ModernButton)ModernTheme.Button("닫기", 110,
            () => { window.DialogResult = DialogResult.OK; window.Close(); },
            accent: true, height: 34);
        close.SetBounds(416, 297, 110, 32);
        window.Controls.Add(close);
        window.AcceptButton = close;
        window.CancelButton = close;
        return window;
    }
}
