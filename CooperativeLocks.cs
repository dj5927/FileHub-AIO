using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using FluentFTP;
using Renci.SshNet;

namespace NASMount;

// A portable, server-side, cooperative lock primitive shared by clients using
// WebDAV MKCOL, FTP MKD, or SFTP mkdir. Atomic directory creation is the arbiter.
// IMPORTANT: This component does NOT intercept Windows file opens. Until it
// is integrated into a lock-aware virtual filesystem, mounting a remote alone
// does not protect Office documents from concurrent writes.
internal static class CooperativeLocks
{
    internal sealed class LockConflictException(string message)
        : IOException(message);
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, bool>
        FtpExclusiveMkdirVerified = new(StringComparer.OrdinalIgnoreCase);
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, bool>
        SftpExclusiveMkdirVerified = new(StringComparer.OrdinalIgnoreCase);
    public sealed record Lease(string Resource, string Id, string Owner, DateTimeOffset CreatedUtc,
        string? DavToken = null, string? VisibleMarker = null);
    private sealed record StoredLease(string Id, string Owner, DateTimeOffset CreatedUtc);

    private static string LockDir(string relativeFile)
    {
        string path = relativeFile.Replace('\\', '/').TrimStart('/');
        if (path.Length == 0 || path.Contains("..", StringComparison.Ordinal) ||
            path.Contains('\r') || path.Contains('\n') || path.EndsWith('/'))
            throw new ArgumentException("유효하지 않은 잠금 대상 경로입니다.");
        return path + ".nasmount.lock";
    }

    private static string RemotePath(NasProfile profile, string relative)
    {
        string root = profile.Share.Replace('\\', '/').TrimEnd('/');
        return root.Length == 0 ? "/" + relative : root + "/" + relative;
    }

    private static string FingerprintNormalized(string value) =>
        value.Trim().Replace("SHA256:", "", StringComparison.OrdinalIgnoreCase).TrimEnd('=');

    public static Lease Acquire(NasProfile profile, string password, string relativeFile,
        bool useWebDavNativeLock = true)
    {
        string lockFolder = RemotePath(profile, LockDir(relativeFile));
        var lease = new Lease(relativeFile, Guid.NewGuid().ToString("N"),
            Environment.UserName + "@" + Environment.MachineName, DateTimeOffset.UtcNow);
        var json = JsonSerializer.Serialize(new StoredLease(lease.Id, lease.Owner, lease.CreatedUtc));
        switch (profile.Protocol)
        {
            case "WebDAV":
                using (var client = WebDav(profile, password))
                {
                    var uri = DavUri(profile, LockDir(relativeFile));
                    using var create = new HttpRequestMessage(new HttpMethod("MKCOL"), uri);
                    using var response = client.Send(create);
                    if (response.StatusCode != HttpStatusCode.Created)
                    {
                        // 423 is an explicit server-side lock conflict.
                        // 405 can mean either an existing lock directory OR
                        // an unsupported MKCOL verb, so verify owner.json
                        // before claiming another editor has the file open.
                        if (response.StatusCode == HttpStatusCode.Locked)
                            throw new LockConflictException(
                                "WebDAV 파일이 서버에서 잠겨 있습니다. HTTP " +
                                (int)response.StatusCode);
                        if (response.StatusCode == HttpStatusCode.MethodNotAllowed)
                        {
                            using var ownerProbe = client.Send(
                                new HttpRequestMessage(HttpMethod.Head,
                                    uri.TrimEnd('/') + "/owner.json"));
                            if (ownerProbe.IsSuccessStatusCode)
                                throw new LockConflictException(
                                    "WebDAV 잠금 소유자 기록이 이미 있습니다. HTTP 405");
                        }
                        throw new IOException(
                            "WebDAV 파일 잠금 요청이 거절됐습니다(다른 사용자 점유 여부 미확인): HTTP " +
                            (int)response.StatusCode);
                    }
                    // If metadata upload fails, keep the lock directory to fail closed.
                    using var put = new HttpRequestMessage(HttpMethod.Put, uri.TrimEnd('/') + "/owner.json");
                    put.Content = new StringContent(json, Encoding.UTF8, "application/json");
                    using var result = client.Send(put);
                    result.EnsureSuccessStatusCode();
                    // v1.3.83: do not create a second fake workbook on the NAS.
                    // The atomic lock directory is the sole cooperative
                    // arbitration primitive. Older marker cleanup in Release
                    // remains for leases created by legacy processes.
                    // RFC4918 native server lock in addition to the cooperative marker.
                    string target = DavUri(profile, relativeFile);
                    using var head = client.Send(new HttpRequestMessage(HttpMethod.Head, target));
                    if (head.IsSuccessStatusCode && useWebDavNativeLock)
                    {
                        const string xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                            "<D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/>" +
                            "</D:lockscope><D:locktype><D:write/></D:locktype>" +
                            "<D:owner>NASMount</D:owner></D:lockinfo>";
                        using var request = new HttpRequestMessage(new HttpMethod("LOCK"), target);
                        request.Headers.TryAddWithoutValidation("Timeout", "Second-600");
                        request.Content = new StringContent(xml, Encoding.UTF8, "application/xml");
                        using var lockResult = client.Send(request);
                        if (lockResult.IsSuccessStatusCode)
                        {
                            if (!lockResult.Headers.TryGetValues("Lock-Token", out var tokens))
                                throw new IOException("WebDAV LOCK 응답에 Lock-Token 없음. 마커 유지.");
                            lease = lease with { DavToken = tokens.FirstOrDefault() };
                        }
                        else if (lockResult.StatusCode is HttpStatusCode.MethodNotAllowed or HttpStatusCode.NotImplemented)
                            AppStore.Log("WebDAV LOCK unsupported. Cooperative lock only.");
                        else
                            throw new IOException("WebDAV LOCK 실패 HTTP " + (int)lockResult.StatusCode + ". 마커 유지.");
                    }
                }
                break;
            case "SFTP":
                using (var client = Sftp(profile, password))
                {
                    EnsureExclusiveSftpMkdir(profile, client);
                    if (client.Exists(lockFolder))
                        throw new LockConflictException(
                            "SFTP 파일잠금 충돌: 이미 다른 FileHub AIO에서 사용 중입니다.");
                    client.CreateDirectory(lockFolder); // atomic server-side mkdir, never ignore failure
                    using var stream = new MemoryStream(Encoding.UTF8.GetBytes(json));
                    client.UploadFile(stream, lockFolder + "/owner.json", false);
                    // No additional OfficeUseMarker workbook. SFTP atomic
                    // mkdir is the only cooperative cross-PC lock primitive.
                }
                break;
            case "FTP":
                using (var client = Ftp(profile, password))
                {
                    EnsureExclusiveFtpMkdir(profile, client);
                    // FTP MKD is unfortunately idempotent on some servers,
                    // returning 257 even when the directory ALREADY exists.
                    // Never overwrite another client's owner.json in that
                    // case. Servers without exclusive MKD semantics require
                    // a separate atomic-lock capability check (fail closed).
                    if (client.DirectoryExists(lockFolder))
                        throw new LockConflictException(
                            "FTP 파일잠금 충돌: 이미 다른 FileHub AIO에서 사용 중입니다.");
                    // Raw MKD avoids an existence-then-create race in convenience methods.
                    var reply = client.Execute("MKD " + lockFolder);
                    if (!reply.Success)
                        throw new IOException("FTP 파일 잠금 충돌/오류: " + reply.Code + ". 편집을 시작하지 마세요.");
                    var result = client.UploadBytes(Encoding.UTF8.GetBytes(json),
                        lockFolder + "/owner.json", FtpRemoteExists.NoCheck);
                    if (result != FtpStatus.Success)
                        throw new IOException("FTP 잠금 소유자 기록 실패. 안전을 위해 잠금 디렉터리를 유지합니다.");
                    // No additional OfficeUseMarker workbook. FTP servers
                    // without atomic MKD remain fail-closed (see preflight).
                }
                break;
            default:
                throw new InvalidOperationException("이 잠금 엔진은 WebDAV/SFTP/FTP용입니다.");
        }
        AppStore.Log("REMOTE_LOCK ACQUIRED " + profile.Protocol + " " + relativeFile + " owner=" + lease.Owner);
        return lease;
    }

    public static void Release(NasProfile profile, string password, Lease lease)
    {
        string path = RemotePath(profile, LockDir(lease.Resource));
        // Check token ownership before deleting any shared metadata.
        switch (profile.Protocol)
        {
            case "WebDAV":
                using (var client = WebDav(profile, password))
                {
                    string baseUri = DavUri(profile, LockDir(lease.Resource)).TrimEnd('/');
                    var data = client.GetStringAsync(baseUri + "/owner.json").GetAwaiter().GetResult();
                    if (JsonSerializer.Deserialize<StoredLease>(data)?.Id != lease.Id)
                        throw new IOException("잠금 소유자 불일치: 다른 사용자의 잠금을 삭제하지 않습니다.");
                    if (!string.IsNullOrEmpty(lease.VisibleMarker))
                    {
                        try
                        {
                            OfficeUseMarker.DeleteIfOwned(client,
                                DavUri(profile, lease.VisibleMarker), lease);
                            AppStore.Log("REMOTE_LOCK VISIBLE_MARKER_CLEANED " +
                                lease.VisibleMarker);
                        }
                        catch (Exception ex)
                        {
                            // Retain the actual lock semantics. A marker
                            // cleanup failure is logged, never silently
                            // deletes a marker owned by another client.
                            AppStore.Log("REMOTE_LOCK VISIBLE_MARKER_CLEANUP_WARNING " +
                                lease.VisibleMarker + " " + ex.Message);
                        }
                    }
                    if (!string.IsNullOrEmpty(lease.DavToken))
                    {
                        using var unlock = new HttpRequestMessage(new HttpMethod("UNLOCK"), DavUri(profile, lease.Resource));
                        unlock.Headers.TryAddWithoutValidation("Lock-Token", lease.DavToken);
                        using var result = client.Send(unlock);
                        result.EnsureSuccessStatusCode();
                    }
                    using var request = new HttpRequestMessage(HttpMethod.Delete, baseUri);
                    using var response = client.Send(request);
                    response.EnsureSuccessStatusCode();
                }
                break;
            case "SFTP":
                using (var client = Sftp(profile, password))
                {
                    string data;
                    using (var reader = new StreamReader(
                        client.OpenRead(path + "/owner.json"), Encoding.UTF8))
                        data = reader.ReadToEnd();
                    if (JsonSerializer.Deserialize<StoredLease>(data)?.Id != lease.Id)
                        throw new IOException("잠금 소유자 불일치: 잠금 삭제 금지.");
                    if (!string.IsNullOrEmpty(lease.VisibleMarker))
                    {
                        string marker = RemotePath(profile, lease.VisibleMarker);
                        try
                        {
                            if (client.Exists(marker))
                            {
                                byte[] content;
                                using (var markerReader = client.OpenRead(marker))
                                using (var bytes = new MemoryStream())
                                {
                                    markerReader.CopyTo(bytes);
                                    content = bytes.ToArray();
                                }
                                if (OfficeUseMarker.ContentBelongsTo(content, lease))
                                    client.DeleteFile(marker);
                                else
                                    AppStore.Log("SFTP_VISIBLE_MARKER_NOT_OURS " + marker);
                            }
                        }
                        catch (Exception ex)
                        {
                            AppStore.Log("SFTP_VISIBLE_MARKER_CLEAN_WARNING " +
                                marker + " " + ex.Message);
                        }
                    }
                    client.DeleteFile(path + "/owner.json");
                    client.DeleteDirectory(path);
                }
                break;
            case "FTP":
                using (var client = Ftp(profile, password))
                {
                    string data;
                    using (var reader = new StreamReader(
                        client.OpenRead(path + "/owner.json"), Encoding.UTF8))
                        data = reader.ReadToEnd();
                    if (JsonSerializer.Deserialize<StoredLease>(data)?.Id != lease.Id)
                        throw new IOException("잠금 소유자 불일치: 잠금 삭제 금지.");
                    if (!string.IsNullOrEmpty(lease.VisibleMarker))
                    {
                        string marker = RemotePath(profile, lease.VisibleMarker);
                        try
                        {
                            if (client.FileExists(marker))
                            {
                                byte[] content;
                                using (var markerReader = client.OpenRead(marker))
                                using (var bytes = new MemoryStream())
                                {
                                    markerReader.CopyTo(bytes);
                                    content = bytes.ToArray();
                                }
                                if (OfficeUseMarker.ContentBelongsTo(content, lease))
                                    client.DeleteFile(marker);
                                else
                                    AppStore.Log("FTP_VISIBLE_MARKER_NOT_OURS " + marker);
                            }
                        }
                        catch (Exception ex)
                        {
                            AppStore.Log("FTP_VISIBLE_MARKER_CLEAN_WARNING " +
                                marker + " " + ex.Message);
                        }
                    }
                    client.DeleteFile(path + "/owner.json");
                    client.DeleteDirectory(path);
                }
                break;
            default:
                throw new InvalidOperationException("지원하지 않는 잠금 프로토콜.");
        }
        AppStore.Log("REMOTE_LOCK RELEASED " + profile.Protocol + " " + lease.Resource);
    }

    // Test atomic conflict handling on a harmless temporary marker.
    // Never assume this proves Windows Explorer/Office open interception.
    public static string Test(NasProfile profile, string password)
    {
        string docName = ".nasmount_probe_" + Guid.NewGuid().ToString("N") + ".xlsx";
        Lease? first = null;
        try
        {
            first = Acquire(profile, password, docName);
            bool blocked = false;
            try { Acquire(profile, password, docName); }
            catch (IOException) { blocked = true; }
            catch (Renci.SshNet.Common.SshException) { blocked = true; }
            if (!blocked) throw new IOException("두 번째 잠금이 허용되었습니다: 협력 잠금 충돌 실패!");
            return "서버 잠금 생성 및 두 번째 잠금 충돌 확인 성공. (Office 자동 잠금은 별도 검증 필요)";
        }
        finally
        {
            if (first is not null) Release(profile, password, first);
        }
    }

    private static void EnsureExclusiveSftpMkdir(NasProfile profile, SftpClient client)
    {
        string endpoint = profile.Host + ":" + profile.Port + "/" +
            profile.Share + "/" + profile.Username + "/" + profile.SftpHostFingerprint;
        if (SftpExclusiveMkdirVerified.ContainsKey(endpoint)) return;
        string probe = RemotePath(profile,
            ".nasmount_mkdir_probe_" + Guid.NewGuid().ToString("N"));
        bool created = false;
        try
        {
            client.CreateDirectory(probe);
            created = true;
            bool duplicateAccepted = false;
            try { client.CreateDirectory(probe); duplicateAccepted = true; }
            catch (Renci.SshNet.Common.SshException) { }
            if (duplicateAccepted)
                throw new IOException(
                    "이 SFTP 서버는 이미 존재하는 폴더의 mkdir도 성공 처리합니다. " +
                    "배타적 파일잠금이 보장되지 않아 편집을 허용하지 않습니다. " +
                    "NAS의 SSH/SFTP 서버 설정을 확인하거나 WebDAV 파일잠금을 사용하세요.");
            SftpExclusiveMkdirVerified.TryAdd(endpoint, true);
            AppStore.Log("SFTP_EXCLUSIVE_MKDIR_VERIFIED " + profile.Host +
                " port=" + (profile.Port == 0 ? 22 : profile.Port));
        }
        finally
        {
            if (created)
            {
                try { client.DeleteDirectory(probe); }
                catch (Exception ex)
                {
                    AppStore.Log("SFTP_MKDIR_PROBE_CLEANUP_WARNING " + ex.Message);
                }
            }
        }
    }

    /// <summary>
    /// FTP has no mandatory exclusive-file-create primitive. Require proof
    /// that THIS server's MKD is non-idempotent; otherwise concurrent FileHub AIO
    /// clients could both acquire the same lease. Fail closed, not falsely safe.
    /// </summary>
    private static void EnsureExclusiveFtpMkdir(NasProfile profile, FtpClient client)
    {
        string endpoint = profile.Host + ":" + profile.Port + "/" +
            profile.Share + "/" + profile.Username + "/" + profile.ExplicitTls;
        if (FtpExclusiveMkdirVerified.ContainsKey(endpoint)) return;
        string probe = RemotePath(profile,
            ".nasmount_mkd_probe_" + Guid.NewGuid().ToString("N"));
        bool created = false;
        try
        {
            var first = client.Execute("MKD " + probe);
            if (!first.Success)
                throw new IOException("FTP 잠금 서버 검사: MKD 폴더 생성 불가 (" +
                    first.Code + ").");
            created = true;
            var duplicate = client.Execute("MKD " + probe);
            if (duplicate.Success)
                throw new IOException(
                    "이 FTP 서버는 이미 있는 폴더의 MKD 요청도 성공 처리합니다. " +
                    "두 PC의 배타적 잠금을 보장할 수 없어 파일잠금을 허용하지 않습니다. " +
                    "SFTP 또는 WebDAV 파일잠금 사용을 권장합니다.");
            FtpExclusiveMkdirVerified.TryAdd(endpoint, true);
            AppStore.Log("FTP_EXCLUSIVE_MKD_VERIFIED " + profile.Host +
                " port=" + (profile.Port == 0 ? 21 : profile.Port));
        }
        finally
        {
            if (created)
            {
                try { client.DeleteDirectory(probe); }
                catch (Exception ex)
                {
                    AppStore.Log("FTP_MKD_PROBE_CLEANUP_WARNING " + ex.Message);
                }
            }
        }
    }

    private static string DavUri(NasProfile profile, string relative)
    {
        int port = profile.Port == 0 ? 5006 : profile.Port;
        string path = RemotePath(profile, relative);
        return (profile.WebDavUseHttp ? "http://" : "https://") + profile.Host + ":" + port + "/" +
            string.Join("/", path.Split('/', StringSplitOptions.RemoveEmptyEntries)
                .Select(Uri.EscapeDataString));
    }

    private static HttpClient WebDav(NasProfile profile, string password)
    {
        var client = WebDavTlsPolicy.CreateClient(profile,
            TimeSpan.FromSeconds(15));
        if (!profile.IsAnonymous)
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Basic",
                Convert.ToBase64String(Encoding.UTF8.GetBytes(profile.Username + ":" + password)));
        return client;
    }

    private static SftpClient Sftp(NasProfile profile, string password)
    {
        if (profile.SftpHostFingerprint.Length == 0)
            throw new InvalidOperationException("SFTP 잠금 사용 전 NAS 서버의 SHA256 SSH 호스트 키 지문을 설정하세요.");
        var client = new SftpClient(profile.Host, profile.Port == 0 ? 22 : profile.Port,
            profile.IsAnonymous ? "anonymous" : profile.Username,
            profile.IsAnonymous ? "" : password);
        client.HostKeyReceived += (_, e) =>
            e.CanTrust = string.Equals(FingerprintNormalized(e.FingerPrintSHA256),
                FingerprintNormalized(profile.SftpHostFingerprint), StringComparison.Ordinal);
        client.Connect();
        return client;
    }

    private static FtpClient Ftp(NasProfile profile, string password)
    {
        var client = new FtpClient(profile.Host,
            profile.IsAnonymous ? "anonymous" : profile.Username,
            profile.IsAnonymous ? "anonymous@" : password,
            profile.Port == 0 ? 21 : profile.Port);
        client.Config.EncryptionMode = profile.FtpImplicitTls ? FtpEncryptionMode.Implicit :
            profile.ExplicitTls ? FtpEncryptionMode.Explicit : FtpEncryptionMode.None;
        if (profile.FtpTls12Only)
            client.Config.SslProtocols = System.Security.Authentication.SslProtocols.Tls12;
        client.Connect();
        return client;
    }
}
