using System.Diagnostics;

namespace NASMount;

/// <summary>
/// The packaged WinFsp driver is tested and hash-pinned. Its version does
/// not float to "latest" during builds, which would defeat OTA verification.
/// The user must approve driver upgrades and disconnect owned mounts first.
/// </summary>
internal static class WinFspUpgradePolicy
{
    // Last official non-beta WinFsp release. Do not float to a prerelease:
    // RaiDrive also uses this system-wide file-system driver.
    internal static readonly Version PackageVersion = new(2, 1, 25156);
    internal const string InstallerFileName = "winfsp-2.1.25156.msi";
    internal const string InstallerSha256 =
        "073A70E00F77423E34BED98B86E600DEF93393BA5822204FAC57A29324DB9F7A";

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

    internal static bool IsKnownBeta(Version? installed) =>
        installed is not null && installed.Major == 2 && installed.Minor == 2;

    internal static bool UpgradeAvailable(Version? installed) =>
        installed is null || installed < PackageVersion;

    // Compare the actual installed DLL build. A calendar date is only a
    // fallback for machines where the version cannot be read.
    internal static string FooterNotice(bool installed, Version? installedVersion)
    {
        if (!installed)
            return "● WinFsp 미설치 · 설정에서 WinFsp 확인/설치";
        if (installedVersion is null)
            return "● WinFsp 버전 확인 불가 · 설정에서 정식판 설치 상태 확인";
        if (IsKnownBeta(installedVersion))
            return "● WinFsp 베타 2.2 감지 · RaiDrive 보호: 수동 복구 필요";
        if (!UpgradeAvailable(installedVersion))
            return string.Empty;
        return $"● WinFsp {installedVersion} · 설정에서 정식판 설치 권장";
    }

    internal static void PolicyTest()
    {
        if (!UpgradeAvailable(null) ||
            !UpgradeAvailable(new Version(2, 1, 24255)) ||
            UpgradeAvailable(new Version(2, 1, 25156)) ||
            UpgradeAvailable(new Version(2, 2, 26286)) ||
            UpgradeAvailable(new Version(2, 3, 0)) ||
            InstallerFileName != $"winfsp-{PackageVersion}.msi" ||
            InstallerSha256.Length != 64 ||
            !InstallerSha256.All(Uri.IsHexDigit))
            throw new InvalidOperationException("WINFSP_DRIVER_UPGRADE_POLICY_FAILED");
        if (!IsKnownBeta(new Version(2, 2, 26215)) ||
            !IsKnownBeta(new Version(2, 2, 26286)) ||
            IsKnownBeta(new Version(2, 1, 25156)) ||
            !FooterNotice(true, new Version(2, 2, 26215)).Contains("수동 복구 필요") ||
            !FooterNotice(true, new Version(2, 2, 26286)).Contains("RaiDrive") ||
            FooterNotice(true, new Version(2, 1, 25156)) != string.Empty ||
            FooterNotice(true, new Version(2, 3, 0)) != string.Empty ||
            !FooterNotice(true, new Version(2, 1, 24255)).Contains("정식판 설치 권장") ||
            !FooterNotice(true, null).Contains("버전 확인 불가") ||
            !FooterNotice(false, null).Contains("미설치"))
            throw new InvalidOperationException("WINFSP_FOOTER_NOTICE_POLICY_FAILED");
        Console.WriteLine("WINFSP_DRIVER_UPGRADE_POLICY_PASS");
    }
}
