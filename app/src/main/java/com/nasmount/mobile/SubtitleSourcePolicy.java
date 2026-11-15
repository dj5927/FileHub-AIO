package com.nasmount.mobile;

/**
 * Subtitle source preference; keeps sidecar files separate from embedded text
 * tracks. The source marker is attached to Media3's SubtitleConfiguration
 * and survives in Format.id/Format.label of its generated text track.
 */
final class SubtitleSourcePolicy {
    static final int AUTO = 0;
    static final int EXTERNAL = 1;
    static final int EMBEDDED = 2;
    static final int OFF = 3;
    static final int NONE = -1;
    static final String EXTERNAL_TRACK_ID = "nasmount.external.subtitle.v1";
    static final String EXTERNAL_LABEL_PREFIX = "NAS Mount 외부 자막";
    static final String[] LABELS = {
            "자동 (외부 자막 우선)",
            "외부 자막",
            "내장 자막",
            "자막 끄기"
    };

    private SubtitleSourcePolicy() {}

    static int normalize(int value) {
        return value >= AUTO && value <= OFF ? value : AUTO;
    }

    static boolean isExternal(String formatId, String formatLabel) {
        return EXTERNAL_TRACK_ID.equals(formatId) ||
                (formatLabel != null &&
                        formatLabel.startsWith(EXTERNAL_LABEL_PREFIX));
    }

    static int desiredSource(int choice, boolean externalFound,
                             boolean embeddedFound) {
        switch (normalize(choice)) {
            case EXTERNAL: return externalFound ? EXTERNAL : NONE;
            case EMBEDDED: return embeddedFound ? EMBEDDED : NONE;
            case OFF: return NONE;
            default:
                if (externalFound) return EXTERNAL;
                if (embeddedFound) return EMBEDDED;
                return NONE;
        }
    }

    static boolean matchesLanguage(String actual, String preferred) {
        if (actual == null || preferred == null) return false;
        if (actual.equalsIgnoreCase(preferred)) return true;
        String a = actual.toLowerCase(java.util.Locale.ROOT);
        String p = preferred.toLowerCase(java.util.Locale.ROOT);
        if (a.startsWith(p + "-") || p.startsWith(a + "-")) return true;
        if ("ko".equals(p)) return "kor".equals(a);
        if ("en".equals(p)) return "eng".equals(a);
        if ("ja".equals(p)) return "jpn".equals(a);
        if ("zh".equals(p)) return "zho".equals(a) || "chi".equals(a);
        return false;
    }

    /** SMI/SAMI are converted to UTF-8 SRT before Media3 receives the file. */
    static boolean matchesExternalFilename(String videoStem, String filename) {
        if (videoStem == null || filename == null) return false;
        String name = filename.toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".srt") && !name.endsWith(".vtt") &&
                !name.endsWith(".smi") && !name.endsWith(".sami")) return false;
        String base = name.substring(0, name.lastIndexOf('.'));
        String movie = videoStem.toLowerCase(java.util.Locale.ROOT);
        if (base.equals(movie)) return true;
        if (!base.startsWith(movie + ".")) return false;
        String suffix = base.substring(movie.length() + 1);
        String[] flags = suffix.split("\\.");
        if (flags.length > 2) return false;
        String language = flags[0].split("[-_]")[0];
        boolean allowed = (" ko kor en eng ja jpn zh zho chi fr de es pt ru " +
                "vi th id forced sdh default ").contains(" " + language + " ");
        if (!allowed) return false;
        return flags.length == 1 ||
                "forced".equals(flags[1]) || "sdh".equals(flags[1]) ||
                "default".equals(flags[1]);
    }
}
