namespace NASMount;

/// <summary>
/// Keep WebDAV's full VFS write cache and Office atomic rename semantics, but
/// avoid its unbounded/default initial read chunk when Windows Explorer or
/// CreateProcess rapidly opens EXE/DLL/metadata. In particular, the network
/// WinFsp LockBridge used to miss the office-read tuning entirely.
/// </summary>
internal static class WebDavVfsReadPolicy
{
    internal static void AppendArgs(ICollection<string> args, NasProfile profile,
        bool fastRead, bool officeRead)
    {
        if (profile.Protocol != "WebDAV" || fastRead) return;
        // Preserve the existing opt-in Office tuning, while applying it to
        // LockBridge's read path too (OfficeReadTuning is disabled there).
        if (!officeRead && !profile.UseLockBridge) return;
        args.Add("--buffer-size"); args.Add("8Mi");
        args.Add("--vfs-read-ahead"); args.Add("8Mi");
        args.Add("--vfs-read-chunk-size"); args.Add("8Mi");
        args.Add("--vfs-read-chunk-size-limit"); args.Add("32Mi");
    }

    internal static void PolicyTest()
    {
        var expected = new[] {
            "--buffer-size", "8Mi", "--vfs-read-ahead", "8Mi",
            "--vfs-read-chunk-size", "8Mi",
            "--vfs-read-chunk-size-limit", "32Mi"
        };
        var bridge = new NasProfile { Protocol = "WebDAV", UseLockBridge = true };
        var args = new List<string>();
        AppendArgs(args, bridge, false, false);
        if (!args.SequenceEqual(expected))
            throw new InvalidOperationException("WinFsp LockBridge WebDAV read path not tuned");
        args.Clear();
        AppendArgs(args, new NasProfile { Protocol = "WebDAV" }, false, true);
        if (!args.SequenceEqual(expected))
            throw new InvalidOperationException("Original WebDAV Office tuning changed");
        foreach (var p in new[] {
            new NasProfile { Protocol = "WebDAV" },
            new NasProfile { Protocol = "WebDAV", UseLockBridge = true, FastReadOnly = true },
            new NasProfile { Protocol = "GoogleDrive" },
            new NasProfile { Protocol = "TeraBox" },
            new NasProfile { Protocol = "SFTP" }
        })
        {
            args.Clear();
            AppendArgs(args, p, p.FastReadOnly, false);
            if (args.Count != 0)
                throw new InvalidOperationException("Unrelated VFS provider mode changed");
        }
        Console.WriteLine("WEBDAV_LOCKBRIDGE_FAST_EXE_READ_AND_OFFICE_CACHE_POLICY_PASS");
    }
}
