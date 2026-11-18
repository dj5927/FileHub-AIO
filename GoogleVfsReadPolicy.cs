namespace NASMount;

/// <summary>
/// Bounded first-read chunks and modest read-ahead for the Google full VFS
/// cache. This improves responsiveness where over-large initial requests
/// are the bottleneck, but cannot remove every remote API delay.
/// </summary>
internal static class GoogleVfsReadPolicy
{
    internal static void AppendArgs(ICollection<string> args,
        NasProfile p, bool fastRead)
    {
        if (p.Protocol != "GoogleDrive" || fastRead) return;
        args.Add("--buffer-size");
        args.Add("4Mi");
        args.Add("--vfs-read-ahead");
        args.Add("4Mi");
        args.Add("--vfs-read-chunk-size");
        args.Add("8Mi");
        args.Add("--vfs-read-chunk-size-limit");
        args.Add("64Mi");
        args.Add("--vfs-cache-max-age");
        args.Add("6h");
    }

    internal static void SelfTest()
    {
        var args = new List<string>();
        AppendArgs(args, new NasProfile { Protocol = "GoogleDrive" }, false);
        if (!args.SequenceEqual(new[]
        {
            "--buffer-size", "4Mi",
            "--vfs-read-ahead", "4Mi",
            "--vfs-read-chunk-size", "8Mi",
            "--vfs-read-chunk-size-limit", "64Mi",
            "--vfs-cache-max-age", "6h"
        }))
            throw new InvalidOperationException("Google VFS read policy mismatch");
        foreach (string name in new[]
                 { "TeraBox", "WebDAV", "SFTP", "FTP", "MyBox", "OneDrive", "SMB" })
        {
            var other = new List<string>();
            AppendArgs(other, new NasProfile { Protocol = name }, false);
            if (other.Count > 0)
                throw new InvalidOperationException(
                    "Google VFS tuning unexpectedly changed " + name);
        }
        var fast = new List<string>();
        AppendArgs(fast, new NasProfile { Protocol = "GoogleDrive" }, true);
        if (fast.Count > 0)
            throw new InvalidOperationException("Google fast-read mode was overwritten");
        Console.WriteLine("GOOGLE_VFS_BOUNDED_READ_CHUNKS_AND_LOCAL_CACHE_PASS");
    }
}
