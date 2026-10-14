package myau.ui.impl.mainmenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Large, crisp text for the main menu (2026-10-12).
 *
 * The client's glyph atlases top out around 14 GUI pixels at high scale
 * factors, and scaling a small atlas up blurs it. A title is drawn instead
 * as one picture of the whole string, rendered by Java2D at the screen's
 * real pixel size (with letter spacing, which the atlases cannot do) and
 * kept as a texture. Tinted white, so one picture serves every colour.
 * The menu shows a dozen strings at most; the cache drops the oldest past
 * 64 and everything when the GUI scale changes.
 *
 * 2026-10-13 (menu hitch on opening): loading the fonts, starting Java2D's
 * text pipeline and rasterising every string all happened in the first
 * frame of the menu, on the render thread -- a visible freeze that also ate
 * the fade-in. Now the fonts are loaded and Java2D warmed up on a worker
 * thread while the game starts ({@link #warmUp()}), strings are rasterised
 * on that worker, and only the texture upload stays on the render thread.
 * A string not rasterised yet is simply not drawn; the menu waits for
 * {@link #ready()} and {@link #busy()} before it fades in.
 */
public final class TextImage {
    /** The faces the menu uses, warmed up at start. */
    private static final String[] MENU_FONTS = {"outfit-regular.ttf", "outfit-medium.ttf", "bebas-neue.ttf"};
    private static final Map<String, Font> FONTS = new ConcurrentHashMap<>();
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Atlas-TextImage");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });
    private static volatile Future<?> warm;
    private static int cachedScale;

    private static final class Entry {
        final int width;
        final int height;
        final float advance;
        final float ascent;
        /* Filled in by the worker; turned into the texture on the render thread. */
        volatile BufferedImage image;
        DynamicTexture texture;

        Entry(int width, int height, float advance, float ascent) {
            this.width = width;
            this.height = height;
            this.advance = advance;
            this.ascent = ascent;
        }
    }

    private TextImage() {
    }

    /** Loads the menu fonts and starts Java2D's text pipeline off the render thread. Safe to call more than once. */
    public static synchronized void warmUp() {
        if (warm != null) {
            return;
        }
        warm = WORKER.submit(() -> {
            for (String file : MENU_FONTS) {
                Font font = base(file);
                /* One small string drawn: the first TextLayout and the first
                   anti-aliased draw are what take the time. */
                BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                new TextLayout("ATLAS", font.deriveFont(16.0F), new FontRenderContext(null, true, true))
                        .draw(g, 2.0F, 20.0F);
                g.dispose();
            }
        });
    }

    /** The fonts are loaded (starts the warm-up if nothing did). */
    static boolean ready() {
        if (warm == null) {
            warmUp();
        }
        return warm.isDone();
    }

    /** Strings still being rasterised. */
    static boolean busy() {
        return PENDING.get() > 0;
    }

    private static Font base(String file) {
        Font font = FONTS.get(file);
        if (font == null) {
            /* From the jar directly: the resource manager is not for other threads. */
            try (InputStream in = TextImage.class.getResourceAsStream("/assets/myau/font/" + file)) {
                font = Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) {
                font = new Font("SansSerif", Font.PLAIN, 12);
            }
            FONTS.put(file, font);
        }
        return font;
    }

    private static int scale() {
        return new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor();
    }

    private static Entry entry(String text, String file, float size, float tracking) {
        int sf = scale();
        if (sf != cachedScale) {
            clear();
            cachedScale = sf;
        }
        int px = Math.max(4, Math.round(size * sf));
        String key = file + '|' + px + '|' + tracking + '|' + text;
        Entry entry = CACHE.get(key);
        if (entry != null) {
            return entry;
        }
        Map<TextAttribute, Object> attributes = new HashMap<>();
        attributes.put(TextAttribute.SIZE, (float) px);
        attributes.put(TextAttribute.TRACKING, tracking);
        Font font = base(file).deriveFont(attributes);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        TextLayout layout = new TextLayout(text.isEmpty() ? " " : text, font, frc);
        float ascent = layout.getAscent();
        float descent = layout.getDescent();
        float advance = layout.getAdvance();
        int pad = Math.max(2, px / 8);
        int w = (int) Math.ceil(advance) + pad * 2;
        int h = (int) Math.ceil(ascent + descent) + pad * 2;
        Entry created = new Entry(w, h, advance, ascent + pad);
        CACHE.put(key, created);
        PENDING.incrementAndGet();
        WORKER.execute(() -> {
            try {
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(java.awt.Color.WHITE);
                if (!text.isEmpty()) {
                    layout.draw(g, pad, pad + ascent);
                }
                g.dispose();
                created.image = image;
            } finally {
                PENDING.decrementAndGet();
            }
        });
        if (CACHE.size() > 64) {
            Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
            Entry old = it.next().getValue();
            if (old.texture != null) {
                old.texture.deleteGlTexture();
            }
            it.remove();
        }
        return created;
    }

    static void clear() {
        for (Entry entry : CACHE.values()) {
            if (entry.texture != null) {
                entry.texture.deleteGlTexture();
            }
        }
        CACHE.clear();
    }

    /** Width in GUI pixels, as drawn with {@code xScale}. */
    static float width(String text, String file, float size, float tracking, float xScale) {
        return entry(text, file, size, tracking).advance / scale() * xScale;
    }

    /**
     * Draws {@code text} with its top at {@code y}; align 0 left, 1 centre,
     * 2 right of {@code x}. {@code xScale} below 1 narrows the letters.
     * Returns the width drawn.
     */
    static float draw(String text, String file, float size, float tracking, float x, float y, int color,
                      int align, float xScale) {
        int alpha = color >>> 24;
        Entry e = entry(text, file, size, tracking);
        float sf = scale();
        float advance = e.advance / sf * xScale;
        if (e.texture == null) {
            BufferedImage image = e.image;
            if (image == null) {
                /* Still on the worker: drawn from the next frame on. */
                return advance;
            }
            e.texture = new DynamicTexture(image);
            e.image = null;
        }
        if (alpha <= 2) {
            return advance;
        }
        float left = align == 1 ? x - advance / 2.0F : align == 2 ? x - advance : x;
        int pad = (e.width - (int) Math.ceil(e.advance)) / 2;
        float dx = left - pad / sf * xScale;
        /* y is where the capitals start: the ascent above them is font
           leading, about a fifth of the size in these faces. */
        float dy = y - (e.ascent - size * sf * 0.74F) / sf;
        float dw = e.width / sf * xScale;
        float dh = e.height / sf;

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 771);
        GlStateManager.disableAlpha();
        GlStateManager.bindTexture(e.texture.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.color((color >> 16 & 255) / 255.0F, (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F,
                alpha / 255.0F);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_TEX);
        wr.pos(dx, dy + dh, 0.0D).tex(0.0D, 1.0D).endVertex();
        wr.pos(dx + dw, dy + dh, 0.0D).tex(1.0D, 1.0D).endVertex();
        wr.pos(dx + dw, dy, 0.0D).tex(1.0D, 0.0D).endVertex();
        wr.pos(dx, dy, 0.0D).tex(0.0D, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableAlpha();
        return advance;
    }
}
