using System.Security.Cryptography;
using System.Text;
using FluentFTP;
using Renci.SshNet;

namespace NASMount;

internal sealed record LegacyEntry(string Name, bool IsDirectory, long Size,
    DateTimeOffset Modified);

internal interface ILegacyCharsetStore : IDisposable
{
    IReadOnlyList<LegacyEntry> List(string relative);
    LegacyEntry? Stat(string relative);
    Stream OpenRead(string relative, long offset);
    void Upload(string relative, string localFile);
    void Rename(string from, string to);
    void Delete(string relative, bool directory);
    void CreateDirectory(string relative);
}

/// <summary>
/// Uses FluentFTP's control-channel Encoding or SSH.NET's SFTP session
/// Encoding. This is actual wire-name conversion, not escaping local names.
/// The gateway serializes access to a store; never share a client in parallel.
/// </summary>
internal sealed class LegacyCharsetStorage : ILegacyCharsetStore
{
    private readonly NasProfile profile;
    private readonly Encoding encoding;
    private readonly FtpClient? ftp;
    private readonly SftpClient? sftp;
    private readonly string root;

    internal LegacyCharsetStorage(NasProfile profile, string? password, Encoding encoding)
    {
        this.profile = profile.Clone();
        this.encoding = encoding;
        string configuredRoot = profile.Share.Trim().Replace('\\', '/').TrimEnd('/');
        // Preserve rclone semantics: a relative root is relative to the
        // account home. Never silently change it to the server's absolute /.
        root = configuredRoot.Length == 0 ? "." : configuredRoot;
        if (profile.Protocol == "SFTP")
        {
            if (!SftpHostKeyTrust.HasTrustedKey(profile))
                throw new InvalidOperationException("SFTP 호스트키가 승인되지 않았습니다.");
            var info = new PasswordConnectionInfo(profile.Host,
                profile.Port == 0 ? 22 : profile.Port,
                RemoteMount.RemoteUsername(profile), password ?? "")
            {
                Encoding = encoding,
                Timeout = TimeSpan.FromSeconds(10)
            };
            sftp = new SftpClient(info);
            sftp.HostKeyReceived += (_, e) =>
            {
                string actual = Convert.ToBase64String(e.HostKey);
                string[] pin = profile.SftpPinnedHostKey.Split(' ', 2);
                string sha = "SHA256:" +
                    Convert.ToBase64String(SHA256.HashData(e.HostKey)).TrimEnd('=');
                e.CanTrust = pin.Length == 2 &&
                    string.Equals(pin[1], actual, StringComparison.Ordinal) &&
                    SftpHostKeyTrust.MatchesFingerprint(profile.SftpHostFingerprint, sha);
            };
            sftp.Connect();
        }
        else if (profile.Protocol == "FTP")
        {
            ftp = new FtpClient(profile.Host,
                RemoteMount.RemoteUsername(profile),
                RemoteMount.RemoteSecret(profile, password) ?? "",
                profile.Port == 0 ? 21 : profile.Port);
            ftp.Encoding = encoding;
            ftp.Config.EncryptionMode = profile.FtpImplicitTls
                ? FtpEncryptionMode.Implicit
                : profile.ExplicitTls ? FtpEncryptionMode.Explicit : FtpEncryptionMode.None;
            ftp.Config.DataConnectionType = profile.FtpPreferPasv
                ? FtpDataConnectionType.PASV : FtpDataConnectionType.AutoPassive;
            if (profile.FtpTls12Only)
                ftp.Config.SslProtocols = System.Security.Authentication.SslProtocols.Tls12;
            // Never enable ValidateAnyCertificate: TLS must be authenticated.
            ftp.Connect();
        }
        else throw new ArgumentException("Only FTP/SFTP use legacy charset storage");
    }

    private string PathOf(string path)
    {
        var segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        foreach (string name in segments)
        {
            if (name is "." or ".." ||
                name.IndexOfAny(['\\', '\0', '\r', '\n']) >= 0)
                throw new IOException("안전하지 않은 원격 경로입니다.");
            // Prevent replacing an unencodable name with '?' and damaging
            // an unrelated remote file, including during overwrite/rename.
            if (encoding.GetString(encoding.GetBytes(name)) != name)
                throw new IOException("원격 문자셋으로 표현할 수 없는 파일명: " + name);
        }
        string relative = string.Join("/", segments);
        return relative.Length == 0 ? root :
            root == "." ? relative : root.TrimEnd('/') + "/" + relative;
    }

    public IReadOnlyList<LegacyEntry> List(string relative)
    {
        string path = PathOf(relative);
        if (ftp is not null)
            return ftp.GetListing(path).Where(x =>
                    x.Name is not ("." or "..") && !string.IsNullOrWhiteSpace(x.Name))
                .Select(x => new LegacyEntry(x.Name, x.Type == FtpObjectType.Directory,
                    Math.Max(0, x.Size),
                    x.Modified == DateTime.MinValue
                        ? DateTimeOffset.UnixEpoch : new DateTimeOffset(x.Modified.ToUniversalTime())))
                .ToArray();
        return sftp!.ListDirectory(path).Where(x => x.Name is not ("." or ".."))
            .Select(x => new LegacyEntry(x.Name, x.IsDirectory,
                Math.Max(0, x.Length),
                new DateTimeOffset(x.LastWriteTimeUtc, TimeSpan.Zero))).ToArray();
    }

    public LegacyEntry? Stat(string relative)
    {
        if (string.IsNullOrWhiteSpace(relative))
            return new LegacyEntry("", true, 0, DateTimeOffset.UnixEpoch);
        string path = PathOf(relative);
        if (ftp is not null)
        {
            if (ftp.DirectoryExists(path))
                return new LegacyEntry(relative.Split('/')[^1], true, 0, DateTimeOffset.UnixEpoch);
            if (!ftp.FileExists(path)) return null;
            var info = ftp.GetObjectInfo(path);
            return new LegacyEntry(relative.Split('/')[^1], false,
                info is null ? 0 : Math.Max(0, info.Size),
                info?.Modified is { } dt && dt != DateTime.MinValue
                    ? new DateTimeOffset(dt.ToUniversalTime()) : DateTimeOffset.UnixEpoch);
        }
        if (!sftp!.Exists(path)) return null;
        var attrs = sftp.GetAttributes(path);
        return new LegacyEntry(relative.Split('/')[^1], attrs.IsDirectory,
            Math.Max(0, attrs.Size),
            new DateTimeOffset(attrs.LastWriteTimeUtc, TimeSpan.Zero));
    }

    public Stream OpenRead(string relative, long offset)
    {
        string path = PathOf(relative);
        if (ftp is not null)
            return ftp.OpenRead(path, FtpDataType.Binary, offset, false);
        var stream = sftp!.OpenRead(path);
        if (offset > 0) stream.Seek(offset, SeekOrigin.Begin);
        return stream;
    }

    public void Upload(string relative, string localFile)
    {
        string path = PathOf(relative);
        using var source = File.OpenRead(localFile);
        using var destination = ftp is not null ?
            ftp.OpenWrite(path, FtpDataType.Binary, false) : sftp!.Create(path);
        source.CopyTo(destination);
        destination.Flush();
    }

    public void Rename(string from, string to)
    {
        string origin = PathOf(from), target = PathOf(to);
        if (ftp is not null) ftp.Rename(origin, target);
        else sftp!.RenameFile(origin, target);
    }

    public void Delete(string relative, bool directory)
    {
        string path = PathOf(relative);
        if (ftp is not null)
        {
            if (directory) ftp.DeleteDirectory(path);
            else ftp.DeleteFile(path);
        }
        else
        {
            if (directory) sftp!.DeleteDirectory(path);
            else sftp!.DeleteFile(path);
        }
    }

    public void CreateDirectory(string relative)
    {
        string path = PathOf(relative);
        if (ftp is not null) ftp.CreateDirectory(path);
        else sftp!.CreateDirectory(path);
    }

    public void Dispose() { ftp?.Dispose(); sftp?.Dispose(); }
}
