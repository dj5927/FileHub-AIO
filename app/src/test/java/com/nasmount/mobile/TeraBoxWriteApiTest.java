package com.nasmount.mobile;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/**
 * All tests use localhost, fake ndus, and fake server responses. Never
 * connect to the actual user's TeraBox account in automated tests.
 */
public final class TeraBoxWriteApiTest {
    private static final String SESSION = "ndus=TEST_FAKE_COOKIE_1234567890";
    private static final String JS = "fake_js_token_1234567890";
    private static final String JS_HTML = "before `function%20fn%28a%29%7Bwindow.jsToken%20" +
            "%3D%20a%7D%3Bfn%28%22" + JS + "%22%29` after";

    private static Models.RemoteProfile profile() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.id = "test-profile";
        p.protocol = "TeraBox";
        p.root = "/";
        p.password = SESSION;
        return p;
    }
    private static Models.Entry file(String name, String folder) {
        return new Models.Entry(name,
                RemoteProvider.child(folder, name), false);
    }
    private static TeraBoxWriteApi api(MockWebServer server, List<Models.Entry> existing) {
        return new TeraBoxWriteApi(server.url("/").toString(),
                (p, dir) -> existing);
    }
    private static RecordedRequest request(MockWebServer server) throws Exception {
        RecordedRequest received = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(received);
        return received;
    }
    private static void expectSessionPage(MockWebServer server) {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(JS_HTML));
    }
    private static void expectOk(MockWebServer server) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"errno\":0}"));
    }
    private static JSONObject form(RecordedRequest recorded) throws Exception {
        String body = recorded.getBody().readUtf8();
        // FormBody QueryEscape: percent escapes are decoded by HttpUrl.
        HttpUrl decoded = HttpUrl.get("http://127.0.0.1/?" + body);
        String filelist = decoded.queryParameter("filelist");
        return new JSONObject().put("filelist", filelist)
                .put("path", decoded.queryParameter("path"))
                .put("isdir", decoded.queryParameter("isdir"))
                .put("rtype", decoded.queryParameter("rtype"));
    }
    private static void assertAuthorized(RecordedRequest recorded) {
        assertEquals(SESSION, recorded.getHeader("Cookie"));
        assertEquals("XMLHttpRequest",
                recorded.getHeader("X-Requested-With"));
    }

    @Test public void trustedRegionalApiHostCanBeSelectedAfterExplicitMinusSix()
            throws Exception {
        okhttp3.HttpUrl oldUrl = okhttp3.HttpUrl.get(
                "https://www.terabox.com/api/create?jsToken=abc");
        okhttp3.HttpUrl redirected = TeraBoxWriteApi.approvedApiDomainFromHeader(
                oldUrl, "c-jp");
        assertEquals("c-jp.terabox.com", redirected.host());
        assertEquals("/api/create", redirected.encodedPath());
        assertEquals("abc", redirected.queryParameter("jsToken"));
        assertTrue(redirected.isHttps());
        assertEquals(443, redirected.port());
    }

    @Test public void rejectUntrustedRegionalApiRedirectHeaders() {
        okhttp3.HttpUrl oldUrl = okhttp3.HttpUrl.get(
                "https://www.terabox.com/api/create");
        for (String prefix : new String[]{
                "evil.com", "terabox.com.evil", "../evil", "a/b", "", "a:b",
                "%%%%", "foo bar"}) {
            assertThrows(java.io.IOException.class, () ->
                    TeraBoxWriteApi.approvedApiDomainFromHeader(oldUrl, prefix));
        }
    }

    @Test public void cookieSessionCanFollowSafeSameOriginPageRedirect() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", "/main"));
            expectSessionPage(server);
            expectOk(server);
            api(server, Collections.emptyList()).mkdir(profile(), "/new-folder");
            RecordedRequest root = request(server);
            RecordedRequest redirected = request(server);
            RecordedRequest created = request(server);
            assertEquals("/", root.getRequestUrl().encodedPath());
            assertEquals("/main", redirected.getRequestUrl().encodedPath());
            assertAuthorized(redirected);
            assertEquals("/api/create", created.getRequestUrl().encodedPath());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void neverSendNdusCookieToExternalRedirect() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", "https://untrusted.example/"));
            assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).mkdir(profile(), "/new-folder"));
            assertAuthorized(request(server));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void staleJsTokenIsRefreshedAfterServerExplicitlyRejectsIt()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"errno\":4000023}"));
            expectSessionPage(server);
            expectOk(server);
            api(server, Collections.emptyList()).mkdir(profile(), "/refresh");
            request(server);
            RecordedRequest rejected = request(server);
            request(server);
            RecordedRequest retried = request(server);
            assertEquals("/api/create", rejected.getRequestUrl().encodedPath());
            assertEquals("/api/create", retried.getRequestUrl().encodedPath());
            assertEquals(JS, retried.getRequestUrl().queryParameter("jsToken"));
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void makeDirectoryFromNdusWithNoAutoRename() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            expectOk(server);
            api(server, Collections.emptyList()).mkdir(profile(), "/사진/새폴더");
            assertEquals("GET", request(server).getMethod());
            RecordedRequest made = request(server);
            assertEquals("POST", made.getMethod());
            assertEquals("/api/create", made.getRequestUrl().encodedPath());
            assertEquals(JS, made.getRequestUrl().queryParameter("jsToken"));
            assertAuthorized(made);
            JSONObject data = form(made);
            assertEquals("/사진/새폴더", data.getString("path"));
            assertEquals("1", data.getString("isdir"));
            assertEquals("0", data.getString("rtype"));
        }
    }

    @Test public void renameUsesFilemanagerFormWithNoOverwrite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            expectOk(server);
            api(server, Collections.emptyList()).rename(
                    profile(), "/원래이름.txt", "/변경이름.txt");
            request(server);
            RecordedRequest changed = request(server);
            assertEquals("rename", changed.getRequestUrl().queryParameter("opera"));
            assertEquals("fail", changed.getRequestUrl().queryParameter("onnest"));
            JSONArray list = new JSONArray(form(changed).getString("filelist"));
            assertEquals("/원래이름.txt", list.getJSONObject(0).getString("path"));
            assertEquals("변경이름.txt", list.getJSONObject(0).getString("newname"));
            assertFalse(list.getJSONObject(0).has("ondup"));
        }
    }

    @Test public void copyUsesTaskQueryBeforeReportingSuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody("{\"errno\":0,\"taskid\":1234}"));
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"status\":\"success\",\"list\":[]}"));
            api(server, Collections.emptyList()).copyOrMove(
                    profile(), "/source/file.txt", "/destination", false);
            request(server);
            RecordedRequest copying = request(server);
            assertEquals("copy", copying.getRequestUrl().queryParameter("opera"));
            assertEquals("2", copying.getRequestUrl().queryParameter("async"));
            JSONArray files = new JSONArray(form(copying).getString("filelist"));
            JSONObject f = files.getJSONObject(0);
            assertEquals("/source/file.txt", f.getString("path"));
            assertEquals("/destination", f.getString("dest"));
            assertEquals("file.txt", f.getString("newname"));
            RecordedRequest task = request(server);
            assertEquals("/share/taskquery", task.getRequestUrl().encodedPath());
            assertEquals("1234", task.getRequestUrl().queryParameter("taskid"));
        }
    }

    @Test public void moveUsesServerSideMoveAndDestinationFolder() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            expectOk(server);
            api(server, Collections.emptyList()).copyOrMove(
                    profile(), "/source/file.txt", "/", true);
            request(server);
            RecordedRequest moved = request(server);
            assertEquals("move", moved.getRequestUrl().queryParameter("opera"));
            assertEquals("1", moved.getRequestUrl().queryParameter("async"));
            JSONArray list = new JSONArray(form(moved).getString("filelist"));
            assertEquals("/", list.getJSONObject(0).getString("dest"));
        }
    }

    @Test public void normalDeleteOnlyMovesToTrashNotPurge() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            expectOk(server);
            api(server, Collections.emptyList()).delete(profile(), "/가족/일기.txt");
            request(server);
            RecordedRequest deleted = request(server);
            assertEquals("delete", deleted.getRequestUrl().queryParameter("opera"));
            JSONArray paths = new JSONArray(form(deleted).getString("filelist"));
            assertEquals("/가족/일기.txt", paths.getString(0));
            assertFalse(deleted.getPath().contains("recycle"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void collisionStopsAllMutatingHttpCalls() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            assertThrows(FileTransferEngine.NameConflict.class, () ->
                    api(server, Collections.singletonList(file("existing.txt", "/")))
                            .mkdir(profile(), "/existing.txt"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void rejectsMissingWebSessionTokenBeforeWrite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("<html>login required</html>"));
            assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).mkdir(profile(), "/new"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void rootAndOwnChildAreProtected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            TeraBoxWriteApi api = api(server, Collections.emptyList());
            assertThrows(IOException.class, () -> api.delete(profile(), "/"));
            assertThrows(IOException.class, () ->
                    api.copyOrMove(profile(), "/folder", "/folder/sub", true));
            assertThrows(IOException.class, () ->
                    api.copyOrMove(profile(), "/folder", "/folder", false));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void rejectUntrustedUploadHostAndHttp() {
        assertFalse(TeraBoxWriteApi.isApprovedUploadHost(
                HttpUrl.get("https://evil.example/rest/2.0/pcs/superfile2"), false));
        assertFalse(TeraBoxWriteApi.isApprovedUploadHost(
                HttpUrl.get("http://c-jp.terabox.com/rest/2.0/pcs/superfile2"), false));
        assertFalse(TeraBoxWriteApi.isApprovedUploadHost(
                HttpUrl.get("https://terabox.com.evil.com/"), false));
        assertTrue(TeraBoxWriteApi.isApprovedUploadHost(
                HttpUrl.get("https://c-jp.terabox.com/rest/2.0/pcs/superfile2"), false));
        assertFalse(TeraBoxWriteApi.isApprovedMainHost(
                HttpUrl.get("https://terabox.com.example.com"), false));
    }

    @Test public void zeroByteUploadIsRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).upload(profile(), "/",
                            "empty.txt", new ByteArrayInputStream(new byte[0]), 0));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void writeCapabilityIsAvailableForAllFileManagerItems() {
        Models.Entry file = file("test.txt", "/");
        file.size = 128;
        assertTrue(FileTransferEngine.remote(profile(), file).writable());
        assertFalse(CloudProvider.isReadOnly(profile()));
    }

    @Test public void canUploadViaPrecreateChunkAndCreateWithRealMd5()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] data = new byte[163_887];
            new java.util.Random(17).nextBytes(data);
            String md5 = md5(data, 0, data.length);
            server.enqueue(new MockResponse().setBody("{\"errno\":0,\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"return_type\":1,\"uploadid\":\"upload-id-1\"}"));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("md5", md5).put("uploadid", "upload-id-1").toString()));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("errno", 0).put("size", data.length).toString()));
            api(server, Collections.emptyList()).upload(profile(), "/", "movie.mkv",
                    new ByteArrayInputStream(data), data.length);
            RecordedRequest locate = request(server);
            assertEquals("locateupload", locate.getRequestUrl().queryParameter("method"));
            request(server); // jsToken page
            RecordedRequest precreate = request(server);
            assertEquals("/api/precreate", precreate.getRequestUrl().encodedPath());
            assertAuthorized(precreate);
            RecordedRequest chunk = request(server);
            assert