namespace NASMount;

/// <summary>
/// Regression guard for real WinFsp WebDAV names on a second PC. WinFsp
/// may register the live UNC using only the FIRST character of a profile
/// name (observed on the user's Google drive). Such prefixes must be
/// accepted ONLY under the exact managed GUID/session server. Foreign
/// sessions and unrelated profile names must still be rejected.
/// </summary>
internal static class ShortNetworkLabelPolicy
{
    internal static void SelfTest()
    {
        const string wintec = "\uC6D0\uD14D";
        const string oreum = "\uC624\uB984";
        const string munSuNas = "\uBB38\uC218\uB098\uC2A4";
        static void Check(bool passed, string message)
        {
            if (!passed) throw new InvalidOperationException(message);
        }
        Check(ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-46095be6#" + wintec, "NASMount-46095be6", wintec) &&
              ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-32d555d9#" + oreum, "NASMount-32d555d9", oreum) &&
              ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-87654321#" + munSuNas,
                "NASMount-87654321", munSuNas),
            "Exact 2-character and long Korean WebDAV share names must match");
        Check(ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-abcdef12#X", "NASMount-abcdef12", "X"),
            "Exact 1-character share name must match");
        Check(ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-46095be6#" + wintec[..1],
                "NASMount-46095be6", wintec) &&
              ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-abcdef12#Go", "NASMount-abcdef12", "Google Drive"),
            "Live owned one-character and two-character prefixes must match");
        Check(
              !ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-32d555d9#" + oreum,
                "NASMount-46095be6", oreum) &&
              !ExplorerDriveIcon.IsCurrentMountPointKey(
                "##NASMount-46095be6#" + wintec,
                "NASMount-46095be6", oreum) &&
              !ExplorerDriveIcon.IsCurrentMountPointKey(
                "##RaiDrive-Foreign#Go", "NASMount-abcdef12", "Google Drive"),
            "Foreign session, other profile, or vendor must still be rejected");
        Console.WriteLine("SHORT_KOREAN_WEBDAV_NETWORK_LABEL_POLICY_PASS");
    }
}
