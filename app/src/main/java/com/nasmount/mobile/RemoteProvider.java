package com.nasmount.mobile;

import android.util.Base64;
import android.webkit.MimeTypeMap;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;
import jcifs.smb.SmbFileOutputStream;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPSClient;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Network transport. All methods must execute on a worker thread. */
final class RemoteProvider {
    private RemoteProvider() {}
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(11, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS).build();

    // Relax only WebDAV's DNS-alias name comparison after normal platform
    // CA trust-chain verification. Other protocols keep standard TLS.
    static OkHttpClient webClient(Models.RemoteProfile p) {
        if (!"WebDAV".equals(p.protocol) || !p.ssl) return HTTP;
        return HTTP.newBuilder()
                .hostnameVerifier(WebDavTlsAliasPolicy.forProfile(p))
                .build();
    }

    // Server-side COPY of a large folder and recursive collection DELETE
    // can legitimately take longer than a normal small-file 35s read.
    // Extend only these long-running server operations, not every request.
    private static OkHttpClient webDavServerOperationClient(Models.RemoteProfile p) {
        return webClient(p).newBuilder()
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
    }

    static final class UntrustedHostKey extends IOException {
        final String fingerprint;
        UntrustedHostKey(String fingerprint) {
            super("새 SFTP 서버의 호스트 키를 확인해야 합니다.");
            this.fingerprint = fingerprint;
        }
    }

    static String relativePath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return "/";
        StringBuilder out = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") || part.indexOf('\0') >= 0)
                throw new IllegalArgumentException("허용되지 않는 경로입니다.");
            out.append('/').append(part);
        }
        return out.length() == 0 ? "/" : out.toString();
    }
    static String child(String parent, String name) {
        if (name == null || name.trim().isEmpty() ||
                name.contains("/") || name.contains("\\") ||
                name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("허용되지 않는 파일명입니다.");
        return relativePath((parent.equals("/") ? "" : parent) + "/" + name);
    }
    static String parent(String path) {
        String safe = relativePath(path);
        int i = safe.lastIndexOf('/');
        return i <= 0 ? "/" : safe.substring(0, i);
    }
    private static String absolute(Models.RemoteProfile p, String path) {
        String base = relativePath(p.root);
        String sub = relativePath(path);
        return relativePath((base.equals("/") ? "" : base) +
                (sub.equals("/") ? "" : sub));
    }
    private static int port(Models.RemoteProfile p, int defaultPort) {
        return p.port > 0 ? p.port : defaultPort;
    }
    private static String encodedPath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : relativePath(path).split("/")) {
            if (part.isEmpty()) continue;
            try {
                out.append('/').append(URLEncoder.encode(part, "UTF-8")
                        .replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException impossible) {
                throw new IllegalStateException("UTF-8 unavailable", impossible);
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }
    private static String smbPath(Models.RemoteProfile p, String path, boolean folder) {
        if (p.root == null || relativePath(p.root).equals("/"))
            throw new IllegalArgumentException("SMB 공유 이름을 입력하세요.");
        String s = "smb://" + p.host.trim() +
                (p.port > 0 && p.port != 445 ? ":" + p.port : "") +
                encodedPath(absolute(p, path));
        return folder && !s.endsWith("/") ? s + "/" : s;
    }
    private static CIFSContext smbContext(Models.RemoteProfile p) throws Exception {
        Properties props = new Properties();
        props.setProperty("jcifs.smb.client.responseTimeout", "16000");
        props.setProperty("jcifs.smb.client.connTimeout", "12000");
        CIFSContext context = new BaseContext(new PropertyConfiguration(props));
        if (!p.username.isEmpty()) {
            String domain = "";
            String user = p.username;
            int sep = user.indexOf('\\');
            if (sep > 0) { domain = user.substring(0, sep); user = user.substring(sep + 1); }
            context = context.withCredentials(
                    new NtlmPasswordAuthenticator(domain, user, p.password));
        }
        return context;
    }

    private static FTPClient ftp(Models.RemoteProfile p) throws Exception {
        if (p.ftpExplicitTls && p.ftpImplicitTls)
            throw new IOException("FTP TLS 설정이 중복됐습니다.");
        // FTPES and implicit FTPS validate TLS certificates/hostnames. Never
        // install a permissive TrustManager or accept-all hostname verifier.
        FTPClient ftp = (p.ftpExplicitTls || p.ftpImplicitTls)
                ? new FTPSClient(p.ftpImplicitTls) : new FTPClient();
        if (ftp instanceof FTPSClient) {
            FTPSClient secure = (FTPSClient) ftp;
            secure.setEndpointCheckingEnabled(true);
            if (p.ftpTls12Only)
                secure.setEnabledProtocols(new String[]{"TLSv1.2"});
        }
        ftp.setConnectTimeout(11000);
        ftp.setDefaultTimeout(16000);
        ftp.setDataTimeout(35000);
        ftp.setControlEncoding(ftpEncoding(p.ftpCharset));
        ftp.connect(p.host.trim(), port(p, p.ftpImplicitTls ? 990 : 21));
        String user = p.username.isEmpty() ? "anonymous" : p.username;
        String pass = p.username.isEmpty() ? "anonymous@" : p.password;
        if (!ftp.login(user, pass))
            throw new IOException("FTP 로그인에 실패했습니다.");
        if (ftp instanceof FTPSClient) {
            FTPSClient secure = (FTPSClient) ftp;
            secure.execPBSZ(0);
            secure.execPROT("P");
        }
        if (p.ftpPreferPasv) ftp.enterLocalPassiveMode();
        else ftp.enterLocalActiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }
    static String ftpEncoding(String requested) {
        if (requested == null) return "UTF-8";
        switch (requested.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "CP949":
            case "MS949":
            case "CP949 / EUC-KR (한국어)": return "MS949";
            case "EUC-KR": return "EUC-KR";
            case "서버 기본": return "ISO-8859-1";
            case "UTF-8": return "UTF-8";
            default: throw new IllegalArgumentException("지원하지 않는 FTP 문자셋");
        }
    }
    private static void closeFtp(FTPClient client) {
        if (client == null) return;
        try { if (client.isConnected()) client.logout(); }
        catch (Exception ignored) {}
        try { if (client.isConnected()) client.disconnect(); }
        catch (Exception ignored) {}
    }

    private static SSHClient ssh(Models.RemoteProfile p) throws Exception {
        // Prefer ECDSA over Ed25519 on Android where Conscrypt's EdDSA
        // public-key wrapper is not always supported by SSHJ 0.41.x.
        // The SteamOS/OpenSSH server offers ECDSA; retain Ed25519 fallback.
        net.schmizz.sshj.DefaultConfig sshConfig =
                SftpSshAlgorithms.androidCompatible();
        try {
            SftpSshAlgorithms.verifyRequiredCrypto();
        } catch (java.security.GeneralSecurityException cryptoFailure) {
            throw SftpConnectionDiagnostics.from("CRYPTO", cryptoFailure);
        }
        SSHClient ssh = new SSHClient(sshConfig);
        ssh.setConnectTimeout(12000);
        // Apply the selected encoding to actual SSHJ SFTP filename packets.
        // A saved setting without setRemoteCharset would not affect any file.
        ssh.setRemoteCharset(SftpFilenameCharset.resolve(p.sftpCharset));
        SftpHostKeyTrust trust = new SftpHostKeyTrust(p.fingerprint);
        ssh.addHostKeyVerifier(new HostKeyVerifier() {
            @Override public List<String> findExistingAlgorithms(String host, int serverPort) {
                return Collections.emptyList();
            }
            @Override public boolean verify(String hostname, int serverPort,
                                             java.security.PublicKey key) {
                // Never throw on the SSHJ transport thread: it can wrap the
                // first-connect confirmation as a generic TransportException.
                return trust.verify(key);
            }
        });
        String phase = "HANDSHAKE";
        try {
            ssh.connect(p.host.trim(), port(p, 22));
            phase = "AUTH";
            ssh.authPassword(p.username, p.password);
            return ssh;
        } catch (Exception e) {
            try { ssh.close(); } catch (IOException ignored) {}
            // Translate SSHJ's transport-layer host key rejection back into
            // a user-facing confirmation instead of a misleading network
            // failure; changed keys are still strictly rejected.
            trust.rethrowIfRejected();
            throw SftpConnectionDiagnostics.from(phase, e);
        }
    }

    /** Blank SFTP start path means login home; literal '/' explicitly means root. */
    static boolean sftpUsesLoginHome(Models.RemoteProfile profile) {
        return profile != null && "SFTP".equals(profile.protocol) &&
                (profile.root == null || profile.root.trim().isEmpty());
    }

    /** SFTP REALPATH(.) is the authenticated account's actual login home.
     * Never guess /home/{user}: NAS appliances and chrooted users differ. */
    static String sftpLoginHome(Models.RemoteProfile profile) throws Exception {
        if (!"SFTP".equals(profile.protocol))
            throw new IllegalArgumentException("SFTP 연결에만 사용할 수 있습니다.");
        try (SSHClient ssh = ssh(profile);
             SFTPClient sftp = ssh.newSFTPClient()) {
            String home = sftp.canonicalize(".");
            if (home == null || !home.startsWith("/"))
                throw new IOException("SFTP 서버가 절대 홈 경로를 반환하지 않았습니다.");
            return relativePath(home);
        } catch (UntrustedHostKey | SftpConnectionDiagnostics |
                 SecurityException diagnostic) {
            throw diagnostic;
        } catch (Exception error) {
            throw SftpConnectionDiagnostics.from("HOME", error);
        }
    }

    /** Whitelisted user-visible SFTP permission errors: no server path leaked. */
    static final class SftpPermissionFailure extends IOException {
        SftpPermissionFailure(String reason) { super(reason); }
    }

    /** Read chmod bits using LSTAT to avoid changing a symlink's target. */
    static int sftpPermissions(Models.RemoteProfile p, String path) throws Exception {
        if (p == null || !"SFTP".equals(p.protocol))
            throw new IllegalArgumentException("리눅스 파일 권한은 SFTP에서만 확인할 수 있습니다.");
        try (SSHClient ssh = ssh(p);
             SFTPClient sftp = ssh.newSFTPClient()) {
            net.schmizz.sshj.sftp.FileAttributes attr =
                    sftp.lstat(absolute(p, relativePath(path)));
            if (attr.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK)
                throw new SftpPermissionFailure(
                        "심볼릭 링크는 대상 파일에 영향을 줄 수 있어 chmod를 지원하지 않습니다.");
            return LinuxFilePermissions.checkedMode(attr.getMode().getPermissionsMask());
        } catch (net.schmizz.sshj.sftp.SFTPException denied) {
            throw new SftpPermissionFailure(
                    "SFTP 서버에서 파일 권한 조회를 거부했습니다. 계정과 파일 접근 권한을 확인하세요.");
        }
    }

    /** Standard SFTP SETSTAT chmod, no SSH shell/sudo. Reject stale modes. */
    static int sftpChmod(Models.RemoteProfile p, String path,
                         int expectedMode, int newMode) throws Exception {
        if (p == null || !"SFTP".equals(p.protocol))
            throw new IllegalArgumentException("리눅스 권한 변경은 SFTP에서만 지원합니다.");
        if (p.readOnly)
            throw new SftpPermissionFailure("읽기 전용 SFTP 연결에서는 권한을 변경할 수 없습니다.");
        LinuxFilePermissions.checkedMode(expectedMode);
        LinuxFilePermissions.checkedMode(newMode);
        try (SSHClient ssh = ssh(p);
             SFTPClient sftp = ssh.newSFTPClient()) {
            String target = absolute(p, relativePath(path));
            net.schmizz.sshj.sftp.FileAttributes attr = sftp.lstat(target);
            if (attr.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK)
                throw new SftpPermissionFailure("심볼릭 링크의 권한 변경은 지원하지 않습니다.");
            if (attr.getMode().getPermissionsMask() != expectedMode)
                throw new SftpPermissionFailure("서버에서 파일 권한이 이미 변경됐습니다. 다시 확인하세요.");
            sftp.chmod(target, newMode);
            int verified = sftp.lstat(target).getMode().getPermissionsMask();
            if (verified != newMode)
                throw new SftpPermissionFailure("서버가 chmod 변경을 일부 적용하지 않았습니다. " +
                        "현재 권한: " + LinuxFilePermissions.octal(verified));
            return verified;
        } catch (net.schmizz.sshj.sftp.SFTPException denied) {
            throw new SftpPermissionFailure(
                    "SFTP 서버가 chmod 요청을 거부했습니다. 파일 소유자 또는 쓰기 권한을 확인하세요.");
        }
    }

    private static HttpUrl webUrl(Models.RemoteProfile p, String path, boolean folder) {
        HttpUrl.Builder b = new HttpUrl.Builder()
                .scheme(p.ssl ? "https" : "http")
                .host(p.host.trim())
                .port(port(p, p.ssl ? 443 : 80));
        for (String part : absolute(p, path).split("/"))
            if (!part.isEmpty()) b.addPathSegment(part);
        if (folder) b.addPathSegment("");
        return b.build();
    }
    static Request.Builder webRequest(Models.RemoteProfile p, String path,
                                              boolean folder) {
        Request.Builder b = new Request.Builder().url(webUrl(p, path, folder));
        if (!p.username.isEmpty()) {
            String auth = p.username + ":" + p.password;
            b.header("Authorization", "Basic " + Base64.encodeToString(
                    auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        return b;
    }

    static WebDavQuota webdavQuota(Models.RemoteProfile p) throws Exception {
        if (!"WebDAV".equals(p.protocol))
            throw new IllegalArgumentException("WebDAV 전용 용량 조회");
        Request request = webRequest(p, "/", true)
                .header("Depth", "0")
                .method("PROPFIND", RequestBody.create(
                        "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                        "<d:quota-available-bytes/><d:quota-used-bytes/>" +
                        "</d:prop></d:propfind>",
                        MediaType.parse("application/xml; charset=utf-8")))
                .build();
        try (Response response = webClient(p).newCall(request).execute()) {
            if (response.code() != 207 || response.body() == null) return null;
            // Okio readUtf8(byteCount) insists on exactly that many bytes,
            // so short, valid quota replies would otherwise throw EOF.
            ByteArrayOutputStream limited = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int count;
            try (InputStream input = response.body().byteStream()) {
                while ((count = input.read(bytes)) != -1) {
                    if (limited.size() + count > 65_536) return null;
                    limited.write(bytes, 0, count);
                }
            }
            String xml = limited.toString("UTF-8");
            return WebDavQuota.parse(xml);
        }
    }

    static final class WebDavRemoteVersion {
        final boolean exists;
        final String etag;
        WebDavRemoteVersion(boolean exists, String etag) {
            this.exists = exists;
            this.etag = etag;
        }
    }

    /**
     * Some NAS modules omit ETag on HEAD but expose DAV:getetag via
     * PROPFIND. Never invent an ETag from dates or sizes.
     */
    private static String webdavPropfindEtag(Models.RemoteProfile p, String path)
            throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/>" +
                "</d:prop></d:propfind>";
        Request request = webRequest(p, path, false).header("Depth", "0")
                .method("PROPFIND", RequestBody.create(body,
                        MediaType.parse("application/xml; charset=utf-8"))).build();
        try (Response response = webClient(p).newCall(request).execute()) {
            if (response.code() != 207 || response.body() == null) return null;
            byte[] buffer = response.body().bytes();
            if (buffer.length > 32768) return null;
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            org.w3c.dom.Document xml = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(buffer));
            org.w3c.dom.NodeList tags = xml.getElementsByTagNameNS("DAV:", "getetag");
            for (int i = 0; i < tags.getLength(); i++) {
                String value = tags.item(i).getTextContent().trim();
                try { return WebDavWriteGuard.checkedEtag(value); }
                catch (IOException weakOrMissing) { /* continue to next propstat */ }
            }
        } catch (javax.xml.parsers.ParserConfigurationException |
                 org.xml.sax.SAXException malformed) {
            android.util.Log.w("FileHubAIO", "Malformed WebDAV PROPFIND ETag response");
        }
        return null;
    }

    /** 404 = absent. Missing strong ETag is allowed only in read-only probes. */
    static WebDavRemoteVersion webdavDestinationState(
            Models.RemoteProfile p, String path, boolean allowLockInsteadOfEtag)
            throws Exception {
        Request head = webRequest(p, path, false).head().build();
        try (Response response = webClient(p).newCall(head).execute()) {
            if (response.code() == 404) return new WebDavRemoteVersion(false, null);
            if (!response.isSuccessful())
                throw new IOException("WebDAV 변경 충돌 확인 실패 (HTTP " +
                        response.code() + ")");
            String strong = null;
            try { strong = WebDavWriteGuard.checkedEtag(response.header("ETag")); }
            catch (IOException noHeader) { /* PROPFIND may still supply it */ }
            if (strong == null) strong = webdavPropfindEtag(p, path);
            if (strong == null && !allowLockInsteadOfEtag)
                throw new IOException("NAS가 강한 ETag를 제공하지 않아 안전한 덮어쓰기가 불가능합니다.");
            return new WebDavRemoteVersion(true, strong);
        }
    }

    static List<Models.Entry> list(Models.RemoteProfile p, String path) throws Exception {
        if (CloudProvider.isCloud(p)) return CloudProvider.list(p, relativePath(path));
        String normalized = relativePath(path);
        switch (p.protocol) {
            case "SMB":
                try (SmbFile dir = new SmbFile(smbPath(p, normalized, true),
                        smbContext(p))) {
                    List<Models.Entry> out = new ArrayList<>();
                    for (SmbFile f : dir.listFiles()) {
                        String n = f.getName().replaceAll("/$", "");
                        if (n.isEmpty() || n.equals(".") || n.equals("..")) continue;
                        Models.Entry e = new Models.Entry(n, child(normalized, n),
                                f.isDirectory());
                        if (!e.directory) e.size = f.length();
                        e.modified = f.getLastModified();
                        out.add(e);
                    }
                    return sort(out);
                }
            case "FTP":
                FTPClient ftp = ftp(p);
                try {
                    FTPFile[] files = ftp.listFiles(absolute(p, normalized));
                    if (files == null || ftp.getReplyCode() >= 400)
                        throw new IOException("FTP 폴더 목록을 읽을 수 없습니다.");
                    List<Models.Entry> out = new ArrayList<>();
                    for (FTPFile f : files) {
                        String n = f.getName();
                        if (n.equals(".") || n.equals("..") || n.isEmpty()) continue;
                        Models.Entry e = new Models.Entry(n, child(normalized, n),
                                f.isDirectory());
                        e.size = f.getSize();
                        if (f.getTimestamp() != null)
                            e.modified = f.getTimestamp().getTimeInMillis();
                        out.add(e);
                    }
                    return sort(out);
                } finally { closeFtp(ftp); }
            case "SFTP":
                try (SSHClient ssh = ssh(p);
                     SFTPClient sftp = ssh.newSFTPClient()) {
                    List<Models.Entry> out = new ArrayList<>();
                    for (RemoteResourceInfo item : sftp.ls(absolute(p, normalized))) {
                        String n = item.getName();
                        if (n.equals(".") || n.equals("..") || n.isEmpty()) continue;
                        String childPath = child(normalized, n);
                        net.schmizz.sshj.sftp.FileAttributes attrs =
                                item.getAttributes();
                        net.schmizz.sshj.sftp.FileAttributes target = null;
                        if (attrs.getType() ==
                                net.schmizz.sshj.sftp.FileMode.Type.SYMLINK) {
                            // OpenSSH READDIR reports the link itself as a
                            // file. Follow it ONLY to classify its target
                            // for browsing. Keep the original alias path.
                            try {
                                target = sftp.stat(absolute(p, childPath));
                            } catch (net.schmizz.sshj.sftp.SFTPException unavailable) {
                                // A broken link or permission-denied target
                                // must not make the entire folder disappear.
                                target = null;
                            }
                        }
                        out.add(SftpLinkPolicy.entry(n, childPath, attrs, target));
                    }
                    return sort(out);
                } catch (UntrustedHostKey | SftpConnectionDiagnostics |
                         SecurityException diagnostic) {
                    throw diagnostic;
                } catch (Exception failure) {
                    throw SftpConnectionDiagnostics.from("FOLDER", failure);
                }
            case "WebDAV":
                Request request = webRequest(p, normalized, true)
                        .header("Depth", "1")
                        .header("Accept", "application/xml, text/xml")
                        .method("PROPFIND", RequestBody.create(
                                "<d:propfind xmlns:d='DAV:'><d:allprop/></d:propfind>",
                                MediaType.parse("application/xml; charset=utf-8")))
                        .build();
                try (Response response = webClient(p).newCall(request).execute()) {
                    if (response.code() != 207 || response.body() == null)
                        throw new WebDavHttpFailure(response.code());
                    return sort(WebDavXml.parse(response.body().byteStream(),
                            response.request().url(), normalized));
                }
            default: throw new IllegalArgumentException("지원하지 않는 원격 저장소");
        }
    }

    static final class WebDavHttpFailure extends IOException {
        final int status;
        WebDavHttpFailure(int status) {
            super("WebDAV HTTP " + status);
            this.status = status;
        }
    }

    static void download(Models.RemoteProfile p, String path, OutputStream target)
            throws Exception {
        if (CloudProvider.isCloud(p)) {
            CloudProvider.download(p, relativePath(path), target);
            return;
        }
        String normalized = relativePath(path);
        switch (p.protocol) {
            case "SMB":
                try (SmbFile f = new SmbFile(smbPath(p, normalized, false),
                        smbContext(p)); InputStream in = f.getInputStream()) {
                    copy(in, target);
                }
                return;
            case "FTP":
                FTPClient ftp = ftp(p);
                try {
                    if (!ftp.retrieveFile(absolute(p, normalized), target))
                        throw new IOException("FTP 다운로드에 실패했습니다.");
                } finally { closeFtp(ftp); }
                return;
            case "SFTP":
                try (SSHClient ssh = ssh(p);
                     SFTPClient sftp = ssh.newSFTPClient()) {
                    File temp = File.createTempFile("nas_download_", ".tmp");
                    try {
                        sftp.get(absolute(p, normalized), temp.getAbsolutePath());
                        try (InputStream in = new FileInputStream(temp)) { copy(in, target); }
                    } finally { temp.delete(); }
                }
                return;
            case "WebDAV":
                try (Response response = webClient(p).newCall(webRequest(p, normalized, false)
                        .get().build()).execute()) {
                    if (!response.isSuccessful() || response.body() == null)
                        throw new IOException("WebDAV 다운로드 실패 (HTTP " +
                                response.code() + ")");
                    copy(response.body().byteStream(), target);
                }
                return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }

    /**
     * Seekable network stream. Closing also releases the live transport.
     * No full-sized temporary video is created.
     */
    static InputStream openStream(Models.RemoteProfile p, String path, long position)
            throws Exception {
        if (position < 0) throw new IOException("잘못된 파일 위치입니다.");
        if (CloudProvider.isCloud(p)) return CloudProvider.openStream(p, path, position);
        String normalized = relativePath(path);
        switch (p.protocol) {
            case "SMB": {
                SmbFile file = new SmbFile(smbPath(p, normalized, false), smbContext(p));
                SmbRandomAccessFile handle = null;
                try {
                    handle = new SmbRandomAccessFile(file, "r");
                    handle.seek(position);
                    SmbRandomAccessFile open = handle;
                    return new InputStream() {
                        private boolean closed;
                        @Override public int read() throws IOException { return open.read(); }
                        @Override public int read(byte[] b, int off, int len)
                                throws IOException { return open.read(b, off, len); }
                        @Override public void close() throws IOException {
                            if (closed) return;
                            closed = true;
                            try { open.close(); } finally { file.close(); }
                        }
                    };
                } catch (Exception fail) {
                    if (handle != null) try { handle.close(); } catch (Exception ignored) {}
                    try { file.close(); } catch (Exception ignored) {}
                    throw fail;
                }
            }
            case "FTP": {
                FTPClient client = ftp(p);
                try {
                    client.setRestartOffset(position);
                    InputStream input = client.retrieveFileStream(absolute(p, normalized));
                    if (input == null) throw new IOException("FTP 스트림 열기 실패");
                    return new FilterInputStream(input) {
                        private boolean closed;
                        @Override public void close() throws IOException {
                            if (closed) return;
                            closed = true;
                            try {
                                super.close();
                                if (!client.completePendingCommand())
                                    throw new IOException("FTP 스트리밍 종료 응답 실패");
                            } finally { closeFtp(client); }
                        }
                    };
                } catch (Exception fail) {
                    closeFtp(client);
                    throw fail;
                }
            }
            case "SFTP": {
                SSHClient ssh = ssh(p);
                SFTPClient sftp = null;
                RemoteFile file = null;
                try {
                    sftp = ssh.newSFTPClient();
                    file = sftp.open(absolute(p, normalized));
                    InputStream input = file.new RemoteFileInputStream(position);
                    SFTPClient openClient = sftp;
                    RemoteFile openFile = file;
                    return new FilterInputStream(input) {
                        private boolean closed;
                        @Override public void close() throws IOException {
                            if (closed) return;
                            closed = true;
                            try { super.close(); }
                            finally {
                                try { openFile.close(); }
                                finally { try { openClient.close(); }
                                finally { ssh.close(); } }
                            }
                        }
                    };
                } catch (Exception fail) {
                    if (file != null) try { file.close(); } catch (Exception ignored) {}
                    if (sftp != null) try { sftp.close(); } catch (Exception ignored) {}
                    try { ssh.close(); } catch (Exception ignored) {}
                    throw fail;
                }
            }
            case "WebDAV": {
                Request.Builder request = webRequest(p, normalized, false).get();
                if (position > 0) request.header("Range", "bytes=" + position + "-");
                Response response = webClient(p).newCall(request.build()).execute();
                if (!response.isSuccessful() || response.body() == null ||
                        (position > 0 && response.code() != 206)) {
                    int code = response.code();
                    response.close();
                    throw new IOException(position > 0 && code == 200
                            ? "서버가 영상 탐색에 필요한 HTTP Range를 지원하지 않습니다."
                            : "WebDAV 스트리밍 실패 (HTTP " + code + ")");
                }
                if (position > 0) {
                    String returnedRange = response.header("Content-Range", "");
                    if (!returnedRange.toLowerCase(Locale.ROOT)
                            .startsWith("bytes " + position + "-")) {
                        response.close();
                        throw new IOException("WebDAV 서버가 다른 영상 구간을 반환했습니다.");
                    }
                }
                return new FilterInputStream(response.body().byteStream()) {
                    private boolean closed;
                    @Override public void close() throws IOException {
                        if (closed) return;
                        closed = true;
                        try { super.close(); } finally { response.close(); }
                    }
                };
            }
            default: throw new IOException("지원하지 않는 스트리밍 방식입니다.");
        }
    }

    static void upload(Models.RemoteProfile p, String folder, String name,
                       InputStream input) throws Exception {
        upload(p, folder, name, input, -1L);
    }
    static void upload(Models.RemoteProfile p, String folder, String name,
                       InputStream input, long byteLength) throws Exception {
        upload(p, folder, name, input, byteLength, false);
    }
    static void upload(Models.RemoteProfile p, String folder, String name,
                       InputStream input, long byteLength, boolean overwrite) throws Exception {
        upload(p, folder, name, input, byteLength, overwrite, null);
    }

    /** Expected original WebDAV ETag from the moment an Office edit began.
     * Other file transfers retain the normal upload behavior. */
    static void upload(Models.RemoteProfile p, String folder, String name,
                       InputStream input, long byteLength, boolean overwrite,
                       String expectedWebDavEtag) throws Exception {
        requireWritable(p);
        if ("MYBOX".equals(p.protocol)) {
            MyBoxWriteApi.LIVE.upload(p, folder, name, input, byteLength, overwrite);
            return;
        }
        if ("TeraBox".equals(p.protocol)) {
            TeraBoxWriteApi.LIVE.upload(p, folder, name, input, byteLength, overwrite);
            return;
        }
        String finalPath = child(folder, name);
        String tempPath = child(folder, ".nasmount-" +
                UUID.randomUUID().toString().substring(0, 12) + ".upload");
        switch (p.protocol) {
            case "SMB":
                try (SmbFile dest = new SmbFile(smbPath(p, finalPath, false),
                        smbContext(p));
                     SmbFile staging = new SmbFile(smbPath(p, tempPath, false),
                             smbContext(p));
                     SmbFile backup = new SmbFile(smbPath(p, child(folder,
                             ".nasmount-" + UUID.randomUUID().toString().substring(0, 12) +
                             ".backup"), false), smbContext(p))) {
                    if (dest.exists() && !overwrite) throw new FileTransferEngine.NameConflict();
                    try {
                        try (OutputStream out = new SmbFileOutputStream(staging)) {
                            copy(input, out);
                        }
                        boolean backedUp = false;
                        if (overwrite && dest.exists()) {
                            dest.renameTo(backup);
                            backedUp = true;
                        }
                        try { staging.renameTo(dest); }
                        catch (Exception failure) {
                            if (backedUp)
                                try { backup.renameTo(dest); } catch (Exception ignored) {}
                            throw failure;
                        }
                        if (backedUp && backup.exists()) backup.delete();
                    } catch (Exception error) {
                        try { if (staging.exists()) staging.delete(); }
                        catch (Exception ignored) {}
                        throw error;
                    }
                }
                return;
            case "FTP":
                FTPClient ftp = ftp(p);
                try {
                    String target = absolute(p, finalPath);
                    String staged = absolute(p, tempPath);
                    boolean targetExists = ftp.listFiles(target).length != 0;
                    if (targetExists && !overwrite) throw new FileTransferEngine.NameConflict();
                    String backup = absolute(p, child(folder,
                            ".nasmount-" + UUID.randomUUID().toString().substring(0, 12) +
                            ".backup"));
                    try {
                        if (!ftp.storeFile(staged, input))
                            throw new IOException("FTP 임시파일 업로드 실패");
                        boolean backedUp = false;
                        if (targetExists) {
                            if (!ftp.rename(target, backup))
                                throw new IOException("FTP 이전 파일 백업 실패");
                            backedUp = true;
                        }
                        if (!ftp.rename(staged, target)) {
                            if (backedUp) ftp.rename(backup, target);
                            throw new IOException("FTP 임시파일 반영 실패");
                        }
                        if (backedUp && !ftp.deleteFile(backup))
                            throw new IOException("FTP 저장 성공, 이전 백업 파일 정리 실패");
                    } catch (Exception error) {
                        ftp.deleteFile(staged);
                        throw error;
                    }
                } finally { closeFtp(ftp); }
                return;
            case "SFTP":
                try (SSHClient ssh = ssh(p);
                     SFTPClient sftp = ssh.newSFTPClient()) {
                    String finalName = absolute(p, finalPath);
                    String stagingName = absolute(p, tempPath);
                    boolean targetExists = false;
                    try { sftp.stat(finalName); targetExists = true; }
                    catch (net.schmizz.sshj.sftp.SFTPException expectedMissing) {
                        // SFTP status NO_SUCH_FILE is the only acceptable
                        // reason to proceed. Other errors must stop upload.
                        if (!expectedMissing.getStatusCode().name().equals("NO_SUCH_FILE"))
                            throw expectedMissing;
                    }
                    if (targetExists && !overwrite) throw new FileTransferEngine.NameConflict();
                    String backup = absolute(p, child(folder,
                            ".nasmount-" + UUID.randomUUID().toString().substring(0, 12) +
                            ".backup"));
                    File temp = File.createTempFile("nasmount_sftp_", ".tmp");
                    try {
                        try (OutputStream out = new FileOutputStream(temp)) {
                            copy(input, out);
                        }
                        try {
                            sftp.put(temp.getAbsolutePath(), stagingName);
                            boolean backedUp = false;
                            if (targetExists) {
                                sftp.rename(finalName, backup);
                                backedUp = true;
                            }
                            try { sftp.rename(stagingName, finalName); }
                            catch (Exception failure) {
                                if (backedUp)
                                    try { sftp.rename(backup, finalName); } catch (Exception ignored) {}
                                throw failure;
                            }
                            if (backedUp) sftp.rm(backup);
                        } catch (Exception error) {
                            try { sftp.rm(stagingName); } catch (Exception ignored) {}
                            throw error;
                        }
                    } finally { temp.delete(); }
                }
                return;
            case "WebDAV":
                // WebDAV MOVE gives a clean final name only once the full
                // upload succeeded. Overwrite=F avoids deleting an existing
                // destination without user consent.
                WebDavRemoteVersion before = overwrite ?
                        webdavDestinationState(p, finalPath, false) :
                        new WebDavRemoteVersion(false, null);
                if (expectedWebDavEtag != null) {
                    if (!overwrite || !before.exists)
                        throw new IOException("Excel 원본이 삭제되거나 변경되었습니다.");
                    WebDavWriteGuard.sameVersion(expectedWebDavEtag, before.etag);
                }
                try {
                RequestBody body = new RequestBody() {
                    @Override public MediaType contentType() {
                        return MediaType.parse("application/octet-stream");
                    }
                    @Override public void writeTo(BufferedSink sink) throws IOException {
                        byte[] buf = new byte[32768];
                        int read;
                        while ((read = input.read(buf)) != -1) sink.write(buf, 0, read);
                    }
                };
                Request upload = webRequest(p, tempPath, false).put(body).build();
                try (Response resp = webClient(p).newCall(upload).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("WebDAV PUT HTTP " +
                            resp.code());
                }
                    WebDavRemoteVersion latest = overwrite ?
                            webdavDestinationState(p, finalPath, false) :
                            new WebDavRemoteVersion(false, null);
                    if (overwrite && before.exists != latest.exists)
                        throw new IOException("다른 기기에서 파일을 변경했습니다. 덮어쓰지 않았습니다.");
                    if (before.etag != null)
                        WebDavWriteGuard.sameVersion(before.etag, latest.etag);
                    Request.Builder moveBuilder = webRequest(p, tempPath, false)
                            .header("Destination", webUrl(p, finalPath, false).toString())
                            .header("Overwrite", before.exists ? "T" : "F");
                    if (before.etag != null)
                        moveBuilder.header("If", WebDavWriteGuard.taggedIf(
                                webUrl(p, finalPath, false).toString(), before.etag));
                    Request move = moveBuilder.method("MOVE",
                            RequestBody.create(new byte[0], null)).build();
                    try (Response resp = webClient(p).newCall(move).execute()) {
                        if (!resp.isSuccessful() || resp.code() == 207)
                            throw new IOException("WebDAV MOVE HTTP " + resp.code());
                    }
                } catch (Exception error) {
                    try (Response ignored = webClient(p).newCall(webRequest(p, tempPath, false)
                            .delete().build()).execute()) {}
                    catch (Exception ignored) {}
                    throw error;
                }
                return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }

    static void mkdir(Models.RemoteProfile p, String folder) throws Exception {
        requireWritable(p);
        if ("MYBOX".equals(p.protocol)) {
            MyBoxWriteApi.LIVE.mkdir(p, folder);
            return;
        }
        if ("TeraBox".equals(p.protocol)) {
            TeraBoxWriteApi.LIVE.mkdir(p, folder);
            return;
        }
        switch (p.protocol) {
            case "SMB":
                try (SmbFile f = new SmbFile(smbPath(p, folder, true), smbContext(p))) {
                    f.mkdir();
                } return;
            case "FTP":
                FTPClient ftp = ftp(p);
                try { if (!ftp.makeDirectory(absolute(p, folder)))
                    throw new IOException("FTP 폴더를 생성할 수 없습니다."); }
                finally { closeFtp(ftp); }
                return;
            case "SFTP":
                try (SSHClient ssh = ssh(p); SFTPClient sftp = ssh.newSFTPClient()) {
                    sftp.mkdir(absolute(p, folder));
                } return;
            case "WebDAV":
                Request r = webRequest(p, folder, true).method("MKCOL",
                        RequestBody.create(new byte[0], null)).build();
                try (Response resp = webClient(p).newCall(r).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("MKCOL HTTP " +
                            resp.code());
                } return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }
    static void rename(Models.RemoteProfile p, String source,
                       String destination, boolean directory) throws Exception {
        requireWritable(p);
        if ("MYBOX".equals(p.protocol)) {
            MyBoxWriteApi.LIVE.rename(p, source, destination);
            return;
        }
        if ("TeraBox".equals(p.protocol)) {
            TeraBoxWriteApi.LIVE.rename(p, source, destination);
            return;
        }
        if (source.equals("/") || destination.equals("/"))
            throw new IllegalArgumentException("저장소 루트는 이름을 변경할 수 없습니다.");
        if (relativePath(source).equals(relativePath(destination))) return;
        switch (p.protocol) {
            case "SMB":
                try (SmbFile oldFile = new SmbFile(smbPath(p, source, directory),
                        smbContext(p)); SmbFile newFile = new SmbFile(
                        smbPath(p, destination, directory), smbContext(p))) {
                    if (newFile.exists()) throw new IOException("같은 이름의 파일이 이미 있습니다.");
                    oldFile.renameTo(newFile);
                } return;
            case "FTP":
                FTPClient ftp = ftp(p);
                try {
                    if (ftp.listFiles(absolute(p, destination)).length > 0)
                        throw new IOException("같은 이름의 파일이 이미 있습니다.");
                    if (!ftp.rename(absolute(p, source), absolute(p, destination)))
                        throw new IOException("FTP 이름 변경 실패");
                } finally { closeFtp(ftp); }
                return;
            case "SFTP":
                try (SSHClient ssh = ssh(p); SFTPClient sftp = ssh.newSFTPClient()) {
                    if (sftp.statExistence(absolute(p, destination)) != null)
                        throw new IOException("같은 이름의 파일이 이미 있습니다.");
                    sftp.rename(absolute(p, source), absolute(p, destination));
                } return;
            case "WebDAV":
                Request move = webRequest(p, source, directory)
                        .header("Destination", webUrl(p, destination, directory).toString())
                        .header("Overwrite", "F")
                        .method("MOVE", RequestBody.create(new byte[0], null))
                        .build();
                try (Response response = webClient(p).newCall(move).execute()) {
                    if (!response.isSuccessful() || response.code() == 207)
                        throw new IOException("WebDAV 이름 변경 실패 (HTTP " +
                                response.code() + ")");
                } return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }

    /**
     * Same-account server-side COPY, without Android round-tripping the
     * file body. Only 201/204 confirms success. No blind retries for 207
     * Multi-Status or uncertain HTTP errors.
     *
     * @return false only if the WebDAV server does not support COPY.
     */
    static boolean tryWebDavServerCopy(Models.RemoteProfile p, String source,
                                      String destination, boolean directory)
            throws Exception {
        if (!"WebDAV".equals(p.protocol))
            throw new IllegalArgumentException("WebDAV storage required");
        requireWritable(p);
        if (relativePath(source).equals(relativePath(destination)))
            throw new IOException("원본과 대상이 같은 WebDAV 파일입니다.");
        Request.Builder request = webRequest(p, source, directory)
                .header("Destination",
                        webUrl(p, destination, directory).toString())
                .header("Overwrite", "F")
                .method("COPY", RequestBody.create(new byte[0], null));
        if (directory) request.header("Depth", "infinity");
        Request copy = request.build();
        try (Response response = webDavServerOperationClient(p)
                .newCall(copy).execute()) {
            if (response.code() == 201 || response.code() == 204)
                return true;
            if (response.code() == 405 || response.code() == 501)
                return false;
            throw new IOException("WebDAV COPY HTTP " + response.code());
        }
    }

    static void delete(Models.RemoteProfile p, String path, boolean directory)
            throws Exception {
        requireWritable(p);
        if ("MYBOX".equals(p.protocol)) {
            MyBoxWriteApi.LIVE.delete(p, path);
            return;
        }
        if ("TeraBox".equals(p.protocol)) {
            TeraBoxWriteApi.LIVE.delete(p, path);
            return;
        }
        if (relativePath(path).equals("/"))
            throw new IllegalArgumentException("저장소 루트는 삭제할 수 없습니다.");
        switch (p.protocol) {
            case "SMB":
                try (SmbFile f = new SmbFile(smbPath(p, path, directory),
                        smbContext(p))) { f.delete(); } return;
            case "FTP":
                FTPClient ftp = ftp(p);
                try {
                    boolean ok = directory ? ftp.removeDirectory(absolute(p, path)) :
                            ftp.deleteFile(absolute(p, path));
                    if (!ok) throw new IOException("FTP 삭제 실패 (폴더가 비어 있는지 확인)");
                } finally { closeFtp(ftp); } return;
            case "SFTP":
                try (SSHClient ssh = ssh(p); SFTPClient sftp = ssh.newSFTPClient()) {
                    String actual = absolute(p, path);
                    // unlink a symbolic link itself; never try RMDIR on the
                    // linked directory or recursively remove its target.
                    net.schmizz.sshj.sftp.FileAttributes entry = sftp.lstat(actual);
                    if (entry.getType() == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK)
                        sftp.rm(actual);
                    else if (entry.getType() == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)
                        sftp.rmdir(actual);
                    else sftp.rm(actual);
                } return;
            case "WebDAV":
                try (Response resp = webDavServerOperationClient(p).newCall(
                        webRequest(p, path, directory).delete().build()).execute()) {
                    // 207 may contain per-resource errors: never mark the
                    // entire collection as deleted on a partial response.
                    if (!resp.isSuccessful() || resp.code() == 207)
                        throw new IOException("WebDAV DELETE HTTP " + resp.code());
                } return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }
    private static void requireWritable(Models.RemoteProfile p) throws IOException {
        if (p.readOnly)
            throw new IOException("읽기 전용 연결입니다. 설정에서 쓰기 권한을 변경하세요.");
    }
    private static List<Models.Entry> sort(List<Models.Entry> files) {
        // Read-only connections still permit normal enumeration.
        files.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return files;
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        int len;
        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        out.flush();
    }
}
