package atlas.ui.impl.clickgui.atlas;

import java.awt.Color;

/**
 * The chat colour for the [Atlas] prefix from the Appearance accent (2026-11-14). Chat has only sixteen colours; the
 * nearest by RGB distance put a soft accent such as Violet (A78BFA) on grey, and the prefix stayed aqua in game. This
 * picks the bright chat colour nearest by hue instead, and none (0) for a grey or very dark accent.
 */
final class ChatAccent {

    private static final char[] CODES = {'c', '6', 'e', 'a', 'b', '9', 'd'};
    private static final float[] HUES = {0.0F, 40.0F, 60.0F, 120.0F, 180.0F, 240.0F, 300.0F};

    private ChatAccent() {
    }

    /** The colour code (c, 6, e, a, b, 9 or d) for an RGB accent, or 0 when it has too little colour. */
    static char codeFor(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        if (hsb[1] < 0.15F || hsb[2] < 0.2F) {
            return 0;
        }
        float hue = hsb[0] * 360.0F;
        char best = CODES[0];
        float bestGap = Float.MAX_VALUE;
        for (int i = 0; i < CODES.length; i++) {
            float gap = Math.abs(hue - HUES[i]);
            gap = Math.min(gap, 360.0F - gap);
            if (gap < bestGap) {
                bestGap = gap;
                best = CODES[i];
            }
        }
        return best;
    }
}
