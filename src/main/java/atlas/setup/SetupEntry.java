package atlas.setup;

import java.util.Locale;

// Ported from OpenSkid (GPL-3.0).

public final class SetupEntry {
    public enum Section {
        MODS,
        PACKS
    }

    public enum Source {
        DIRECT,

        MEDIAFIRE_SHARE,

        OPTIFINE
    }

    public final Section section;
    public final Source source;
    public final String name;
    public final String blurb;
    public final String fileName;
    public final String url;
    public final long expectedBytes;
    public final boolean auto;
    public final boolean matchPrefix;

    public final String sha1;

    public SetupEntry(Section section, Source source, String name, String blurb,
                      String fileName, String url, long expectedBytes, boolean auto) {
        this(section, source, name, blurb, fileName, url, expectedBytes, auto, false);
    }

    public SetupEntry(Section section, Source source, String name, String blurb,
                      String fileName, String url, long expectedBytes, boolean auto, boolean matchPrefix) {
        this(section, source, name, blurb, fileName, url, expectedBytes, auto, matchPrefix, null);
    }

    public SetupEntry(Section section, Source source, String name, String blurb,
                      String fileName, String url, long expectedBytes, boolean auto, boolean matchPrefix,
                      String sha1) {
        this.sha1 = sha1;
        this.section = section;
        this.source = source;
        this.name = name;
        this.blurb = blurb;
        this.fileName = fileName;
        this.url = url;
        this.expectedBytes = expectedBytes;
        this.auto = auto;
        this.matchPrefix = matchPrefix;
    }

    public static String stripColors(String text) {
        return text == null ? "" : text.replaceAll("\u00A7.", "");
    }

    public String displayName() {
        return stripColors(name).trim();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
