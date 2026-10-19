package myau.ui.impl.clickgui.slinky;

import myau.util.font.impl.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Inter for the Slinky-style menu, at exact pixel sizes.
 *
 * FontManager's fonts follow Minecraft's GUI scale (a size-16 font is 16
 * pixels at scale 2 and 24 at scale 3). This menu is laid out in screen
 * pixels, like the menu it copies, so its text is made at the pixel size
 * asked for and stays that size whatever the GUI scale is. FontRenderer draws
 * one texture pixel per screen pixel, so a font derived at 16 px is 16 px on
 * screen.
 *
 * Style 0 is Inter Medium (labels), style 1 Inter SemiBold (key badges).
 * Inter is under the SIL Open Font License; see font/Inter-OFL.txt.
 */
final class SlinkyFonts {
    private static final String[] FILES = {"Inter-Medium.ttf", "Inter-SemiBold.ttf"};
    private static final Font[] BASE = new Font[2];
    private static final boolean[] FAILED = new boolean[2];
    private static final Map<Integer, FontRenderer> CACHE = new HashMap<Integer, FontRenderer>();

    private SlinkyFonts() {
    }

    /** The font for a style and pixel size; null when Inter cannot be read. */
    static FontRenderer get(int style, float size) {
        int px = Math.max(6, Math.round(size));
        int key = style * 1000 + px;
        FontRenderer cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Font base = base(style);
        if (base == null) {
            return null;
        }
        FontRenderer made = new FontRenderer(base.deriveFont(Font.PLAIN, (float) px));
        CACHE.put(key, made);
        return made;
    }

    private static Font base(int style) {
        if (BASE[style] != null || FAILED[style]) {
            return BASE[style];
        }
        InputStream in = null;
        try {
            in = Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation("myau:font/" + FILES[style])).getInputStream();
            BASE[style] = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            FAILED[style] = true;
            System.err.println("[Atlas] Failed to load font: " + FILES[style]);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // Nothing further to do.
                }
            }
        }
        return BASE[style];
    }
}
