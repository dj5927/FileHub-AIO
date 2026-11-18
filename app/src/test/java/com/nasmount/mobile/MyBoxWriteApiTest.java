package com.nasmount.mobile;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Tests use fake PAT and loopback server; NEVER access a real MYBOX account. */
public class MyBoxWriteApiTest {
    private static final String TOKEN = "mbx_pat_FAKE_UNIT_TEST_0123456789012345";

    private static Models.RemoteProfile profile() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "MYBOX";
        p.password = TOKEN;
        return p;
    }
    private static class FakeLookup implements MyBoxWriteApi.Lookup {
        private final List<Models.Entry> root = new ArrayList<>();
        @Override public String folderId(Models.RemoteProfile p, String folder) {
            return folder.equals("/") ? null : "folderid1234";
        }
        @Override public List<Models.Entry> list(Models.RemoteProfile p, String folder) {
            if (folder.equals("/")) return root;
            return Collections.emptyList();
        }
    }
    // MockWebServer 5 uses localhost rather than 127.0.0.1 by default.
    // Preserve the production strict upload-host allowlist by explicitly
    // constructing loopback URLs in TESTS, not weakening live URL validation.
    private static HttpUrl loopback(MockWebServer s, String path) {
        return s.url(path).newBuilder().host("127.0.0.1").build();
    }
    private static MyBoxWriteApi api(MockWebServer s, FakeLookup lookup) {
        return new MyBoxWriteApi(loopback(s, "/").toString(), lookup);
    }
    private static Models.Entry entry(String name, boolean directory) {
        Models.Entry entry = new Models.Entry(name, "/" + name, directory);
        entry.remoteId = "resource-id-1234";
        return entry;
    }
    private static RecordedRequest request(MockWebServer server) throws Exception {
        RecordedRequest item = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(item);
        return item;
    }
    private static void assertPat(RecordedRequest item) {
        assertEquals("Bearer " + TOKEN, item.getHeader("Authorization"));
    }

    @Test public void productionWriterIsInitializedWithLiveFolderResolver()
            throws Exception {
        // Regression for v0.1.31: LIVE was declared ABOVE LIVE_LOOKUP.
        // During Java class initialization LIVE_LOOKUP was still null,
        // making every real MYBOX write operation throw NPE, while reads
        // went through CloudProvider and continued to work normally.
        java.lang.reflect.Field field =
                MyBoxWriteApi.class.getDeclaredField("lookup");
        field.setAccessible(true);
        assertNotNull("MYBOX production lookup must never be null",
                field.get(MyBoxWriteApi.LIVE));
    }

    @Test public void nullLookupFailsFastRatherThanDuringAnActualWrite() {
        assertThrows(NullPointerException.class, () ->
                new MyBoxWriteApi("http://127.0.0.1/", null));
    }

    @Test public void failedUploadSessionReportsExactCloudHttpStage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(403)
                    .setBody("{\"code\":\"PLAT-403\",\"message\":\"FORBIDDEN\"}"));
            CloudProvider.CloudFailure error = assertThrows(
                    CloudProvider.CloudFailure.class,
                    () -> api(server, new FakeLookup()).upload(profile(), "/",
                            "sample.txt", new ByteArrayInputStream(new byte[]{1}), 1L));
            assertEquals("MYBOX", error.provider);
            assertEquals(403, error.status);
            assertTrue(error.userMessage().contains("업로드 URL"));
            assertFalse(error.userMessage().contains(TOKEN));
            assertPat(request(server));
        }
    }

    @Test public void createFolderUsesOfficialUrlAndParentId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"name\":\"새폴더\",\"resourceId\":\"new-id\"}"));
            api(server, new FakeLookup()).mkdir(profile(), "/대상/새폴더");
            RecordedRequest request = request(server);
            assertEquals("POST", request.getMethod());
            assertEquals("/v1/drive/folders", request.getPath());
            assertPat(request);
            JSONObject data = new JSONObject(request.getBody().readUtf8());
            assertEquals("새폴더", data.getString("folderName"));
            assertEquals("folderid1234", data.getString("parentId"));
        }
    }

    @Test public void rootFolderCreationOmitsParentId() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"name\":\"루트\",\"resourceId\":\"new-id\"}"));
            api(server, new FakeLookup()).mkdir(profile(), "/루트");
            JSONObject data = new JSONObject(request(server).getBody().readUtf8());
            assertFalse(data.has("parentId"));
        }
    }

    @Test public void renameUsesResourceIdAndValidatesResult() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            FakeLookup lookup = new FakeLookup();
            lookup.root.add(entry("old.txt", false));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"name\":\"new.txt\"}"));
            api(server, lookup).rename(profile(), "/old.txt", "/new.txt");
            RecordedRequest request = request(server);
            assertEquals("POST", request.getMethod());
            assertEquals("/v1/drive/resources/resource-id-1234/rename", request.getPath());
            assertPat(request);
            assertEquals("new.txt",
                    new JSONObject(request.getBody().readUtf8()).getString("name"));
        }
    }

    @Test public void deleteUsesOfficialTrashEndpointWithoutPurging() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            FakeLookup lookup = new FakeLookup();
            lookup.root.add(entry("delete-me.txt", false));
            server.enqueue(new MockResponse().setResponseCode(204));
            api(server, lookup).delete(profile(), "/delete-me.txt");
            RecordedRequest request = request(server);
            assertEquals("DELETE", request.getMethod());
            assertEquals("/v1/drive/resources/resource-id-1234", request.getPath());
            assertPat(request);
            assertFalse(request.getPath().contains("trash/"));
        }
    }

    @Test public void moveUsesDestinationParentAndNoOverwrite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            FakeLookup lookup = new FakeLookup();
            lookup.root.add(entry("clip.mp4", false));
            server.enqueue(new MockResponse().setResponseCode(200));
            api(server, lookup).copyOrMove(profile(), "/clip.mp4", "/새폴더", true);
            RecordedRequest request = request(server);
            assertEquals("POST", request.getMethod());
            assertTrue(request.getPath().endsWith("/move"));
            assertPat(request);
            JSONObject data = new JSONObject(request.getBody().readUtf8());
            assertEquals("folderid1234", data.getString("parentId"));
            assertFalse(data.getBoolean("isOverwrite"));
        }
    }

    @Test public void copyUsesDedicatedServerSideEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            FakeLookup lookup = new FakeLookup();
            lookup.root.add(entry("clip.mp4", false));
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"name\":\"clip.mp4\",\"resourceId\":\"copy-id\"}"));
            api(server, lookup).copyOrMove(profile(), "/clip.mp4", "/새폴더", false);
            RecordedRequest request = request(server);
            assertEquals("POST", request.getMethod());
            assertTrue(request.getPath().endsWith("/copy"));
            assertEquals(false, new JSONObject(
                    request.getBody().readUtf8()).getBoolean("isOverwrite"));
        }
    }

    @Test public void rootMoveCopiesThenTrashesSource() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            FakeLookup lookup = new FakeLookup() {
                @Override public List<Models.Entry> list(Models.RemoteProfile p,
                                                        String folder) {
                    if (folder.equals("/새폴더"))
                        return Collections.singletonList(entry("movie.mkv", false));
                    return Collections.emptyList();
                }
            };
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody("{\"name\":\"movie.mkv\",\"resourceId\":\"copy-id\"}"));
            server.enqueue(new MockResponse().setResponseCode(204));
            api(server, lookup).copyOrMove(profile(), "/새폴더/movie.mkv", "/", true);
            assertTrue(request(server).getPath().endsWith("/copy"));
            assertEquals("DELETE", request(server).getMethod());
        }
    }

    @Test public void sameNameCollisionRejectsWithoutSendingRequests() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            FakeLookup lookup = new FakeLookup();
            lookup.root.add(entry("existing.txt", false));
            assertThrows(FileTransferEngine.NameConflict.class, () ->
                    api(server, lookup).mkdir(profile(), "/existing.txt"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void uploadSessionSendsExactSizeAndNoOverwrite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            MyBoxWriteApi api = api(server, new FakeLookup());
            Request session = api.createUploadSessionRequest(
                    profile(), "/", "영상.mkv", 9876543210L);
            assertEquals("POST", session.method());
            assertEquals("/v1/drive/files", session.url().encodedPath());
            assertEquals("Bearer " + TOKEN, session.header("Authorization"));
            JSONObject data = new JSONObject(okioToString(session.body()));
            assertEquals(9876543210L, data.getLong("fileSize"));
            assertFalse(data.getBoolean("isOverwrite"));
            assertFalse(data.has("parentId"));
        }
    }

    @Test public void signedMultipartUsesFiledataAndNeverSendsPat() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] payload = new byte[137_000];
            new java.util.Random(11).nextBytes(payload);
            server.enqueue(new MockResponse().setResponseCode(201));
            MyBoxWriteApi api = api(server, new FakeLookup());
            Request post = api.createMultipartUpload(
                    loopback(server, "/v1/storage/upload?stoken=testing"),
                    "video.mkv", new ByteArrayInputStream(payload), payload.length);
            try (Response response = new OkHttpClient().newCall(post).execute()) {
                assertEquals(201, response.code());
            }
            RecordedRequest sent = request(server);
            assertNull(sent.getHeader("Authorization"));
            assertEquals("POST", sent.getMethod());
            assertTrue(sent.getHeader("Content-Type").startsWith("multipart/form-data;"));
            byte[] body = sent.getBody().readByteArray();
            String latin = new String(body, StandardCharsets.ISO_8859_1);
            assertTrue(latin.contains("name=\"Filedata\""));
            assertTrue(latin.contains("filename=\"video.mkv\""));
            assertTrue(latin.contains(new String(Arrays.copyOfRange(
                    payload, 0, 32), StandardCharsets.ISO_8859_1)));
        }
    }

    @Test public void uploadUrlCannotBeHttpOrPrivateOrUnexpectedPath() {
        assertFalse(MyBoxWriteApi.validUploadUrl(
                HttpUrl.get("http://example.org/v1/storage/upload?stoken=x"), false));
        assertFalse(MyBoxWriteApi.validUploadUrl(
                HttpUrl.get("https://127.0.0.1/v1/storage/upload?stoken=x"), false));
        assertFalse(MyBoxWriteApi.validUploadUrl(
                HttpUrl.get("https://example.com/wrong/path?stoken=x"), false));
        assertFalse(MyBoxWriteApi.validUploadUrl(
                HttpUrl.get("https://example.com/v1/storage/upload"), false));
    }

    @Test public void uploadEndToEndRequestsTicketThenPostsExactFile()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] payload = new byte[262_155];
            new java.util.Random(75).nextBytes(payload);
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody(new JSONObject()
                            .put("offset", 0)
                            .put("uploadUrl", loopback(server,
                                    "/v1/storage/upload?stoken=test-ticket").toString())
                            .toString()));
            server.enqueue(new MockResponse().setResponseCode(201));
            api(server, new FakeLookup()).upload(profile(), "/", "파일.zip",
                    new ByteArrayInputStream(payload), payload.length);
            RecordedRequest ticket = request(server);
            assertEquals("POST", ticket.getMethod());
            assertEquals("/v1/drive/files", ticket.getPath());
            assertPat(ticket);
            JSONObject intent = new JSONObject(ticket.getBody().readUtf8());
            assertEquals(payload.length, intent.getLong("fileSize"));
            assertFalse(intent.getBoolean("isOverwrite"));
            RecordedRequest transfer = request(server);
            assertEquals("POST", transfer.getMethod());
            assertTrue(transfer.getPath().startsWith("/v1/storage/upload"));
            assertNull(transfer.getHeader("Authorization"));
            assertEquals(2, server.getRequestCount());
            assertTrue(transfer.getBodySize() > payload.length);
            byte[] multipart = transfer.getBody().readByteArray();
            assertTrue(indexOf(multipart, payload) >= 0);
        }
    }

    @Test public void uploadRejectsUnexpectedOffsetBeforePostingFile() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(201)
                    .setBody(new JSONObject()
                            .put("offset", 3)
                            .put("uploadUrl", loopback(server,
                                    "/v1/storage/upload?stoken=test-ticket").toString())
                            .toString()));
            assertThrows(java.io.IOException.class, () ->
                    api(server, new FakeLookup()).upload(profile(), "/", "file.txt",
                            new ByteArrayInputStream(new byte[]{1,2,3}), 3));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void myboxAndTeraboxItemsWritable() {
        Models.RemoteProfile mybox = profile();
        Models.Entry file = entry("document.pdf", false);
        file.size = 1234L;
        FileTransferEngine.Item selected = FileTransferEngine.remote(mybox, file);
        assertTrue(selected.writable());
        assertEquals(1234L, selected.size);
        Models.RemoteProfile terabox = new Models.RemoteProfile();
        terabox.protocol = "TeraBox";
        assertTrue(FileTransferEngine.remote(terabox, file).writable());
        assertFalse(CloudProvider.isReadOnly(terabox));
        assertFalse(CloudProvider.isReadOnly(mybox));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (haystack[i+j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static String okioToString(okhttp3.RequestBody body) throws Exception {
        okio.Buffer buffer = new okio.Buffer();
        body.writeTo(buffer);
        return buffer.readUtf8();
    }
}
