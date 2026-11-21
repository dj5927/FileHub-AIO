using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Portable, passphrase-encrypted settings for FileHub AIO PC.
/// Keeps the legacy NASMount Windows Credential Manager keys and AppData path
/// so an in-place upgrade never loses existing mounts or OAuth credentials.
/// The archive does not contain logs, file caches, or recoverable NAS files.
/// </summary>
internal static class ProfileBackup
{
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("FH-AIO-PC-V1");
    // Android SettingsBackupCodec uses the identical header, PBKDF2 and AAD.
    private static readonly byte[] PortableMagic = Encoding.ASCII.GetBytes("FHBKUP01");
    private const int PortableIterations = 210_000;
    private const int MaxPortableArchiveBytes = 4 * 1024 * 1024 + 128;
    private static readonly byte[] Context = Encoding.UTF8.GetBytes("FileHub-AIO-PC-settings-v1");
    private const int SaltSize = 16;
    private const int NonceSize = 12;
    private const int TagSize = 16;
    private const int KeySize = 32;
    private const int Iterations = 240_000;
    private const int MaxArchiveBytes = 12 * 1024 * 1024;
    private const int MaxOauthChars = 1024 * 1024;
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    internal sealed class Snapshot
    {
        public int Format { get; set; } = 1;
        public AppConfig Config { get; set; } = new();
        public Dictionary<Guid, string> Passwords { get; set; } = new();
        public Dictionary<Guid, string> GoogleOAuth { get; set; } = new();
        public Dictionary<Guid, string> OneDriveOAuth { get; set; } = new();
    }

    internal static Snapshot Capture(AppConfig config, out List<string> warnings)
    {
        warnings = new List<string>();
        // Deep copy so a live UI edit cannot mutate the object during export.
        var snapshot = new Snapshot {
            Config = JsonSerializer.Deserialize<AppConfig>(JsonSerializer.Serialize(config, Json), Json)
                ?? throw new InvalidDataException("설정을 복사하지 못했습니다.")
        };
        foreach (var profile in snapshot.Config.Profiles)
        {
            if (profile.RememberPassword)
            {
                var secret = WindowsCredentials.Read(profile.Id);
                if (secret is not null)
                    snapshot.Passwords.Add(profile.Id, secret);
                else
                    warnings.Add(profile.DisplayName + " · 저장된 비밀번호를 찾지 못했습니다.");
            }
            if (profile.Protocol == "GoogleDrive")
                CaptureOAuth(profile, GoogleDriveAuth.ConfigPath(profile),
                    snapshot.GoogleOAuth, warnings);
            if (profile.Protocol == "OneDrive")
                CaptureOAuth(profile, OneDriveAuth.ConfigPath(profile),
                    snapshot.OneDriveOAuth, warnings);
        }
        Validate(snapshot);
        return snapshot;
    }

    private static void CaptureOAuth(NasProfile profile, string path,
        Dictionary<Guid, string> destination, List<string> warnings)
    {
        if (!File.Exists(path))
        {
            warnings.Add(profile.DisplayName + " · 클라우드 인증 파일이 없어 재로그인이 필요합니다.");
            return;
        }
        string content = File.ReadAllText(path, Encoding.UTF8);
        if (content.Length > MaxOauthChars)
            throw new InvalidDataException("클라우드 인증 정보 크기가 비정상적입니다.");
        destination.Add(profile.Id, content);
    }

    internal static byte[] Pack(Snapshot snapshot, string password)
    {
        Validate(snapshot);
        if (string.IsNullOrWhiteSpace(password) || password.Length < 10)
            throw new ArgumentException("백업 암호를 10자 이상 입력하세요.");
        byte[] plaintext = JsonSerializer.SerializeToUtf8Bytes(snapshot, Json);
        byte[] salt = RandomNumberGenerator.GetBytes(SaltSize);
        byte[] nonce = RandomNumberGenerator.GetBytes(NonceSize);
        byte[] tag = new byte[TagSize];
        byte[] encrypted = new byte[plaintext.Length];
        byte[] key = Rfc2898DeriveBytes.Pbkdf2(password, salt,
            Iterations, HashAlgorithmName.SHA256, KeySize);
        try
        {
            using var aes = new AesGcm(key, TagSize);
            aes.Encrypt(nonce, plaintext, encrypted, tag, Context);
            using var output = new MemoryStream();
            output.Write(Magic);
            output.Write(salt);
            output.Write(nonce);
            output.Write(tag);
            output.Write(encrypted);
            if (output.Length > MaxArchiveBytes)
                throw new InvalidDataException("백업 파일이 너무 큽니다.");
            return output.ToArray();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plaintext);
        }
    }

    internal static Snapshot Unpack(byte[] archive, string password)
    {
        if (archive.AsSpan().StartsWith(PortableMagic))
            return PortableProfileBackup.Unpack(archive, password);
        int headerSize = Magic.Length + SaltSize + NonceSize + TagSize;
        if (archive.Length <= headerSize || archive.Length > MaxArchiveBytes ||
            !archive.AsSpan(0, Math.Min(Magic.Length, archive.Length))
                .SequenceEqual(Magic))
            throw new InvalidDataException("FileHub AIO PC 설정 백업 파일이 아닙니다.");
        if (string.IsNullOrEmpty(password))
            throw new ArgumentException("백업 암호를 입력하세요.");
        int index = Magic.Length;
        var salt = archive.AsSpan(index, SaltSize); index += SaltSize;
        var nonce = archive.AsSpan(index, NonceSize); index += NonceSize;
        var tag = archive.AsSpan(index, TagSize); index += TagSize;
        var payload = archive.AsSpan(index);
        var plaintext = new byte[payload.Length];
        byte[] key = Rfc2898DeriveBytes.Pbkdf2(password, salt,
            Iterations, HashAlgorithmName.SHA256, KeySize);
        try
        {
            using var aes = new AesGcm(key, TagSize);
            try { aes.Decrypt(nonce, payload, tag, plaintext, Context); }
            catch (CryptographicException)
            {
                throw new InvalidDataException("백업 암호가 다르거나 파일이 손상되었습니다.");
            }
            var snapshot = JsonSerializer.Deserialize<Snapshot>(plaintext, Json)
                ?? throw new InvalidDataException("설정 내용을 읽지 못했습니다.");
            Validate(snapshot);
            return snapshot;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plaintext);
        }
    }

    internal static void WriteArchive(string path, byte[] bytes)
    {
        string folder = Path.GetDirectoryName(Path.GetFullPath(path)) ??
            throw new IOException("백업 경로가 올바르지 않습니다.");
        Directory.CreateDirectory(folder);
        string temporary = Path.Combine(folder, "." + Guid.NewGuid().ToString("N") + ".fhbackup.tmp");
        try
        {
            using (var file = new FileStream(temporary, FileMode.CreateNew,
                FileAccess.Write, FileShare.None))
            {
                file.Write(bytes);
                file.Flush(flushToDisk: true);
            }
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    private static void Validate(Snapshot snapshot)
    {
        if (snapshot.Format != 1 || snapshot.Config is null ||
            snapshot.Config.Profiles is null || snapshot.Passwords is null ||
            snapshot.GoogleOAuth is null || snapshot.OneDriveOAuth is null)
            throw new InvalidDataException("지원하지 않는 설정 백업 형식입니다.");
        if (snapshot.Config.Profiles.Count > 256)
            throw new InvalidDataException("연결 목록이 너무 많습니다.");
        var ids = new HashSet<Guid>();
        var letters = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var profile in snapshot.Config.Profiles)
        {
            if (profile is null || profile.Id == Guid.Empty || !ids.Add(profile.Id))
                throw new InvalidDataException("중복되거나 잘못된 연결 ID입니다.");
            if (profile.Letter is null || profile.Letter.Length != 1 ||
                profile.Letter[0] is < 'D' or > 'Z' ||
                !letters.Add(profile.Letter))
                throw new InvalidDataException("드라이브 문자가 중복되거나 유효하지 않습니다.");
            if (profile.Protocol is not ("SMB" or "WebDAV" or "FTP" or "SFTP" or
                    "GoogleDrive" or "OneDrive" or "TeraBox" or "MyBox") ||
                profile.DisplayName is null || profile.DisplayName.Length > 1024 ||
                profile.Host is null || profile.Host.Length > 4096 ||
                profile.Share is null || profile.Share.Length > 4096 ||
                profile.Username is null || profile.Username.Length > 4096)
                throw new InvalidDataException("연결 정보가 올바르지 않습니다.");
        }
        foreach (var item in snapshot.Passwords)
            if (!ids.Contains(item.Key) || item.Value is null ||
                item.Value.Length > 2560)
                throw new InvalidDataException("비밀번호 항목이 잘못되었습니다.");
        foreach (var item in snapshot.GoogleOAuth)
            ValidateOAuth(snapshot, item, "GoogleDrive");
        foreach (var item in snapshot.OneDriveOAuth)
            ValidateOAuth(snapshot, item, "OneDrive");
    }

    private static void ValidateOAuth(Snapshot snapshot,
        KeyValuePair<Guid, string> item, string protocol)
    {
        var profile = snapshot.Config.Profiles.SingleOrDefault(p => p.Id == item.Key);
        string section = "[nm_" + item.Key.ToString("N") + "]";
        string type = protocol == "GoogleDrive" ? "type = drive" : "type = onedrive";
        if (profile?.Protocol != protocol || item.Value is null ||
            item.Value.Length > MaxOauthChars ||
            !item.Value.Contains(section, StringComparison.Ordinal) ||
            !item.Value.Contains(type, StringComparison.Ordinal))
            throw new InvalidDataException("클라우드 연결 인증 정보가 유효하지 않습니다.");
    }

    /// <summary>
    /// The caller must ensure there are no active mounts or pending connects.
    /// Config is published last; existing local config is backed up first.
    /// Old passwords/OAuth are restored if the transaction fails.
    /// </summary>
    internal static string Restore(Snapshot snapshot, bool windowsAutostart,
        IReadOnlySet<Guid>? preservedDeviceCloudIds = null)
    {
        Validate(snapshot);
        var undo = new Stack<Action>();
        string backupPath = "";
        // Windows Run-key target is machine-specific; preserve its live state.
        snapshot.Config.StartWithWindows = windowsAutostart;
        try
        {
            foreach (var p in snapshot.Config.Profiles)
            {
                string? old = WindowsCredentials.Read(p.Id);
                if (snapshot.Passwords.TryGetValue(p.Id, out string? current))
                {
                    WindowsCredentials.Store(p.Id, p.Username, current);
                    undo.Push(() =>
                    {
                        if (old is null) WindowsCredentials.Delete(p.Id);
                        else WindowsCredentials.Store(p.Id, p.Username, old);
                    });
                }
                else if (p.RememberPassword)
                {
                    // Don't silently autoconnect with a credential from a
                    // different account/profile version on the target PC.
                    p.RememberPassword = false;
                    p.AutoConnect = false;
                }
                if (p.Protocol == "GoogleDrive" &&
                    snapshot.GoogleOAuth.TryGetValue(p.Id, out string? google))
                {
                    var path = GoogleDriveAuth.ConfigPath(p);
                    var previous = File.Exists(path) ? File.ReadAllText(path, Encoding.UTF8) : null;
                    GoogleDriveAuth.ImportAuthorizedConfig(p, google);
                    undo.Push(() => RestorePreviousOAuth(path, previous));
                }
                else if (p.Protocol == "GoogleDrive" &&
                         preservedDeviceCloudIds?.Contains(p.Id) != true)
                    p.AutoConnect = false;
                if (p.Protocol == "OneDrive" &&
                    snapshot.OneDriveOAuth.TryGetValue(p.Id, out string? oneDrive))
                {
                    var path = OneDriveAuth.ConfigPath(p);
                    var previous = File.Exists(path) ? File.ReadAllText(path, Encoding.UTF8) : null;
                    OneDriveAuth.ImportAuthorizedConfig(p, oneDrive);
                    undo.Push(() => RestorePreviousOAuth(path, previous));
                }
                else if (p.Protocol == "OneDrive" &&
                         preservedDeviceCloudIds?.Contains(p.Id) != true)
                    p.AutoConnect = false;
            }
            if (File.Exists(AppStore.ConfigPath))
            {
                backupPath = AppStore.ConfigPath + ".before-import-" +
                    DateTime.Now.ToString("yyyyMMdd-HHmmss-fff") + ".json";
                File.Copy(AppStore.ConfigPath, backupPath, overwrite: false);
            }
            AppStore.Save(snapshot.Config);
            return backupPath;
        }
        catch
        {
            while (undo.Count > 0)
            {
                try { undo.Pop()(); }
                catch (Exception ex) { AppStore.Log("CONFIG_IMPORT_ROLLBACK_WARNING " + ex.GetType().Name); }
            }
            throw;
        }
    }

    private static void RestorePreviousOAuth(string path, string? previous)
    {
        if (previous is null) { if (File.Exists(path)) File.Delete(path); }
        else File.WriteAllText(path, previous, new UTF8Encoding(false));
    }
}
