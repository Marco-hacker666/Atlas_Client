package atlas.ui.impl.mainmenu;

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

public final class TextImage {
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

    public static synchronized void warmUp() {
        if (warm != null) {
            return;
        }
        warm = WORKER.submit(() -> {
            for (String file : MENU_FONTS) {
                Font font = base(file);

                BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = image.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                new TextLayout("ATLAS", font.deriveFont(16.0F), new FontRenderContext(null, true, true))
                        .draw(g, 2.0F, 20.0F);
                g.dispose();
            }
        });
    }

    static boolean ready() {
        if (warm == null) {
            warmUp();
        }
        return warm.isDone();
    }

    static boolean busy() {
        return PENDING.get() > 0;
    }

    private static Font base(String file) {
        Font font = FONTS.get(file);
        if (font == null) {
            try (InputStream in = TextImage.class.getResourceAsStream("/assets/atlas/font/" + file)) {
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

    static float width(String text, String file, float size, float tracking, float xScale) {
        return entry(text, file, size, tracking).advance / scale() * xScale;
    }

    static float draw(String text, String file, float size, float tracking, float x, float y, int color,
                      int align, float xScale) {
        int alpha = color >>> 24;
        Entry e = entry(text, file, size, tracking);
        float sf = scale();
        float advance = e.advance / sf * xScale;
        if (e.texture == null) {
            BufferedImage image = e.image;
            if (image == null) {
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
