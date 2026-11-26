package com.nasmount.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Pure validation for GitHub's public release metadata. */
final class OtaReleasePolicy {
    static final String API="https://api.github.com/repos/dj5927/FileHub-AIO/releases/latest";
    static final String PREVIEW_API="https://api.github.com/repos/dj5927/FileHub-AIO/releases?per_page=20";
    static final String REPO="https://github.com/dj5927/FileHub-AIO";
    static final String FILE_PREFIX="FileHub-AIO-v";

    static final class Release {
        final String version, notes, url, sha256;
        final int code;
        final long size;
        Release(String version,int code,String notes,String url,String sha256,long size) {
            this.version=version;this.code=code;this.notes=notes;
            this.url=url;this.sha256=sha256;this.size=size;
        }
    }

    static Release parse(JSONObject json) throws Exception {
        return parse(json,false);
    }
    private static Release parse(JSONObject json,boolean preview) throws Exception {
        if(json.optBoolean("draft") || json.optBoolean("prerelease")!=preview)
            throw new IllegalArgumentException("Wrong release channel");
        String tag=json.getString("tag_name");
        String prefix=preview?"flutter-test-v":"v";
        if(!tag.matches(prefix+"[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("Invalid release tag");
        String version=tag.substring(prefix.length());
        String body=json.optString("body","");
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile(
                "(?m)^versionCode:\\s*(\\d{1,9})\\s*$").matcher(body);
        if(!matcher.find())throw new IllegalArgumentException("Release missing versionCode");
        int code=Integer.parseInt(matcher.group(1));
        if(code<1)throw new IllegalArgumentException("Invalid versionCode");
        JSONArray assets=json.getJSONArray("assets");
        for(int i=0;i<assets.length();i++) {
            JSONObject asset=assets.getJSONObject(i);
            String expected=FILE_PREFIX+version+".apk";
            if(!expected.equals(asset.optString("name")))continue;
            String download=asset.getString("browser_download_url");
            String urlPrefix=REPO+"/releases/download/"+tag+"/";
            if(!download.equals(urlPrefix+expected))
                throw new IllegalArgumentException("Untrusted release URL");
            String checksum=asset.optString("digest","");
            if(!checksum.matches("sha256:[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("Missing SHA256 asset digest");
            long size=asset.getLong("size");
            if(size<100_000 || size>500_000_000)
                throw new IllegalArgumentException("Invalid APK size");
            return new Release(version,code,body,download,
                    checksum.substring(7).toLowerCase(Locale.ROOT),size);
        }
        throw new IllegalArgumentException("Release APK not found");
    }

    static Release newestPreview(JSONArray releases) throws Exception {
        Release best=null;
        for(int i=0;i<releases.length();i++){
            try{
                Release candidate=parse(releases.getJSONObject(i),true);
                if(best==null||candidate.code>best.code)best=candidate;
            }catch(Exception ignored){/* unrelated or invalid release */}
        }
        return best;
    }
    static boolean publicDownloadAllowed(Release release,boolean preview){
        if(release==null||!release.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))return false;
        String tag=(preview?"flutter-test-v":"v")+release.version;
        return (REPO+"/releases/download/"+tag+"/"+FILE_PREFIX+release.version+".apk").equals(release.url);
    }
    static boolean isNewer(Release release,long currentCode) {
        return release!=null && release.code>currentCode;
    }
}
