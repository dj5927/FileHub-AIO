package com.nasmount.mobile;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Offline loopback WebDAV tests. No private NAS, files or credentials. */
public final class WebDavProtocolTest {
    private Models.RemoteProfile local(int port) {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "WebDAV";
        p.host = "127.0.0.1";
        p.port = port;
        p.ssl = false;
        p.root = "/dav";
        return p;
    }
    @Test public void browseAndDecodeUnicodeNames() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            String xml = "<?xml version='1.0' encoding='UTF-8'?>" +
                    "<d:multistatus xmlns:d='DAV:'>" +
                    "<d:response><d:href>/dav/</d:href><d:propstat><d:prop>" +
                    "<d:resourcetype><d:collection/></d:resourcetype>" +
                    "</d:prop></d:propstat></d:response>" +
                    "<d:response><d:href>/dav/%ED%95%9C%EA%B8%80%20%ED%8F%B4%EB%8D%94/</d:href>" +
                    "<d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>" +
                    "</d:prop></d:propstat></d:response>" +
                    "<d:response><d:href>/dav/Report.xlsx</d:href>" +
                    "<d:propstat><d:prop><d:getcontentlength>2048</d:getcontentlength>" +
                    "</d:prop></d:propstat></d:response></d:multistatus>";
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody(xml).addHeader("Content-Type", "application/xml"));
            List<Models.Entry> files = RemoteProvider.list(local(server.getPort()), "/");
            assertEquals(2, files.size());
            assertEquals("한글 폴더", files.get(0).name);
            assertTrue(files.get(0).directory);
            assertEquals("/한글 폴더", files.get(0).path);
            assertEquals("Report.xlsx", files.get(1).name);
            assertEquals(2048, files.get(1).size);
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("PROPFIND", request.getMethod());
            assertEquals("1", request.getHeader("Depth"));
            assertEquals("/dav/", request.getPath());
        }
    }
    @Test public void webDavModificationDateAppearsOnFileEntry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'>" +
                            "<d:response><d:href>/dav/</d:href></d:response>" +
                            "<d:response><d:href>/dav/video.mkv</d:href>" +
                            "<d:propstat><d:prop>" +
                            "<d:getcontentlength>654321</d:getcontentlength>" +
                            "<d:getlastmodified>Thu, 12 Nov 2026 03:05:07 GMT</d:getlastmodified>" +
                            "<d:creationdate>2024-07-20T11:00:00Z</d:creationdate>" +
                            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                            "</d:response></d:multistatus>"));
            List<Models.Entry> result = RemoteProvider.list(local(server.getPort()), "/");
            assertEquals(1, result.size());
            assertEquals(654321L, result.get(0).size);
            assertEquals(java.time.Instant.parse("2026-11-12T03:05:07Z")
                    .toEpochMilli(), result.get(0).modified);
        }
    }

    @Test public void webDavCreationDateIsFallbackOnlyIfNoModification() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'>" +
                            "<d:response><d:href>/dav/</d:href></d:response>" +
                            "<d:response><d:href>/dav/new-file.zip</d:href>" +
                            "<d:propstat><d:prop>" +
                            "<d:creationdate>2025-06-20T12:01:03Z</d:creationdate>" +
                            "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>" +
                            "</d:response></d:multistatus>"));
            List<Models.Entry> result = RemoteProvider.list(local(server.getPort()), "/");
            assertEquals(1, result.size());
            assertEquals(java.time.Instant.parse("2025-06-20T12:01:03Z")
                    .toEpochMilli(), result.get(0).modified);
        }
    }

    @Test public void webDavRejectsFailedPropstatDateAndNeverInventsOne() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'>" +
                            "<d:response><d:href>/dav/</d:href></d:response>" +
                            "<d:response><d:href>/dav/missing-date.mp4</d:href>" +
                            "<d:propstat><d:prop>" +
                            "<d:getlastmodified>Thu, 12 Nov 2026 03:05:07 GMT</d:getlastmodified>" +
                            "</d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>" +
                            "</d:response></d:multistatus>"));
            List<Models.Entry> result = RemoteProvider.list(local(server.getPort()), "/");
            assertEquals(1, result.size());
            assertEquals(0, result.get(0).modified);
        }
    }

    @Test public void standardDefaultNamespaceAndRelativeHrefs() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<?xml version='1.0'?>" +
                    "<multistatus xmlns='DAV:'>" +
                    "<response><href>/dav/</href><propstat><status>HTTP/1.1 200 OK</status></propstat></response>" +
                    "<response><href>folder%20%26%20space/</href><propstat><prop><resourcetype><collection/></resourcetype></prop><status>HTTP/1.1 200 OK</status></propstat></response>" +
                    "<response><href>file%2Bname.txt</href><propstat><prop><getcontentlength>12</getcontentlength></prop><status>HTTP/1.1 200 OK</status></propstat></response>" +
                    "</multistatus>"));
            List<Models.Entry> result = RemoteProvider.list(local(server.getPort()), "/");
            assertEquals(2, result.size());
            assertEquals("folder & space", result.get(0).name);
            assertTrue(result.get(0).directory);
            assertEquals("file+name.txt", result.get(1).name);
            assertEquals(12L, result.get(1).size);
        }
    }

    @Test public void rejectsXmlDocTypeWithoutExternalFetch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<?xml version='1.0'?><!DOCTYPE a [<!ENTITY evil SYSTEM 'file:///secret'>]>" +
                            "<d:multistatus xmlns:d='DAV:'><d:response>" +
                            "<d:href>&evil;</d:href></d:response></d:multistatus>"));
            try {
                RemoteProvider.list(local(server.getPort()), "/");
                fail("External entities must never be processed");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("DTD") ||
                        expected.getMessage().contains("개체"));
            }
        }
    }

    @Test public void informativeUnauthorizedStatus() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(401));
            try {
                RemoteProvider.list(local(server.getPort()), "/");
                fail("Should signal unauthorized");
            } catch (RemoteProvider.WebDavHttpFailure expected) {
                assertEquals(401, expected.status);
            }
        }
    }

    @Test public void uploadUsesTemporaryNameThenAtomicMove() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile p = local(server.getPort());
            RemoteProvider.upload(p, "/", "파일.txt",
                    new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
            RecordedRequest put = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(put);
            assertNotNull(move);
            assertEquals("PUT", put.getMethod());
            assertTrue(put.getPath().contains(".nasmount-"));
            assertEquals("content", put.getBody().readUtf8());
            assertEquals("MOVE", move.getMethod());
            assertEquals("F", move.getHeader("Overwrite"));
            assertTrue(move.getHeader("Destination").contains("%ED%8C%8C%EC%9D%BC.txt"));
        }
    }

    @Test public void createEmptyFileUsesPutThenMoveWithoutOverwriting() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(201));
            RemoteProvider.upload(local(server.getPort()), "/", "새 문서.txt",
                    new ByteArrayInputStream(new byte[0]));
            RecordedRequest put = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(put);
            assertNotNull(move);
            assertEquals("PUT", put.getMethod());
            assertEquals(0L, put.getBodySize());
            assertEquals("MOVE", move.getMethod());
            assertEquals("F", move.getHeader("Overwrite"));
            assertTrue(move.getHeader("Destination").contains(".txt"));
        }
    }

    @Test public void finalMoveMultiStatusNeverReportsUploadedFileComplete()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(201)); // PUT
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'/>")); // partial MOVE
            server.enqueue(new MockResponse().setResponseCode(204)); // cleanup
            java.io.IOException failure = assertThrows(
                    java.io.IOException.class, () -> RemoteProvider.upload(
                            local(server.getPort()), "/", "incomplete.txt",
                            new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))));
            assertTrue(failure.getMessage().contains("MOVE HTTP 207"));
            RecordedRequest put = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest move = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest cleanup = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("PUT", put.getMethod());
            assertEquals("MOVE", move.getMethod());
            assertEquals("DELETE", cleanup.getMethod());
            assertEquals(put.getPath(), cleanup.getPath());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void renameMultiStatusNeverReportsSuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'/>"));
            java.io.IOException error = assertThrows(java.io.IOException.class,
                    () -> RemoteProvider.rename(local(server.getPort()),
                            "/old.txt", "/new.txt", false));
            assertTrue(error.getMessage().contains("207"));
            assertEquals("MOVE",
                    server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void streamingSeekUsesHttpRangeAndDoesNotDownloadWholeFile()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 1024-1027/4096")
                    .setBody("ABCD"));
            try (InputStream stream = RemoteProvider.openStream(
                    local(server.getPort()), "/clip.mp4", 1024)) {
                byte[] result = new byte[4];
                assertEquals(4, stream.read(result));
                assertEquals("ABCD", new String(result, StandardCharsets.UTF_8));
            }
            RecordedRequest get = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(get);
            assertEquals("GET", get.getMethod());
            assertEquals("/dav/clip.mp4", get.getPath());
            assertEquals("bytes=1024-", get.getHeader("Range"));
        }
    }

    @Test public void streamingSeekRejectsServerIgnoringRange() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("entire video unexpectedly returned"));
            try {
                RemoteProvider.openStream(local(server.getPort()), "/movie.mkv", 1024);
                fail("Seeking must fail if the server ignores Range.");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("Range"));
            }
        }
    }

    @Test public void streamingSeekRejectsIncorrectContentRange() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/4096")
                    .setBody("BAD!"));
            try {
                RemoteProvider.openStream(local(server.getPort()), "/movie.mkv", 1024);
                fail("Must not decode data from an incorrect range.");
            } catch (java.io.IOException expected) {
                assertTrue(expected.getMessage().contains("구간"));
            }
        }
    }

    @Test public void nestedKoreanFolderUsesDecodedSegments() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<?xml version='1.0'?>" +
                    "<d:multistatus xmlns:d='DAV:'>" +
                    "<d:response><d:href>/dav/개인자료/</d:href><d:propstat>" +
                    "<d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                    "<d:response><d:href>/dav/개인자료/완료/</d:href><d:propstat>" +
                    "<d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                    "<d:response><d:href>/dav/%EA%B0%9C%EC%9D%B8%EC%9E%90%EB%A3%8C/report.xlsx</d:href>" +
                    "<d:propstat><d:prop><d:getcontentlength>77</d:getcontentlength></d:prop>" +
                    "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>" +
                    "</d:multistatus>"));
            List<Models.Entry> actual = RemoteProvider.list(
                    local(server.getPort()), "/개인자료");
            assertEquals(2, actual.size());
            assertEquals("완료", actual.get(0).name);
            assertTrue(actual.get(0).directory);
            assertEquals("/개인자료/완료", actual.get(0).path);
            assertEquals("report.xlsx", actual.get(1).name);
            assertEquals(77L, actual.get(1).size);
        }
    }

    @Test public void nestedEncodedFolderAndRelativeHref() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<?xml version='1.0'?>" +
                    "<d:multistatus xmlns:d='DAV:'>" +
                    "<d:response><d:href>/dav/%ED%95%9C%EA%B8%80/</d:href></d:response>" +
                    "<d:response><d:href>more%20files/</d:href><d:propstat>" +
                    "<d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>" +
                    "</d:propstat></d:response>" +
                    "<d:response><d:href>/dav/한글/file%2bname.txt</d:href>" +
                    "<d:propstat><d:prop><d:getcontentlength>3</d:getcontentlength></d:prop>" +
                    "</d:propstat></d:response></d:multistatus>"));
            List<Models.Entry> actual = RemoteProvider.list(
                    local(server.getPort()), "/한글");
            assertEquals(2, actual.size());
            assertEquals("more files", actual.get(0).name);
            assertEquals("file+name.txt", actual.get(1).name);
        }
    }

    @Test public void nestedFolderNeverIncludesSiblings() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'>" +
                    "<d:response><d:href>/dav/abc/</d:href></d:response>" +
                    "<d:response><d:href>/dav/abc2/private.txt</d:href></d:response>" +
                    "<d:response><d:href>/dav/abc/child.txt</d:href></d:response>" +
                    "</d:multistatus>"));
            List<Models.Entry> actual = RemoteProvider.list(
                    local(server.getPort()), "/abc");
            assertEquals(1, actual.size());
            assertEquals("child.txt", actual.get(0).name);
        }
    }
}
