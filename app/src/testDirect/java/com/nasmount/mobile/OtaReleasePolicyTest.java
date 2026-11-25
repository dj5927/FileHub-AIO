package com.nasmount.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OtaReleasePolicyTest {
    private JSONObject release(String tag, int code) throws Exception {
        JSONObject body=new JSONObject();
        body.put("tag_name",tag);
        body.put("body","버그 수정\nversionCode: "+code);
        String name="FileHub-AIO-"+tag+".apk";
        String digest="sha256:"+"a".repeat(64);
        JSONObject asset=new JSONObject();
        asset.put("name",name);
        asset.put("browser_download_url",
                "https://github.com/dj5927/FileHub-AIO/releases/download/"+tag+"/"+name);
        asset.put("digest",digest);
        asset.put("size",72_000_000L);
        body.put("assets",new JSONArray().put(asset));
        return body;
    }

    @Test public void validSignedReleaseMetadataIsRecognized() throws Exception {
        OtaReleasePolicy.Release candidate=OtaReleasePolicy.parse(release("v0.1.86",87));
        assertEquals("0.1.86",candidate.version);
        assertEquals(87,candidate.code);
        assertTrue(OtaReleasePolicy.isNewer(candidate,86));
        assertFalse(OtaReleasePolicy.isNewer(candidate,87));
        assertEquals(64,candidate.sha256.length());
    }

    @Test public void missingChecksumOrInvalidDownloadUrlRejected() throws Exception {
        JSONObject noDigest=release("v0.1.86",87);
        noDigest.getJSONArray("assets").getJSONObject(0).put("digest","");
        assertThrows(IllegalArgumentException.class,()->OtaReleasePolicy.parse(noDigest));
        JSONObject wrongUrl=release("v0.1.86",87);
        wrongUrl.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url","https://example.com/fake.apk");
        assertThrows(IllegalArgumentException.class,()->OtaReleasePolicy.parse(wrongUrl));
    }

    @Test public void prereleaseAndMissingCodeRejected() throws Exception {
        JSONObject pre=release("v0.1.86",87);
        pre.put("prerelease",true);
        assertThrows(IllegalArgumentException.class,()->OtaReleasePolicy.parse(pre));
        JSONObject noCode=release("v0.1.86",87);
        noCode.put("body","No code");
        assertThrows(IllegalArgumentException.class,()->OtaReleasePolicy.parse(noCode));
    }

    @Test public void invalidTagAndWrongApkFilenameRejected() throws Exception {
        JSONObject invalid=release("v0.1.86",87);
        invalid.put("tag_name","main");
        assertThrows(IllegalArgumentException.class,()->OtaReleasePolicy.parse(invalid));
        JSONObject wrong=release("v0.1.86",87);
        wrong.getJSONArray("assets").getJSONObject(0).put("name","other.apk");
        assertThrows(IllegalArgumentException.class,()->OtaReleasePolicy.parse(wrong));
    }
}
