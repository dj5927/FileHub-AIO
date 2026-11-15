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
            assertEquals("upload", chunk.getRequestUrl().queryParameter("method"));
            assertEquals("0", chunk.getRequestUrl().queryParameter("partseq"));
            assertEquals("upload-id-1", chunk.getRequestUrl().queryParameter("uploadid"));
            assertAuthorized(chunk);
            assertTrue(chunk.getHeader("Content-Type").startsWith("multipart/form-data;"));
            assertTrue(find(chunk.getBody().readByteArray(), data) >= 0);
            RecordedRequest created = request(server);
            assertEquals("/api/create", created.getRequestUrl().encodedPath());
            assertEquals("0", created.getRequestUrl().queryParameter("rtype"));
            assertEquals(5, server.getRequestCount());
        }
    }

    @Test public void multipartUploadStreamsTwoChunksWithoutMutatingInput()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] data = new byte[TeraBoxWriteApi.CHUNK_BYTES + 731];
            new java.util.Random(23).nextBytes(data);
            server.enqueue(new MockResponse().setBody("{\"errno\":0,\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"return_type\":1,\"uploadid\":\"u-2\"}"));
            server.enqueue(new MockResponse().setBody(
                    new JSONObject().put("md5", md5(data, 0, TeraBoxWriteApi.CHUNK_BYTES)).toString()));
            server.enqueue(new MockResponse().setBody(
                    new JSONObject().put("md5", md5(data, TeraBoxWriteApi.CHUNK_BYTES, 731)).toString()));
            server.enqueue(new MockResponse().setBody(
                    new JSONObject().put("errno", 0).put("size", data.length).toString()));
            api(server, Collections.emptyList()).upload(profile(), "/",
                    "large-file.dat", new ByteArrayInputStream(data), data.length);
            request(server); request(server); request(server);
            assertEquals("0", request(server).getRequestUrl().queryParameter("partseq"));
            assertEquals("1", request(server).getRequestUrl().queryParameter("partseq"));
            RecordedRequest created = request(server);
            JSONObject params = form(created);
            assertTrue(created.getPath().contains("rtype=0"));
            assertEquals(6, server.getRequestCount());
        }
    }

    @Test public void approvedCopyUsesExplicitServerOverwriteNotSilentDefault()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            Models.Entry existing = file("movie.mkv", "/");
            List<Models.Entry> targetList = Collections.singletonList(existing);
            TeraBoxWriteApi api = api(server, targetList);
            assertThrows(FileTransferEngine.NameConflict.class,
                    () -> api.copyOrMove(profile(), "/source/movie.mkv", "/", false));
            assertEquals(0, server.getRequestCount());
            expectSessionPage(server);
            expectOk(server);
            api.copyOrMove(profile(), "/source/movie.mkv", "/", false, true);
            request(server);
            RecordedRequest copy = request(server);
            assertEquals("copy", copy.getRequestUrl().queryParameter("opera"));
            JSONObject operation = new JSONArray(form(copy).getString("filelist"))
                    .getJSONObject(0);
            assertEquals("overwrite", operation.getString("ondup"));
            assertEquals("/source/movie.mkv", operation.getString("path"));
            assertEquals("/", operation.getString("dest"));
        }
    }

    @Test public void approvedUploadStagesBeforeReplacingExistingTarget()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] content = new byte[127_007];
            new java.util.Random(91).nextBytes(content);
            String checksum = md5(content, 0, content.length);
            List<Models.Entry> targetList =
                    Collections.singletonList(file("movie.mkv", "/"));
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"return_type\":1,\"uploadid\":\"staged-upload\"}"));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("md5", checksum).toString()));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("errno", 0).put("size", content.length).toString()));
            expectOk(server); // server-side COPY replaces only after upload success
            expectOk(server); // removes staging to TeraBox trash
            api(server, targetList).upload(profile(), "/", "movie.mkv",
                    new ByteArrayInputStream(content), content.length, true);
            request(server); request(server); request(server);
            request(server); // chunk
            RecordedRequest finalized = request(server);
            assertEquals("0", finalized.getRequestUrl().queryParameter("rtype"));
            assertTrue(form(finalized).getString("path").startsWith(
                    "/.nasmount-"));
            RecordedRequest copy = request(server);
            JSONObject op = new JSONArray(form(copy).getString("filelist"))
                    .getJSONObject(0);
            assertEquals("copy", copy.getRequestUrl().queryParameter("opera"));
            assertEquals("overwrite", op.getString("ondup"));
            assertEquals("movie.mkv", op.getString("newname"));
            assertTrue(op.getString("path").startsWith("/.nasmount-"));
            RecordedRequest deleted = request(server);
            assertEquals("delete", deleted.getRequestUrl().queryParameter("opera"));
            assertEquals(7, server.getRequestCount());
        }
    }

    @Test public void brokenStagingUploadNeverSendsOverwriteCopy() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] content = new byte[]{1,2,3,4,5};
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"return_type\":1,\"uploadid\":\"u-fail\"}"));
            server.enqueue(new MockResponse().setBody("{\"md5\":\"BAD-CHECKSUM\"}"));
            assertThrows(IOException.class, () ->
                    api(server, Collections.singletonList(file("movie.mkv", "/")))
                            .upload(profile(), "/", "movie.mkv",
                                    new ByteArrayInputStream(content), content.length, true));
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void freeAccountLargeUploadChecksEntitlementBeforeAnyFileWrite()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"data\":{\"member_info\":{\"is_vip\":0}}}"));
            assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).upload(profile(), "/",
                            "huge.bin", new ByteArrayInputStream(new byte[]{1}),
                            TeraBoxWriteApi.FREE_FILE_LIMIT + 1));
            RecordedRequest membership = request(server);
            assertTrue(membership.getRequestUrl().encodedPath().contains("membership"));
            assertEquals("query", membership.getRequestUrl().queryParameter("method"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void locationMayOmitErrnoWhenServerHostIsPresent()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] data = new byte[]{10, 20, 30};
            server.enqueue(new MockResponse().setBody("{\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"return_type\":1,\"uploadid\":\"locate-ok\"}"));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("md5", md5(data, 0, data.length)).toString()));
            server.enqueue(new MockResponse().setBody(
                    new JSONObject().put("errno", 0).put("size", data.length).toString()));
            api(server, Collections.emptyList()).upload(profile(), "/", "tiny.txt",
                    new ByteArrayInputStream(data), data.length);
            assertEquals("locateupload", request(server).getRequestUrl()
                    .queryParameter("method"));
            assertEquals(5, server.getRequestCount());
        }
    }

    @Test public void precreateAndFinishMayOmitErrnoWithStrongSuccessProof()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] data = new byte[]{1, 2, 3, 4};
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"return_type\":1,\"uploadid\":\"ticket-without-errno\"}"));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("md5", md5(data, 0, data.length)).toString()));
            server.enqueue(new MockResponse().setBody(
                    new JSONObject().put("fs_id", 123L).put("size", data.length).toString()));
            api(server, Collections.emptyList()).upload(profile(), "/", "short.txt",
                    new ByteArrayInputStream(data), data.length);
            assertEquals(5, server.getRequestCount());
        }
    }

    @Test public void missingCodeAndHostFailsBeforeUploadWithoutFakeMinus999()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"message\":\"unexpected\"}"));
            IOException error = assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).upload(profile(), "/",
                            "tiny.txt", new ByteArrayInputStream(new byte[]{42}), 1));
            assertTrue(error.getMessage().contains("locateupload"));
            assertFalse(error.getMessage().contains("-999"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void serverExplicitMinus999IsNeverIgnored()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":-999,\"host\":\"c-jp.terabox.com\"}"));
            IOException error = assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).upload(profile(), "/",
                            "tiny.txt", new ByteArrayInputStream(new byte[]{42}), 1));
            assertTrue(error.getMessage().contains("-999"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void regularMutatingWriteStillRequiresErrno()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody("{\"host\":\"c-jp.terabox.com\"}"));
            IOException error = assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).mkdir(profile(), "/safe"));
            assertTrue(error.getMessage().contains("폴더 생성"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void copyResponseInfoWithoutIndividualErrnoMustNotFail() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("errno", 0)
                    .put("info", new JSONArray().put(new JSONObject()
                            .put("path", "/target/movie.txt")
                            .put("fs_id", 913L))).toString()));
            api(server, Collections.emptyList()).copyOrMove(profile(),
                    "/source/movie.txt", "/target", false);
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void asyncTaskSuccessListWithMetadataWithoutErrnoMustNotFail()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"taskid\":1893,\"info\":[]}"));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("errno", 0).put("status", "success")
                    .put("list", new JSONArray().put(new JSONObject()
                            .put("path", "/target/movie.txt")
                            .put("fs_id", 951L))).toString()));
            api(server, Collections.emptyList()).copyOrMove(profile(),
                    "/source/movie.txt", "/target", false);
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void explicitNestedInfoErrorStillStopsOverwrite() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"info\":[{\"errno\":-8,\"path\":\"/target/existing.txt\"}]}"));
            CloudProvider.CloudApiFailure fail = assertThrows(
                    CloudProvider.CloudApiFailure.class, () ->
                    api(server, Collections.emptyList()).copyOrMove(profile(),
                            "/source/existing.txt", "/target", false, true));
            assertTrue(fail.getMessage().contains("-8"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void asyncTaskNestedExplicitErrorMustFail() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody("{\"errno\":0,\"taskid\":4001}"));
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"status\":\"success\",\"list\":[{\"errno\":-9}]}"));
            assertThrows(CloudProvider.CloudApiFailure.class, () ->
                    api(server, Collections.emptyList()).copyOrMove(profile(),
                            "/source/absent.txt", "/target", false));
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void documentOverwriteHandlesMetadataInCopyAndCleanupReplies()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] content = "document change".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"host\":\"c-jp.terabox.com\"}"));
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"return_type\":1,\"uploadid\":\"save-edit\"}"));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("md5", md5(content, 0, content.length)).toString()));
            server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("errno", 0).put("size", content.length).toString()));
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"info\":[{\"path\":\"/OFFICE.md\",\"fs_id\":28}]}"));
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"info\":[{\"path\":\"/.nasmount-temp.upload\"}]}"));
            api(server, Collections.singletonList(file("OFFICE.md", "/")))
                    .upload(profile(), "/", "OFFICE.md",
                            new ByteArrayInputStream(content), content.length, true);
            request(server); request(server); request(server);
            request(server); request(server);
            RecordedRequest replaced = request(server);
            assertEquals("copy", replaced.getRequestUrl().queryParameter("opera"));
            RecordedRequest cleaned = request(server);
            assertEquals("delete", cleaned.getRequestUrl().queryParameter("opera"));
            assertEquals(7, server.getRequestCount());
        }
    }

    @Test public void nestedExplicitFailureFlagMustNotBeIgnoredWhenErrnoZero()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            expectSessionPage(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"errno\":0,\"info\":[{\"errno\":0,\"status\":\"failed\"}]}"));
            IOException error = assertThrows(IOException.class, () ->
                    api(server, Collections.emptyList()).copyOrMove(
                            profile(), "/source/file.txt", "/", false));
            assertTrue(error.getMessage().contains("개별 파일 오류"));
            assertEquals(2, server.getRequestCount());
        }
    }

    private static String md5(byte[] bytes, int off, int length) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(bytes, off, length);
        StringBuilder out = new StringBuilder();
        for (byte value : md.digest())
            out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static int find(byte[] all, byte[] part) {
        outer: for (int i = 0; i <= all.length - part.length; i++) {
            for (int j = 0; j < part.length; j++)
                if (all[i + j] != part[j]) continue outer;
            return i;
        }
        return -1;
    }
}
