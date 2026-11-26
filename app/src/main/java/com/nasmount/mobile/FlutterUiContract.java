package com.nasmount.mobile;

import java.util.*;

/** UI data only; never send credentials, tokens or file paths to Dart. */
final class FlutterUiContract {
    static final String CHANNEL = "com.filehubaio/ui.v1";
    static final String ROUTE = "filehub.flutter.route";
    static final String ACTION = "filehub.flutter.action";
    static final String PROFILE_ID = "filehub.flutter.profile";
    static final int REQUEST_CONNECTIONS = 530;
    static final int REQUEST_INTERFACE = 731;
    static final int REQUEST_EDITOR = 531;
    static final String CONNECTIONS = "connections";
    static final String INTERFACE = "interface";
    static final String EDITOR = "editor";
    static final String HOME = "home";
    static final String DRAWER = "drawer";
    static final String EXPLORER = "explorer";
    static final String TRANSFER = "transfer";
    static final String FILE_PROMPT = "filePrompt";
    static final String VIEWER = "viewer";
    static final String PANEL = "panel";
    static final String SHELF = "shelf";
    static final String FAVORITES = "favorites";
    private static final Set<String> GLOBAL_ACTIONS =
            actions("add", "backup", "upgrade", "nativeList");
    private static final Set<String> HOME_ACTIONS = actions(
            "storage", "sd", "downloads", "images", "audio", "videos",
            "videoShelf", "comicShelf", "textShelf", "connections", "upgrade");
    private static final Set<String> DRAWER_ACTIONS = actions(
            "home", "connections", "settings", "favorites", "close",
            "toggleFavorites", "toggleNetwork", "nativeDrawer", "upgrade");
    private FlutterUiContract() {}
    private static Set<String> actions(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }
    static boolean validRoute(String route) {
        return CONNECTIONS.equals(route) || INTERFACE.equals(route) || EDITOR.equals(route);
    }
    static boolean validLanguage(Object value) {
        return value instanceof Integer && (Integer)value >= 0 && (Integer)value <= 4;
    }
    static boolean validTheme(Object value) {
        return value instanceof Integer && (Integer)value >= 0 && (Integer)value <= 2;
    }
    static boolean validAction(String route, String action, String id) {
        if (!CONNECTIONS.equals(route) || action == null) return false;
        if ("open".equals(action) || "manage".equals(action) || "edit".equals(action))
            return id != null && !id.isEmpty() && id.length() <= 256;
        return GLOBAL_ACTIONS.contains(action);
    }
    static boolean passiveExplorerRequest(String route,String method) {
        return EXPLORER.equals(route)&&("explorerPreview".equals(method)||"explorerPage".equals(method));
    }
    static boolean validNavigationAction(String route, String action, String id) {
        if (action == null) return false;
        if(CONNECTIONS.equals(route))return validAction(route,action,id);
        if(FAVORITES.equals(route))return ("favorite".equals(action)||"favoriteManage".equals(action))
                &&id!=null&&id.matches("[0-9a-f]{64}");
        if (TRANSFER.equals(route))
            return id==null&&("transferHide".equals(action)||"transferCancel".equals(action)||"transferClassic".equals(action));
        if (FILE_PROMPT.equals(route))
            return id==null&&("promptCancel".equals(action)||"promptClassic".equals(action));
        if (EXPLORER.equals(route)) {
            if (FileOperationUiPolicy.valid(action,id)) return true;
            if ("classicExplorer".equals(action)) return id==null;
            return ("entryOpen".equals(action)||"entrySelect".equals(action)||"crumb".equals(action))
                    && id!=null&&id.matches("[0-9a-f-]{36}:[0-9]{1,10}");
        }
        if (HOME.equals(route)) return HOME_ACTIONS.contains(action) && id == null;
        if (!DRAWER.equals(route)) return false;
        if ("open".equals(action)) return id != null && !id.isEmpty() && id.length() <= 256;
        if ("favorite".equals(action) || "favoriteManage".equals(action))
            return id != null && id.matches("[0-9a-f]{64}");
        return DRAWER_ACTIONS.contains(action) && id == null;
    }

    /** Opaque ID for bookmarks; the real storage path never leaves Java. */
    static String favoriteId(Models.Favorite f) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(f.key().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static List<Map<String,Object>> favoriteSummaries(List<Models.Favorite> favorites) {
        List<Map<String,Object>> out = new ArrayList<>();
        for (Models.Favorite f : favorites) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", favoriteId(f));
            row.put("label", f.displayTitle());
            row.put("original", f.hasCustomTitle() ? f.title : "");
            out.add(row);
        }
        return out;
    }
    static List<String> resolveFavoriteOrder(List<Models.Favorite> live, Object requested) {
        if (!(requested instanceof List<?>)) throw new IllegalArgumentException("order");
        Map<String,String> ids = new HashMap<>();
        for (Models.Favorite f : live) ids.put(favoriteId(f), f.key());
        List<?> order = (List<?>)requested;
        if (order.size() != ids.size()) throw new IllegalArgumentException("stale order");
        List<String> keys = new ArrayList<>();
        for (Object id : order) {
            if (!(id instanceof String) || !ids.containsKey(id)) throw new IllegalArgumentException("unknown favorite");
            keys.add(ids.remove(id));
        }
        return keys;
    }
    static List<Map<String,Object>> summaries(List<Models.RemoteProfile> profiles, boolean full) {
        Set<String> allowed = new HashSet<>();
        for (Models.RemoteProfile p : MonetizationPolicy.accessible(profiles, full))
            allowed.add(p.id);
        List<Map<String,Object>> out = new ArrayList<>();
        for (Models.RemoteProfile p : profiles) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("id", p.id);
            row.put("label", p.name == null || p.name.trim().isEmpty() ?
                    p.protocol : p.name.trim());
            row.put("protocol", p.protocol);
            row.put("allowed", allowed.contains(p.id));
            row.put("readOnly", p.readOnly);
            out.add(row);
        }
        return out;
    }
}
