using System.ComponentModel;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

namespace NASMount;

public sealed class NasProfile
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Protocol { get; set; } = "SMB";
    public int Port { get; set; }
    public bool ExplicitTls { get; set; } = true;
    // FTP options supported by rclone. MODE Z is unsupported and not saved.
    public bool FtpPreferPasv { get; set; } = true;
    public bool FtpDisableUtf8 { get; set; }
    public string FtpCharset { get; set; } = "UTF-8";
    public string SftpCharset { get; set; } = "UTF-8";
    public bool IsAnonymous { get; set; }
    public bool FtpImplicitTls { get; set; }
    public bool FtpTls12Only { get; set; }
    // HTTPS remains the default. Plain HTTP WebDAV can only be enabled by an
    // explicit user choice after a credential transport warning.
    public bool WebDavUseHttp { get; set; }
    public string SftpHostFingerprint { get; set; } = "";
    // Explicitly approved public SSH host key for rclone SFTP. Unlike the
    // global ~/.ssh/known_hosts file, this is per-profile and survives restart.
    public string SftpPinnedHostKey { get; set; } = "";
    public string SftpTrustedHost { get; set; } = "";
    public int SftpTrustedPort { get; set; }
    public bool AllowUnprotectedWrites { get; set; }
    public bool UseLockBridge { get; set; }
    // Deprecated experimental RFC4918 gateway switch. Read-only compatibility
    // shim: legacy JSON/imports may carry true, but the old connection mode
    // must never activate again after the v1.3.134 migration.
    [System.Text.Json.Serialization.JsonIgnore]
    public bool UseNativeWebDavLocks { get => false; set { } }
    public bool FastReadOnly { get; set; }
    public bool OfficeReadTuning { get; set; }
    // With false, preserve legacy rclone --network-mode (UNC \\server\...).
    // With true, rclone mounts as a fixed WinFsp disk at the same drive letter.
    // This affects Windows/Office path classification, NOT NAS file-lock safety.
    public bool UseFixedDriveMode { get; set; }
    public string DisplayName { get; set; } = "새 NAS";
    public string Host { get; set; } = "";
    public string Share { get; set; } = "";
    public string Letter { get; set; } = "R";
    public string Username { get; set; } = "";
    public bool RememberPassword { get; set; }
    public bool AutoConnect { get; set; } = true;
    public string Unc => @"\\" + Host.Trim().Trim('\\') + @"\" + Share.Trim().Trim('\\');
    public string RemoteDisplay => Protocol == "MyBox" ? "네이버 MYBOX" : Protocol == "TeraBox" ? "TeraBox" : Protocol == "GoogleDrive" ? "Google Drive" : Protocol == "OneDrive" ? "OneDrive" : Protocol == "SMB" ? Unc :
        $"{(Protocol == "WebDAV" ? (WebDavUseHttp ? "http" : "https") : Protocol)}://{Host}:{(Port == 0 ? (Protocol == "SFTP" ? 22 : Protocol == "WebDAV" ? 5006 : 21) : Port)}/{Share.TrimStart('/')}";
    public NasProfile Clone() => (NasProfile)MemberwiseClone();
}

public sealed class AppConfig
{
    public bool StartWithWindows { get; set; }
    public bool GoogleStartupDiagnostics { get; set; }
    public string CacheParentDirectory { get; set; } = "";
    public int CacheRetentionMinutes { get; set; } = 30;
    public int CacheMaxGiB { get; set; } = 5;
    public List<NasProfile> Profiles { get; set; } = [];
}

internal static class AppStore
{
    public static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "NASMount");
    public static string ConfigPath => Path.Combine(DirectoryPath, "config.json");
    private static readonly object LogSync = new();
    private static DateTime nextLogPruneUtc = DateTime.MinValue;
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };

    public static AppConfig Load()
    {
        try
        {
            if (!File.Exists(ConfigPath)) return new AppConfig();
            var config = ReadConfig(ConfigPath);
            // Older releases generated this exact MYBOX default with a
            // "(beta)" suffix. Normalize only that built-in default.
            // User-chosen custom connection names are never rewritten.
            if (NormalizeLegacyMyBoxDefaultNames(config))
            {
                try
                {
                    Save(config);
                    Log("MYBOX_LEGACY_DEFAULT_DISPLAY_NAME_NORMALIZED");
                }
                catch (Exception ex)
                {
                    // Display the normalized label in this session even if
                    // saving a cosmetic migration fails.
                    Log("MYBOX_DISPLAY_NAME_MIGRATION_SAVE_FAILED " +
                        ex.GetType().Name);
                }
            }
            return config;
        }
        catch (Exception ex)
        {
            Log("Config error: " + ex.Message);
            MessageBox.Show("설정 파일을 읽지 못했습니다. 기존 설정 파일을 보존하고 빈 목록으로 시작합니다.\n"
                + ex.Message, "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return new AppConfig();
        }
    }

    public static AppConfig ReadConfig(string path) =>
        JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(path, Encoding.UTF8), Options)
        ?? new AppConfig();

    internal static bool NormalizeLegacyMyBoxDefaultNames(AppConfig config)
    {
        bool updated = false;
        foreach (var profile in config.Profiles)
        {
            if (profile.Protocol == "MyBox" &&
                string.Equals(profile.DisplayName?.Trim(),
                    "네이버 MYBOX (beta)", StringComparison.OrdinalIgnoreCase))
            {
                profile.DisplayName = "네이버 MYBOX";
                updated = true;
            }
        }
        return updated;
    }

    public static void Save(AppConfig config) => WriteConfig(ConfigPath, config);

    public static void WriteConfig(string path, AppConfig config)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        string temporary = path + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(config, Options), new UTF8Encoding(false));
        File.Move(temporary, path, true);
    }

    public static void Log(string entry)
    {
        try
        {
            lock (LogSync)
            {
                string folder = Path.Combine(DirectoryPath, "logs");
                Directory.CreateDirectory(folder);
                var now = DateTime.UtcNow;
                if (now >= nextLogPruneUtc)
                {
                    PruneLogs();
                    nextLogPruneUtc = now.AddMinutes(1);
                }
                // Separate process/minute files prevent bridge/GUI append
                // contention and keep retention short even during idle time.
                string path = Path.Combine(folder,
                    now.ToString("yyyyMMdd-HHmm", System.Globalization.CultureInfo.InvariantCulture) +
                    "-" + Environment.ProcessId + ".log");
                if (!File.Exists(path) || new FileInfo(path).Length < 2 * 1024 * 1024)
                    File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") +
                        " " + entry + Environment.NewLine);
            }
        }
        catch { /* Do not crash due to an unwritable log folder. */ }
    }

    internal static void PruneLogDirectory(string folder, DateTime utcNow)
    {
        if (!Directory.Exists(folder) ||
            (File.GetAttributes(folder) & FileAttributes.ReparsePoint) != 0) return;
        foreach (string path in Directory.EnumerateFiles(folder, "*.log"))
        {
            try
            {
                if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0) continue;
                string name = Path.GetFileName(path);
                bool legacy = System.Text.RegularExpressions.Regex.IsMatch(name,
                    @"^\d{4}-\d{2}-\d{2}\.log$");
                bool rolling = System.Text.RegularExpressions.Regex.IsMatch(name,
                    @"^\d{8}-\d{4}-\d+\.log$");
                if (legacy || (rolling && File.GetLastWriteTimeUtc(path) <= utcNow.AddMinutes(-30)))
                    File.Delete(path);
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    internal static void PruneLogs()
    {
        try { PruneLogDirectory(Path.Combine(DirectoryPath, "logs"), DateTime.UtcNow); }
        catch { }
    }

    public static bool IsAutoStartEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        return key?.GetValue("NASMount") is string;
    }

    // Keep the legacy Run-value name for backward compatibility. If the user
    // had already enabled automatic startup in NAS Mount, re-point that
    // existing entry to this renamed executable, not the obsolete old EXE.
    public static void UpdateExistingAutoStartTarget()
    {
        string? executable = Environment.ProcessPath;
        if (string.IsNullOrEmpty(executable) ||
            !Path.GetFileName(executable).Equals("FileHubAIO.exe",
                StringComparison.OrdinalIgnoreCase)) return;
        using var key = Registry.CurrentUser.OpenSubKey(
            @"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
        if (key?.GetValue("NASMount") is not string previous) return;
        string target = "\"" + executable + "\" --minimized";
        if (string.Equals(previous, target, StringComparison.OrdinalIgnoreCase))
            return;
        key.SetValue("NASMount", target, RegistryValueKind.String);
        Log("AUTOSTART_RENAMED_TARGET_MIGRATED");
    }

    public static void SetAutoStart(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
        if (enabled)
        {
            string exe = Environment.ProcessPath ?? throw new InvalidOperationException("실행 파일을 찾을 수 없습니다.");
            key.SetValue("NASMount", "\"" + exe + "\" --minimized", RegistryValueKind.String);
        }
        else
            key.DeleteValue("NASMount", false);
    }
}

internal static class WindowsCredentials
{
    private const int Generic = 1;
    private const int PersistLocalMachine = 2;
    private static string Target(Guid id) => "NASMount:" + id.ToString("D");

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Credential
    {
        public int Flags;
        public int Type;
        [MarshalAs(UnmanagedType.LPWStr)] public string TargetName;
        [MarshalAs(UnmanagedType.LPWStr)] public string? Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public int CredentialBlobSize;
        public IntPtr CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public IntPtr Attributes;
        [MarshalAs(UnmanagedType.LPWStr)] public string? TargetAlias;
        [MarshalAs(UnmanagedType.LPWStr)] public string? UserName;
    }

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CredWriteW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredWrite(ref Credential credential, uint flags);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CredReadW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredRead(string targetName, int type, int flags, out IntPtr credentialPtr);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CredDeleteW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredDelete(string targetName, int type, int flags);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr buffer);

    public static void Store(Guid id, string username, string password)
    {
        var bytes = Encoding.Unicode.GetBytes(password);
        IntPtr pointer = Marshal.AllocCoTaskMem(bytes.Length);
        try
        {
            Marshal.Copy(bytes, 0, pointer, bytes.Length);
            var credential = new Credential
            {
                Type = Generic,
                TargetName = Target(id),
                UserName = username,
                CredentialBlobSize = bytes.Length,
                CredentialBlob = pointer,
                Persist = PersistLocalMachine
            };
            if (!CredWrite(ref credential, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        finally
        {
            Array.Clear(bytes);
            if (bytes.Length > 0)
            {
                var zeros = new byte[bytes.Length];
                Marshal.Copy(zeros, 0, pointer, zeros.Length);
            }
            Marshal.FreeCoTaskMem(pointer);
        }
    }

    public static string? Read(Guid id)
    {
        if (!CredRead(Target(id), Generic, 0, out var ptr)) return null;
        try
        {
            var item = Marshal.PtrToStructure<Credential>(ptr);
            if (item.CredentialBlobSize < 0 || item.CredentialBlobSize > 5120)
                return null;
            if (item.CredentialBlobSize == 0) return "";
            return Marshal.PtrToStringUni(item.CredentialBlob, item.CredentialBlobSize / 2);
        }
        finally { CredFree(ptr); }
    }

    public static void Delete(Guid id)
    {
        if (!CredDelete(Target(id), Generic, 0))
        {
            int err = Marshal.GetLastWin32Error();
            if (err != 1168) throw new Win32Exception(err);
        }
    }
}

internal readonly record struct OperationResult(bool Success, string Message)
{
    public static OperationResult Ok(string message) => new(true, message);
    public static OperationResult Fail(string message) => new(false, message);
}

internal static class WindowsShare
{
    private const int NoConnection = 2250;
    private const int NotConnected = 1201;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NetResource
    {
        public int Scope;
        public int Type;
        public int DisplayType;
        public int Usage;
        public string? LocalName;
        public string? RemoteName;
        public string? Comment;
        public string? Provider;
    }

    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetAddConnection2W")]
    private static extern int AddConnection(ref NetResource resource, string? password, string? username, int flags);
    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetCancelConnection2W")]
    private static extern int CancelConnection(string name, int flags, [MarshalAs(UnmanagedType.Bool)] bool force);
    [DllImport("mpr.dll", CharSet = CharSet.Unicode, EntryPoint = "WNetGetConnectionW")]
    private static extern int GetConnection(string localName, StringBuilder remoteName, ref int length);

    public static bool TryValidate(NasProfile profile, out string reason)
    {
        reason = "";
        string host = profile.Host.Trim();
        string share = profile.Share.Trim();
        if (host.Length == 0 || share.Length == 0)
            reason = "NAS 주소와 공유 폴더 이름을 입력하세요.";
        else if (host.Contains("://") || host.Contains('\\') || host.Contains('/') ||
                 host.Contains(':') || host.Any(char.IsWhiteSpace))
            reason = "NAS 주소에는 IP 또는 호스트 이름만 입력하세요. (https://, 포트, 경로 제외)";
        else if (share is "." or ".." || share.IndexOfAny(['\\', '/', ':', '*', '?', '"', '<', '>', '|']) >= 0)
            reason = "공유 폴더에는 DSM의 공유 폴더 이름만 입력하세요. 하위 경로는 사용할 수 없습니다.";
        else if (profile.Letter.Trim().Length != 1 ||
                 !char.IsLetter(profile.Letter.Trim()[0]))
            reason = "드라이브 문자는 A~Z 중 하나를 선택하세요.";
        return reason.Length == 0;
    }

    public static string? MappedUnc(string letter)
    {
        if (string.IsNullOrWhiteSpace(letter)) return null;
        var result = new StringBuilder(4096);
        int length = result.Capacity;
        int code = GetConnection(letter.ToUpperInvariant()[0] + ":", result, ref length);
        return code == 0 ? result.ToString() : null;
    }

    public static bool IsOurMapping(NasProfile profile)
    {
        var mapping = MappedUnc(profile.Letter);
        return string.Equals(mapping?.TrimEnd('\\'), profile.Unc.TrimEnd('\\'),
            StringComparison.OrdinalIgnoreCase);
    }

    public static OperationResult Connect(NasProfile profile, string? password)
    {
        if (!TryValidate(profile, out var reason)) return OperationResult.Fail(reason);
        string local = profile.Letter.ToUpperInvariant() + ":";
        var current = MappedUnc(profile.Letter);
        if (current is not null)
            return string.Equals(current.TrimEnd('\\'), profile.Unc.TrimEnd('\\'),
                StringComparison.OrdinalIgnoreCase)
                ? OperationResult.Ok("이미 연결되어 있습니다.")
                : OperationResult.Fail($"{local} 드라이브가 다른 공유({current})에 사용 중입니다.");

        if (Environment.GetLogicalDrives().Any(x => x.StartsWith(local, StringComparison.OrdinalIgnoreCase)))
            return OperationResult.Fail($"{local} 드라이브가 이미 사용 중입니다. 다른 문자를 선택하세요.");

        var resource = new NetResource
        {
            Type = 1, // RESOURCETYPE_DISK
            LocalName = local,
            RemoteName = profile.Unc
        };
        int code = AddConnection(ref resource,
            string.IsNullOrEmpty(password) ? null : password,
            string.IsNullOrWhiteSpace(profile.Username) ? null : profile.Username,
            0); // App-managed reconnection, not Windows persistent mapping.
        return code == 0
            ? OperationResult.Ok($"{local} → {profile.Unc} 연결 완료")
            : OperationResult.Fail(ExplainError(code));
    }

    public static OperationResult Disconnect(NasProfile profile)
    {
        var current = MappedUnc(profile.Letter);
        if (current is null) return OperationResult.Ok("연결되어 있지 않습니다.");
        if (!string.Equals(current.TrimEnd('\\'), profile.Unc.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
            return OperationResult.Fail("드라이브가 다른 공유에 연결되어 있어 안전을 위해 해제하지 않습니다.");
        int result = CancelConnection(profile.Letter.ToUpperInvariant() + ":", 0, false);
        return result == 0 || result is NoConnection or NotConnected
            ? OperationResult.Ok("연결을 해제했습니다.")
            : OperationResult.Fail(result == 2401
                ? "문서를 사용하는 프로그램이 있습니다. 모두 닫은 뒤 다시 시도하세요. (강제 해제 안 함)"
                : ExplainError(result));
    }

    public static OperationResult ProbeFileLock(string directory, string? fileName = null)
    {
        if (!Directory.Exists(directory))
            return OperationResult.Fail("대상 폴더에 접근할 수 없습니다.");

        string path = Path.Combine(directory, fileName ?? (".nasmount_lock_" + Guid.NewGuid().ToString("N") + ".tmp"));
        try
        {
            using (new FileStream(path, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None))
            {
                bool blocked = false;
                try
                {
                    using var second = new FileStream(path, FileMode.Open, FileAccess.ReadWrite,
                        FileShare.ReadWrite | FileShare.Delete);
                }
                catch (IOException) { blocked = true; }
                if (!blocked)
                    return OperationResult.Fail("두 번째 핸들이 열렸습니다. 이 파일시스템에서 배타적 잠금을 확인하지 못했습니다.");
            }
            return OperationResult.Ok("동일 PC의 두 번째 파일 열기가 정상 차단되었습니다. 실제 2대 PC·Excel 검증은 별도로 필요합니다.");
        }
        catch (Exception ex)
        {
            return OperationResult.Fail("검사 파일 생성/잠금 실패: " + ex.Message);
        }
        finally
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch { /* Keep the original test error; log orphan below. */ }
        }
    }

    private static string ExplainError(int code)
    {
        string detail = code switch
        {
            5 => "접근이 거부되었습니다. NAS 공유 권한을 확인하세요.",
            53 => "NAS 서버를 찾을 수 없습니다. 네트워크/VPN/DNS/SMB(445)를 확인하세요.",
            67 => "공유 폴더를 찾을 수 없습니다. 공유 이름을 확인하세요.",
            85 => "드라이브 문자가 이미 사용 중입니다.",
            86 or 1326 => "NAS 사용자 이름 또는 비밀번호를 확인하세요.",
            1219 => "같은 NAS에 다른 계정으로 이미 연결돼 있습니다. Windows의 기존 NAS 연결/자격 증명을 확인하세요.",
            1222 => "네트워크를 사용할 수 없습니다. VPN이나 NAS 연결을 확인하세요.",
            2401 => "열려 있는 파일 때문에 연결을 해제할 수 없습니다.",
            _ => new Win32Exception(code).Message
        };
        return $"Windows 오류 {code}: {detail}";
    }
}
