package com.nasmount.mobile;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MultipartBody;
import okhttp3.MediaType;
import okhttp3.Response;
import okio.BufferedSink;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Cloud integrations without copying Windows rclone's executable:
 *
 * MYBOX: official personal-access-token REST API, read/write.
 * TeraBox: unofficial ndus session API for listing, reading and writes.
 * Google Drive: use Android's own SAF DocumentsProvider in MainActivity;
 * no embedded third-party OAuth client IDs or Google passwords.
 *
 * Never redirect bearer tokens/cookies to a server returned in API data.
 * Calls run on MainActivity's executor only, not the main thread.
 */
final class CloudProvider {
    private CloudProvider() {}
    /** User-visible cloud failure with no URLs, tokens or raw JSON bodies. */
    static final class CloudFailure extends IOException {
        final String provider, operation;
        final int status;
        CloudFailure(String provider, String operation, int status) {
            super(provider + " " + operation + " 실패 (HTTP " + status + ")");
            this.provider = provider;
            this.operation = operation;
            this.status = status;
        }
        String userMessage() {
            String hint = (status == 401 || status == 403) ?
                    "인증 토큰 및 계정 접근 권한을 확인하세요." :
                    status == 404 ? "파일이 이동·삭제됐는지 확인하세요." :
                    status == 409 ? "같은 이름의 파일이 있는지 확인하세요." :
                    status == 423 ? "파일 잠금 상태를 확인하세요." :
                    status == 429 ? "서버 요청 제한입니다. 잠시 후 다시 시도하세요." :
                    status == 507 ? "클라우드 남은 용량을 확인하세요." :
                    "서버 또는 클라우드 API의 응답을 확인하세요.";
            return getMessage() + "\n" + hint;
        }
    }
    static final class CloudApiFailure extends IOException {
        final String provider, operation;
        final int apiCode;
        CloudApiFailure(String provider, String operation, int apiCode,
                        String safeDescription) {
            super(provider + " " + operation + " 오류 (" + apiCode + "): " +
                    safeDescription);
            this.provider = provider;
            this.operation = operation;
            this.apiCode = apiCode;
        }
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(11, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build();
    private static final String MYBOX_ORIGIN = "https://open-api.mybox.naver.com/";
    private static final String TERA_ORIGIN = "https://www.terabox.com/";
    private static final String TERA_BROWSER_AGENT =
            "terabox;1.37.0.7;PC;PC-Windows;10.0.22631;WindowsTeraBox";

    static boolean isCloud(Models.RemoteProfile p) {
        return "MYBOX".equals(p.protocol) || "TeraBox".equals(p.protocol);
    }
    static boolean isReadOnly(Models.RemoteProfile p) {
        return false; // MYBOX and TeraBox both have write transports.
    }
    private static String validName(String name) {
        return RemoteProvider.child("/", name).substring(1);
    }
    static boolean validMyBoxToken(String value) {
        return value != null && value.startsWith("mbx_pat_") &&
                value.length() >= 20 && value.length() <= 1024 &&
                value.matches("[!-~]+");
    }
    static String ndusCookie(String value) throws IOException {
        if (value == null) throw new IOException("TeraBox 로그인 세션(ndus)이 필요합니다.");
        String source = value.trim();
        String token = null;
        for (String part : source.split(";")) {
            String item = part.trim();
            if (item.startsWith("ndus=")) { token = item.substring(5); break; }
        }
        if (token == null && !source.contains("=")) token = source;
        if (token == null || token.length() < 10 || token.length() > 4096 ||
                !token.matches("[A-Za-z0-9._~%+\\-/=]+"))
            throw new IOException("TeraBox ndus 쿠키 형식을 확인하세요.");
        return "ndus=" + token;
    }
    private static JSONObject getJson(String url, String authorization,
                                      String cookie) throws IOException {
        Request.Builder b = new Request.Builder().url(url).header("Accept",
                "application/json").get();
        if (authorization != null) b.header("Authorization", "Bearer " + authorization);
        if (cookie != null) {
            b.header("Cookie", cookie)
                    .header("Referer", TERA_ORIGIN)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("User-Agent", TERA_BROWSER_AGENT);
        }
        try (Response response = HTTP.newCall(b.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new CloudFailure(authorization != null ? "MYBOX" : "TeraBox",
                        "API 요청", response.code());
            try { return new JSONObject(response.body().string()); }
            catch (Exception ex) {
                throw new IOException("클라우드 서버가 JSON 대신 다른 응답을 보냈습니다. " +
                        "세션 만료 또는 변경된 API인지 확인하세요.");
            }
        }
    }
    private static HttpUrl requireEndpoint(String origin, String relative) {
        HttpUrl url = HttpUrl.parse(origin + relative);
        if (url == null || !url.isHttps())
            throw new IllegalArgumentException("보안 API 주소가 유효하지 않습니다.");
        return url;
    }
    private static String encodedSegment(String value) {
        return new HttpUrl.Builder().scheme("https").host("invalid.example")
                .addPathSegment(value).build().encodedPath().substring(1);
    }
    static String joined(Models.RemoteProfile p, String rel) {
        String root = RemoteProvider.relativePath(p.root);
        String part = RemoteProvider.relativePath(rel);
        return RemoteProvider.relativePath((root.equals("/") ? "" : root) +
                (part.equals("/") ? "" : part));
    }
    private static List<Models.Entry> myboxChildren(Models.RemoteProfile p,
                                                     String folderId,
                                                     String parent) throws Exception {
        if (!validMyBoxToken(p.password))
            throw new IOException("MYBOX 개인 액세스 토큰(PAT)이 필요합니다.");
        List<Models.Entry> all = new ArrayList<>();
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < 100; page++) {
            HttpUrl.Builder url = folderId == null
                    ? requireEndpoint(MYBOX_ORIGIN, "v1/drive/resources").newBuilder()
                    : requireEndpoint(MYBOX_ORIGIN,
                        "v1/drive/folders/" + encodedSegment(folderId) +
                        "/resources").newBuilder();
            url.addQueryParameter("count", "1000");
            if (cursor != null) url.addQueryParameter("cursor", cursor);
            JSONObject response = getJson(url.build().toString(), p.password, null);
            JSONArray resources = response.optJSONArray("resources");
            if (resources == null) throw new IOException("MYBOX 서버 목록 응답을 해석할 수 없습니다.");
            appendMyboxItems(resources, parent, all);
            JSONObject meta = response.optJSONObject("responseMetaData");
            cursor = meta == null ? null : meta.optString("nextCursor", "");
            if (cursor == null || cursor.isEmpty()) return all;
            if (!cursors.add(cursor)) throw new IOException("MYBOX 목록 커서가 반복됩니다.");
        }
        throw new IOException("MYBOX 폴더 페이지 수가 제한을 초과했습니다.");
    }
    static void appendMyboxItems(JSONArray resources, String parent,
                                 List<Models.Entry> all) throws IOException {
            for (int i = 0; i < resources.length(); i++) {
                JSONObject item = resources.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name");
                String id = item.optString("resourceId");
                String kind = item.optString("type");
                if (id.isEmpty() || (!"folder".equals(kind) && !"file".equals(kind)))
                    continue;
                try { validName(name); }
                catch (IllegalArgumentException badName) { continue; }
                Models.Entry e = new Models.Entry(name,
                        RemoteProvider.child(parent, name), "folder".equals(kind));
                e.size = item.optLong("size", -1);
                e.remoteId = id;
                all.add(e);
                if (all.size() > 100_000)
                    throw new IOException("MYBOX 목록 항목 수 제한을 초과했습니다.");
            }
    }
    static String myboxFolderId(Models.RemoteProfile p, String path)
            throws Exception {
        String logical = RemoteProvider.relativePath(path);
        if (logical.equals("/")) return null;
        String id = null;
        String current = "/";
        for (String segment : logical.substring(1).split("/")) {
            Models.Entry found = null;
            for (Models.Entry entry : myboxChildren(p, id, current)) {
                if (entry.directory && entry.name.equals(segment)) {
                    found = entry; break;
                }
            }
            if (found == null) throw new IOException("MYBOX 하위 폴더를 찾지 못했습니다.");
            id = found.remoteId;
            current = found.path;
        }
        return id;
    }
    private static List<Models.Entry> listMybox(Models.RemoteProfile p, String path)
            throws Exception {
        return myboxChildren(p, myboxFolderId(p, path), path);
    }
    private static List<Models.Entry> listTerabox(Models.RemoteProfile p, String path)
            throws Exception {
        String cookie = ndusCookie(p.password);
        String dir = joined(p, path);
        List<Models.Entry> entries = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            HttpUrl url = requireEndpoint(TERA_ORIGIN, "api/list").newBuilder()
                    .addQueryParameter("dir", dir)
                    .addQueryParameter("page", String.valueOf(page))
                    .addQueryParameter("num", "100")
                    .addQueryParameter("app_id", "250528")
                    .addQueryParameter("channel", "dubox")
                    .addQueryParameter("clienttype", "0").build();
            JSONObject root = getJson(url.toString(), null, cookie);
            if (root.optInt("errno", -999) != 0)
                throw new CloudApiFailure("TeraBox", "폴더 목록",
                        root.optInt("errno", -999),
                        "로그인 세션이 만료됐거나 서버 API가 변경됐습니다.");
            JSONArray rows = root.optJSONArray("list");
            if (rows == null) throw new IOException("TeraBox 목록 응답을 해석할 수 없습니다.");
            appendTeraboxItems(rows, path, entries);
            if (rows.length() < 100) return entries;
        }
        throw new IOException("TeraBox 목록 페이지 수 제한을 초과했습니다.");
    }
    static void appendTeraboxItems(JSONArray rows, String path,
                                   List<Models.Entry> entries) throws IOException {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("server_filename");
                try { validName(name); }
                catch (IllegalArgumentException invalid) { continue; }
                Models.Entry entry = new Models.Entry(name,
                        RemoteProvider.child(path, name), item.optInt("isdir") != 0);
                entry.size = item.optLong("size", 0);
                entry.modified = item.optLong("server_mtime", 0) * 1000L;
                entry.remoteId = item.optString("fs_id");
                entries.add(entry);
                if (entries.size() > 100_000)
                    throw new IOException("TeraBox 목록이 너무 깁니다.");
            }
    }
    static List<Models.Entry> list(Models.RemoteProfile p, String path) throws Exception {
        if ("MYBOX".equals(p.protocol)) return listMybox(p, path);
        if ("TeraBox".equals(p.protocol)) return listTerabox(p, path);
        throw new IOException("지원하지 않는 클라우드 종류입니다.");
    }
    static boolean safeDownloadUrl(HttpUrl url) {
        if (url == null || !url.isHttps()) return false;
        String host = url.host();
        if (host.equals("localhost") || host.endsWith(".local") ||
                host.endsWith(".internal")) return false;
        // Disallow IP literals or private-network downloader URLs.
        if (host.matches("[0-9.]+") || host.contains(":")) return false;
        if (!host.contains(".") || host.endsWith(".")) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(host))
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                    address.isSiteLocalAddress() || address.isLinkLocalAddress() ||
                    address.isMulticastAddress())
                    return false;
        } catch (Exception lookupFailed) { return false; }
        return true;
    }
    private static void downloadUrl(String url, OutputStream output)
            throws IOException {
        HttpUrl next = HttpUrl.parse(url);
        for (int redirect = 0; redirect < 6; redirect++) {
            if (!safeDownloadUrl(next))
                throw new IOException("클라우드 다운로드 주소가 안전하지 않습니다.");
            try (Response response = HTTP.newCall(
                    new Request.Builder().url(next).get().build()).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    next = location == null ? null : next.resolve(location);
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null)
                    throw new IOException("클라우드 파일 다운로드 실패 (HTTP " +
                            response.code() + ")");
                try (java.io.InputStream source = response.body().byteStream()) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = source.read(buffer)) != -1) output.write(buffer, 0, n);
                    output.flush();
                }
                return;
            }
        }
        throw new IOException("클라우드 다운로드 리디렉션이 너무 많습니다.");
    }
    private static String resolveDownload(Models.RemoteProfile p, String path)
            throws Exception {
        return resolveDownload(p, path, false);
    }

    private static String resolveDownload(Models.RemoteProfile p, String path,
                                          boolean forceSigned)
            throws Exception {
        String parent = RemoteProvider.parent(path);
        String basename = path.substring(path.lastIndexOf('/') + 1);
        Models.Entry file = null;
        for (Models.Entry candidate : list(p, parent)) {
            if (!candidate.directory && candidate.name.equals(basename)) {
                file = candidate; break;
            }
        }
        if (file == null) throw new IOException("클라우드 파일을 찾지 못했습니다.");
        String download;
        if ("MYBOX".equals(p.protocol)) {
            String endpoint = "v1/drive/files/" + encodedSegment(file.remoteId) +
                    "/download";
            JSONObject result = getJson(
                    requireEndpoint(MYBOX_ORIGIN, endpoint).toString(),
                    p.password, null);
            download = result.optString("downloadUrl");
        } else if ("TeraBox".equals(p.protocol)) {
            download = "";
            if (!forceSigned) {
                JSONArray paths = new JSONArray().put(joined(p, path));
                HttpUrl url = requireEndpoint(TERA_ORIGIN, "api/filemetas").newBuilder()
                        .addQueryParameter("target", paths.toString())
                        .addQueryParameter("dlink", "1")
                        .addQueryParameter("app_id", "250528")
                        .addQueryParameter("channel", "dubox")
                        .addQueryParameter("clienttype", "0").build();
                JSONObject info = getJson(url.toString(), null, ndusCookie(p.password));
                JSONArray rows = info.optJSONArray("info");
                if (rows != null && rows.length() > 0) {
                    JSONObject first = rows.optJSONObject(0);
                    if (first != null && first.optInt("errno", 0) == 0)
                        download = first.optString("dlink", "");
                }
            }
            // Some files have no dlink in /api/filemetas; the desktop
            // TeraBox backend also fetches a signed /api/download link.
            if (download.isEmpty())
                download = signedTeraboxDownload(p, file.remoteId);
        } else throw new IOException("지원하지 않는 클라우드입니다.");
        if (download.isEmpty())
            throw new IOException(p.protocol + "에서 다운로드 주소를 받지 못했습니다.");
        return download;
    }

    private static String signedTeraboxDownload(Models.RemoteProfile p, String id)
            throws Exception {
        if (id == null || !id.matches("[0-9]{1,20}"))
            throw new IOException("TeraBox 다운로드 파일 ID가 없습니다.");
        String session = ndusCookie(p.password);
        HttpUrl home = requireEndpoint(TERA_ORIGIN, "api/home/info").newBuilder()
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("channel", "dubox")
                .addQueryParameter("clienttype", "0").build();
        JSONObject metadata = getJson(home.toString(), null, session);
        JSONObject data = metadata.optJSONObject("data");
        if (metadata.optInt("errno", -1) != 0 || data == null)
            throw new CloudApiFailure("TeraBox", "다운로드 서명",
                    metadata.optInt("errno", -1),
                    "ndus 로그인 세션을 갱신하거나 다운로드 권한을 확인하세요.");
        String signature = TeraBoxDownloadSign.sign(
                data.optString("sign3", ""), data.optString("sign1", ""));
        HttpUrl url = requireEndpoint(TERA_ORIGIN, "api/download").newBuilder()
                .addQueryParameter("type", "dlink")
                .addQueryParameter("vip", "2")
                .addQueryParameter("sign", signature)
                .addQueryParameter("timestamp", String.valueOf(
                        System.currentTimeMillis() / 1000))
                .addQueryParameter("need_speed", "1")
                .addQueryParameter("fidlist", "[" + id + "]")
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("channel", "dubox")
                .addQueryParameter("clienttype", "0").build();
        JSONObject response = getJson(url.toString(), null, session);
        if (response.optInt("errno", -1) != 0)
            throw new CloudApiFailure("TeraBox", "파일 다운로드 링크 발급",
                    response.optInt("errno", -1),
                    "로그인 세션·다운로드 권한 또는 파일 상태를 확인하세요.");
        JSONArray links = response.optJSONArray("dlink");
        JSONObject first = links == null ? null : links.optJSONObject(0);
        String download = first == null ? "" : first.optString("dlink", "");
        if (download.isEmpty())
            throw new IOException("TeraBox 서버가 다운로드 주소를 발급하지 않았습니다.");
        return download;
    }
    /** Keep account tokens away from untrusted signed-download redirects. */
    static boolean trustedTeraboxDownloadHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(java.util.Locale.ROOT);
        // The first dlink is normally https://d.terabox.com/file/...,
        // NOT www.terabox.com. It needs the same ndus session to issue the
        // actual CDN Location response. Never send ndus to arbitrary hosts.
        return value.equals("terabox.com") ||
                value.endsWith(".terabox.com") ||
                value.equals("nephobox.com") ||
                value.endsWith(".nephobox.com");
    }

    static Request.Builder cloudDownloadRequest(Models.RemoteProfile p, HttpUrl url,
                                                long position) throws IOException {
        if (!safeDownloadUrl(url))
            throw new IOException("클라우드 다운로드 서버 주소가 안전하지 않습니다.");
        Request.Builder request = new Request.Builder().url(url).get();
        if (position > 0) request.header("Range", "bytes=" + position + "-");
        if ("TeraBox".equals(p.protocol)) {
            request.header("User-Agent", TERA_BROWSER_AGENT)
                    .header("Referer", TERA_ORIGIN);
            // Cookies must never be passed to arbitrary CDN hosts.
            if (url.port() == 443 && trustedTeraboxDownloadHost(url.host()))
                request.header("Cookie", ndusCookie(p.password));
        }
        return request;
    }

    static InputStream openStream(Models.RemoteProfile p, String path, long position)
            throws Exception {
        if (position < 0) throw new IOException("잘못된 파일 위치입니다.");
        HttpUrl next = HttpUrl.parse(resolveDownload(p, path));
        boolean signedRetry = false;
        for (int redirect = 0; redirect < 8; redirect++) {
            Request.Builder request = cloudDownloadRequest(p, next, position);
            Response response = HTTP.newCall(request.build()).execute();
            if (response.isRedirect()) {
                String location = response.header("Location");
                HttpUrl destination = location == null ? null : next.resolve(location);
                response.close();
                next = destination;
                continue;
            }
            if ("TeraBox".equals(p.protocol) && !signedRetry &&
                    (response.code() == 401 || response.code() == 403 ||
                     response.code() == 404 || response.code() == 410)) {
                response.close();
                signedRetry = true;
                next = HttpUrl.parse(resolveDownload(p, path, true));
                continue;
            }
            if (!response.isSuccessful() || response.body() == null ||
                    (position > 0 && response.code() != 206)) {
                int status = response.code();
                response.close();
                throw new IOException(position > 0 && status == 200 ?
                        "클라우드 서버가 영상 구간 이동(HTTP Range)을 지원하지 않습니다." :
                        p.protocol + " 파일 읽기 실패 (HTTP " + status + "). " +
                        "다운로드 주소 만료, 로그인 세션 및 다운로드 권한을 확인하세요.");
            }
            if (position > 0) {
                String range = response.header("Content-Range", "");
                if (!range.toLowerCase(java.util.Locale.ROOT)
                        .startsWith("bytes " + position + "-")) {
                    response.close();
                    throw new IOException("클라우드 서버가 다른 영상 구간을 반환했습니다.");
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
        throw new IOException("클라우드 스트리밍 리디렉션이 너무 많습니다.");
    }
    static void download(Models.RemoteProfile p, String path, OutputStream out)
            throws Exception {
        try (InputStream in = openStream(p, path, 0)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > 0) out.write(buffer, 0, count);
            }
            out.flush();
        }
    }
}
