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

    // Compare the actual installed DLL build. A calendar date is only a
    // fallback for machines where the version cannot be read.
    internal static string FooterNotice(bool installed, Version? installedVersion)
    {
        if (!installed)
            return "● WinFsp 미설치 · 설정에서 WinFsp 확인/설치";
        if (installedVersion is null)
            return "● WinFsp 버전 확인 불가 · 11/18 이전 설치자는 수동 업데이트 필수";
        if (!UpgradeAvailable(installedVersion))
            return string.Empty;
        return $"● WinFsp {installedVersion} · 설정에서 WinFsp 확인/설치 수동 업데이트 필수";
    }

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
        if (!FooterNotice(true, new Version(2, 2, 26215)).Contains("수동 업데이트 필수") ||
            !FooterNotice(true, new Version(2, 2, 26215)).Contains("2.2.26215") ||
            FooterNotice(true, new Version(2, 2, 26286)) != string.Empty ||
            FooterNotice(true, new Version(2, 3, 0)) != string.Empty ||
            !FooterNotice(true, null).Contains("11/18 이전 설치자") ||
            !FooterNotice(false, null).Contains("미설치"))
            throw new InvalidOperationException("WINFSP_FOOTER_NOTICE_POLICY_FAILED");
        Console.WriteLine("WINFSP_DRIVER_UPGRADE_POLICY_PASS");
    }
}
