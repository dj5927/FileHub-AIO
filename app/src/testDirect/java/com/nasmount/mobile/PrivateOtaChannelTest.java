package com.nasmount.mobile;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class PrivateOtaChannelTest {
    private static final String ACCESS="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Test public void acceptsOnlyScopedHttpsPrivateManifest() throws Exception {
        PrivateOtaChannel channel=new PrivateOtaChannel("https://example.invalid/filehub/direct",ACCESS);
        assertEquals("https://example.invalid/filehub/direct/",channel.base);
        JSONObject json=new JSONObject().put("channel","direct").put("package","com.nasmount.mobile")
                .put("versionName","0.1.120").put("versionCode",121).put("size",100000)
                .put("sha256","a".repeat(64)).put("filename","FileHub-AIO-v0.1.120.apk");
        OtaReleasePolicy.Release release=channel.parse(json);
        assertEquals(channel.base+"download/FileHub-AIO-v0.1.120.apk",release.url);
        java.net.HttpURLConnection connection=channel.openDownload(release);
        assertFalse(connection.getInstanceFollowRedirects());
        connection.disconnect();
        assertThrows(SecurityException.class,()->channel.open("https://other.invalid/download/FileHub-AIO-v0.1.120.apk"));
        assertThrows(SecurityException.class,()->channel.open(channel.base+"download/../tokens.json"));
        json.put("package","com.filehubaio.mobile");
        assertThrows(SecurityException.class,()->channel.parse(json));
        assertThrows(IllegalArgumentException.class,()->new PrivateOtaChannel("http://example.invalid/",ACCESS));
        assertThrows(IllegalArgumentException.class,()->new PrivateOtaChannel("https://example.invalid/a/../b",ACCESS));
        assertThrows(IllegalArgumentException.class,()->new PrivateOtaChannel("https://user@example.invalid/",ACCESS));
    }
}
