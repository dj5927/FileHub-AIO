namespace NASMount;

/// <summary>
/// Offline tests: never reads the user's real NAS profiles, Windows vault,
/// network drives, or OAuth tokens.
/// </summary>
internal static class ProfileBackupLab
{
    internal static void Run()
    {
        var profile = new NasProfile {
            Id = Guid.NewGuid(),
            Protocol = "WebDAV", DisplayName = "백업 테스트",
            Host = "example.invalid", Share = "/demo", Letter = "Q",
            Username = "testuser", RememberPassword = true, AutoConnect = false,
            UseLockBridge = true, UseNativeWebDavLocks = true
        };
        var sample = new ProfileBackup.Snapshot {
            Config = new AppConfig {
                StartWithWindows = true,
                Profiles = new List<NasProfile> { profile }
            },
            Passwords = new Dictionary<Guid, string> { [profile.Id] = "not-a-real-credential" }
        };
        const string password = "Example-passphrase-123!";
        byte[] sealed1 = ProfileBackup.Pack(sample, password);
        byte[] sealed2 = ProfileBackup.Pack(sample, password);
        if (sealed1.AsSpan().SequenceEqual(sealed2))
            throw new InvalidOperationException("Encryption reused a salt/nonce");
        if (System.Text.Encoding.UTF8.GetString(sealed1)
            .Contains("not-a-real-credential", StringComparison.Ordinal))
            throw new InvalidOperationException("Secret leaked to plaintext");
        var recovered = ProfileBackup.Unpack(sealed1, password);
        if (recovered.Config.Profiles.Count != 1 ||
            recovered.Config.Profiles[0].Id != profile.Id ||
            !recovered.Config.Profiles[0].UseNativeWebDavLocks ||
            recovered.Passwords[profile.Id] != "not-a-real-credential")
            throw new InvalidOperationException("Settings backup round-trip differs");
        try
        {
            ProfileBackup.Unpack(sealed1, "wrong password");
            throw new InvalidOperationException("Wrong password was accepted");
        }
        catch (InvalidDataException) { }
        var tampered = (byte[])sealed1.Clone();
        tampered[^1] ^= 0x41;
        try
        {
            ProfileBackup.Unpack(tampered, password);
            throw new InvalidOperationException("Modified ciphertext was accepted");
        }
        catch (InvalidDataException) { }
        var duplicate = new ProfileBackup.Snapshot {
            Config = new AppConfig {
                Profiles = new List<NasProfile> { profile, profile.Clone() }
            }
        };
        try
        {
            ProfileBackup.Pack(duplicate, password);
            throw new InvalidOperationException("Duplicate profile IDs accepted");
        }
        catch (InvalidDataException) { }
        string directory = Path.Combine(Path.GetTempPath(),
            "filehub-backup-lab-" + Guid.NewGuid().ToString("N"));
        string path = Path.Combine(directory, "settings.fhbackup");
        try
        {
            ProfileBackup.WriteArchive(path, sealed1);
            if (!File.ReadAllBytes(path).AsSpan().SequenceEqual(sealed1))
                throw new InvalidOperationException("Archive write changed bytes");
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
        Console.WriteLine("FILEHUB_PROFILE_BACKUP_LAB_PASS encryption_roundtrip=true " +
            "wrong_password_rejected=true tamper_detected=true " +
            "duplicate_ids_rejected=true disk_roundtrip=true");
    }
}
