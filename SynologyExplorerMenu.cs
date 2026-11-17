using Microsoft.Win32;

namespace NASMount;

// Classic Win32 command for unpackaged ZIP deployments. Windows 11 exposes
// this via "Show more options"; never claim to be a tier-one IExplorerCommand.
// HKCU only: no elevated rights, Explorer restart, global menu hacks or COM DLL.
internal static class SynologyExplorerMenu
{
    private const string FileKey = @"Software\Classes\*\shell\FileHubAIO.SynologyShare";
    private const string FolderKey = @"Software\Classes\Directory\shell\FileHubAIO.SynologyShare";

    internal static string BuildCommand(string exe) =>
        "\"" + exe.Replace("\"", "") + "\" --synology-share \"%1\"";

    internal static bool IsRegistered()
    {
        using var key = Registry.CurrentUser.OpenSubKey(FileKey + @"\command");
        return key?.GetValue("") is string command &&
            command.Contains("--synology-share", StringComparison.Ordinal);
    }

    internal static void Register()
    {
        string exe = Environment.ProcessPath ??
            throw new IOException("현재 실행 중인 프로그램 경로를 알 수 없습니다.");
        if (!File.Exists(exe) ||
            !Path.GetFileName(exe).Equals("FileHubAIO.exe", StringComparison.OrdinalIgnoreCase))
            throw new IOException("설치된 FileHubAIO.exe에서만 탐색기 메뉴를 등록할 수 있습니다.");
        foreach (string path in new[] { FileKey, FolderKey })
        {
            using var key = Registry.CurrentUser.CreateSubKey(path, writable: true)
                ?? throw new IOException("탐색기 메뉴 레지스트리를 열지 못했습니다.");
            key.SetValue("MUIVerb", "FileHub AIO - Synology 공유 링크 생성");
            key.SetValue("Icon", "\"" + exe + "\"");
            using var command = key.CreateSubKey("command", writable: true)
                ?? throw new IOException("탐색기 명령을 등록하지 못했습니다.");
            command.SetValue("", BuildCommand(exe), RegistryValueKind.String);
        }
    }

    internal static void Unregister()
    {
        using var root = Registry.CurrentUser.OpenSubKey(@"Software\Classes", writable: true);
        if (root is null) return;
        root.DeleteSubKeyTree(@"*\shell\FileHubAIO.SynologyShare", throwOnMissingSubKey: false);
        root.DeleteSubKeyTree(@"Directory\shell\FileHubAIO.SynologyShare", throwOnMissingSubKey: false);
    }
}
