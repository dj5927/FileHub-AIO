package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class NasSharePolicyTest {
    @Test public void onlyExplicitlyConfiguredNasCanShare() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.protocol = "WebDAV";
        p.host = "nas.example.com";
        assertFalse(NasSharePolicy.supported(p));
        p.nasVendor = "Synology";
        assertFalse(NasSharePolicy.supported(p));
        p.nasApiBase = "https://nas.example.com:5001";
        p.nasShareRoot = "/video";
        assertTrue(NasSharePolicy.supported(p));
        p.nasVendor = "TeraBox";
        assertFalse(NasSharePolicy.supported(p));
    }

    @Test public void rejectsUnsafeManagementEndpoints() {
        for (String url : new String[]{"http://nas.example.com:5000",
                "https://user:pass@nas.example.com", "https://nas.example.com/api",
                "https://nas.example.com?foo=bar", "https://nas.example.com/#secret"})
            assertThrows(IllegalArgumentException.class,
                    () -> NasSharePolicy.validateApiBase(url));
        assertEquals("https://nas.example.com:5001",
                NasSharePolicy.validateApiBase("https://nas.example.com:5001"));
    }

    @Test public void mapsNasFileStationPathWithoutTraversal() {
        assertEquals("/video/abc.mp4", NasSharePolicy.mapPath("/video", "/abc.mp4"));
        assertEquals("/Public/Movies/a.mkv", NasSharePolicy.mapPath("/Public", "/Movies/a.mkv"));
        assertThrows(IllegalArgumentException.class,
                () -> NasSharePolicy.mapPath("/video", "/../etc/passwd"));
    }

    @Test public void indefiniteAndExpiredDates() {
        assertEquals("0", NasSharePolicy.expiryDate(0, ""));
        assertThrows(IllegalArgumentException.class,
                () -> NasSharePolicy.expiryDate(-1, "2020-01-01"));
    }

    @Test public void multipleSelectedSharesAreAuthorizedButOthersBlocked() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.nasVendor = "Synology";
        p.nasApiBase = "https://nas.example.com:5001";
        p.nasShareRoots.add("/01.개인자료");
        p.nasShareRoots.add("/02.게임자료");
        p.nasShareRoots.add("/03.영상자료");
        p.nasShareRoots.add("/04.유틸자료");
        assertTrue(NasSharePolicy.supported(p));
        for (String folder : p.nasShareRoots) {
            assertTrue(NasSharePolicy.inAllowedRoot(p, folder));
            assertTrue(NasSharePolicy.inAllowedRoot(p, folder + "/sub/movie.mp4"));
        }
        assertFalse(NasSharePolicy.inAllowedRoot(p, "/00.회사자료/a.docx"));
        assertFalse(NasSharePolicy.inAllowedRoot(p, "/01.개인자료2/file.mp4"));
    }

    @Test public void existingSmbPathMapsToCorrectShareNotFirstSelectedShare() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.nasVendor = "Synology";
        p.root = "02.게임자료";
        p.nasShareRoots.add("/01.개인자료");
        p.nasShareRoots.add("/02.게임자료");
        p.nasShareRoots.add("/03.영상자료");
        assertEquals("/02.게임자료/abc.zip", NasSharePolicy.mapPath(p, "/abc.zip"));
        p.root = "random-unrelated-share";
        assertThrows(IllegalArgumentException.class,
                () -> NasSharePolicy.mapPath(p, "/abc.zip"));
    }

    @Test public void legacySingleShareStillWorks() {
        Models.RemoteProfile p = new Models.RemoteProfile();
        p.nasShareRoot = "/video";
        assertEquals(1, NasSharePolicy.roots(p).size());
        assertEquals("/video/f.mp4", NasSharePolicy.mapPath(p, "/f.mp4"));
    }
}
