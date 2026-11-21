package com.nasmount.mobile;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class WebDavQuotaAndWriteGuardTest {
    private static Models.RemoteProfile profile(MockWebServer server) {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "WebDAV";
        p.ssl = false;
        p.host = "127.0.0.1";
        p.port = server.getPort();
        p.root = "/dav";
        return p;
    }

    @Test public void rfc4331OnlyAcceptsSuccessfulProperties() throws Exception {
        String sample = "<d:multistatus xmlns:d='DAV:'><d:response><d:propstat>" +
                "<d:prop><d:quota-used-bytes>68000000000</d:quota-used-bytes>" +
                "<d:quota-available-bytes>4932000000000</d:quota-available-bytes>" +
                "</d:prop><d:status>HTTP/1.1 200 OK</d:status>" +
                "</d:propstat></d:response></d:multistatus>";
        WebDavQuota q = WebDavQuota.parse(sample);
        assertNotNull(q);
        assertEquals(5000000000000L, q.total);
        assertEquals(4932000000000L, q.free);
        assertNull(WebDavQuota.parse(sample.replace("200 OK", "404 Not Found")));
        assertNull(WebDavQuota.parse(sample.replace("4932000000000", "-1")));
        assertThrows(Exception.class, () ->
                WebDavQuota.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/hosts'>]>" +
                        "<d:multistatus xmlns:d='DAV;'>&x;</d:multistatus>"));
    }

    @Test public void serverQuotaUsesRemotePropsNotLocalDisk() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207).setBody(
                    "<d:multistatus xmlns:d='DAV:'><d:response><d:propstat>" +
                    "<d:status>HTTP/1.1 200 OK</d:status><d:prop>" +
                    "<d:quota-used-bytes>10</d:quota-used-bytes>" +
                    "<d:quota-available-bytes>20</d:quota-available-bytes>" +
                    "</d:prop></d:propstat></d:response></d:multistatus>"));
            WebDavQuota q = RemoteProvider.webdavQuota(profile(server));
            assertEquals(30, q.total);
            assertEquals(20, q.free);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("PROPFIND", request.getMethod());
            assertEquals("0", request.getHeader("Depth"));
            assertTrue(request.getPath().startsWith("/dav/"));
        }
    }

    @Test public void existingFileChangedByAnotherClientIsNeverOverwritten()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v2\""));
            server.enqueue(new MockResponse().setResponseCode(204));
            assertThrows(IOException.class, () -> RemoteProvider.upload(
                    profile(server), "/", "document.xlsx",
                    new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, true));
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals("PUT", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals("DELETE", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void unchangedExistingFileUsesDestinationTaggedRfc4918Condition()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(201));
            RemoteProvider.upload(profile(server), "/", "document.xlsx",
                    new ByteArrayInputStream(new byte[]{9}), 1, true);
            server.takeRequest(2, TimeUnit.SECONDS);
            server.takeRequest(2, TimeUnit.SECONDS);
            server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("MOVE", move.getMethod());
            assertEquals("T", move.getHeader("Overwrite"));
            assertTrue(move.getHeader("If").contains("([\"v1\"])"));
        }
    }

    @Test public void readOnlyBlocksWritesBeforeAnyRemoteRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            Models.RemoteProfile p = profile(server);
            p.readOnly = true;
            assertThrows(IOException.class, () -> RemoteProvider.upload(
                    p, "/", "new.txt", new ByteArrayInputStream(new byte[]{1}),
                    1, false));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void serverSideLockProtectsDestinationAndAlwaysUnlocks()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("Lock-Token", "<opaquelocktoken:my-lease>"));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(204));
            server.enqueue(new MockResponse().setResponseCode(204));
            Models.RemoteProfile p = profile(server);
            p.useNativeWebDavLocks = true;
            RemoteProvider.upload(p, "/", "doc.xlsx",
                    new ByteArrayInputStream(new byte[]{3}), 1, true);
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            RecordedRequest lock = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("LOCK", lock.getMethod());
            assertEquals("0", lock.getHeader("Depth"));
            assertTrue(lock.getBody().readUtf8().contains("<d:exclusive/>"));
            assertEquals("PUT", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals("HEAD", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("MOVE", move.getMethod());
            assertTrue(move.getHeader("If").contains("<opaquelocktoken:my-lease>"));
            assertTrue(move.getHeader("If").contains("[\"v1\"]"));
            RecordedRequest unlock = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("UNLOCK", unlock.getMethod());
            assertEquals("<opaquelocktoken:my-lease>",
                    unlock.getHeader("Lock-Token"));
            assertEquals(6, server.getRequestCount());
        }
    }

    @Test public void unsupportedNativeLockFallsBackToStrongEtagOnly()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(405));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile p = profile(server);
            p.useNativeWebDavLocks = true;
            RemoteProvider.upload(p, "/", "doc.xlsx",
                    new ByteArrayInputStream(new byte[]{1}), 1, true);
            server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("LOCK", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            server.takeRequest(2, TimeUnit.SECONDS);
            server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("MOVE", move.getMethod());
            assertTrue(move.getHeader("If").contains("[\"v1\"]"));
            assertFalse(move.getHeader("If").contains("opaquelocktoken"));
            assertEquals(5, server.getRequestCount());
        }
    }

    @Test public void missingEtagCanSaveOnlyWithRealNativeLock() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("Lock-Token", "<urn:uuid:my-lease>"));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(204));
            server.enqueue(new MockResponse().setResponseCode(204));
            Models.RemoteProfile p = profile(server);
            p.useNativeWebDavLocks = true;
            RemoteProvider.upload(p, "/", "doc.xlsx",
                    new ByteArrayInputStream(new byte[]{3}), 1, true);
            for (int i = 0; i < 4; i++)
                server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            assertTrue(move.getHeader("If").contains("<urn:uuid:my-lease>"));
            assertFalse(move.getHeader("If").contains("["));
            assertEquals("UNLOCK", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
        }
    }

    @Test public void noEtagAndUnsupportedLockNeverDestroysRemote() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200));
            server.enqueue(new MockResponse().setResponseCode(501));
            Models.RemoteProfile p = profile(server);
            p.useNativeWebDavLocks = true;
            assertThrows(IOException.class, () -> RemoteProvider.upload(p, "/",
                    "doc.xlsx", new ByteArrayInputStream(new byte[]{9}), 1, true));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void anotherUsersLockBlocksUploadWithoutUnsafeFallback()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .addHeader("ETag", "\"v1\""));
            server.enqueue(new MockResponse().setResponseCode(423));
            Models.RemoteProfile p = profile(server);
            p.useNativeWebDavLocks = true;
            assertThrows(IOException.class, () -> RemoteProvider.upload(p, "/",
                    "doc.xlsx", new ByteArrayInputStream(new byte[]{9}), 1, true));
            assertEquals(2, server.getRequestCount());
        }
    }
}
