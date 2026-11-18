package atlas.ui.impl.clickgui.slinky;

import atlas.util.font.impl.FontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// Inter is under the SIL Open Font License

final class SlinkyFonts {
    private static final String[] FILES = {"Inter-Medium.ttf", "Inter-SemiBold.ttf"};
    private static final Font[] BASE = new Font[2];
    private static final boolean[] FAILED = new boolean[2];
    private static final Map<Integer, FontRenderer> CACHE = new HashMap<Integer, FontRenderer>();

    private SlinkyFonts() {
    }

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
                    .getResource(new ResourceLocation("atlas:font/" + FILES[style])).getInputStream();
            BASE[style] = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            FAILED[style] = true;
            System.err.println("[Atlas] Failed to load font: " + FILES[style]);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return BASE[style];
    }
}
