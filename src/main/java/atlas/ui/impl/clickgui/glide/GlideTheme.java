package atlas.ui.impl.clickgui.glide;

import atlas.Atlas;
import atlas.module.Module;
import atlas.module.modules.ClickGUIModule;
import atlas.module.modules.RenderFixes;
import atlas.util.render.MobileSafe;

/**
 * The Glide style's colours and sizes. Everything the style paints reads
 * from here.
 *
 * 2026-10-26 (owner: "too different, I want it the same"): measured from the
 * owner's screenshot of the reference menu -- opaque #1A1A1A panels whose
 * header is the same colour, rows about as tall as the header and flush with
 * each other, an on-module filling its whole row with the accent, a vertical
 * three-dot mark at the right of every row, the main window's header a shade
 * darker. Only colours and proportions were taken from the picture.
 */
final class GlideTheme {
    static final float WIDTH = 112.0F;
    static final float HEADER = 20.0F;
    static final float ROW = 20.0F;
    static final float RADIUS = 3.0F;
    static final float GAP = 6.0F;
    static final float MARGIN = 10.0F;
    /** Space below the last row. */
    static final float PAD = 2.0F;
    /** A panel never opens shorter than this many rows, even near the bottom of the screen. */
    static final int MIN_ROWS = 4;

    static final int PANEL = 0xFF1A1A1A;
    static final int HEADER_BG = 0xFF1A1A1A;
    static final int MAIN_HEADER = 0xFF141414;
    static final int SEPARATOR = 0xFF121212;
    static final int ROW_HOVER = 0xFF232323;
    /** Hover over settings rows and other lighter parts: a white wash. */
    static final int HOVER = 0x0CFFFFFF;
    static final int TEXT = 0xFFFFFFFF;
    static final int TITLE = 0xFFE6E6E6;
    static final int DIM = 0xFFA3A3A3;
    static final int FAINT = 0xFF6E6E6E;
    static final int SECTION = 0xFF5A5A5A;
    static final int CHIP = 0xFF2B2B2B;
    /** A key chip on an on-module's accent row: white over the accent. */
    static final int CHIP_ON = 0x40FFFFFF;
    static final int TOOLTIP = 0xF2141414;
    static final int SETTINGS_BG = 0xFF151515;
    static final int TRACK = 0xFF2E2E2E;
    static final int SEARCH_BG = 0xFF141414;
    static final int WARN = 0xFFFFB347;

    /** Milliseconds the screen takes to fade in. */
    static final float FADE_MS = 140.0F;
    /** Each panel starts this much after the one before it, and takes PANEL_MS. */
    static final float STAGGER_MS = 15.0F;
    static final float PANEL_MS = 180.0F;
    /** A description shows after the pointer rests on its row this long. */
    static final long TOOLTIP_MS = 450L;
    static final float TOOLTIP_WIDTH = 170.0F;

    private static final int FALLBACK_ACCENT = 0xFF048668;

    private GlideTheme() {
    }

    private static ClickGUIModule module() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.getModule("ClickGUI");
        return module instanceof ClickGUIModule ? (ClickGUIModule) module : null;
    }

    static int accent() {
        ClickGUIModule module = module();
        return module == null ? FALLBACK_ACCENT : module.glideAccent.getValue() | 0xFF000000;
    }

    /** Blur behind the menu: the switch, and only where shaders work. */
    static boolean blur() {
        ClickGUIModule module = module();
        return (module == null || module.glideBlur.getValue()) && RenderFixes.shouldUseShaders()
                && !MobileSafe.active();
    }

    /** {@code color} with its alpha scaled by {@code f} (0-1). */
    static int alpha(int color, float f) {
        int a = Math.round((color >>> 24) * Math.max(0.0F, Math.min(1.0F, f)));
        return a << 24 | color & 0xFFFFFF;
    }

    static int mix(int from, int to, float t) {
        int a1 = from >>> 24, r1 = from >> 16 & 255, g1 = from >> 8 & 255, b1 = from & 255;
        int a2 = to >>> 24, r2 = to >> 16 & 255, g2 = to >> 8 & 255, b2 = to & 255;
        return Math.round(a1 + (a2 - a1) * t) << 24 | Math.round(r1 + (r2 - r1) * t) << 16
                | Math.round(g1 + (g2 - g1) * t) << 8 | Math.round(b1 + (b2 - b1) * t);
    }

    /** Cubic ease-out of a 0-1 progress. */
    static float easeOut(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        float u = 1.0F - t;
        return 1.0F - u * u * u;
    }
}
