package com.nasmount.mobile;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Cross-device Excel owner protocol, without NAS accounts or credentials. */
public class WebDavExcelLeaseTest {
    private static Models.RemoteProfile profile(MockWebServer server) {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "WebDAV";
        p.host = "127.0.0.1";
        p.port = server.getPort();
        p.root = "/dav";
        p.ssl = false;
        return p;
    }

    private static MockResponse head(String etag) {
        MockResponse response = new MockResponse().setResponseCode(200);
        return etag == null ? response : response.addHeader("ETag", etag);
    }
    private static MockResponse missing() {
        return new MockResponse().setResponseCode(404);
    }
    private static void initialAvailable(MockWebServer server) {
        server.enqueue(head("\"doc-1\""));   // workbook
        server.enqueue(missing());           // ~$ full name
        server.enqueue(missing());           // ~$ replacement name
        server.enqueue(new MockResponse().setResponseCode(201)); // conditional PUT
        server.enqueue(head("\"owner-1\"")); // newly created owner
        server.enqueue(head("\"owner-1\"")); // verify owner
        server.enqueue(missing());           // alternate owner absent
        server.enqueue(head("\"doc-1\""));   // unchanged original
    }
    private static RecordedRequest take(MockWebServer server) throws Exception {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("Expected WebDAV request", request);
        return request;
    }

    @Test public void ownerNamingSupportsKoreanAndBothExcelConventions() {
        assertArrayEquals(new String[] {"/~$11월매출.xlsx", "/~$월매출.xlsx"},
                WebDavExcelLease.candidateOwners("/11월매출.xlsx"));
        assertArrayEquals(new String[] {"/2026/~$report.xlsx",
                        "/2026/~$port.xlsx"},
                WebDavExcelLease.candidateOwners("/2026/report.xlsx"));
        byte[] bytes = WebDavExcelLease.ownerBytes();
        assertEquals(165, bytes.length);
        assertEquals("FileHub Android",
                new String(bytes, 2, 15, StandardCharsets.US_ASCII));
        assertEquals(15, bytes[1]);
        assertEquals(15, bytes[59]);
    }

    @Test public void otherPcOwnerMakesAndroidReadOnlyWithoutNewOwner()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(head("\"doc-1\""));
            server.enqueue(head("\"rai-owner\""));
            WebDavExcelLease opened = WebDavExcelLease.acquire(profile(server),
                    "/11월매출.xlsx");
            assertTrue(opened.readOnly());
            assertTrue(opened.reason().contains("읽기 전용"));
            assertThrows(IOException.class, opened::verifyBeforeSave);
            opened.release();
            assertEquals("HEAD", take(server).getMethod());
            assertEquals("HEAD", take(server).getMethod());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void alternateExcelOwnerAlsoBlocksEdit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(head("\"doc-1\""));
            server.enqueue(missing());
            server.enqueue(head("\"other\""));
            WebDavExcelLease opened = WebDavExcelLease.acquire(profile(server),
                    "/11월매출.xlsx");
            assertTrue(opened.readOnly());
            opened.release();
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void exclusiveOwnerIsVisibleSavedAndDeletedConditionally()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            initialAvailable(server);
            server.enqueue(head("\"owner-1\"")); // second save verification
            server.enqueue(missing());
            server.enqueue(head("\"doc-1\""));
            server.enqueue(head("\"doc-2\""));   // after first completed save
            server.enqueue(head("\"owner-1\"")); // cleanup
            server.enqueue(new MockResponse().setResponseCode(204));

            WebDavExcelLease edit = WebDavExcelLease.acquire(profile(server),
                    "/11월매출.xlsx");
            assertFalse(edit.readOnly());
            assertEquals("\"doc-1\"", edit.originalEtag());
            edit.verifyBeforeSave();
            edit.saved();
            assertEquals("\"doc-2\"", edit.originalEtag());
            edit.release();
            edit.release(); // idempotent

            assertEquals("HEAD", take(server).getMethod());
            assertEquals("HEAD", take(server).getMethod());
            assertEquals("HEAD", take(server).getMethod());
            RecordedRequest creation = take(server);
            assertEquals("PUT", creation.getMethod());
            assertEquals("*", creation.getHeader("If-None-Match"));
            assertTrue(creation.getPath().contains("~$11"));
            assertArrayEquals(WebDavExcelLease.ownerBytes(),
                    creation.getBody().readByteArray());
            for (int i = 0; i < 9; i++)
                assertEquals("HEAD", take(server).getMethod());
            RecordedRequest deletion = take(server);
            assertEquals("DELETE", deletion.getMethod());
            assertEquals("\"owner-1\"", deletion.getHeader("If-Match"));
            assertEquals(14, server.getRequestCount());
        }
    }

    @Test public void modifiedOwnerIsNeverDeleted() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            initialAvailable(server);
            server.enqueue(head("\"somebody-else\""));
            WebDavExcelLease edit = WebDavExcelLease.acquire(profile(server),
                    "/11월매출.xlsx");
            edit.release();
            assertEquals(9, server.getRequestCount());
            for (int i = 0; i < 9; i++)
                assertEquals(i == 3 ? "PUT" : "HEAD", take(server).getMethod());
        }
    }

    @Test public void changedWorkbookIsNeverOverwrittenFromStagedCopy()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            initialAvailable(server);
            server.enqueue(head("\"owner-1\""));
            server.enqueue(missing());
            server.enqueue(head("\"doc-CHANGED\""));
            server.enqueue(head("\"owner-1\""));
            server.enqueue(new MockResponse().setResponseCode(204));
            WebDavExcelLease edit = WebDavExcelLease.acquire(profile(server),
                    "/11월매출.xlsx");
            assertThrows(IOException.class, edit::verifyBeforeSave);
            edit.release();
            assertEquals(13, server.getRequestCount());
        }
    }

    @Test public void missingEtagNeverStartsWritableEditor() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(head(null)); // older NAS cannot supply ETag
            server.enqueue(missing());
            server.enqueue(missing());
            WebDavExcelLease edit = WebDavExcelLease.acquire(profile(server),
                    "/11월매출.xlsx");
            assertTrue(edit.readOnly());
            edit.release();
            assertEquals(3, server.getRequestCount()); // no PUT or DELETE
        }
    }

    @Test public void staleExpectedEtagFailsBeforeUploadingAnyBytes()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(head("\"doc-CHANGED\""));
            byte[] replacement = "safe".getBytes(StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> RemoteProvider.upload(
                    profile(server), "/", "11월매출.xlsx",
                    new ByteArrayInputStream(replacement), replacement.length,
                    true, "\"doc-1\""));
            RecordedRequest req = take(server);
            assertEquals("HEAD", req.getMethod());
            assertEquals(1, server.getRequestCount()); // no temp PUT or MOVE
        }
    }
}
