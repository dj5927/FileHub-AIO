using System.Drawing;
using System.Text;
using System.Windows.Forms;

namespace NASMount;

internal sealed partial class MainForm
{
    private void ExportSettings(Form settingsWindow)
    {
        using var save = new SaveFileDialog {
            Title = "FileHub AIO 설정 내보내기",
            Filter = "암호화 설정 백업 (*.fhbackup)|*.fhbackup",
            DefaultExt = "fhbackup",
            AddExtension = true,
            FileName = "FileHub-AIO-PC-" + DateTime.Now.ToString("yyyyMMdd") + ".fhbackup",
            OverwritePrompt = true
        };
        if (save.ShowDialog(this) != DialogResult.OK) return;
        if (!PromptBackupPassword(this, confirm: true, out string password))
            return;
        try
        {
            var data = ProfileBackup.Capture(cfg, out var warnings);
            var encrypted = ProfileBackup.Pack(data, password);
            ProfileBackup.WriteArchive(save.FileName, encrypted);
            AppStore.Log("PROFILE_EXPORT_OK profiles=" + data.Config.Profiles.Count +
                " secretCount=" + data.Passwords.Count +
                " googleAuthCount=" + data.GoogleOAuth.Count +
                " oneDriveAuthCount=" + data.OneDriveOAuth.Count);
            string warning = warnings.Count == 0 ? "" :
                "\n\n일부 연결은 인증 정보를 복사하지 못했습니다:\n· " +
                string.Join("\n· ", warnings.Take(8)) +
                (warnings.Count > 8 ? "\n(이외 " + (warnings.Count - 8) + "개)" : "");
            MessageBox.Show(this,
                "설정 내보내기 완료\n\n연결 " + data.Config.Profiles.Count +
                "개, 저장된 비밀번호 " + data.Passwords.Count +
                "개, 클라우드 인증 " + (data.GoogleOAuth.Count + data.OneDriveOAuth.Count) +
                "개를 백업했습니다.\n\n" +
                "백업 파일에는 서버 주소와 로그인 정보가 암호화되어 있습니다.\n" +
                "비밀번호를 잊으면 복구할 수 없으니 안전하게 보관하세요." + warning,
                "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            AppStore.Log("PROFILE_EXPORT_FAILED " + ex.GetType().Name);
            MessageBox.Show(this, "설정 내보내기에 실패했습니다.\n" +
                ex.Message, "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void ImportSettings(Form settingsWindow)
    {
        if (connectBatchRunning || deletingProfile || preparingConnections.Count > 0 ||
            cfg.Profiles.Any(p => IsConnected(p) ||
                (p.Protocol != "SMB" && RemoteMount.HasManagedRecord(p))))
        {
            MessageBox.Show(this,
                "현재 연결된 드라이브나 진행 중인 연결이 있습니다.\n\n" +
                "Excel 등 열린 문서를 저장하고, 모든 드라이브를 안전하게 연결 해제한 후" +
                " 다시 가져와 주세요.\n업로드 대기 파일을 보호하기 위해 현재 상태에서는" +
                " 설정을 교체하지 않습니다.",
                "연결 해제가 필요합니다", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        using var open = new OpenFileDialog {
            Title = "FileHub AIO 설정 가져오기",
            Filter = "암호화 설정 백업 (*.fhbackup)|*.fhbackup",
            CheckFileExists = true,
            Multiselect = false
        };
        if (open.ShowDialog(this) != DialogResult.OK) return;
        if (!PromptBackupPassword(this, confirm: false, out string password))
            return;

        try
        {
            var file = new FileInfo(open.FileName);
            if (file.Length > 12L * 1024 * 1024)
                throw new InvalidDataException("설정 백업 파일 크기가 너무 큽니다.");
            var snapshot = ProfileBackup.Unpack(File.ReadAllBytes(open.FileName), password);
            int secretCount = snapshot.Passwords.Count;
            int oauthCount = snapshot.GoogleOAuth.Count + snapshot.OneDriveOAuth.Count;
            int missingCount = snapshot.Config.Profiles.Count(p =>
                p.RememberPassword && !snapshot.Passwords.ContainsKey(p.Id));
            int cloudMissing = snapshot.Config.Profiles.Count(p =>
                p.Protocol == "GoogleDrive" && !snapshot.GoogleOAuth.ContainsKey(p.Id) ||
                p.Protocol == "OneDrive" && !snapshot.OneDriveOAuth.ContainsKey(p.Id));
            var occupied = Environment.GetLogicalDrives()
                .Select(s => s[..1]).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var conflicts = snapshot.Config.Profiles
                .Where(p => occupied.Contains(p.Letter))
                .Select(p => p.Letter + ":").Distinct().ToArray();
            string prompt = "백업에서 다음 설정을 가져옵니다.\n\n" +
                "연결 " + snapshot.Config.Profiles.Count + "개\n" +
                "저장된 비밀번호 " + secretCount + "개\n" +
                "클라우드 인증 " + oauthCount + "개\n\n" +
                "기존 연결 목록은 모두 교체되며, 교체 전 config.json을 자동 백업합니다.\n" +
                "현재 PC의 Windows 시작 프로그램 설정은 유지합니다.\n" +
                "가져온 연결은 프로그램 재실행 후 적용됩니다.";
            if (missingCount + cloudMissing > 0)
                prompt += "\n\n인증 누락 연결 " + (missingCount + cloudMissing) +
                    "개는 자동 연결을 끄고 재인증하도록 처리합니다.";
            if (conflicts.Length > 0)
                prompt += "\n\n주의: 현재 Windows에서 사용 중인 드라이브 문자: " +
                    string.Join(", ", conflicts) +
                    "\n해당 연결은 다른 드라이브 문자를 배정해야 할 수 있습니다.";
            prompt += "\n\n정말 가져오시겠습니까?";
            if (MessageBox.Show(this, prompt, "설정 가져오기 · 기존 목록 교체",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                    MessageBoxDefaultButton.Button2) != DialogResult.Yes)
                return;
            timer.Stop();
            string originalConfigBackup;
            try
            {
                originalConfigBackup = ProfileBackup.Restore(snapshot,
                    AppStore.IsAutoStartEnabled());
                cfg.Profiles.Clear();
                cfg.Profiles.AddRange(snapshot.Config.Profiles);
                cfg.StartWithWindows = snapshot.Config.StartWithWindows;
                cfg.GoogleStartupDiagnostics = snapshot.Config.GoogleStartupDiagnostics;
                GoogleStartupTrace.Enabled = cfg.GoogleStartupDiagnostics;
                selectedId = null;
                RefreshList();
                // Prevent old timer from auto-connecting immediately after
                // the import; the user will restart the application.
                timer.Stop();
            }
            catch
            {
                if (!shutdownRequested && !exiting) timer.Start();
                throw;
            }
            AppStore.Log("PROFILE_IMPORT_OK profiles=" + cfg.Profiles.Count +
                " credentials=" + secretCount + " oauth=" + oauthCount);
            MessageBox.Show(this,
                "설정 가져오기 완료\n\n연결 " + cfg.Profiles.Count +
                "개를 복원했습니다.\n\n" +
                (originalConfigBackup.Length > 0 ?
                    "기존 설정 파일은 같은 폴더에 .before-import 백업으로 보관했습니다.\n" : "") +
                "현재 프로그램을 종료한 후 다시 실행하면 새 연결 설정이 적용됩니다.",
                "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Information);
            settingsWindow.Close();
        }
        catch (Exception ex)
        {
            AppStore.Log("PROFILE_IMPORT_FAILED " + ex.GetType().Name);
            MessageBox.Show(this, "설정 가져오기에 실패했습니다.\n" +
                ex.Message, "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static bool PromptBackupPassword(IWin32Window owner,
        bool confirm, out string password)
    {
        password = "";
        using var dialog = new Form {
            Text = confirm ? "설정 백업 암호 만들기" : "설정 백업 암호 입력",
            ClientSize = new Size(420, confirm ? 225 : 174),
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false, MinimizeBox = false,
            ShowInTaskbar = false, Font = new Font("Malgun Gothic", 9f),
            BackColor = ModernTheme.Background,
            ForeColor = ModernTheme.Text
        };
        ModernTheme.EnableDarkTitleBar(dialog);
        var label = new Label {
            Text = confirm ? "다른 PC에서 복원할 때 사용할 암호 (10자 이상)" :
                "백업할 때 설정했던 암호를 입력하세요.",
            ForeColor = ModernTheme.Text,
            Bounds = new Rectangle(22, 18, 372, 28)
        };
        dialog.Controls.Add(label);
        var first = new TextBox {
            Bounds = new Rectangle(22, 49, 376, 30),
            UseSystemPasswordChar = true
        };
        dialog.Controls.Add(first);
        TextBox? second = null;
        if (confirm)
        {
            var verify = new Label {
                Text = "백업 암호 확인",
                ForeColor = ModernTheme.Text,
                Bounds = new Rectangle(22, 93, 370, 23)
            };
            dialog.Controls.Add(verify);
            second = new TextBox {
                Bounds = new Rectangle(22, 121, 376, 30),
                UseSystemPasswordChar = true
            };
            dialog.Controls.Add(second);
        }
        int buttonY = confirm ? 173 : 119;
        var ok = new Button {
            Text = "확인", Bounds = new Rectangle(231, buttonY, 78, 34),
            DialogResult = DialogResult.None
        };
        var cancel = new Button {
            Text = "취소", Bounds = new Rectangle(319, buttonY, 78, 34),
            DialogResult = DialogResult.Cancel
        };
        ok.Click += (_, _) =>
        {
            if (string.IsNullOrEmpty(first.Text) ||
                (confirm && first.Text.Length < 10))
            {
                MessageBox.Show(dialog, confirm ?
                    "암호를 10자 이상 입력하세요." : "백업 암호를 입력하세요.");
                return;
            }
            if (confirm && first.Text != second!.Text)
            {
                MessageBox.Show(dialog, "두 암호가 일치하지 않습니다.");
                return;
            }
            dialog.DialogResult = DialogResult.OK;
            dialog.Close();
        };
        dialog.Controls.Add(ok);
        dialog.Controls.Add(cancel);
        dialog.AcceptButton = ok;
        dialog.CancelButton = cancel;
        dialog.ActiveControl = first;
        if (dialog.ShowDialog(owner) != DialogResult.OK) return false;
        password = first.Text;
        return true;
    }
}
