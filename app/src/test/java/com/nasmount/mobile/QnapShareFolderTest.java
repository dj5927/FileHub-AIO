package com.nasmount.mobile;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class QnapShareFolderTest {
    @Test public void qnapArrayShareRootResponseIsParsed() throws Exception {
        String json = "[{\"id\":\"VPublic\",\"text\":\"Public\"}," +
                "{\"id\":\"VMultimedia\",\"text\":\"Multimedia\"}," +
                "{\"id\":\"VDownload\",\"text\":\"Download\"}]";
        List<NasShareApi.NasEntry> shares = NasShareApi.parseQnapShares(json);
        assertEquals(3, shares.size());
        assertEquals("/Public", shares.get(0).path);
        assertEquals("/Multimedia", shares.get(1).path);
        assertEquals("/Download", shares.get(2).path);
    }

    @Test public void qnapShareRootErrorsAreNotSilent() {
        assertThrows(Exception.class,
                () -> NasShareApi.parseQnapShares("{\"status\":0}"));
    }

    @Test public void qnapFolderListingCreatesAbsolutePaths() throws Exception {
        String json = "{\"status\":1,\"datas\":[" +
                "{\"filename\":\"Movies\",\"isfolder\":1}," +
                "{\"filename\":\"video.mp4\",\"isfolder\":0}]}";
        List<NasShareApi.NasEntry> entries = NasShareApi.parseQnapFolder(json, "/Multimedia");
        assertEquals(2, entries.size());
        assertEquals("/Multimedia/Movies", entries.get(0).path);
        assertTrue(entries.get(0).directory);
        assertEquals("/Multimedia/video.mp4", entries.get(1).path);
        assertFalse(entries.get(1).directory);
    }

    @Test public void qnapMultipleRootsFollowSamePermissionRules() {
        Models.RemoteProfile qnap = new Models.RemoteProfile();
        qnap.nasVendor = "QNAP";
        qnap.nasApiBase = "https://qnap.example.com:443";
        qnap.nasShareRoots.add("/Public");
        qnap.nasShareRoots.add("/Multimedia");
        assertTrue(NasSharePolicy.supported(qnap));
        assertTrue(NasSharePolicy.inAllowedRoot(qnap, "/Public/readme.txt"));
        assertTrue(NasSharePolicy.inAllowedRoot(qnap, "/Multimedia/video.mp4"));
        assertFalse(NasSharePolicy.inAllowedRoot(qnap, "/homes/private.txt"));
    }
}
