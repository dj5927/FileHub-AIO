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
        byte[] portable = PortableProfileBackup.Pack(sample, password);
        if (!portable.AsSpan().StartsWith(
                System.Text.Encoding.ASCII.GetBytes("FHBKUP01")))
            throw new InvalidOperationException("Android-compatible backup magic missing");
        var imported = ProfileBackup.Unpack(portable, password);
        if (imported.Config.Profiles.Count != 1 ||
            imported.Config.Profiles[0].Id != profile.Id ||
            !imported.Config.Profiles[0].UseNativeWebDavLocks ||
            imported.Passwords[profile.Id] != "not-a-real-credential")
            throw new InvalidOperationException("Android-compatible backup differs");
        var desktopGoogle = new NasProfile {
            Id = Guid.NewGuid(), Protocol = "GoogleDrive",
            DisplayName = "Windows-only cloud fixture", Letter = "Q",
            AutoConnect = true, RememberPassword = false
        };
        var merged = ProfileBackup.Unpack(portable, password);
        var keep = PortableProfileBackup.PreserveWindowsCloudProfiles(merged,
            new AppConfig {
                Profiles = new List<NasProfile> { desktopGoogle }
            });
        if (!keep.Contains(desktopGoogle.Id) ||
            merged.Config.Profiles.Count != 2 ||
            merged.Config.Profiles.Single(x => x.Id == desktopGoogle.Id).Letter != "Q" ||
            merged.Config.Profiles.Single(x => x.Id == profile.Id).Letter == "Q")
            throw new InvalidOperationException(
                "Android import overwrote desktop cloud or reused drive letter");
        try
        {
            ProfileBackup.Unpack(portable, "wrong-password");
            throw new InvalidOperationException("Portable wrong password accepted");
        }
        catch (InvalidDataException) { }
        var tamperedPortable = (byte[])portable.Clone();
        tamperedPortable[^2] ^= 1;
        try
        {
            ProfileBackup.Unpack(tamperedPortable, password);
            throw new InvalidOperationException("Portable tamper accepted");
        }
        catch (InvalidDataException) { }
        string? fixturePath = Environment.GetEnvironmentVariable(
            "FILEHUB_PORTABLE_TEST_FIXTURE");
        if (!string.IsNullOrWhiteSpace(fixturePath))
            File.WriteAllBytes(fixturePath, portable);
        string? androidFixture = Environment.GetEnvironmentVariable(
            "FILEHUB_ANDROID_PORTABLE_TEST_FIXTURE");
        if (!string.IsNullOrWhiteSpace(androidFixture) &&
            File.Exists(androidFixture))
        {
            var androidDecoded = ProfileBackup.Unpack(
                File.ReadAllBytes(androidFixture), password);
            if (androidDecoded.Config.Profiles.Count != 1 ||
                androidDecoded.Config.Profiles[0].Protocol != "FTP" ||
                !androidDecoded.Config.Profiles[0].FtpImplicitTls ||
                androidDecoded.Config.Profiles[0].FtpCharset !=
                    "CP949 / EUC-KR (한국어)" ||
                androidDecoded.Passwords.Values.Single() != "not-real-ftp-password")
                throw new InvalidOperationException(
                    "Android-generated encrypted backup not imported by PC");
            Console.WriteLine("FILEHUB_ANDROID_GENERATED_BACKUP_PC_DECRYPT_PASS");
        }
        Console.WriteLine("FILEHUB_PORTABLE_ANDROID_ENVELOPE_BACKUP_PASS");
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
