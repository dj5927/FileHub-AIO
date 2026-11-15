package com.nasmount.mobile;

import java.net.URI;
import java.util.Locale;

/**
 * Keep TeraBox's sign-in window on the official web experience.
 * Never dispatch intent://, market://, Play Store or APK install links.
 * This is a navigation policy, not a way to bypass a site's authentication.
 */
final class TeraBoxWebPolicy {
    static final String LOGIN_URL = "https://www.terabox.com/korean/login";
    static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private TeraBoxWebPolicy() {}

    static boolean isAllowedWebNavigation(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    uri.getRawUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "/" :
                    uri.getPath().toLowerCase(Locale.ROOT);
            if (host.equals("play.google.com") ||
                    host.equals("play.app.goo.gl") ||
                    host.equals("apps.apple.com") ||
                    host.endsWith(".appsflyer.com") ||
                    host.endsWith(".adjust.com") ||
                    host.equals("app.adjust.com")) return false;
            if (path.endsWith(".apk") ||
                    path.matches(".*/(?:download-app|install-app|app-download)(?:/.*)?") ||
                    path.matches("^/(?:korean/)?(?:download|install)(?:/.*)?"))
                return false;
            return isHost(host, "terabox.com") ||
                    isHost(host, "terabox.app") ||
                    isHost(host, "teraboxapp.com") ||
                    isHost(host, "facebook.com") ||
                    host.equals("appleid.apple.com");
        } catch (Exception malformed) {
            return false;
        }
    }
    private static boolean isHost(String name, String domain) {
        return name.equals(domain) || name.endsWith("." + domain);
    }

    static boolean isGoogleAuthNavigation(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) &&
                    uri.getRawUserInfo() == null &&
                    "accounts.google.com".equalsIgnoreCase(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }
}
