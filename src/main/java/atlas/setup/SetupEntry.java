package atlas.setup;

import java.util.Locale;

/**
 * One item of the setup catalog: a companion mod jar or a resource pack.
 *
 * <p>Ported from OpenSkid (GPL-3.0). {@code matchPrefix} lets an entry count as
 * installed when the user already has a different build of the same mod (for
 * example any {@code OptiFine_1.8.9_HD_U_*} jar instead of exactly the one we
 * ship).
 */
public final class SetupEntry {
    public enum Section {
        MODS,
        PACKS
    }

    public enum Source {
        /** {@code url} points straight at the file. */
        DIRECT,
        /** {@code url} is a MediaFire share page; the direct link is scraped from it. */
        MEDIAFIRE_SHARE,
        /**
         * {@code url} is optifine.net's own download page (adloadx): the link
         * with that visit's key is read from it, then the file comes from
         * optifine.net itself (2026-10-11).
         */
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
    /** SHA-1 of the one right file (lower-case hex), or null when it is not pinned. */
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

    /** Removes Minecraft {@code §x} colour codes so names can be drawn or compared. */
    public static String stripColors(String text) {
        return text == null ? "" : text.replaceAll("\u00A7.", "");
    }

    public String displayName() {
        return stripColors(name).trim();
    }

    /** Human-readable byte count for the UI, e.g. {@code "1.4 MB"}. */
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
