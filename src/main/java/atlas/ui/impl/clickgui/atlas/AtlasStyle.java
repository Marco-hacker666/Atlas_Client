package atlas.ui.impl.clickgui.atlas;

/**
 * The Atlas menu's design tokens (2026-10-23, docs/GUI-AUDIT-2026-10-23.md
 * section 3): its colours in dark and light, and the sizes and timings its
 * shared controls use. One place, so that a control drawn by any part of the
 * menu matches the rest.
 *
 * The colours are repainted by {@link #paint(boolean)} at the start of every
 * frame. AtlasClickGui still keeps its own TEXT, DIM, FAINT and INK, copied
 * from here, so its call sites did not have to change.
 */
final class AtlasStyle {

    /* Names, values, titles. */
    static int text = 0xFFF5F8FF;
    /* Labels, help, hints. */
    static int dim = 0xD1C3CEDF;
    /* Counts and decoration: never the only place something is said. */
    static int faint = 0x9E93A3BA;
    /* The neutral every wash and rim is mixed from. */
    static int ink = 0xFFFFFFFF;

    /* Colours that mean something, and are used only for that. */
    static int ok = 0xFF6BD49A;
    static int warn = 0xFFFFC857;
    static int danger = 0xFFFF5A5F;
    /* A search result from the other page. */
    static int foreign = 0xFFB59CFF;
    static int foreignText = 0xFFE9E1FF;
    /* The surface of a toast: nearly opaque, so it reads over anything. */
    static int toast = 0xF2181C24;

    /* Type scale, in LiquidFont points before the theme's text size. */
    static final float META = 8.0F;
    static final float BODY = 8.5F;

    /* Corners. */
    static final float R_CONTROL = 10.0F;

    /* How fast hover washes fade in and out. */
    static final float HOVER_RATE = 12.0F;
    /* Half a blink of a text caret. */
    static final long CARET_BLINK_MS = 500L;

    private AtlasStyle() {
    }

    static void paint(boolean light) {
        text = light ? 0xFF161A21 : 0xFFF5F8FF;
        dim = light ? 0xD1404958 : 0xD1C3CEDF;
        faint = light ? 0xA6667080 : 0x9E93A3BA;
        ink = light ? 0xFF0E1726 : 0xFFFFFFFF;
        ok = light ? 0xFF1E9E5A : 0xFF6BD49A;
        warn = light ? 0xFFB7791F : 0xFFFFC857;
        danger = light ? 0xFFD93A40 : 0xFFFF5A5F;
        foreign = light ? 0xFF6A4BD6 : 0xFFB59CFF;
        foreignText = light ? 0xFF3E2A99 : 0xFFE9E1FF;
        toast = light ? 0xF5FBFCFE : 0xF2181C24;
    }

    /** Whether a caret drawn now is in the visible half of its blink. */
    static boolean caretOn() {
        return (System.currentTimeMillis() / CARET_BLINK_MS) % 2L == 0L;
    }
}
