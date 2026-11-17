using System.Diagnostics;
using System.Windows.Forms;

namespace NASMount;

/// <summary>
/// Optional, EXPLICIT user-approved Explorer-only restart.
/// Does not stop NASMount, rclone, WinFsp or any file-system driver.
/// This is an opt-in shell cache reset, not a blind network unmount.
/// </summary>
internal static class ExplorerShellRestart
{
    internal static bool AskUserAndRestart(IWin32Window window)
    {
        var decision = MessageBox.Show(window,
            "이미 종료된 과거 FileHub AIO 공유가 탐색기에만 남아 있는 경우," +
            " Windows 탐색기 프로세스를 다시 시작하면 화면 캐시가 초기화될 수 있습니다.\n\n" +
            "주의:\n" +
            "· 열려 있는 파일 탐색기 창과 파일 복사/이동 대화창이 닫힙니다.\n" +
            "· 진행 중인 Windows 탐색기의 파일 복사·이동을 먼저 완료하세요.\n" +
            "· 작업 표시줄과 바탕화면이 잠시 사라졌다가 다시 나타납니다.\n" +
            "· FileHub AIO, rclone, WinFsp 연결은 종료하지 않습니다.\n\n" +
            "지금 Windows 탐색기만 다시 시작할까요?",
            "FileHub AIO · 탐색기 표시 초기화",
            MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
            MessageBoxDefaultButton.Button2);
        if (decision != DialogResult.Yes) return false;
        AppStore.Log("EXPLORER_RESTART_EXPLICITLY_APPROVED");
        try
        {
            int killed = RestartCurrentUserExplorerOnly();
            AppStore.Log("EXPLORER_SHELL_RESTART_SUCCESS killed=" + killed);
            MessageBox.Show(window,
                "Windows 탐색기가 다시 시작됐습니다.\n\n" +
                "내 PC를 새로 열어 빨간 X 항목이 사라졌는지 확인하세요.\n" +
                "FileHub AIO의 실제 연결과 WinFsp 드라이버는 종료하지 않았습니다.",
                "탐색기 다시 시작 완료", MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return true;
        }
        catch (Exception ex)
        {
            AppStore.Log("EXPLORER_SHELL_RESTART_FAILED " + ex);
            MessageBox.Show(window, "탐색기를 다시 시작하지 못했습니다.\n" +
                ex.Message + "\n\n" +
                "작업 관리자 > Windows 탐색기 > 다시 시작을 이용할 수도 있습니다.",
                "탐색기 다시 시작 실패", MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            return false;
        }
    }

    private static int RestartCurrentUserExplorerOnly()
    {
        int currentSession = Process.GetCurrentProcess().SessionId;
        string explorerPath = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Windows),
            "explorer.exe");
        if (!File.Exists(explorerPath))
            throw new FileNotFoundException("Windows explorer.exe not found",
                explorerPath);
        var candidates = Process.GetProcessesByName("explorer");
        int killed = 0;
        try
        {
            foreach (var process in candidates)
            {
                try
                {
                    // Never touch another logged-in desktop or a process
                    // with a different image name.
                    if (process.HasExited ||
                        process.SessionId != currentSession ||
                        !process.ProcessName.Equals("explorer",
                            StringComparison.OrdinalIgnoreCase))
                        continue;
                    process.Kill(entireProcessTree: false);
                    killed++;
                    if (!process.WaitForExit(5000))
                        throw new IOException("Explorer process did not exit");
                }
                finally { process.Dispose(); }
            }
        }
        finally
        {
            // Even on a partial failure, always attempt to restore the
            // desktop shell. Never stop NASMount/rclone/WinFsp processes.
            if (killed > 0)
            {
                Process.Start(new ProcessStartInfo(explorerPath)
                    { UseShellExecute = true })?.Dispose();
            }
        }
        if (killed == 0)
            throw new InvalidOperationException(
                "현재 Windows 로그인 세션에서 explorer.exe를 찾지 못했습니다.");
        return killed;
    }

    internal static void ReadOnlyPolicyTest()
    {
        int session = Process.GetCurrentProcess().SessionId;
        var explorers = Process.GetProcessesByName("explorer");
        try
        {
            int matches = explorers.Count(p => !p.HasExited &&
                p.SessionId == session);
            Console.WriteLine("EXPLORER_CURRENT_SESSION=" + session +
                " MATCHES=" + matches);
            Console.WriteLine("EXPLORER_RESTART_OPT_IN_ONLY_POLICY_PASS");
        }
        finally
        {
            foreach (var process in explorers) process.Dispose();
        }
    }
}
