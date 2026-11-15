package com.nasmount.mobile;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Official MYBOX PAT write API. Request headers are sent ONLY to the fixed
 * MYBOX API origin. One-time upload URLs never receive the PAT header.
 * All calls must run on a worker thread. No user account is touched in tests.
 */
final class MyBoxWriteApi {
    interface Lookup {
        String folderId(Models.RemoteProfile p, String folder) throws Exception;
        List<Models.Entry> list(Models.RemoteProfile p, String folder) throws Exception;
    }
    private static final Lookup LIVE_LOOKUP = new Lookup() {
        @Override public String folderId(Models.RemoteProfile p, String folder)
                throws Exception {
            return CloudProvider.myboxFolderId(p, folder);
        }
        @Override public List<Models.Entry> list(Models.RemoteProfile p, String folder)
                throws Exception {
            return CloudProvider.list(p, folder);
        }
    };
    // Declare LIVE only after LIVE_LOOKUP is initialized; otherwise
    // production uploads, copies, renames and mkdir throw NPE.
    static final MyBoxWriteApi LIVE = new MyBoxWriteApi(
            "https://open-api.mybox.naver.com/", LIVE_LOOKUP);
    private final HttpUrl base;
    private final Lookup lookup;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    // Localhost origin is used only by the packaged MockWebServer tests.
    MyBoxWriteApi(String origin) {
        this(origin, LIVE_LOOKUP);
    }

    // Test-friendly resolver isolates personal accounts from loopback tests.
    MyBoxWriteApi(String origin, Lookup folderLookup) {
        base = HttpUrl.get(origin);
        // A missing folder resolver must be rejected at initialization,
        // never surface as a NullPointerException after a user operation.
        lookup = java.util.Objects.requireNonNull(folderLookup,
                "MYBOX folder resolver was not initialized");
        if (!base.host().equals("open-api.mybox.naver.com") &&
                !base.host().equals("127.0.0.1") &&
                !base.host().equals("localhost"))
            throw new IllegalArgumentException("MYBOX endpoint host not allowed");
    }

    static boolean canWrite(Models.RemoteProfile profile) {
        return profile != null && "MYBOX".equals(profile.protocol);
    }

    private static void checkToken(Models.RemoteProfile profile) throws IOException {
        if (profile == null || !CloudProvider.validMyBoxToken(profile.password))
            throw new IOException("MYBOX PAT를 확인해 주세요.");
    }

    private HttpUrl path(String... segments) {
        HttpUrl.Builder builder = base.newBuilder();
        for (String segment : segments) builder.addPathSegment(segment);
        return builder.build();
    }

    private Request.Builder authorized(Models.RemoteProfile p, HttpUrl endpoint)
            throws IOException {
        checkToken(p);
        if (!endpoint.host().equals(base.host()) ||
                endpoint.port() != base.port() ||
                !endpoint.scheme().equals(base.scheme()))
            throw new IOException("MYBOX 인증 요청 주소가 일치하지 않습니다.");
        return new Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + p.password)
                .header("Accept", "application/json");
    }

    private static RequestBody json(JSONObject body) {
        return RequestBody.create(body.toString(),
                MediaType.parse("application/json; charset=utf-8"));
    }

    private JSONObject executeJson(Request request, int expected) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != expected)
                throw new CloudProvider.CloudFailure("MYBOX",
                        request.url().encodedPath().endsWith("/files") ?
                                "업로드 URL 발급" : "파일 작업", response.code());
            String body = response.body() == null ? "" : response.body().string();
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        }
    }

    private void executeEmpty(Request request, int expected) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (response.code() != expected)
                throw new CloudProvider.CloudFailure("MYBOX", "파일 변경",
                        response.code());
        }
    }

    private String parentId(Models.RemoteProfile p, String parent) throws Exception {
        return lookup.folderId(p, parent);
    }

    private Models.Entry existing(Models.RemoteProfile p, String logicalPath)
            throws Exception {
        String path = RemoteProvider.relativePath(logicalPath);
        if (path.equals("/")) throw new IOException("MYBOX 루트 폴더는 변경할 수 없습니다.");
        String parent = RemoteProvider.parent(path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (Models.Entry entry : lookup.list(p, parent))
            if (entry.name.equals(name)) return entry;
        throw new IOException("MYBOX 파일 또는 폴더가 존재하지 않습니다.");
    }

    private void checkNoCollision(Models.RemoteProfile p, String parent, String name)
            throws Exception {
        RemoteProvider.child("/", name);
        for (Models.Entry entry : lookup.list(p, parent))
            if (entry.name.equalsIgnoreCase(name))
                throw new FileTransferEngine.NameConflict();
    }

    void mkdir(Models.RemoteProfile p, String folder) throws Exception {
        String parent = RemoteProvider.parent(folder);
        String name = folder.substring(folder.lastIndexOf('/') + 1);
        checkNoCollision(p, parent, name);
        JSONObject body = new JSONObject().put("folderName", name);
        String id = parentId(p, parent);
        if (id != null) body.put("parentId", id);
        JSONObject result = executeJson(authorized(p,
                path("v1","drive","folders")).post(json(body)).build(), 201);
        if (result.optString("resourceId").isEmpty())
            throw new IOException("MYBOX 폴더 생성 응답에 ID가 없습니다.");
    }

    void rename(Models.RemoteProfile p, String from, String to) throws Exception {
        String source = RemoteProvider.relativePath(from);
        String target = RemoteProvider.relativePath(to);
        if (!RemoteProvider.parent(source).equals(RemoteProvider.parent(target)))
            throw new IOException("다른 폴더로 이동할 때는 이동 기능을 사용하세요.");
        if (source.equals(target)) return;
        String name = target.substring(target.lastIndexOf('/') + 1);
        checkNoCollision(p, RemoteProvider.parent(target), name);
        Models.Entry entry = existing(p, source);
        JSONObject result = executeJson(authorized(p,
                path("v1","drive","resources",entry.remoteId,"rename"))
                .post(json(new JSONObject().put("name", name))).build(), 200);
        if (!name.equals(result.optString("name")))
            throw new IOException("MYBOX 이름 변경 결과를 확인하지 못했습니다.");
    }

    void delete(Models.RemoteProfile p, String itemPath) throws Exception {
        Models.Entry item = existing(p, itemPath);
        executeEmpty(authorized(p, path("v1","drive","resources",item.remoteId))
                .delete().build(), 204);
        // MYBOX official DELETE moves file/folder into the account Trash.
    }

    void copyOrMove(Models.RemoteProfile p, String from, String destFolder,
                    boolean moving) throws Exception {
        copyOrMove(p, from, destFolder, moving, false);
    }

    void copyOrMove(Models.RemoteProfile p, String from, String destFolder,
                    boolean moving, boolean overwrite) throws Exception {
        Models.Entry source = existing(p, from);
        String folder = RemoteProvider.relativePath(destFolder);
        if (!overwrite) checkNoCollision(p, folder, source.name);
        String id = parentId(p, folder);
        if (moving && folder.equals("/")) {
            // Official /move requires parentId; root has no parentId in the
            // public API. Copy to root (optional parentId), then trash source.
            copyOrMove(p, from, folder, false, overwrite);
            delete(p, from);
            return;
        }
        JSONObject body = new JSONObject().put("isOverwrite", overwrite);
        if (id != null) body.put("parentId", id);
        if (moving) {
            executeEmpty(authorized(p,
                    path("v1","drive","resources",source.remoteId,"move"))
                    .post(json(body)).build(), 200);
        } else {
            JSONObject result = executeJson(authorized(p,
                    path("v1","drive","resources",source.remoteId,"copy"))
                    .post(json(body)).build(), 201);
            if (result.optString("resourceId").isEmpty())
                throw new IOException("MYBOX 복사 결과에 ID가 없습니다.");
        }
    }

    static boolean validUploadUrl(HttpUrl url, boolean testLocalhost) {
        if (url == null) return false;
        if (testLocalhost && url.host().equals("127.0.0.1"))
            return url.encodedPath().equals("/v1/storage/upload");
        return url.isHttps() && url.port() == 443 &&
                url.encodedPath().equals("/v1/storage/upload") &&
                url.queryParameter("stoken") != null &&
                CloudProvider.safeDownloadUrl(url);
    }

    Request createUploadSessionRequest(Models.RemoteProfile p, String parent,
                                       String name, long byteCount) throws Exception {
        return createUploadSessionRequest(p, parent, name, byteCount, false);
    }

    Request createUploadSessionRequest(Models.RemoteProfile p, String parent,
                                       String name, long byteCount, boolean overwrite)
            throws Exception {
        if (byteCount < 0) throw new IOException("파일 크기를 확인할 수 없습니다.");
        JSONObject body = new JSONObject()
                .put("fileName", RemoteProvider.child("/", name).substring(1))
                .put("fileSize", byteCount)
                .put("isOverwrite", overwrite)
                .put("resume", false);
        String id = parentId(p, parent);
        if (id != null) body.put("parentId", id);
        return authorized(p, path("v1","drive","files")).post(json(body)).build();
    }

    Request createMultipartUpload(HttpUrl url, String name, InputStream bytes,
                                  long length) throws IOException {
        if (!validUploadUrl(url, !base.isHttps()))
            throw new IOException("MYBOX 업로드 URL의 보안을 확인할 수 없습니다.");
        if (length < 0) throw new IOException("알 수 없는 파일 크기");
        RequestBody file = new RequestBody() {
            @Override public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }
            @Override public long contentLength() { return length; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException {
                byte[] buffer = new byte[65536];
                long remaining = length;
                while (remaining > 0) {
                    int count = bytes.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("MYBOX 전송할 파일이 예상보다 짧습니다.");
                    if (count == 0) continue;
                    sink.write(buffer, 0, count);
                    remaining -= count;
                }
                if (bytes.read() != -1)
                    throw new IOException("MYBOX 전송할 파일이 예상보다 큽니다.");
            }
        };
        MultipartBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("Filedata", name, file)
                .build();
        // No Authorization/PAT header on signed uploadUrl.
        return new Request.Builder().url(url).post(multipart).build();
    }

    void upload(Models.RemoteProfile p, String folder, String name,
                InputStream input, long length) throws Exception {
        upload(p, folder, name, input, length, false);
    }

    void upload(Models.RemoteProfile p, String folder, String name,
                InputStream input, long length, boolean overwrite) throws Exception {
        if (!overwrite) checkNoCollision(p, folder, name);
        File staged = null;
        InputStream payload = input;
        try {
            if (length < 0) {
                // The API requires fileSize. Only providers without metadata
                // use disk staging; normal known-length files stream directly.
                staged = File.createTempFile("nasmount-mybox-", ".tmp");
                try (OutputStream out = new FileOutputStream(staged)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1)
                        out.write(buffer, 0, count);
                }
                length = staged.length();
                payload = new FileInputStream(staged);
            }
            JSONObject ticket = executeJson(
                    createUploadSessionRequest(p, folder, name, length, overwrite), 201);
            if (ticket.optLong("offset", 0) != 0)
                throw new IOException("예상하지 못한 MYBOX 이어올리기 응답입니다.");
            HttpUrl signedUrl = HttpUrl.parse(ticket.optString("uploadUrl"));
            Request request = createMultipartUpload(signedUrl, name, payload, length);
            // Reject redirects to ensure the one-time signed upload token
            // is never forwarded to an unintended third party.
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful())
                    throw new CloudProvider.CloudFailure("MYBOX", "파일 업로드",
                            response.code());
            }
        } finally {
            if (staged != null) {
                if (payload != input) try { payload.close(); } catch (IOException ignored) {}
                if (staged.exists() && !staged.delete())
                    staged.deleteOnExit();
            }
        }
    }
}
