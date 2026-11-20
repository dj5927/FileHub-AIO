using System.IO.Pipes;
using Fsp;

namespace NASMount.LockBridge;

/// <summary>
/// The FileHub-owned, one-WinFsp WebDAV mount. Starts from the normal GUI
/// using the saved profile ID and an inherited credential; unlike the lab
/// executable it exposes PING/STATUS/QUIT on the existing ownership pipe.
/// Never force-unmount an open workbook or a pending recovery journal.
/// </summary>
internal static class ManagedDirectWebDavHost
{
    internal static int Run(Guid id, string target, bool writable,
        NasProfile? labProfile = null)
    {
        if (target.Length != 2 || target[1] != ':' ||
            target[0] < 'D' || target[0] > 'Z')
            throw new ArgumentException("Invalid FileHub WebDAV drive letter");
        var profile = labProfile ?? AppStore.Load().Profiles.SingleOrDefault(p => p.Id == id)
            ?? throw new ArgumentException("WebDAV profile not found");
        if (labProfile is not null && (labProfile.Host != "127.0.0.1" ||
            !labProfile.WebDavUseHttp || !labProfile.IsAnonymous))
            throw new UnauthorizedAccessException("Managed lab is strictly loopback-only");
        if (profile.Protocol != "WebDAV" ||
            !profile.Letter.Equals(target[..1], StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("WebDAV profile drive mismatch");
        string? inherited = Environment.GetEnvironmentVariable("NASMOUNT_BRIDGE_PASSWORD");
        Environment.SetEnvironmentVariable("NASMOUNT_BRIDGE_PASSWORD", null);
        string password = profile.IsAnonymous ? "" :
            !string.IsNullOrEmpty(inherited) ? inherited :
            WindowsCredentials.Read(id) ??
            throw new UnauthorizedAccessException("WebDAV password was not supplied");

        using var backend = new DirectWebDavReadOnlyBackend(profile, password);
        // Verify the real profile's exact remote root, not a guessed vendor
        // URL, before occupying the Windows drive letter.
        _ = backend.List("/");
        string journalRoot = labProfile is null
            ? Path.Combine(AppStore.DirectoryPath, "direct-journals", id.ToString("N"))
            : Path.Combine(Path.GetTempPath(), "FileHubDirectManagedLab_" + id.ToString("N"));
        using var store = writable ? new DirectWebDavWriteStore(profile,
            password, backend, journalRoot) : null;
        LeaseCoordinator? leases = null;
        if (writable)
        {
            store!.RequireVerifiedConditionalWrites();
            store.RequireExclusiveLockDirectory();
            leases = new LeaseCoordinator(profile, password,
                TimeSpan.FromSeconds(5), uploadSafe: () => true);
            // Automatic crash replay is allowed only with an acquired Office
            // lease and the unchanged strong remote ETag. Conflicts remain
            // on disk for explicit user review, never silently discarded.
            store.RecoverPending(leases);
        }
        using DirectWebDavReadOnlyFileSystem provider = writable
            ? new DirectWebDavWritableFileSystem(backend, store!,
                Path.GetTempPath(), leases)
            : new DirectWebDavReadOnlyFileSystem(backend, Path.GetTempPath());
        using var fs = new FileSystemHost(provider);
        fs.FileSystemName = "NTFS";
        if (!profile.UseFixedDriveMode)
            fs.Prefix = MountPresentation.WinFspPrefix(profile);
        int mounted = fs.Mount(target, null, true);
        if (mounted < 0)
            throw new IOException("Direct WinFsp mount failed 0x" + mounted.ToString("X8"));
        provider.StartWatching(fs);
        Console.WriteLine("DIRECT_WEBDAV_MANAGED_READY letter=" + target +
            " mode=" + (writable ? "write-safe" : "read-only") +
            " layers=1 rclone=false");
        Console.Out.Flush();

        // One named pipe per saved profile. Existing BridgeOwnership probes
        // and the application GUI can reuse the same owner identity.
        string pipeName = "NASMountBridge_" + id.ToString("N");
        while (true)
        {
            using var pipe = new NamedPipeServerStream(pipeName, PipeDirection.InOut,
                1, PipeTransmissionMode.Byte, PipeOptions.None);
            pipe.WaitForConnection();
            using var reader = new StreamReader(pipe, leaveOpen: true);
            using var writer = new StreamWriter(pipe, leaveOpen: true) { AutoFlush = true };
            string command = reader.ReadLine() ?? "";
            int open = provider.ActiveHandles +
                (provider is DirectWebDavWritableFileSystem rw ? rw.ActiveWriteHandles : 0);
            bool safe = provider is DirectWebDavWritableFileSystem writableFs
                ? writableFs.CanShutdown
                : true;
            if (command is "PING" or "STATUS")
            {
                writer.WriteLine("READY " + open +
                    " writers=" + (provider is DirectWebDavWritableFileSystem wr
                        ? wr.ActiveWriteHandles : 0) +
                    " recovery=" + (store?.PendingRecovery().Length ?? 0) +
                    " lease=" + (leases?.ActiveHandles ?? 0) +
                    " " + (safe ? "SAFE" : "BUSY"));
                continue;
            }
            if (command == "QUIT")
            {
                if (!safe)
                {
                    writer.WriteLine("OPEN_OR_RECOVERY_PENDING " + open);
                    continue;
                }
                writer.WriteLine("OK");
                break;
            }
            writer.WriteLine("UNKNOWN");
        }
        Console.WriteLine("DIRECT_WEBDAV_MANAGED_SAFE_UNMOUNT letter=" + target);
        Console.Out.Flush();
        return 0;
    }
}
