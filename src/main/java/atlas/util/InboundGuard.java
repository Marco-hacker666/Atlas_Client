package atlas.util;

import java.util.Locale;

public final class InboundGuard {
    public static final int MAX_JSON_DEPTH = 64;
    public static final int MAX_JSON_OPENS = 4096;

    public enum Pack {
        WEB,

        LOCAL_PROBE,

        OTHER
    }

    private InboundGuard() {
    }

    public static boolean sane(double value, double limit) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && Math.abs(value) <= limit;
    }

    public static boolean finite(double... values) {
        for (double v : values) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return false;
            }
        }
        return true;
    }

    public static boolean jsonTooComplex(String json, int maxDepth, int maxOpens) {
        if (json == null) {
            return false;
        }
        int depth = 0;
        int opens = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                opens++;
                if (depth > maxDepth || opens > maxOpens) {
                    return true;
                }
            } else if ((c == '}' || c == ']') && depth > 0) {
                depth--;
            }
        }
        return false;
    }

    public static Pack classifyPack(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return Pack.WEB;
        }
        if (u.startsWith("level://")) {
            return Pack.LOCAL_PROBE;
        }
        return Pack.OTHER;
    }

    public static boolean localPackAllowed(String url, boolean singleplayer) {
        if (!singleplayer || url == null) {
            return false;
        }
        String path = url.trim().substring("level://".length()).replace('\\', '/');
        return !path.contains("..") && !path.startsWith("/") && !path.contains(":");
    }
}
