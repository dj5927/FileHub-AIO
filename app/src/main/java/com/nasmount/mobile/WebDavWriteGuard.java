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

    static String checkedLockToken(String token) throws IOException {
        if (token == null) throw new IOException("WebDAV 잠금 토큰이 없습니다.");
        token = token.trim();
        if (token.length() < 6 || token.length() > 512 ||
                !token.startsWith("<") || !token.endsWith(">") ||
                !token.substring(1, token.length() - 1)
                        .matches("[A-Za-z][A-Za-z0-9+.-]*:[^<>\\s]+"))
            throw new IOException("WebDAV 잠금 토큰 형식이 올바르지 않습니다.");
        return token;
    }

    /** Both the destination lock token and (if present) its ETag must match. */
    static String taggedIfLocked(String destination, String etag, String token)
            throws IOException {
        if (destination == null || destination.contains("\r") ||
                destination.contains("\n") ||
                destination.contains("<") || destination.contains(">"))
            throw new IOException("잘못된 WebDAV 대상 주소입니다.");
        return "<" + destination + "> (" + checkedLockToken(token) +
                (etag == null ? "" : " [" + checkedEtag(etag) + "]") + ")";
    }
}
