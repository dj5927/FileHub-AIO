using System.Diagnostics;

namespace NASMount;

/// <summary>
/// The packaged WinFsp driver is tested and hash-pinned. Its version does
/// not float to "latest" during builds, which would defeat OTA verification.
/// The user must approve driver upgrades and disconnect owned mounts first.
/// </summary>
internal static class WinFspUpgradePolicy
{
    internal static readonly Version PackageVersion = new(2, 2, 26286);
    internal const string InstallerFileName = "winfsp-2.2.26286.msi";
    internal const string InstallerSha256 =
        "293FFB9BCC0B625C85079FE85FA77F8FE98170ABA51A02AC9C83D58F6C3B0E2F";

    internal static string? InstalledDllPath()
    {
        foreach (string root in new[] {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles)
        })
        {
            string candidate = Path.Combine(root, "WinFsp", "bin", "winfsp-x64.dll");
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    }

    internal static Version? InstalledVersion()
    {
        string? dll = InstalledDllPath();
        if (dll is null) return null;
        try
        {
            var info = FileVersionInfo.GetVersionInfo(dll);
            if (info.FileMajorPart <= 0 || info.FileBuildPart < 0) return null;
            return new Version(info.FileMajorPart, info.FileMinorPart, info.FileBuildPart);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
            or ArgumentException)
        {
            AppStore.Log("WINFSP_VERSION_QUERY_UNAVAILABLE " + ex.GetType().Name);
            return null;
        }
    }

    internal static bool UpgradeAvailable(Version? installed) =>
        installed is null || installed < PackageVersion;

    internal static void PolicyTest()
    {
        if (!UpgradeAvailable(null) ||
            !UpgradeAvailable(new Version(2, 2, 26215)) ||
            UpgradeAvailable(new Version(2, 2, 26286)) ||
            UpgradeAvailable(new Version(2, 3, 0)) ||
            InstallerFileName != $"winfsp-{PackageVersion}.msi" ||
            InstallerSha256.Length != 64 ||
            !InstallerSha256.All(Uri.IsHexDigit))
            throw new InvalidOperationException("WINFSP_DRIVER_UPGRADE_POLICY_FAILED");
        Console.WriteLine("WINFSP_DRIVER_UPGRADE_POLICY_PASS");
    }
}
