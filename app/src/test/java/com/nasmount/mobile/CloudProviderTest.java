package com.nasmount.mobile;

import org.json.JSONArray;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class CloudProviderTest {
    @Test public void myboxPatValidation() {
        assertTrue(CloudProvider.validMyBoxToken("mbx_pat_12345678901234567890"));
        assertFalse(CloudProvider.validMyBoxToken("not_a_pat"));
        assertFalse(CloudProvider.validMyBoxToken("mbx_pat_\nsecret"));
    }
    @Test public void myboxJsonAndKoreanFolders() throws Exception {
        JSONArray rows = new JSONArray(
                "[{\"name\":\"사진\",\"resourceId\":\"folder_11\",\"type\":\"folder\",\"size\":0}," +
                "{\"name\":\"정산.xlsx\",\"resourceId\":\"file_22\",\"type\":\"file\",\"size\":2048}," +
                "{\"name\":\"../unsafe\",\"resourceId\":\"file_33\",\"type\":\"file\"}]");
        List<Models.Entry> out = new ArrayList<>();
        CloudProvider.appendMyboxItems(rows, "/2026", out);
        assertEquals(2, out.size());
        assertTrue(out.get(0).directory);
        assertEquals("/2026/사진", out.get(0).path);
        assertEquals("folder_11", out.get(0).remoteId);
        assertEquals(2048, out.get(1).size);
    }
    @Test public void teraboxNdusOnlyCookie() throws Exception {
        assertEquals("ndus=abcdefghijklmnop==",
                CloudProvider.ndusCookie("a=b; ndus=abcdefghijklmnop==; x=y"));
        assertEquals("ndus=abcdefghijklmnop",
                CloudProvider.ndusCookie("abcdefghijklmnop"));
        try {
            CloudProvider.ndusCookie("ndus=aaa\r\nX-Injected:yes");
            fail("CRLF must not be accepted");
        } catch (java.io.IOException expected) {}
    }
    @Test public void teraboxListParserRejectsTraversal() throws Exception {
        JSONArray data = new JSONArray("[{\"server_filename\":\"완료\"," +
                "\"isdir\":1,\"size\":0,\"fs_id\":1}," +
                "{\"server_filename\":\"video.mp4\",\"isdir\":0," +
                "\"size\":125000,\"server_mtime\":1600000000,\"fs_id\":2}," +
                "{\"server_filename\":\"../etc\",\"isdir\":0,\"fs_id\":3}]");
        List<Models.Entry> out = new ArrayList<>();
        CloudProvider.appendTeraboxItems(data, "/사무실", out);
        assertEquals(2, out.size());
        assertEquals("/사무실/완료", out.get(0).path);
        assertTrue(out.get(0).directory);
        assertEquals(125000, out.get(1).size);
        assertEquals("/사무실/video.mp4", out.get(1).path);
    }
}
