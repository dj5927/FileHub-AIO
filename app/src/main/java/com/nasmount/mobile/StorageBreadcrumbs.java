package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Local storage breadcrumb paths, bounded to the actual storage volume.
 * Never build buttons that navigate above the main or removable SD root. */
final class StorageBreadcrumbs {
    private StorageBreadcrumbs() {}

    // Android uses slash-delimited absolute storage paths. Keep this
    // deterministic in Windows-hosted JVM tests as well as on the device;
    // java.nio.file.Paths would otherwise reinterpret /storage as a drive.
    private static List<String> components(String input) {
        if (input == null || !input.startsWith("/"))
            throw new IllegalArgumentException("절대 저장소 경로가 아닙니다.");
        List<String> segments = new ArrayList<>();
        for (String part : input.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (segments.isEmpty())
                    throw new IllegalArgumentException("경로가 최상위를 벗어났습니다.");
                segments.remove(segments.size() - 1);
            } else {
                segments.add(part);
            }
        }
        return segments;
    }

    private static String pathFor(List<String> segments) {
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    static boolean withinRoot(String rootPath, String childPath) {
        try {
            List<String> root = components(rootPath);
            List<String> child = components(childPath);
            if (child.size() < root.size()) return false;
            for (int i = 0; i < root.size(); i++)
                if (!root.get(i).equals(child.get(i))) return false;
            return true;
        } catch (IllegalArgumentException badPath) {
            return false;
        }
    }

    static List<RemoteBreadcrumbs.Step> steps(String rootPath, String currentPath) {
        if (!withinRoot(rootPath, currentPath))
            throw new IllegalArgumentException("현재 폴더가 저장소 범위를 벗어났습니다.");
        List<String> root = components(rootPath);
        List<String> current = components(currentPath);
        List<RemoteBreadcrumbs.Step> out = new ArrayList<>();
        out.add(new RemoteBreadcrumbs.Step("⌂", pathFor(root)));
        for (int i = root.size(); i < current.size(); i++) {
            out.add(new RemoteBreadcrumbs.Step(current.get(i),
                    pathFor(current.subList(0, i + 1))));
        }
        return Collections.unmodifiableList(out);
    }
}
