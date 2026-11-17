using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Exactly one PUBLIC drive letter per saved profile. A lock-protected rclone
/// backend is mounted to a private NONEXISTENT directory under LocalAppData,
/// never to a second drive letter. WinFsp requires the directory parent to
/// exist and the mountpoint leaf NOT to exist before mounting.
/// </summary>
internal static class MountPresentation
{
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
        "\\NASMount-" + p.Id.ToString("N")[..8] + "\\" + SafeLabel(p.DisplayName);

    // rclone --volname accepts standard TWO-leading-backslash UNC format.
    internal static string RcloneNetworkVolume(NasProfile p) => "\\" + WinFspPrefix(p);
}
