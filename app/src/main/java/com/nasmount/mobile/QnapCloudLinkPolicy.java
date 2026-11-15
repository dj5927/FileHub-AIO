package com.nasmount.mobile;

import android.net.Uri;
import java.net.URI;
import java.util.Locale;

/** QNAP cloud access is browser-mediated; no private CloudLink relay API is used. */
final class QnapCloudLinkPolicy {
    static final String PORTAL = "https://www.myqnapcloud.com/";
    private QnapCloudLinkPolicy() {}

    static String smartUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return PORTAL;
        try {
            URI parsed = new URI(raw.trim());
            if (!"https".equalsIgnoreCase(parsed.getScheme()) ||
                    parsed.getHost() == null || parsed.getUserInfo() != null ||
                    parsed.getFragment() != null || parsed.getPort() != -1)
                throw new IllegalArgumentException("QNAP 클라우드 주소는 HTTPS 형식이어야 합니다.");
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("qlink.to") || host.equals("www.qlink.to") ||
                    host.equals("myqnapcloud.com") ||
                    host.equals("www.myqnapcloud.com") ||
                    host.endsWith(".myqnapcloud.com")))
                throw new IllegalArgumentException("QNAP 공식 SmartURL 주소만 지원합니다.");
            if (parsed.getQuery() != null)
                throw new IllegalArgumentException("SmartURL에 쿼리 값이 포함되면 사용할 수 없습니다.");
            return parsed.toASCIIString();
        } catch (Exception error) {
            throw new IllegalArgumentException("myQNAPcloud의 공식 HTTPS SmartURL을 입력하세요.");
        }
    }

    static boolean isSmartShare(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            URI uri = new URI(raw.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    uri.getHost() == null || uri.getUserInfo() != null ||
                    uri.getFragment() != null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return (host.equals("qlink.to") || host.equals("www.qlink.to") ||
                    host.endsWith(".myqnapcloud.com") || host.equals("myqnapcloud.com") ||
                    host.equals("www.myqnapcloud.com"));
        } catch (Exception invalid) { return false; }
    }
}
