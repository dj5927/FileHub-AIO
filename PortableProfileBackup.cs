using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Cross-platform FileHub .fhbackup wire format. The encrypted envelope and
/// JSON fields match the Android SettingsBackupCodec/SettingsBackup classes.
/// Historical PC archives are still decoded by ProfileBackup.Unpack.
/// Google Drive and OneDrive credentials are deliberately NOT portable.
/// </summary>
internal static class PortableProfileBackup
{
    private static readonly byte[] Magic = Encoding.ASCII.GetBytes("FHBKUP01");
    private const int Iterations = 210_000;
    private const int MaxFile = 4 * 1024 * 1024 + 128;
    private const int SaltBytes = 16;
    private const int NonceBytes = 12;
    private const int TagBytes = 16;

    internal static bool IsPortable(ReadOnlySpan<byte> archive) =>
        archive.StartsWith(Magic);

    /// <summary>
    /// Importing a mobile backup never removes existing PC-only clouds.
    /// Keep their original local OAuth identity and assigned drive letter.
    /// </summary>
    internal static HashSet<Guid> PreserveWindowsCloudProfiles(
        ProfileBackup.Snapshot incoming, AppConfig current)
    {
        var kept = new HashSet<Guid>();
        var cloud = current.Profiles.Where(p =>
            p.Protocol is "GoogleDrive" or "OneDrive")
            .Select(p => p.Clone()).ToArray();
        var occupied = cloud.Select(p => p.Letter)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        foreach (var p in incoming.Config.Profiles)
            if (!occupied.Add(p.Letter))
                p.Letter = Enumerable.Range('D', 'Z' - 'D' + 1)
                    .Select(x => ((char)x).ToString())
                    .FirstOrDefault(occupied.Add) ??
                    throw new InvalidDataException("복원할 드라이브 문자가 부족합니다.");
        foreach (var p in cloud)
        {
            if (incoming.Config.Profiles.Any(x => x.Id == p.Id)) continue;
            incoming.Config.Profiles.Add(p);
            kept.Add(p.Id);
            if (p.RememberPassword)
            {
                string? password = WindowsCredentials.Read(p.Id);
                if (!string.IsNullOrEmpty(password))
                    incoming.Passwords[p.Id] = password;
            }
        }
        incoming.Config.StartWithWindows = current.StartWithWindows;
        return kept;
    }

    internal static byte[] Pack(ProfileBackup.Snapshot snapshot, string password)
    {
        if (string.IsNullOrWhiteSpace(password) || password.Length < 8 ||
            password.Length > 128)
            throw new ArgumentException("공통 백업 암호는 8~128자로 입력하세요.");
        var remotes = snapshot.Config.Profiles
            .Where(p => p.Protocol is not ("GoogleDrive" or "OneDrive"))
            .Select(p => new
            {
                id = p.Id.ToString("D"),
                name = p.DisplayName,
                protocol = p.Protocol == "MyBox" ? "MYBOX" : p.Protocol,
                host = p.Host,
                port = p.Port > 0 ? p.Port : p.Protocol switch
                {
                    "SMB" => 445,
                    "FTP" => p.FtpImplicitTls ? 990 : 21,
                    "SFTP" => 22,
                    "WebDAV" => p.WebDavUseHttp ? 80 : 5006,
                    _ => 0
                },
                root = p.Share,
                username = p.Username,
                password = snapshot.Passwords.GetValueOrDefault(p.Id) ?? "",
                ssl = !p.WebDavUseHttp,
                fingerprint = p.SftpHostFingerprint,
                ftpExplicitTls = p.Protocol == "FTP" &&
                    p.ExplicitTls && !p.FtpImplicitTls,
                ftpImplicitTls = p.Protocol == "FTP" && p.FtpImplicitTls,
                ftpPreferPasv = p.FtpPreferPasv,
                ftpCharset = p.FtpCharset,
                ftpTls12Only = p.FtpTls12Only,
                sftpCharset = p.SftpCharset,
                readOnly = p.Protocol != "SMB" &&
                    !p.UseLockBridge && !p.AllowUnprotectedWrites,
                useLockBridge = p.UseLockBridge,
                useNativeWebDavLocks = p.UseNativeWebDavLocks,
                officeReadTuning = p.OfficeReadTuning,
                autoConnect = p.AutoConnect,
                windowsLetter = p.Letter,
                isAnonymous = p.IsAnonymous,
            }).ToArray();
        byte[] plain = JsonSerializer.SerializeToUtf8Bytes(new
        {
            kind = "filehub-portable-settings",
            version = 1,
            created = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            remotes,
            favorites = Array.Empty<object>(),
            preferences = new Dictionary<string, object>()
        });
        if (plain.Length > 4 * 1024 * 1024)
            throw new InvalidDataException("공통 설정 백업 용량 제한을 초과했습니다.");
        var salt = RandomNumberGenerator.GetBytes(SaltBytes);
        var nonce = RandomNumberGenerator.GetBytes(NonceBytes);
        var ciphertext = new byte[plain.Length];
        var tag = new byte[TagBytes];
        byte[] key = Rfc2898DeriveBytes.Pbkdf2(password, salt,
            Iterations, HashAlgorithmName.SHA256, 32);
        try
        {
            using var cipher = new AesGcm(key, TagBytes);
            cipher.Encrypt(nonce, plain, ciphertext, tag, Magic);
            using var output = new MemoryStream();
            output.Write(Magic);
            output.Write(salt);
            output.Write(nonce);
            output.Write(ciphertext);
            output.Write(tag); // Android Cipher.doFinal appends the GCM tag.
            return output.ToArray();
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plain);
        }
    }

    internal static ProfileBackup.Snapshot Unpack(byte[] data, string password)
    {
        int header = Magic.Length + SaltBytes + NonceBytes;
        if (data.Length <= header + TagBytes || data.Length > MaxFile ||
            !data.AsSpan().StartsWith(Magic) || string.IsNullOrEmpty(password) ||
            password.Length < 8)
            throw new InvalidDataException("공통 백업 형식이 올바르지 않습니다.");
        byte[] key = Rfc2898DeriveBytes.Pbkdf2(password,
            data.AsSpan(Magic.Length, SaltBytes), Iterations,
            HashAlgorithmName.SHA256, 32);
        int length = data.Length - header - TagBytes;
        byte[] plain = new byte[length];
        try
        {
            using (var cipher = new AesGcm(key, TagBytes))
                cipher.Decrypt(data.AsSpan(Magic.Length + SaltBytes, NonceBytes),
                    data.AsSpan(header, length),
                    data.AsSpan(header + length, TagBytes), plain, Magic);
            using var json = JsonDocument.Parse(plain,
                new JsonDocumentOptions { MaxDepth = 32 });
            var root = json.RootElement;
            if (Field(root, "kind") != "filehub-portable-settings" ||
                Number(root, "version", 0) != 1)
                throw new InvalidDataException("지원하지 않는 공통 백업 버전입니다.");
            var list = root.GetProperty("remotes");
            if (list.ValueKind != JsonValueKind.Array || list.GetArrayLength() > 100)
                throw new InvalidDataException("공통 백업 연결 수가 유효하지 않습니다.");
            var result = new ProfileBackup.Snapshot();
            var ids = new HashSet<Guid>();
            var letters = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var item in list.EnumerateArray())
            {
                string protocol = Field(item, "protocol");
                if (protocol is "GoogleDrive" or "OneDrive")
                    continue; // Never import unsupported Android OAuth profiles.
                if (protocol == "MYBOX") protocol = "MyBox";
                if (protocol is not ("WebDAV" or "SMB" or "FTP" or "SFTP" or
                    "TeraBox" or "MyBox"))
                    throw new InvalidDataException("지원하지 않는 원격 저장소입니다.");
                if (!Guid.TryParse(Field(item, "id"), out var id) ||
                    !ids.Add(id))
                    throw new InvalidDataException("중복되거나 잘못된 연결 ID입니다.");
                string letter = Field(item, "windowsLetter").ToUpperInvariant();
                if (letter.Length != 1 || letter[0] < 'D' || letter[0] > 'Z' ||
                    !letters.Add(letter))
                    letter = Enumerable.Range('D', 'Z' - 'D' + 1)
                        .Select(v => ((char)v).ToString())
                        .FirstOrDefault(letters.Add) ??
                        throw new InvalidDataException("사용 가능한 드라이브 문자가 없습니다.");
                string secret = Field(item, "password");
                var p = new NasProfile
                {
                    Id = id, Protocol = protocol,
                    DisplayName = Field(item, "name"),
                    Host = Field(item, "host"),
                    Share = Field(item, "root"),
                    Port = Number(item, "port"),
                    Username = Field(item, "username"),
                    Letter = letter,
                    SftpHostFingerprint = Field(item, "fingerprint"),
                    WebDavUseHttp = !Boolean(item, "ssl", true),
                    ExplicitTls = Boolean(item, "ftpExplicitTls"),
                    FtpImplicitTls = Boolean(item, "ftpImplicitTls"),
                    FtpPreferPasv = Boolean(item, "ftpPreferPasv", true),
                    FtpCharset = Field(item, "ftpCharset", "UTF-8"),
                    SftpCharset = Field(item, "sftpCharset", "UTF-8"),
                    FtpTls12Only = Boolean(item, "ftpTls12Only"),
                    IsAnonymous = Boolean(item, "isAnonymous"),
                    UseLockBridge = Boolean(item, "useLockBridge"),
                    UseNativeWebDavLocks = Boolean(item, "useNativeWebDavLocks"),
                    OfficeReadTuning = Boolean(item, "officeReadTuning"),
                    RememberPassword = !string.IsNullOrEmpty(secret),
                    AutoConnect = Boolean(item, "autoConnect"),
                };
                if (Boolean(item, "readOnly"))
                {
                    p.UseLockBridge = false;
                    p.AllowUnprotectedWrites = false;
                }
                else if (!p.UseLockBridge) p.AllowUnprotectedWrites = true;
                if (protocol == "SFTP")
                    p.AutoConnect = false; // Require host identity re-confirmation.
                if (protocol == "WebDAV" && p.UseLockBridge)
                    p.AllowUnprotectedWrites = false;
                result.Config.Profiles.Add(p);
                if (!string.IsNullOrEmpty(secret))
                    result.Passwords.Add(id, secret);
            }
            return result;
        }
        catch (CryptographicException)
        {
            throw new InvalidDataException("백업 비밀번호가 틀렸거나 데이터가 손상됐습니다.");
        }
        catch (JsonException)
        {
            throw new InvalidDataException("백업의 설정 데이터가 올바르지 않습니다.");
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plain);
        }
    }

    private static string Field(JsonElement item, string key, string fallback = "")
    {
        if (!item.TryGetProperty(key, out var value) ||
            value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
            return fallback;
        if (value.ValueKind != JsonValueKind.String)
            throw new InvalidDataException("백업의 문자 필드가 올바르지 않습니다.");
        return value.GetString() ?? fallback;
    }
    private static bool Boolean(JsonElement item, string key, bool fallback = false)
    {
        if (!item.TryGetProperty(key, out var value)) return fallback;
        if (value.ValueKind is JsonValueKind.True or JsonValueKind.False)
            return value.GetBoolean();
        throw new InvalidDataException("백업의 옵션 필드가 올바르지 않습니다.");
    }
    private static int Number(JsonElement item, string key, int fallback = 0)
    {
        if (!item.TryGetProperty(key, out var value)) return fallback;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out int i))
            return i;
        throw new InvalidDataException("백업의 숫자 필드가 올바르지 않습니다.");
    }
}
