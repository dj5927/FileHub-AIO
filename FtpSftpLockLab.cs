namespace NASMount;

/// <summary>
/// Offline protocol integration lab. Run ONLY against explicitly started
/// local loopback rclone FTP/SFTP servers, never an end-user NAS.
/// </summary>
internal static class FtpSftpLockLab
{
    internal static void RunNonExclusiveServerRejection()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_FTP_SFTP_LOCK_LAB") != "1")
            throw new InvalidOperationException("Nonexclusive FTP/SFTP lab not enabled.");
        foreach (var protocol in new[] { "FTP", "SFTP" })
        {
            var p = new NasProfile {
                Protocol = protocol, Host = "127.0.0.1",
                Port = protocol == "FTP" ? 21944 : 21945,
                Username = "naslab", Share = "", ExplicitTls = false,
                UseLockBridge = true
            };
            if (protocol == "SFTP")
                SftpHostKeyTrust.Approve(p, SftpHostKeyTrust.ObserveAsync(p)
                    .GetAwaiter().GetResult());
            bool refused = false;
            var doc = "NonExclusive_" + protocol + "_" +
                Guid.NewGuid().ToString("N")[..9] + ".xlsx";
            try
            {
                var lease = CooperativeLocks.Acquire(p, "naslab-pass", doc);
                CooperativeLocks.Release(p, "naslab-pass", lease);
            }
            catch (IOException ex) when (
                ex.Message.Contains("배타적", StringComparison.Ordinal))
            {
                refused = true;
            }
            Assert(refused, protocol + " cannot grant a lock on idempotent mkdir server");
            Console.WriteLine("NONEXCLUSIVE_" + protocol +
                "_LOCK_FAIL_CLOSED_PASS");
        }
    }

    internal static void Run()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_FTP_SFTP_LOCK_LAB") != "1")
            throw new InvalidOperationException("Local FTP/SFTP lock lab is not enabled.");
        string root = @"E:\NASMount_Releases\QA_lock_1344\server";
        if (!Directory.Exists(root))
            throw new DirectoryNotFoundException(root);

        foreach (var protocol in new[] { "FTP", "SFTP" })
        {
            int port = protocol == "FTP" ? 21944 : 21945;
            var profile = new NasProfile {
                Protocol = protocol, Host = "127.0.0.1", Port = port,
                Username = "naslab", DisplayName = protocol + "-Lab",
                Share = "", Letter = "Q", UseLockBridge = true,
                RememberPassword = false, ExplicitTls = false
            };
            if (protocol == "SFTP")
            {
                var observed = SftpHostKeyTrust.ObserveAsync(profile)
                    .GetAwaiter().GetResult();
                SftpHostKeyTrust.Approve(profile, observed);
            }
            string filename = protocol + "_Demo_1344_" +
                Guid.NewGuid().ToString("N")[..8] + ".xlsx";
            string marker = OfficeUseMarker.RelativePath(filename);
            string lockdir = filename + ".nasmount.lock";
            CooperativeLocks.Lease? lease = null;
            try
            {
                lease = CooperativeLocks.Acquire(profile, "naslab-pass", filename);
                Assert(lease.VisibleMarker == marker,
                    protocol + " creates a genuine Excel status marker");
                string markerFile = Path.Combine(root, marker);
                Assert(File.Exists(markerFile), protocol + " marker visible on remote");
                byte[] markerBytes = File.ReadAllBytes(markerFile);
                Assert(OfficeUseMarker.ContentBelongsTo(markerBytes, lease),
                    protocol + " marker has correct owner token");
                Assert(OfficeMarkerWorkbook.ReadLeaseId(markerBytes) == lease.Id,
                    protocol + " marker is readable OOXML Excel");
                Assert(File.Exists(Path.Combine(root, lockdir, "owner.json")),
                    protocol + " lease owner exists");
                bool blocked = false;
                try
                {
                    var other = CooperativeLocks.Acquire(profile, "naslab-pass", filename);
                    try { CooperativeLocks.Release(profile, "naslab-pass", other); }
                    finally { throw new InvalidOperationException(
                        protocol + " second writer acquired the lock"); }
                }
                catch (IOException) { blocked = true; }
                catch (Renci.SshNet.Common.SshException) { blocked = true; }
                Assert(blocked, protocol + " concurrent writer blocked");
                Console.WriteLine("LOCK_LAB_" + protocol +
                    "_OWNER_MARKER_AND_CONFLICT_PASS");
            }
            finally
            {
                if (lease is not null)
                    CooperativeLocks.Release(profile, "naslab-pass", lease);
            }
            Assert(!File.Exists(Path.Combine(root, marker)),
                protocol + " owner marker removed immediately on release");
            Assert(!Directory.Exists(Path.Combine(root, lockdir)),
                protocol + " actual lock directory removed");
            Console.WriteLine("LOCK_LAB_" + protocol +
                "_MARKER_CREATE_READ_DELETE_PASS");
        }
    }

    private static void Assert(bool condition, string what)
    {
        if (!condition)
            throw new InvalidOperationException("LOCK_LAB_FAILED " + what);
    }
}
