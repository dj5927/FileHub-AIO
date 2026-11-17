using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal sealed partial class MainForm
{
    private bool checkingPcUpdate;
    private PcUpdateService.Candidate? pendingPcRelease;

    // Non-blocking startup notification. Only the Windows pc-v* releases
    // appear; an Android APK can never trigger a desktop update offer.
    private async Task CheckStartupPcUpdateAsync()
    {
        if (checkingPcUpdate || shutdownRequested ||
            Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") == "1") return;
        checkingPcUpdate = true;
        try
        {
            pendingPcRelease = await PcUpdateService.FindUpdateAsync();
            if (pendingPcRelease is null || shutdownRequested || IsDisposed) return;
            tray.BalloonTipTitle = "FileHub AIO PC 새 업데이트";
            tray.BalloonTipText =
                $"Windows {pendingPcRelease.Version} 버전이 나왔습니다. 클릭하여 확인하세요.";
            tray.BalloonTipIcon = ToolTipIcon.Info;
            tray.ShowBalloonTip(4500);
            AppStore.Log("PC_UPDATE_AVAILABLE " + pendingPcRelease.Tag);
        }
        catch (Exception ex)
        {
            // GitHub offline, rate-limit and absent PC releases must not
            // interrupt startup or NAS auto-connect.
            AppStore.Log("PC_UPDATE_CHECK_SKIPPED " + ex.GetType().Name);
        }
        finally { checkingPcUpdate = false; }
    }

    private async Task CheckForPcUpdateAsync(Form? owner = null)
    {
        if (checkingPcUpdate || shutdownRequested) return;
        checkingPcUpdate = true;
        PcUpdateService.Staged? staged = null;
        try
        {
            status.Text = "GitHub PC 릴리즈 확인 중...";
            var candidate = await PcUpdateService.FindUpdateAsync();
            if (candidate is null)
            {
                MessageBox.Show(owner ?? this,
                    $"현재 Windows 버전 {PcUpdateService.InstalledVersion}이 최신이거나 " +
                    "별도 PC 릴리즈가 아직 등록되지 않았습니다.",
                    "FileHub AIO PC 업데이트", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return;
            }
            pendingPcRelease = candidate;
            string notes = candidate.Notes.Trim();
            if (notes.Length > 720) notes = notes[..720] + "...";
            if (MessageBox.Show(owner ?? this,
                "PC 업데이트가 있습니다.\n\n" +
                $"현재: {PcUpdateService.InstalledVersion}\n" +
                $"새 버전: {candidate.Version}\n" +
                $"크기: {candidate.Size / 1048576.0:F1} MB\n\n" +
                (notes.Length > 0 ? notes + "\n\n" : "") +
                "지금 다운로드하시겠습니까?\n" +
                "드라이브 해제와 설치는 다운로드 후 다시 확인합니다.",
                "FileHub AIO PC 업데이트", MessageBoxButtons.YesNo,
                MessageBoxIcon.Question) != DialogResult.Yes) return;

            using var progress = new PcUpdateDownloadForm(candidate.Version);
            progress.Show(owner ?? this);
            try
            {
                staged = await PcUpdateService.DownloadAndStageAsync(
                    candidate, progress.Progress, progress.Cancellation);
            }
            finally { progress.Close(); }

            if (MessageBox.Show(this,
                "업데이트 파일의 SHA-256과 구성품 검사가 완료됐습니다.\n\n" +
                "지금 업데이트를 설치할까요?\n\n" +
                "먼저 연결된 NAS 드라이브를 안전하게 해제합니다. " +
                "미완료 업로드나 열려 있는 파일로 해제가 실패하면 설치도 취소됩니다.\n" +
                "프로그램 설정은 유지되며 업데이트 후 자동 재실행됩니다.",
                "FileHub AIO PC 업데이트 설치",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question) ==
                DialogResult.Yes)
            {
                checkingPcUpdate = false;
                await ExitSafely(staged);
            }
        }
        catch (OperationCanceledException)
        {
            status.Text = "PC 업데이트 다운로드 취소됨";
        }
        catch (Exception ex)
        {
            AppStore.Log("PC_UPDATE_FAILED " + ex.GetType().Name +
                " " + ex.Message);
            if (!IsDisposed)
                MessageBox.Show(this, "업데이트를 완료하지 못했습니다.\n\n" +
                    ex.Message + "\n\n기존 프로그램은 유지됩니다.",
                    "PC 업데이트 실패", MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
        }
        finally { checkingPcUpdate = false; }
    }

    private sealed class PcUpdateDownloadForm : Form
    {
        private readonly CancellationTokenSource cts = new();
        private readonly Label info = new();
        private readonly ProgressBar bar = new();
        internal CancellationToken Cancellation => cts.Token;
        internal IProgress<(long, long)> Progress { get; }

        internal PcUpdateDownloadForm(Version version)
        {
            Text = "PC 업데이트 다운로드";
            ClientSize = new Size(410, 165);
            StartPosition = FormStartPosition.CenterParent;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Malgun Gothic", 9);
            BackColor = ModernTheme.Background;
            ForeColor = ModernTheme.Text;
            ModernTheme.EnableDarkTitleBar(this);
            info.Text = $"FileHub AIO PC {version} 다운로드 준비 중...";
            info.SetBounds(18, 21, 372, 26);
            info.ForeColor = ModernTheme.Text;
            Controls.Add(info);
            bar.Minimum = 0;
            bar.Maximum = 100;
            bar.SetBounds(18, 62, 374, 24);
            Controls.Add(bar);
            var cancel = ModernTheme.Button("취소", 98,
                () => { cts.Cancel(); }, height: 34);
            cancel.SetBounds(294, 108, 98, 34);
            Controls.Add(cancel);
            FormClosing += (_, _) => cts.Cancel();
            Progress = new Progress<(long Bytes, long Total)>(p =>
            {
                if (IsDisposed) return;
                int percent = p.Total > 0 ?
                    (int)Math.Clamp(p.Bytes * 100 / p.Total, 0, 100) : 0;
                bar.Value = percent;
                info.Text = $"다운로드 {percent}% · " +
                    $"{p.Bytes / 1048576.0:F1} / {p.Total / 1048576.0:F1} MB";
            });
        }
    }
}
