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
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
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
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(11000);
        ftp.setDefaultTimeout(16000);
        ftp.setDataTimeout(35000);
        ftp.setControlEncoding("UTF-8");
        ftp.connect(p.host.trim(), port(p, 21));
        String user = p.username.isEmpty() ? "anonymous" : p.username;
        String pass = p.username.isEmpty() ? "anonymous@" : p.password;
        if (!ftp.login(user, pass))
            throw new IOException("FTP 로그인에 실패했습니다.");
        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }
    private static void closeFtp(FTPClient client) {
        if (client == null) return;
        try { if (client.isConnected()) client.logout(); }
        catch (Exception ignored) {}
        try { if (client.isConnected()) client.disconnect(); }
        catch (Exception ignored) {}
    }

    private static String fingerprint(java.security.PublicKey key) throws Exception {
        // OpenSSH-compatible SHA256 fingerprint of the SSH public-key blob,
        // not of the X.509 SubjectPublicKeyInfo encoding.
        Buffer.PlainBuffer encoded = new Buffer.PlainBuffer();
        KeyType.fromKey(key).putPubKeyIntoBuffer(key, encoded);
        return "SHA256:" + Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(encoded.getCompactData()),
                Base64.NO_WRAP | Base64.NO_PADDING);
    }
    private static SSHClient ssh(Models.RemoteProfile p) throws Exception {
        SSHClient ssh = new SSHClient();
        ssh.setConnectTimeout(12000);
        ssh.addHostKeyVerifier(new HostKeyVerifier() {
            @Override public List<String> findExistingAlgorithms(String host, int serverPort) {
                return Collections.emptyList();
            }
            @Override public boolean verify(String hostname, int serverPort,
                                             java.security.PublicKey key) {
                try {
                    String fp = fingerprint(key);
                    if (p.fingerprint.isEmpty()) throw new UntrustedHostKey(fp);
                    if (!MessageDigest.isEqual(fp.getBytes(StandardCharsets.UTF_8),
                            p.fingerprint.getBytes(StandardCharsets.UTF_8)))
                        throw new SecurityException(
                                "SFTP 서버 호스트 키가 이전과 다릅니다. 연결을 차단했습니다.");
                    return true;
                } catch (UntrustedHostKey | SecurityException error) {
                    throw new IllegalStateException(error);
                } catch (Exception error) {
                    throw new IllegalStateException("서버 키 검증 실패", error);
                }
            }
        });
        try {
            ssh.connect(p.host.trim(), port(p, 22));
            ssh.authPassword(p.username, p.password);
            return ssh;
        } catch (Exception e) {
            try { ssh.close(); } catch (IOException ignored) {}
            throw e;
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
    private static Request.Builder webRequest(Models.RemoteProfile p, String path,
                                              boolean folder) {
        Request.Builder b = new Request.Builder().url(webUrl(p, path, folder));
        if (!p.username.isEmpty()) {
            String auth = p.username + ":" + p.password;
            b.header("Authorization", "Basic " + Base64.encodeToString(
                    auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        return b;
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
                        Models.Entry e = new Models.Entry(n, child(normalized, n),
                                item.isDirectory());
                        e.size = item.getAttributes().getSize();
                        e.modified = item.getAttributes().getMtime() * 1000L;
                        out.add(e);
                    }
                    return sort(out);
                }
            case "WebDAV":
                Request request = webRequest(p, normalized, true)
                        .header("Depth", "1")
                        .header("Accept", "application/xml, text/xml")
                        .method("PROPFIND", RequestBody.create(
                                "<d:propfind xmlns:d='DAV:'><d:allprop/></d:propfind>",
                                MediaType.parse("application/xml; charset=utf-8")))
                        .build();
                try (Response response = HTTP.newCall(request).execute()) {
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
                try (Response response = HTTP.newCall(webRequest(p, normalized, false)
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
                Response response = HTTP.newCall(request.build()).execute();
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
                try (Response resp = HTTP.newCall(upload).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("WebDAV PUT HTTP " +
                            resp.code());
                }
                try {
                    Request move = webRequest(p, tempPath, false)
                            .header("Destination", webUrl(p, finalPath, false).toString())
                            .header("Overwrite", overwrite ? "T" : "F")
                            .method("MOVE", RequestBody.create(new byte[0], null))
                            .build();
                    try (Response resp = HTTP.newCall(move).execute()) {
                        if (!resp.isSuccessful())
                            throw new IOException("WebDAV MOVE HTTP " + resp.code());
                    }
                } catch (Exception error) {
                    try (Response ignored = HTTP.newCall(webRequest(p, tempPath, false)
                            .delete().build()).execute()) {}
                    catch (Exception ignored) {}
                    throw error;
                }
                return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }

    static void mkdir(Models.RemoteProfile p, String folder) throws Exception {
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
                try (Response resp = HTTP.newCall(r).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("MKCOL HTTP " +
                            resp.code());
                } return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }
    static void rename(Models.RemoteProfile p, String source,
                       String destination, boolean directory) throws Exception {
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
                try (Response response = HTTP.newCall(move).execute()) {
                    if (!response.isSuccessful())
                        throw new IOException("WebDAV 이름 변경 실패 (HTTP " +
                                response.code() + ")");
                } return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }

    static void delete(Models.RemoteProfile p, String path, boolean directory)
            throws Exception {
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
                    if (directory) sftp.rmdir(absolute(p, path));
                    else sftp.rm(absolute(p, path));
                } return;
            case "WebDAV":
                try (Response resp = HTTP.newCall(
                        webRequest(p, path, directory).delete().build()).execute()) {
                    if (!resp.isSuccessful())
                        throw new IOException("WebDAV DELETE HTTP " + resp.code());
                } return;
            default: throw new IOException("지원하지 않는 저장소");
        }
    }
    private static List<Models.Entry> sort(List<Models.Entry> files) {
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
