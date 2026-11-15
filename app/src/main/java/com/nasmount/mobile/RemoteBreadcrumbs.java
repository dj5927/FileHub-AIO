package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual remote folders only; clickable ancestor path labels without protocol text. */
final class RemoteBreadcrumbs {
    private RemoteBreadcrumbs() {}

    static final class Step {
        final String label;
        final String path;
        Step(String label, String path) {
            this.label = label;
            this.path = path;
        }
    }

    static List<Step> steps(String folder) {
        String canonical = RemoteProvider.relativePath(folder);
        List<Step> out = new ArrayList<>();
        out.add(new Step("⌂", "/"));
        if (!canonical.equals("/")) {
            String at = "/";
            for (String part : canonical.substring(1).split("/")) {
                at = RemoteProvider.child(at, part);
                out.add(new Step(part, at));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
