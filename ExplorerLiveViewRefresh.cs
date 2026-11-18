using System.Runtime.InteropServices;

namespace NASMount;

/// <summary>
/// Refresh already-open Windows Explorer "This PC" browser windows/tabs.
/// SHChangeNotify(SHCNE_UPDATEDIR, CSIDL_DRIVES) updates Shell change
/// subscribers, but a Windows 11 Explorer window may continue showing an
/// obsolete network-place tile until its IWebBrowser2 view is explicitly
/// refreshed. This sends the equivalent of F5 to THAT window only.
/// It NEVER closes an Explorer window or restarts explorer.exe, WinFsp or rclone.
/// </summary>
internal static class ExplorerLiveViewRefresh
{
    private const string ThisPcPidl =
        "::{20D04FE0-3AEA-1069-A2D8-08002B30309D}";
    internal static bool IsThisPcLocation(string? path, string? url, string? title)
    {
        string p = (path ?? "").Trim().TrimEnd('\\');
        string u = (url ?? "").Trim();
        if (p.Equals(ThisPcPidl, StringComparison.OrdinalIgnoreCase) ||
            p.Equals("shell:MyComputerFolder", StringComparison.OrdinalIgnoreCase) ||
            p.Equals("MyComputerFolder", StringComparison.OrdinalIgnoreCase))
            return true;
        if (u.Equals("shell:MyComputerFolder", StringComparison.OrdinalIgnoreCase) ||
            u.Equals(ThisPcPidl, StringComparison.OrdinalIgnoreCase))
            return true;
        // Windows 11 Explorer tabs may omit the virtual-folder URL. Accept a
        // localized title ONLY when there is no conflicting filesystem path.
        return p.Length == 0 && u.Length == 0 &&
            ((title ?? "").Equals("내 PC", StringComparison.OrdinalIgnoreCase) ||
             (title ?? "").Equals("This PC", StringComparison.OrdinalIgnoreCase));
    }

    internal sealed record Result(int Matched, int Refreshed, int Failed)
    {
        internal bool AnySucceeded => Refreshed > 0;
    }

    internal static Result RefreshThisPc(string cause)
    {
        if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA)
        {
            // COM ShellWindows and the WinForms main message loop must be
            // used on the UI STA, never the rclone upload/bridge worker.
            AppStore.Log("EXPLORER_LIVE_VIEW_REFRESH_SKIPPED_NON_STA " + cause);
            return new Result(0, 0, 1);
        }
        int matched = 0, refreshed = 0, failed = 0;
        try
        {
            // Shell.Application.Windows() intermittently throws a COM
            // class-factory error on Windows 11 even while Explorer is alive.
            // Instantiate the registered ShellWindows collection directly.
            // This also avoids creating an unused Shell.Application wrapper.
            Type? windowsType = Type.GetTypeFromCLSID(
                new Guid("9BA05972-F6A8-11CF-A442-00A0C90A8F39"));
            if (windowsType is null)
                return new Result(0, 0, 1);
            dynamic? windows = Activator.CreateInstance(windowsType);
            if (windows is null)
                return new Result(0, 0, 1);
            try
            {
                int count = (int)windows.Count;
                for (int i = 0; i < count; i++)
                {
                    try
                    {
                        dynamic? window = windows.Item(i);
                        if (window is null) continue;
                        // Windows 11 localizations have "내 PC", "This PC",
                        // etc. Use the canonical folder GUID, not the label.
                        string path = (string?)window.Document?.Folder?.Self?.Path ?? "";
                        string url = (string?)window.LocationURL ?? "";
                        string title = (string?)window.LocationName ?? "";
                        if (!IsThisPcLocation(path, url, title)) continue;
                        string? fullName = (string?)window.FullName;
                        if (string.IsNullOrWhiteSpace(fullName) ||
                            !Path.GetFileName(fullName).Equals(
                                "explorer.exe", StringComparison.OrdinalIgnoreCase))
                            continue;
                        matched++;
                        // This is the browser's actual view Refresh/F5,
                        // NOT shell namespace cache invalidation and NOT
                        // an explorer.exe process restart.
                        window.Refresh();
                        refreshed++;
                        AppStore.Log("EXPLORER_THIS_PC_WINDOW_F5 " + cause +
                            " hwnd=" + window.HWND);
                    }
                    catch (Exception ex)
                    {
                        failed++;
                        AppStore.Log("EXPLORER_THIS_PC_WINDOW_F5_WARNING " +
                            cause + " " + ex.Message);
                    }
                }
            }
            finally
            {
                if (Marshal.IsComObject(windows)) Marshal.ReleaseComObject(windows);
            }
        }
        catch (Exception ex)
        {
            failed++;
            AppStore.Log("EXPLORER_THIS_PC_REFRESH_EXCEPTION " + cause +
                " " + ex.Message);
        }
        AppStore.Log("EXPLORER_THIS_PC_REFRESH_SUMMARY " + cause +
            " matched=" + matched + " refreshed=" + refreshed +
            " failed=" + failed);
        return new Result(matched, refreshed, failed);
    }

    internal static void PolicyTest()
    {
        if (!ThisPcPidl.Equals(
                "::{20D04FE0-3AEA-1069-A2D8-08002B30309D}",
                StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("This PC shell GUID mismatch");
        if (!IsThisPcLocation(ThisPcPidl, "", "") ||
            !IsThisPcLocation("shell:MyComputerFolder", "", "") ||
            !IsThisPcLocation("", "", "내 PC") ||
            IsThisPcLocation(@"C:\Users", "", "내 PC") ||
            IsThisPcLocation(@"C:\", "", "System (C:)"))
            throw new InvalidOperationException("This PC detection policy regression");
        Console.WriteLine("EXPLORER_THIS_PC_LIVE_F5_OPT_IN_VIEW_ONLY_POLICY_PASS");
    }
}
