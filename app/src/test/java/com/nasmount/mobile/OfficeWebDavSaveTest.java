package com.nasmount.mobile;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class OfficeWebDavSaveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] DATA = {1, 2, 3, 4};

    private Models.RemoteProfile profile(MockWebServer server) {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "WebDAV"; p.host = "127.0.0.1";
        p.port = server.getPort(); p.root = "/dav"; p.ssl = false;
        return p;
    }
    private File payload(byte[] data) throws IOException {
        File f = temp.newFile("edit.xlsx"); Files.write(f.toPath(), data); return f;
    }
    private MockResponse original() {
        return new MockResponse().setResponseCode(200).setHeader("ETag", "\"original\"");
    }
    private MockResponse bytes(byte[] data) {
        return new MockResponse().setResponseCode(200).setBody(new Buffer().write(data));
    }
    private RecordedRequest take(MockWebServer s, String method) throws Exception {
        RecordedRequest r = s.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(r); assertEquals(method, r.getMethod()); return r;
    }
    @Test public void emptyPayloadNeverContactsServer() throws Exception {
        try (MockWebServer s = new MockWebServer()) {
            s.start(); File f = payload(new byte[0]);
            assertThrows(IOException.class, () -> RemoteProvider.uploadOffice(profile(s), "/", "book.xlsx", f, "\"original\""));
            assertEquals(0, s.getRequestCount());
        }
    }
    @Test public void successfulPutWithEmptyStoredBodyNeverMovesOverOriginal() throws Exception {
        try (MockWebServer s = new MockWebServer()) {
            s.start(); s.enqueue(original()); s.enqueue(new MockResponse().setResponseCode(201));
            s.enqueue(bytes(new byte[0])); s.enqueue(new MockResponse().setResponseCode(204));
            File f = payload(DATA);
            assertThrows(IOException.class, () -> RemoteProvider.uploadOffice(profile(s), "/", "book.xlsx", f, "\"original\""));
            take(s, "HEAD"); RecordedRequest put = take(s, "PUT");
            assertArrayEquals(DATA, put.getBody().readByteArray());
            assertEquals(put.getPath(), take(s, "GET").getPath());
            assertEquals(put.getPath(), take(s, "DELETE").getPath());
            assertEquals(4, s.getRequestCount());
        }
    }
    @Test public void verifiedPayloadRequiresReadbackBeforeAndAfterMove() throws Exception {
        try (MockWebServer s = new MockWebServer()) {
            s.start(); s.enqueue(original()); s.enqueue(new MockResponse().setResponseCode(201));
            s.enqueue(bytes(DATA)); s.enqueue(original()); s.enqueue(new MockResponse().setResponseCode(204));
            s.enqueue(bytes(DATA));
            RemoteProvider.uploadOffice(profile(s), "/", "book.xlsx", payload(DATA), "\"original\"");
            take(s, "HEAD"); RecordedRequest put = take(s, "PUT");
            assertEquals(put.getPath(), take(s, "GET").getPath());
            take(s, "HEAD"); RecordedRequest move = take(s, "MOVE");
            assertEquals(put.getPath(), move.getPath());
            assertTrue(take(s, "GET").getPath().endsWith("/book.xlsx"));
            assertEquals(6, s.getRequestCount());
        }
    }
}
