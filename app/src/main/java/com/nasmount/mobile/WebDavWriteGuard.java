package com.nasmount.mobile;

import java.io.IOException;

/**
 * Protects remote WebDAV overwrite from changes while a multi-MB upload is
 * staged. The final MOVE carries RFC 4918's destination-tagged If condition.
 */
final class WebDavWriteGuard {
    private WebDavWriteGuard() {}

    static String checkedEtag(String etag) throws IOException {
        if (etag == null || etag.length() < 3 || etag.length() > 256 ||
                etag.startsWith("W/") || !etag.startsWith("\"") ||
                !etag.endsWith("\"") ||
                etag.contains("\r") || etag.contains("\n"))
            throw new IOException("서버가 안전한 ETag를 제공하지 않아 덮어쓰기를 중단했습니다.");
        return etag;
    }

    static void sameVersion(String expected, String latest) throws IOException {
        if (!expected.equals(checkedEtag(latest)))
            throw new IOException("다른 기기에서 파일을 수정했습니다. 덮어쓰지 않았습니다.");
    }

    static String taggedIf(String destination, String etag) throws IOException {
        if (destination.contains("\r") || destination.contains("\n"))
            throw new IOException("잘못된 WebDAV 대상 주소입니다.");
        return "<" + destination + "> ([" + checkedEtag(etag) + "])";
    }
}
