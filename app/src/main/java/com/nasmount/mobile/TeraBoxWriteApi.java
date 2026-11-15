package com.nasmount.mobile;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cookie-based (unofficial) TeraBox web write API, modeled on the PC rclone
 * terabox backend. All network work happens on an executor, never UI thread.
 * OAuth-only /openapi is NOT applicable to a user's ndus cookie.
 *
 * The web API is undocumented and may change; failures stop rather than
 * silently overwrite or delete user files. Never forward ndus to unrelated
 * hosts, follow redirects or clean the remote trash.
 */
final class TeraBoxWriteApi {
    static final TeraBoxWriteApi LIVE = new TeraBoxWriteApi(
            "https://www.terabox.com/");
    static final int CHUNK_BYTES = 4 * 1024 * 1024;
    static final long FREE_FILE_LIMIT = 4L * 1024 * 1024 * 1024;
    static final long PREMIUM_FILE_LIMIT = 128L * 1024 * 1024 * 1024;
    private static final String[] PRECREATE_BLOCKS = {
            "5910a591dd8fc18c32a8f3df4fdc1761",
            "a5fc157d78e6ad1c7e114b056c92821e"
    };
    interface FolderLookup {
        List<Models.Entry> list(Models.RemoteProfile profile, String folder)
                throws Exception;
    }
    private final HttpUrl origin;
    private final FolderLookup lookup;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build();
    private final Map<String, String> cachedJsTokens = new HashMap<>();

    TeraBoxWriteApi(String baseUrl) {
        this(baseUrl, CloudProvider::list);
    }
    TeraBoxWriteApi(String baseUrl, FolderLookup folderLookup) {
        origin = HttpUrl.get(baseUrl);
        if (!isApprovedMainHost(origin, true))
            throw new IllegalArgumentException("Unexpected TeraBox API host");
        lookup = folderLookup;
    }

    static boolean isApprovedMainHost(HttpUrl address, boolean allowTest) {
        if (address == null) return false;
        if (allowTest && !address.isHttps() &&
                (address.host().equals("127.0.0.1") ||
                 address.host().equals("localhost")))
            return true;
        return address.isHttps() && address.port() == 443 &&
                (address.host().equals("www.terabox.com") ||
                 address.host().equals("terabox.com") ||
                 address.host().equals("www.nephobox.com"));
    }

    static boolean isApprovedUploadHost(HttpUrl address, boolean allowTest) {
        if (address == null || (!address.isHttps() && !allowTest)) return false;
        if (allowTest && address.host().equals("127.0.0.1") &&
                !address.isHttps()) return true;
        String host = address.host().toLowerCase(Locale.ROOT);
        return address.isHttps() && address.port() == 443 &&
                ((host.endsWith(".terabox.com") && !host.equals(".terabox.com")) ||
                 (host.endsWith(".nephobox.com") && !host.equals(".nephobox.com")));
    }

    private HttpUrl endpoint(String relative) {
        if (relative.startsWith("/") || relative.contains(".."))
            throw new IllegalArgumentException("Invalid API route");
        HttpUrl url = origin.resolve(relative);
        if (url == null || !url.host().equals(origin.host()) ||
                !url.scheme().equals(origin.scheme()) || url.port() != origin.port())
            throw new IllegalArgumentException("Unexpected TeraBox API route");
        return url;
    }

    private static String cookie(Models.RemoteProfile profile) throws IOException {
        return CloudProvider.ndusCookie(profile.password);
    }
    private Request.Builder authorized(Models.RemoteProfile profile,
                                       HttpUrl url) throws IOException {
        boolean safe = url.host().equals(origin.host()) &&
                url.scheme().equals(origin.scheme()) &&
                url.port() == origin.port();
        if (!safe && (!isApprovedUploadHost(url, !origin.isHttps()) ||
                (!origin.isHttps() && !url.host().equals(origin.host()))))
            throw new IOException("TeraBox 세션을 확인되지 않은 서버에 보낼 수 없습니다.");
        return new Request.Builder().url(url)
                .header("Cookie", cookie(profile))
                .header("Referer", origin.toString())
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "terabox;1.37.0.7;PC;PC-Windows;10.0.22631;WindowsTeraBox");
    }

    private static int errorNumber(JSONObject body) {
        return body.has("errno") ? body.optInt("errno", -999) :
                body.optInt("error_code", body.optInt("errcode", -999));
    }

    // -999 can mean a missing errno, not an actual server rejection.
    // Accept code-less replies only when the endpoint proves success.
    private enum ReplyKind { STRICT, UPLOAD_LOCATION, PRECREATE, UPLOAD_FINISH }

    private static boolean hasErrorCode(JSONObject reply) {
        return reply.has("errno") || reply.has("error_code") || reply.has("errcode");
    }

    private static boolean hasEndpointSuccessProof(JSONObject reply, ReplyKind kind) {
        if (reply.has("error") || reply.has("error_msg")) return false;
        switch (kind) {
            case UPLOAD_LOCATION: return !reply.optString("host", "").isEmpty();
            case PRECREATE:
                return !reply.optString("uploadid", "").isEmpty() ||
                        reply.optInt("return_type", 0) == 2;
            case UPLOAD_FINISH:
                return reply.optLong("fs_id", 0) > 0 &&
                        reply.optLong("size", -1) >= 0;
            default: return false;
        }
    }

    private static String errorMessage(int code) {
        switch (code) {
            case -8: case 12: return "동일한 이름의 파일 또는 폴더가 있습니다.";
            case -9: return "대상 파일이나 폴더가 존재하지 않습니다.";
            case -10: case -32: case 31116: return "클라우드 저장 용량이 부족합니다.";
            case -6: case 104: case 4000023: case 450016:
                return "TeraBox 로그인 세션 또는 웹 인증 토큰이 만료됐습니다.";
            case 58: return "현재 계정의 업로드 파일 크기 제한을 초과했습니다.";
            case 111: case 10013: return "진행 중인 TeraBox 작업을 완료한 뒤 재시도하세요.";
            default: return "TeraBox 서버 오류 코드: " + code;
        }
    }

    private static JSONObject requireSuccess(JSONObject result, String stage,
                                             ReplyKind kind) throws IOException {
        int code = errorNumber(result);
        if (hasErrorCode(result) && code != 0)
            throw new CloudProvider.CloudApiFailure("TeraBox", stage,
                    code, errorMessage(code));
        if (!hasErrorCode(result) && !hasEndpointSuccessProof(result, kind))
            throw new IOException("TeraBox " + stage +
                    " 응답에 오류 코드와 성공 확인 정보가 없습니다. " +
                    "(서버 응답 형식 불일치; 파일 상태를 확인하세요.)");
        return result;
    }

    private static JSONObject requireSuccess(JSONObject result) throws IOException {
        return requireSuccess(result, "파일 작업", ReplyKind.STRICT);
    }

    // The top-level filemanager/taskquery reply has already passed strict
    // errno validation. Its info/list elements may instead be ordinary file
    // metadata (path, fs_id, size) and are not required to repeat errno.
    // Reject any explicit per-file error; never infer a failure just because
    // a metadata object does not have a separate status.
    private static void requireOperationItemSuccess(JSONObject item, String stage)
            throws IOException {
        if (hasErrorCode(item)) {
            requireSuccess(item, stage, ReplyKind.STRICT);
        }
        String status = item.optString("status", "").toLowerCase(Locale.ROOT);
        if (item.has("error") || item.has("error_msg") ||
                status.equals("failed") || status.equals("failure") ||
                status.equals("error") || (item.has("success") &&
                !item.optBoolean("success", false)))
            throw new IOException("TeraBox " + stage +
                    " 중 개별 파일 오류가 보고됐습니다. 원본 파일을 확인하세요.");
    }

    static HttpUrl approvedApiDomainFromHeader(HttpUrl original, String prefix)
            throws IOException {
        if (original == null || prefix == null ||
                !prefix.matches("[A-Za-z0-9][A-Za-z0-9-]{0,29}") ||
                !original.isHttps() || original.port() != 443)
            throw new IOException("TeraBox 서버 이동 정보가 안전하지 않습니다.");
        String originalHost = original.host();
        String root;
        if (originalHost.equals("terabox.com") ||
                originalHost.endsWith(".terabox.com"))
            root = "terabox.com";
        else if (originalHost.equals("nephobox.com") ||
                originalHost.endsWith(".nephobox.com"))
            root = "nephobox.com";
        else
            throw new IOException("TeraBox 서버 이동 대상이 신뢰할 수 없습니다.");
        HttpUrl redirected = original.newBuilder().host(prefix + "." + root).build();
        if (!isApprovedUploadHost(redirected, false))
            throw new IOException("TeraBox 서버 이동 도메인이 허용되지 않았습니다.");
        return redirected;
    }

    private JSONObject requestJson(Models.RemoteProfile profile, Request request)
            throws Exception {
        return requestJson(profile, request, "파일 작업", ReplyKind.STRICT);
    }

    private JSONObject requestJson(Models.RemoteProfile profile, Request request,
                                   String stage, ReplyKind kind) throws Exception {
        Request current = request;
        boolean refreshedJsToken = false;
        boolean switchedApiDomain = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Response response = http.newCall(current).execute()) {
                if (!response.isSuccessful() || response.body() == null)
                    throw new CloudProvider.CloudFailure("TeraBox",
                            stage, response.code());
                JSONObject parsed;
                try { parsed = new JSONObject(response.body().string()); }
                catch (org.json.JSONException malformed) {
                    throw new IOException("TeraBox " + stage +
                            " 단계에서 JSON이 아닌 응답을 받았습니다.");
                }
                int code = errorNumber(parsed);
                if (!refreshedJsToken && (code == 4000023 || code == 450016) &&
                        current.url().queryParameter("jsToken") != null) {
                    refreshedJsToken = true;
                    String key = profile.id == null ? "" : profile.id;
                    synchronized (cachedJsTokens) { cachedJsTokens.remove(key); }
                    String fresh = jsToken(profile);
                    current = current.newBuilder().url(current.url().newBuilder()
                            .setQueryParameter("jsToken", fresh).build()).build();
                    continue;
                }
                // The TeraBox PC backend receives errno=-6 plus a
                // Url-Domain-Prefix header for region-specific accounts.
                // Retry only after explicit refusal, never after a timeout
                // or ambiguous response which may already have committed.
                if (!switchedApiDomain && code == -6 &&
                        response.header("Url-Domain-Prefix") != null) {
                    switchedApiDomain = true;
                    HttpUrl redirected = approvedApiDomainFromHeader(
                            current.url(), response.header("Url-Domain-Prefix"));
                    current = current.newBuilder().url(redirected).build();
                    continue;
                }
                return requireSuccess(parsed, stage, kind);
            }
        }
        throw new IOException("TeraBox " + stage +
                " 단계에서 재인증과 지역 주소 변경 후에도 완료하지 못했습니다.");
    }

    private String jsToken(Models.RemoteProfile profile) throws Exception {
        String key = profile.id == null ? "" : profile.id;
        synchronized (cachedJsTokens) {
            String saved = cachedJsTokens.get(key);
            if (saved != null && !saved.isEmpty()) return saved;
        }
        String markup;
        HttpUrl current = origin;
        for (int redirects = 0; ; redirects++) {
            if (redirects > 3)
                throw new IOException("TeraBox 로그인 페이지 전환이 너무 많습니다.");
            Request page = authorized(profile, current).get().build();
            try (Response response = http.newCall(page).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("Location");
                    HttpUrl next = location == null ? null : current.resolve(location);
                    if (next == null || !next.host().equals(origin.host()) ||
                            !next.scheme().equals(origin.scheme()) ||
                            next.port() != origin.port())
                        throw new IOException("TeraBox 로그인 세션이 다른 사이트로 " +
                                "이동했습니다. ndus 세션을 갱신하세요.");
                    current = next;
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null)
                    throw new CloudProvider.CloudFailure("TeraBox", "세션 확인",
                            response.code());
                markup = response.body().string();
                break;
            }
        }
        // Format used by the rclone terabox backend (URL-encoded function).
        String start = "`function%20fn%28a%29%7Bwindow.jsToken%20%3D%20a%7D%3Bfn%28%22";
        String end = "%22%29`";
        String token = "";
        int a = markup.indexOf(start);
        if (a >= 0) {
            int b = markup.indexOf(end, a + start.length());
            if (b > a) token = markup.substring(a + start.length(), b);
        }
        if (token.isEmpty()) {
            Matcher match = Pattern.compile(
                    "(?:\\\\\"jsToken\\\\\"|\\bjsToken)\\s*[:=]\\s*\\\\\"([^\\\\\"]{10,512})\\\\\"")
                    .matcher(markup);
            if (match.find()) token = match.group(1);
        }
        if (token.isEmpty() || token.length() > 512 ||
                !token.matches("[A-Za-z0-9._~%+\\-/=]+"))
            throw new IOException("TeraBox 웹 인증(jsToken)을 확인하지 못했습니다. " +
                    "로그인 세션을 갱신하세요.");
        synchronized (cachedJsTokens) { cachedJsTokens.put(key, token); }
        return token;
    }

    private HttpUrl.Builder common(Models.RemoteProfile p, String relative)
            throws Exception {
        return endpoint(relative).newBuilder()
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("channel", "dubox")
                .addQueryParameter("clienttype", "0")
                .addQueryParameter("jsToken", jsToken(p));
    }

    static String fullPath(Models.RemoteProfile p, String path) {
        return CloudProvider.joined(p, path);
    }

    private void assertNotExisting(Models.RemoteProfile p, String folder, String name)
            throws Exception {
        RemoteProvider.child("/", name);
        for (Models.Entry existing : lookup.list(p, folder))
            if (existing.name.equalsIgnoreCase(name))
                throw new FileTransferEngine.NameConflict();
    }

    void mkdir(Models.RemoteProfile p, String folder) throws Exception {
        String logical = RemoteProvider.relativePath(folder);
        if (logical.equals("/")) throw new IOException("TeraBox 루트는 만들 수 없습니다.");
        String parent = RemoteProvider.parent(logical);
        String name = logical.substring(logical.lastIndexOf('/') + 1);
        assertNotExisting(p, parent, name);
        Request request = authorized(p, common(p, "api/create").build())
                .post(new FormBody.Builder()
                        .add("path", fullPath(p, logical))
                        .add("isdir", "1")
                        .add("rtype", "0")
                        .build()).build();
        requestJson(p, request, "폴더 생성", ReplyKind.STRICT);
    }

    private void filemanager(Models.RemoteProfile p, String operation,
                             JSONArray entries) throws Exception {
        if (!operation.equals("copy") && !operation.equals("move") &&
                !operation.equals("rename") && !operation.equals("delete"))
            throw new IOException("지원하지 않는 TeraBox 작업입니다.");
        HttpUrl url = common(p, "api/filemanager")
                .addQueryParameter("opera", operation)
                .addQueryParameter("async", operation.equals("copy") ? "2" : "1")
                .addQueryParameter("onnest", "fail").build();
        JSONObject response = requestJson(p, authorized(p, url)
                .post(new FormBody.Builder()
                        .add("filelist", entries.toString()).build()).build(),
                "파일 " + operation, ReplyKind.STRICT);
        long taskId = response.optLong("taskid", 0);
        if (taskId > 0) waitForTask(p, taskId);
        JSONArray info = response.optJSONArray("info");
        if (info != null) for (int i = 0; i < info.length(); i++) {
            JSONObject item = info.optJSONObject(i);
            if (item != null)
                requireOperationItemSuccess(item, "파일 " + operation + " 결과");
        }
    }

    private void waitForTask(Models.RemoteProfile p, long taskId) throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            if (attempt > 0) Thread.sleep(600);
            HttpUrl url = common(p, "share/taskquery")
                    .addQueryParameter("taskid", String.valueOf(taskId)).build();
            JSONObject result = requestJson(p,
                    authorized(p, url).get().build(), "작업 결과 확인", ReplyKind.STRICT);
            String state = result.optString("status", "");
            if (state.equals("running") || state.equals("pending")) continue;
            JSONArray items = result.optJSONArray("list");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null)
                    requireOperationItemSuccess(item, "비동기 작업 결과");
            }
            if (state.equals("success") || state.equals("finished")) return;
            throw new IOException("TeraBox 비동기 작업 결과: " + state);
        }
        throw new IOException("TeraBox 복사·이동 작업 확인 시간이 초과됐습니다. " +
                "중복 실행 전 폴더에서 결과를 확인하세요.");
    }

    void rename(Models.RemoteProfile p, String from, String to) throws Exception {
        String source = RemoteProvider.relativePath(from);
        String destination = RemoteProvider.relativePath(to);
        if (source.equals("/") || destination.equals("/"))
            throw new IOException("TeraBox 루트는 이름을 변경할 수 없습니다.");
        if (source.equals(destination)) return;
        if (!RemoteProvider.parent(source).equals(RemoteProvider.parent(destination)))
            throw new IOException("다른 폴더로 이동하려면 이동 기능을 사용하세요.");
        String name = destination.substring(destination.lastIndexOf('/') + 1);
        assertNotExisting(p, RemoteProvider.parent(destination), name);
        JSONArray files = new JSONArray().put(new JSONObject()
                .put("path", fullPath(p, source))
                .put("newname", name));
        filemanager(p, "rename", files);
    }

    void copyOrMove(Models.RemoteProfile p, String from, String toFolder,
                    boolean moving) throws Exception {
        copyOrMove(p, from, toFolder, moving, false);
    }

    void copyOrMove(Models.RemoteProfile p, String from, String toFolder,
                    boolean moving, boolean overwrite) throws Exception {
        String source = RemoteProvider.relativePath(from);
        String name = source.substring(source.lastIndexOf('/') + 1);
        copyOrMove(p, from, toFolder, moving, overwrite, name);
    }

    private void copyOrMove(Models.RemoteProfile p, String from, String toFolder,
                            boolean moving, boolean overwrite, String name) throws Exception {
        String source = RemoteProvider.relativePath(from);
        String destination = RemoteProvider.relativePath(toFolder);
        if (source.equals("/"))
            throw new IOException("루트 폴더는 이동하거나 복사할 수 없습니다.");
        RemoteProvider.child("/", name);
        if (source.equals(RemoteProvider.child(destination, name))) {
            if (moving) return;
            throw new IOException("동일한 폴더로 복사할 수 없습니다.");
        }
        if (FileOperationPolicy.childOrSame(source, destination))
            throw new IOException("자기 폴더의 하위로 복사/이동할 수 없습니다.");
        if (!overwrite) assertNotExisting(p, destination, name);
        if (overwrite && moving) {
            boolean targetExists = false;
            for (Models.Entry item : lookup.list(p, destination))
                if (item.name.equalsIgnoreCase(name)) {
                    targetExists = true;
                    break;
                }
            if (targetExists) {
                // COPY's 'ondup=overwrite' is used in rclone. Do not remove
                // the source until replacement was acknowledged.
                copyOrMove(p, from, toFolder, false, true, name);
                delete(p, from);
                return;
            }
        }
        JSONObject operation = new JSONObject()
                .put("path", fullPath(p, source))
                .put("dest", fullPath(p, destination))
                .put("newname", name);
        if (overwrite) operation.put("ondup", "overwrite");
        JSONArray files = new JSONArray().put(operation);
        filemanager(p, moving ? "move" : "copy", files);
    }

    void delete(Models.RemoteProfile p, String path) throws Exception {
        String logical = RemoteProvider.relativePath(path);
        if (logical.equals("/"))
            throw new IOException("저장소 루트를 삭제할 수 없습니다.");
        filemanager(p, "delete", new JSONArray().put(fullPath(p, logical)));
        // Never send recycle-bin purge requests; normal delete uses Trash.
    }

    private HttpUrl uploadHost(Models.RemoteProfile p) throws Exception {
        HttpUrl locate = endpoint("rest/2.0/pcs/file").newBuilder()
                .addQueryParameter("method", "locateupload").build();
        JSONObject response = requestJson(p, authorized(p, locate).get().build(),
                "업로드 서버 조회(locateupload)", ReplyKind.UPLOAD_LOCATION);
        String server = response.optString("host", "");
        if (server.isEmpty() || server.contains("/") || server.contains(":") ||
                server.contains("@") || server.contains(" "))
            throw new IOException("TeraBox 업로드 서버 주소가 올바르지 않습니다.");
        HttpUrl target = new HttpUrl.Builder()
                .scheme(origin.isHttps() ? "https" : "http")
                .host(origin.isHttps() ? server : origin.host())
                .port(origin.port())
                .addPathSegments("rest/2.0/pcs/superfile2").build();
        if (!isApprovedUploadHost(target, !origin.isHttps()))
            throw new IOException("TeraBox 업로드 서버 인증에 실패했습니다.");
        return target;
    }

    private void enforceUploadLimit(Models.RemoteProfile p, long size)
            throws Exception {
        if (size <= FREE_FILE_LIMIT) return;
        if (size > PREMIUM_FILE_LIMIT)
            throw new IOException("TeraBox 단일 업로드 최대 128GB를 초과했습니다.");
        HttpUrl url = endpoint("rest/2.0/membership/proxy/user").newBuilder()
                .addQueryParameter("method", "query")
                .addQueryParameter("membership_version", "1.0").build();
        JSONObject details = requestJson(p, authorized(p, url).get().build(),
                "회원 등급 확인", ReplyKind.STRICT);
        JSONObject data = details.optJSONObject("data");
        JSONObject member = data == null ? null : data.optJSONObject("member_info");
        if (member == null || member.optInt("is_vip", 0) < 1)
            throw new IOException("TeraBox 무료 계정의 파일 업로드 한도는 4GB입니다. " +
                    "프리미엄 계정으로 로그인하거나 파일 크기를 줄이세요.");
    }

    private static String checksum(byte[] data, int count) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        md5.update(data, 0, count);
        byte[] digest = md5.digest();
        StringBuilder s = new StringBuilder(32);
        for (byte part : digest)
            s.append(String.format(Locale.ROOT, "%02x", part & 0xff));
        return s.toString();
    }

    private JSONObject precreate(Models.RemoteProfile p, String path, long size)
            throws Exception {
        JSONArray blockList = new JSONArray();
        blockList.put(PRECREATE_BLOCKS[0]);
        if (size > CHUNK_BYTES) blockList.put(PRECREATE_BLOCKS[1]);
        String parent = RemoteProvider.parent(path);
        FormBody body = new FormBody.Builder()
                .add("path", path)
                .add("autoinit", "1")
                .add("size", String.valueOf(size))
                .add("target_path", parent)
                .add("local_mtime", String.valueOf(System.currentTimeMillis() / 1000))
                .add("file_limit_switch_v34", "true")
                .add("block_list", blockList.toString()).build();
        JSONObject response = requestJson(p, authorized(p,
                common(p, "api/precreate").build()).post(body).build(),
                "업로드 사전등록(precreate)", ReplyKind.PRECREATE);
        if (response.optInt("return_type", 1) == 2)
            throw new FileTransferEngine.NameConflict();
        if (response.optString("uploadid").isEmpty())
            throw new IOException("TeraBox 업로드 준비 ID가 없습니다.");
        return response;
    }

    private String sendChunk(Models.RemoteProfile p, HttpUrl uploadEndpoint,
                             String path, String uploadId, int index,
                             byte[] bytes, int count) throws Exception {
        String expectedMd5 = checksum(bytes, count);
        RequestBody chunk = RequestBody.create(
                java.util.Arrays.copyOf(bytes, count),
                MediaType.parse("application/octet-stream"));
        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "blob", chunk).build();
        HttpUrl url = uploadEndpoint.newBuilder()
                .addQueryParameter("method", "upload")
                .addQueryParameter("app_id", "250528")
                .addQueryParameter("channel", "dubox")
                .addQueryParameter("clienttype", "0")
                .addQueryParameter("jsToken", jsToken(p))
                .addQueryParameter("path", path)
                .addQueryParameter("uploadid", uploadId)
                .addQueryParameter("partseq", String.valueOf(index))
                .addQueryParameter("uploadsign", "0").build();
        Request req = authorized(p, url).post(multipart).build();
        // Upload-chunk replies contain md5/uploadid but no errno field,
        // unlike the regular TeraBox JSON API response types.
        JSONObject response;
        try (Response raw = http.newCall(req).execute()) {
            if (!raw.isSuccessful() || raw.body() == null)
                throw new CloudProvider.CloudFailure("TeraBox",
                        "분할 파일 업로드", raw.code());
            response = new JSONObject(raw.body().string());
            int status = errorNumber(response);
            if (status != -999 && status != 0)
                throw new CloudProvider.CloudApiFailure("TeraBox",
                        "분할 파일 업로드", status, errorMessage(status));
        }
        String uploadedMd5 = response.optString("md5", "");
        if (uploadedMd5.isEmpty() || !expectedMd5.equalsIgnoreCase(uploadedMd5))
            throw new IOException("TeraBox 업로드 조각 무결성 확인 실패.");
        return expectedMd5;
    }

    private void finalizeUpload(Models.RemoteProfile p, String path, String uploadId,
                                long size, JSONArray blocks, boolean overwrite) throws Exception {
        FormBody data = new FormBody.Builder()
                .add("path", path)
                .add("uploadid", uploadId)
                .add("size", String.valueOf(size))
                .add("target_path", RemoteProvider.parent(path))
                .add("block_list", blocks.toString())
                .add("local_mtime", String.valueOf(System.currentTimeMillis() / 1000))
                .build();
        JSONObject result = requestJson(p, authorized(p, common(p, "api/create")
                .addQueryParameter("isdir", "0")
                .addQueryParameter("rtype", overwrite ? "3" : "0").build()).post(data).build(),
                "업로드 최종 확정", ReplyKind.UPLOAD_FINISH);
        if (result.has("size") && result.optLong("size", -1) != size)
            throw new IOException("TeraBox 파일 생성 후 크기가 다릅니다.");
    }

    void upload(Models.RemoteProfile p, String folder, String name,
                InputStream input, long size) throws Exception {
        upload(p, folder, name, input, size, false);
    }

    void upload(Models.RemoteProfile p, String folder, String name,
                InputStream input, long size, boolean overwrite) throws Exception {
        RemoteProvider.child("/", name);
        if (size == 0)
            throw new IOException("TeraBox는 0바이트 파일을 지원하지 않습니다.");
        if (size > PREMIUM_FILE_LIMIT)
            throw new IOException("TeraBox 최대 128GB 파일 제한을 초과했습니다.");
        String destination = RemoteProvider.child(folder, name);
        if (overwrite) {
            boolean exists = false;
            for (Models.Entry entry : lookup.list(p, folder))
                if (entry.name.equalsIgnoreCase(name)) {
                    exists = true;
                    if (entry.directory)
                        throw new IOException("기존 폴더를 파일로 덮어쓸 수 없습니다.");
                    break;
                }
            if (exists) {
                // A distinct remote staging upload means failure never
                // truncates the existing original before final COPY.
                String tempName = ".nasmount-" +
                        UUID.randomUUID().toString().substring(0, 12) + ".upload";
                upload(p, folder, tempName, input, size, false);
                String tempPath = RemoteProvider.child(folder, tempName);
                copyOrMove(p, tempPath, folder, false, true, name);
                delete(p, tempPath); // normal TeraBox Trash, not permanent purge
                return;
            }
        } else assertNotExisting(p, folder, name);
        File pending = null;
        InputStream payload = input;
        try {
            if (size < 0) {
                pending = File.createTempFile("nasmount-terabox-", ".part");
                try (OutputStream to = new FileOutputStream(pending)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) to.write(buffer, 0, read);
                }
                size = pending.length();
                if (size == 0) throw new IOException("빈 파일은 업로드할 수 없습니다.");
                if (size > PREMIUM_FILE_LIMIT)
                    throw new IOException("TeraBox 최대 128GB 파일 제한을 초과했습니다.");
                payload = new FileInputStream(pending);
            }
            enforceUploadLimit(p, size);
            String absolute = fullPath(p, destination);
            HttpUrl chunkEndpoint = uploadHost(p);
            JSONObject ticket = precreate(p, absolute, size);
            String id = ticket.optString("uploadid");
            JSONArray pieces = new JSONArray();
            byte[] buffer = new byte[CHUNK_BYTES];
            long remaining = size;
            for (int index = 0; remaining > 0; index++) {
                int len = (int) Math.min((long)buffer.length, remaining);
                int filled = 0;
                while (filled < len) {
                    int read = payload.read(buffer, filled, len - filled);
                    if (read < 0) throw new IOException("업로드 데이터가 파일 크기보다 짧습니다.");
                    if (read == 0) continue;
                    filled += read;
                }
                String md5 = sendChunk(p, chunkEndpoint, absolute, id, index, buffer, len);
                pieces.put(md5);
                remaining -= len;
            }
            if (payload.read() != -1)
                throw new IOException("업로드 원본의 크기가 변경되었습니다.");
            finalizeUpload(p, absolute, id, size, pieces, overwrite);
        } finally {
            if (pending != null) {
                if (payload != input) try { payload.close(); } catch (IOException ignored) {}
                if (pending.exists() && !pending.delete()) pending.deleteOnExit();
            }
        }
    }
}
