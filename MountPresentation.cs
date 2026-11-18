using System.Text.RegularExpressions;
using System.Collections.Concurrent;

namespace NASMount;

/// <summary>
/// Exactly one PUBLIC drive letter per saved profile. A lock-protected rclone
/// backend is mounted to a private NONEXISTENT directory under LocalAppData,
/// never to a second drive letter. WinFsp requires the directory parent to
/// exist and the mountpoint leaf NOT to exist before mounting.
/// </summary>
internal static class MountPresentation
{
    // A previously crashed GUI can leave an older WinFsp network server
    // name registered even after its drive letter disappeared. Reusing
    // \\NASMount-<guid8>\Google Drive can then return 0x80070050 on every
    // *different* letter. Give each Google mount ATTEMPT its own UNC server;
    // it remains GUID-bound so cleanup never targets another product.
    private static readonly ConcurrentDictionary<Guid, string> CloudSessionNames = new();

    internal static void BeginNetworkMountAttempt(NasProfile p)
    {
        if (p.Protocol == "SMB" || p.UseLockBridge || p.UseFixedDriveMode) return;
        string server = "NASMount-" + p.Id.ToString("N")[..8] + "-" +
            Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(4))
                .ToLowerInvariant();
        CloudSessionNames[p.Id] = server;
    }

    internal static void ForgetNetworkMountAttempt(NasProfile p) =>
        CloudSessionNames.TryRemove(p.Id, out _);

    internal static string NetworkServerName(NasProfile p) =>
        CloudSessionNames.TryGetValue(p.Id, out var server)
            ? server : "NASMount-" + p.Id.ToString("N")[..8];

    internal static void GoogleSessionPolicyTest()
    {
        var google = new NasProfile {
            Id = Guid.Parse("12345678-90ab-cdef-1234-567890abcdef"),
            Protocol = "GoogleDrive", Letter = "O",
            DisplayName = "Google Drive"
        };
        string old = RcloneNetworkVolume(google);
        BeginNetworkMountAttempt(google);
        string first = RcloneNetworkVolume(google);
        BeginNetworkMountAttempt(google);
        string second = RcloneNetworkVolume(google);
        ForgetNetworkMountAttempt(google);
        if (first == second || old == first ||
            !first.StartsWith(@"\\NASMount-12345678-", StringComparison.Ordinal) ||
            !first.EndsWith(@"\Google Drive", StringComparison.Ordinal) ||
            !RcloneNetworkVolume(google).StartsWith(@"\\NASMount-12345678\",
                StringComparison.Ordinal))
            throw new InvalidOperationException(
                "Google WinFsp network names must be unique and profile-owned");
        var tera = new NasProfile {
            Id = Guid.Parse("aa123456-1111-2222-3333-444455556666"),
            Protocol = "TeraBox", Letter = "P", DisplayName = "TeraBox"
        };
        string oldTera = RcloneNetworkVolume(tera);
        BeginNetworkMountAttempt(tera);
        string newTera = RcloneNetworkVolume(tera);
        ForgetNetworkMountAttempt(tera);
        if (oldTera == newTera ||
            !newTera.StartsWith(@"\\NASMount-aa123456-", StringComparison.Ordinal))
            throw new InvalidOperationException(
                "TeraBox must not reuse a stale WinFsp network server");
        Console.WriteLine("GOOGLE_TERABOX_UNIQUE_WIN_FSP_UNC_SESSION_PASS");
    }
    internal static string BackendParent => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "NASMount", "private-mounts");

    internal static string NewBackendMountpoint(NasProfile p)
    {
        Directory.CreateDirectory(BackendParent);  // PARENT ONLY, never leaf.
        string path = Path.Combine(BackendParent,
            p.Id.ToString("N") + "_" + Guid.NewGuid().ToString("N")[..12]);
        if (Directory.Exists(path) || File.Exists(path))
            throw new IOException("Private rclone mountpoint already exists");
        return path;
    }

    internal static bool IsOurBackendMountpoint(NasProfile p, string path)
    {
        if (string.IsNullOrWhiteSpace(path)) return false;
        string parent = Path.GetFullPath(BackendParent).TrimEnd('\\') + "\\";
        string absolute = Path.GetFullPath(path).TrimEnd('\\');
        if (!absolute.StartsWith(parent, StringComparison.OrdinalIgnoreCase)) return false;
        string leaf = absolute[parent.Length..];
        return Regex.IsMatch(leaf,
            "^" + p.Id.ToString("N") + "_[0-9a-fA-F]{12}$", RegexOptions.CultureInvariant);
    }

    internal static string SafeLabel(string? displayName)
    {
        string result = (displayName ?? "").Trim();
        foreach (char bad in new[] { '\\', '/', ':', '*', '?', '"', '<', '>', '|' })
            result = result.Replace(bad, '-');
        result = new string(result.Where(x => !char.IsControl(x)).ToArray()).Trim(' ', '.');
        return string.IsNullOrEmpty(result) ? "FileHub AIO" : result[..Math.Min(36, result.Length)];
    }

    // WinFsp Prefix takes ONE leading backslash (unlike Windows UNC paths).
    // Use profile GUID in server part for uniqueness, real user name in share.
    internal static string WinFspPrefix(NasProfile p) =>
        "\\" + NetworkServerName(p) + "\\" + SafeLabel(p.DisplayName);

    // rclone --volname accepts standard TWO-leading-backslash UNC format.
    internal static string RcloneNetworkVolume(NasProfile p) => "\\" + WinFspPrefix(p);
}
