package com.nasmount.mobile;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure validation and NAS-to-File-Station path mapping. */
final class NasSharePolicy {
    static final String SYNOLOGY = "Synology";
    static final String QNAP = "QNAP";
    private NasSharePolicy() {}

    static boolean supported(Models.RemoteProfile p) {
        return p != null && (SYNOLOGY.equals(p.nasVendor) || QNAP.equals(p.nasVendor))
                && p.nasApiBase != null && !p.nasApiBase.isEmpty()
                && !roots(p).isEmpty();
    }

    static List<String> roots(Models.RemoteProfile p) {
        if (p == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        if (p.nasShareRoots != null && !p.nasShareRoots.isEmpty()) {
            for (String item : p.nasShareRoots) {
                String safe = validateShareRoot(item);
                if (!result.contains(safe)) result.add(safe);
            }
        } else if (p.nasShareRoot != null && !p.nasShareRoot.isEmpty()) {
            result.add(validateShareRoot(p.nasShareRoot));
        }
        return result;
    }

    static boolean inAllowedRoot(Models.RemoteProfile profile, String fullPath) {
        String path = RemoteProvider.relativePath(fullPath);
        for (String root : roots(profile))
            if (path.equals(root) || path.startsWith(root + "/")) return true;
        return false;
    }

    static String mapPath(Models.RemoteProfile profile, String currentRelativePath) {
        List<String> allowed = roots(profile);
        if (allowed.isEmpty())
            throw new IllegalArgumentException("NAS 공유 폴더를 먼저 선택하세요.");
        String relative = RemoteProvider.relativePath(currentRelativePath);
        String connectionRoot = RemoteProvider.relativePath(profile.root);

        // If the transport is rooted inside a NAS share, prepend the verified
        // File Station share prefix rather than incorrectly using the first
        // selected share for every SMB connection.
        if (!"/".equals(connectionRoot)) {
            for (String root : allowed) {
                String name = root.substring(1);
                int position = connectionRoot.indexOf("/" + name);
                if (position < 0) continue;
                int end = position + name.length() + 1;
                if (end < connectionRoot.length() && connectionRoot.charAt(end) != '/')
                    continue;
                String mappedRoot = connectionRoot.substring(position);
                String result = RemoteProvider.relativePath(mappedRoot +
                        ("/".equals(relative) ? "" : relative));
                if (inAllowedRoot(profile, result)) return result;
            }
            // A relative connection folder with an unknown name is safe only
            // when there is exactly one explicitly selected File Station root.
            if (allowed.size() == 1) return mapPath(allowed.get(0), relative);
            throw new IllegalArgumentException("현재 연결 경로와 선택한 NAS 공유 폴더를 연결할 수 없습니다. " +
                    "File Station 탐색기에서 파일을 선택하세요.");
        }

        // A broad WebDAV/SFTP root may expose complete File Station paths.
        if (inAllowedRoot(profile, relative)) return relative;
        if (allowed.size() == 1) return mapPath(allowed.get(0), relative);
        throw new IllegalArgumentException("현재 파일의 공유 폴더를 확인할 수 없습니다. " +
                "File Station 탐색기에서 선택해 주세요.");
    }

    static String validateApiBase(String value) {
        try {
            URI url = new URI(value.trim());
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null ||
                    url.getUserInfo() != null || url.getQuery() != null ||
                    url.getFragment() != null || (url.getPath() != null &&
                    !url.getPath().isEmpty() && !url.getPath().equals("/")))
                throw new IllegalArgumentException("NAS 관리 URL은 https://서버:포트 형태여야 합니다.");
            int port = url.getPort();
            if (port > 65535 || port == 0)
                throw new IllegalArgumentException("NAS 관리 포트가 올바르지 않습니다.");
            return url.getScheme().toLowerCase(Locale.ROOT) + "://" +
                    url.getRawAuthority();
        } catch (Exception e) {
            throw new IllegalArgumentException("NAS 관리 URL은 HTTPS 주소만 지원합니다. 예: https://nas.example.com:5001");
        }
    }

    static String validateShareRoot(String value) {
        String root = RemoteProvider.relativePath(value);
        if ("/".equals(root))
            throw new IllegalArgumentException("File Station의 공유 폴더 이름을 입력하세요. 예: /video");
        return root;
    }

    static String mapPath(String root, String currentRelativePath) {
        String base = validateShareRoot(root);
        String relative = RemoteProvider.relativePath(currentRelativePath);
        return base + ("/".equals(relative) ? "" : relative);
    }

    static String expiryDate(int days, String customDate) {
        if (days == 0) return "0";
        LocalDate value = days > 0 ? LocalDate.now().plusDays(days) :
                LocalDate.parse(customDate);
        if (value.isBefore(LocalDate.now().plusDays(1)))
            throw new IllegalArgumentException("만료일은 내일 이후로 지정하세요.");
        return value.toString();
    }

    static String validatePublicHost(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String base = validateApiBase(raw);
        return base;
    }
}
