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

    @Test public void remoteFolderDeleteTraversesChildBeforeParent()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(listing("/folder/", "sub/", "note.txt"));
            server.enqueue(listing("/folder/sub/", "deep.bin"));
            for (int i = 0; i < 4; i++)
                server.enqueue(new MockResponse().setResponseCode(204));
            Models.Entry folder = new Models.Entry("folder", "/folder", true);
            assertEquals(1, FileTransferEngine.delete(null,
                    Collections.singletonList(FileTransferEngine.remote(
                            profile(server), folder))));
            String[] expected = {"PROPFIND", "PROPFIND", "DELETE",
                    "DELETE", "DELETE", "DELETE"};
            for (String method : expected) {
                RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals(method, request.getMethod());
            }
            assertEquals(6, server.getRequestCount());
        }
    }
}
