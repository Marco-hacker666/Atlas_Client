package myau.update;

/**
 * A release version such as "2.1.1" or "v2.1.1".
 *
 * Only the numeric parts are compared ("2.1.10" is newer than "2.1.9").
 * Anything after a '-' or '+' ("2.2.0-beta") counts as a pre-release and is
 * older than the same numbers without it. Missing parts count as 0, so
 * "2.1" equals "2.1.0".
 */
public final class UpdateVersion implements Comparable<UpdateVersion> {

    private final int[] parts;
    private final boolean preRelease;
    private final String text;

    private UpdateVersion(int[] parts, boolean preRelease, String text) {
        this.parts = parts;
        this.preRelease = preRelease;
        this.text = text;
    }

    /** Parses a version, or returns null when it is not one (e.g. "dev"). */
    public static UpdateVersion parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        String core = s;
        boolean pre = false;
        int cut = indexOfAny(s, '-', '+');
        if (cut >= 0) {
            core = s.substring(0, cut);
            pre = true;
        }
        if (core.isEmpty()) {
            return null;
        }
        String[] pieces = core.split("\\.", -1);
        if (pieces.length > 4) {
            return null;
        }
        int[] parts = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String p = pieces[i];
            if (p.isEmpty() || p.length() > 6) {
                return null;
            }
            for (int c = 0; c < p.length(); c++) {
                if (!Character.isDigit(p.charAt(c))) {
                    return null;
                }
            }
            parts[i] = Integer.parseInt(p);
        }
        return new UpdateVersion(parts, pre, s);
    }

    private static int indexOfAny(String s, char a, char b) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == a || c == b) {
                return i;
            }
        }
        return -1;
    }

    public boolean isNewerThan(UpdateVersion other) {
        return other == null || compareTo(other) > 0;
    }

    @Override
    public int compareTo(UpdateVersion o) {
        int n = Math.max(parts.length, o.parts.length);
        for (int i = 0; i < n; i++) {
            int a = i < parts.length ? parts[i] : 0;
            int b = i < o.parts.length ? o.parts[i] : 0;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        if (preRelease != o.preRelease) {
            return preRelease ? -1 : 1;
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UpdateVersion && compareTo((UpdateVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        int h = preRelease ? 1 : 0;
        int last = parts.length;
        while (last > 0 && parts[last - 1] == 0) {
            last--;
        }
        for (int i = 0; i < last; i++) {
            h = h * 31 + parts[i];
        }
        return h;
    }

    @Override
    public String toString() {
        return text;
    }
}
