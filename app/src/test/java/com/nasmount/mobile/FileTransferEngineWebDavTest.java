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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Actual RemoteProvider HTTP traffic against disposable localhost NAS fixtures. */
public class FileTransferEngineWebDavTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Models.RemoteProfile profile(MockWebServer server) {
        Models.RemoteProfile profile = new Models.RemoteProfile();
        profile.protocol = "WebDAV";
        profile.host = "127.0.0.1";
        profile.port = server.getPort();
        profile.root = "/dav";
        profile.ssl = false;
        return profile;
    }
    private static MockResponse listing(String folder, String... children) {
        StringBuilder xml = new StringBuilder("<d:multistatus xmlns:d='DAV:'>");
        xml.append("<d:response><d:href>/dav").append(folder)
                .append(folder.endsWith("/") ? "" : "/")
                .append("</d:href><d:propstat><d:prop><d:resourcetype>")
                .append("<d:collection/></d:resourcetype></d:prop>")
                .append("</d:propstat></d:response>");
        for (String child : children) {
            boolean dir = child.endsWith("/");
            xml.append("<d:response><d:href>/dav")
                    .append(folder).append(folder.endsWith("/") ? "" : "/")
                    .append(child).append("</d:href><d:propstat><d:prop>")
                    .append(dir ? "<d:resourcetype><d:collection/></d:resourcetype>" :
                            "<d:getcontentlength>4</d:getcontentlength>")
                    .append("</d:prop></d:propstat></d:response>");
        }
        xml.append("</d:multistatus>");
        return new MockResponse().setResponseCode(207).setBody(xml.toString())
                .addHeader("Content-Type", "application/xml");
    }

    @Test public void copiesNetworkFileBetweenIndependentWebDavAccounts()
            throws Exception {
        try (MockWebServer from = new MockWebServer();
             MockWebServer to = new MockWebServer()) {
            from.start(); to.start();
            byte[] payload = new byte[200_000];
            new java.util.Random(117).nextBytes(payload);
            from.enqueue(new MockResponse().setResponseCode(200)
                    .setBody(new Buffer().write(payload)));
            to.enqueue(listing("/destination/"));
            to.enqueue(new MockResponse().setResponseCode(201));
            to.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile a = profile(from);
            Models.RemoteProfile b = profile(to);
            Models.Entry item = new Models.Entry("영상.mkv", "/origin/영상.mkv", false);
            assertEquals(1, FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(a, item)),
                    FileTransferEngine.remoteFolder(b, "/destination"), false));
            RecordedRequest sourceRequest = from.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sourceRequest);
            assertEquals("GET", sourceRequest.getMethod());
            RecordedRequest list = to.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest put = to.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest commit = to.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(list); assertNotNull(put); assertNotNull(commit);
            assertEquals("PROPFIND", list.getMethod());
            assertEquals("PUT", put.getMethod());
            assertArrayEquals(payload, put.getBody().readByteArray());
            assertEquals("MOVE", commit.getMethod());
            assertEquals("F", commit.getHeader("Overwrite"));
            assertTrue(commit.getHeader("Destination").contains("destination"));
        }
    }

    @Test public void destinationCollisionNeverDownloadsOrOverwritesSource()
            throws Exception {
        try (MockWebServer from = new MockWebServer();
             MockWebServer to = new MockWebServer()) {
            from.start(); to.start();
            to.enqueue(listing("/destination/", "video.mp4"));
            Models.Entry item = new Models.Entry("video.mp4", "/src/video.mp4", false);
            assertThrows(IOException.class, () -> FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(profile(from), item)),
                    FileTransferEngine.remoteFolder(profile(to), "/destination"), false));
            assertEquals(0, from.getRequestCount());
            assertEquals(1, to.getRequestCount());
        }
    }

    @Test public void sameAccountMoveUsesSingleServerSideRename()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/destination/"));
            server.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile account = profile(server);
            Models.Entry item = new Models.Entry(
                    "movie.mkv", "/source/movie.mkv", false);
            assertEquals(1, FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(account, item)),
                    FileTransferEngine.remoteFolder(account, "/destination"), true));
            RecordedRequest list = server.takeRequest(2, TimeUnit.SECONDS);
            RecordedRequest moved = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("PROPFIND", list.getMethod());
            assertEquals("MOVE", moved.getMethod());
            assertTrue(moved.getHeader("Destination").endsWith("/destination/movie.mkv"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void remoteFolderTreeCopiesIntoLocalStorage()
            throws Exception {
        try (MockWebServer source = new MockWebServer()) {
            source.start();
            source.enqueue(listing("/folder/", "sub/", "note.txt"));
            source.enqueue(listing("/folder/sub/", "deep.bin"));
            source.enqueue(new MockResponse().setResponseCode(200)
                    .setBody(new Buffer().write(new byte[]{10,20,30,40})));
            source.enqueue(new MockResponse().setResponseCode(200)
                    .setBody(new Buffer().write(new byte[]{1,2,3,4})));
            File destination = temp.newFolder("destination");
            Models.Entry folder = new Models.Entry("folder", "/folder", true);
            assertEquals(1, FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(
                            profile(source), folder)),
                    FileTransferEngine.localFolder(destination), false));
            assertArrayEquals(new byte[]{10,20,30,40},
                    Files.readAllBytes(new File(destination,
                            "folder/sub/deep.bin").toPath()));
            assertArrayEquals(new byte[]{1,2,3,4},
                    Files.readAllBytes(new File(destination,
                            "folder/note.txt").toPath()));
            assertEquals(4, source.getRequestCount());
        }
    }

    @Test public void remoteWebDavCollectionDeleteIsOneServerAcknowledgedRequest()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(204));
            Models.Entry folder = new Models.Entry("folder", "/folder", true);
            assertEquals(1, FileTransferEngine.delete(null,
                    Collections.singletonList(FileTransferEngine.remote(
                            profile(server), folder))));
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("DELETE", request.getMethod());
            assertEquals("/dav/folder/", request.getPath());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void multiStatusFolderDeleteCannotBeReportedAsSuccess()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(207)
                    .setBody("<d:multistatus xmlns:d='DAV:'/>"));
            Models.Entry folder = new Models.Entry("folder", "/folder", true);
            FileTransferEngine.BatchFailure failure = assertThrows(
                    FileTransferEngine.BatchFailure.class,
                    () -> FileTransferEngine.delete(null,
                            Collections.singletonList(FileTransferEngine.remote(
                                    profile(server), folder))));
            assertEquals(0, failure.completed);
            assertTrue(failure.getCause().getMessage().contains("207"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void sameAccountCopyUsesServerCopyWithoutDownloading()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/destination/"));
            server.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile account = profile(server);
            Models.Entry file = new Models.Entry(
                    "image.jpg", "/source/image.jpg", false);
            file.size = 4096;
            assertEquals(1, FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(account, file)),
                    FileTransferEngine.remoteFolder(account, "/destination"), false));
            assertEquals("PROPFIND",
                    server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            RecordedRequest copied = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(copied);
            assertEquals("COPY", copied.getMethod());
            assertEquals("F", copied.getHeader("Overwrite"));
            assertTrue(copied.getHeader("Destination")
                    .endsWith("/destination/image.jpg"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void sameAccountFolderCopiesRecursivelyOnServerWithOneCopy()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/destination/"));
            server.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile account = profile(server);
            Models.Entry folder = new Models.Entry("photos", "/source/photos", true);
            assertEquals(1, FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(account, folder)),
                    FileTransferEngine.remoteFolder(account, "/destination"), false));
            assertEquals("PROPFIND",
                    server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            RecordedRequest copy = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(copy);
            assertEquals("COPY", copy.getMethod());
            assertEquals("infinity", copy.getHeader("Depth"));
            assertEquals("F", copy.getHeader("Overwrite"));
            assertEquals("/dav/source/photos/", copy.getPath());
            assertTrue(copy.getHeader("Destination")
                    .endsWith("/destination/photos/"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void folderCopyFallsBackWhenServerDoesNotSupportCopy()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/destination/"));
            server.enqueue(new MockResponse().setResponseCode(405));
            server.enqueue(new MockResponse().setResponseCode(201)); // MKCOL
            server.enqueue(listing("/source/photos/", "one.txt"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("DATA"));
            server.enqueue(new MockResponse().setResponseCode(201)); // PUT
            server.enqueue(new MockResponse().setResponseCode(201)); // MOVE
            Models.RemoteProfile account = profile(server);
            Models.Entry folder = new Models.Entry("photos", "/source/photos", true);
            assertEquals(1, FileTransferEngine.transfer(null,
                    Collections.singletonList(FileTransferEngine.remote(account, folder)),
                    FileTransferEngine.remoteFolder(account, "/destination"), false));
            String[] expected = {"PROPFIND", "COPY", "MKCOL", "PROPFIND",
                    "GET", "PUT", "MOVE"};
            for (String method : expected)
                assertEquals(method,
                        server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals(7, server.getRequestCount());
        }
    }

    @Test public void unsupportedServerCopyFallsBackOnceForWholeBatch()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/destination/"));
            server.enqueue(new MockResponse().setResponseCode(405));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("AAAA"));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("BBBB"));
            server.enqueue(new MockResponse().setResponseCode(201));
            server.enqueue(new MockResponse().setResponseCode(201));
            Models.RemoteProfile account = profile(server);
            Models.Entry first = new Models.Entry("first.txt", "/source/first.txt", false);
            Models.Entry second = new Models.Entry("second.txt", "/source/second.txt", false);
            first.size = 4;
            second.size = 4;
            assertEquals(2, FileTransferEngine.transfer(null,
                    Arrays.asList(FileTransferEngine.remote(account, first),
                            FileTransferEngine.remote(account, second)),
                    FileTransferEngine.remoteFolder(account, "/destination"), false));
            String[] expected = {"PROPFIND", "COPY", "GET", "PUT", "MOVE",
                    "GET", "PUT", "MOVE"};
            for (String method : expected)
                assertEquals(method,
                        server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            assertEquals(8, server.getRequestCount());
        }
    }

    @Test public void partialServerCopyNeverFallsBackOrReportsCompletion()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/destination/"));
            server.enqueue(new MockResponse().setResponseCode(207));
            Models.RemoteProfile account = profile(server);
            Models.Entry file = new Models.Entry("file.txt", "/source/file.txt", false);
            FileTransferEngine.BatchFailure failure = assertThrows(
                    FileTransferEngine.BatchFailure.class,
                    () -> FileTransferEngine.transfer(null,
                            Collections.singletonList(FileTransferEngine.remote(account, file)),
                            FileTransferEngine.remoteFolder(account, "/destination"), false));
            assertEquals(0, failure.completed);
            assertTrue(failure.getCause().getMessage().contains("207"));
            assertEquals(2, server.getRequestCount());
        }
    }
}
