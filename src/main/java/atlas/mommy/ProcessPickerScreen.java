package atlas.mommy;

import atlas.util.RenderUtil;
import atlas.util.font.FontManager;
import atlas.util.font.impl.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProcessPickerScreen extends GuiScreen {
    private static final int ACCENT = 0x7C9CFF;
    private static final float ROW = 18.0F;
    private static final float BTN = 18.0F;

    private final GuiScreen parent;
    private final PanicSetup setup = PanicSetup.get();

    private volatile Map<String, String> found;
    private volatile Map<String, String> background;
    private final List<String> chosen = new ArrayList<String>();
    private boolean showBackground;
    private GuiTextField filter;
    private float scroll;
    private float contentH;
    private float viewTop;
    private float viewBottom;
    private final List<float[]> hitBoxes = new ArrayList<float[]>();
    private final List<Runnable> hitRuns = new ArrayList<Runnable>();
    private final long openedAt = System.currentTimeMillis();

    public ProcessPickerScreen(GuiScreen parent) {
        this.parent = parent;
        this.chosen.addAll(this.setup.close);
        Thread t = new Thread(() -> {
            Map<String, String> m = new LinkedHashMap<String, String>();
            for (String[] p : PanicPlatform.openPrograms()) {
                String key = p[0];
                if (!m.containsKey(key) || m.get(key).isEmpty()) {
                    m.put(key, p[1]);
                }
            }
            this.found = m;
            Map<String, String> all = new LinkedHashMap<String, String>();
            for (String n : PanicPlatform.allProcessNames()) {
                if (!m.containsKey(n)) {
                    all.put(n, "");
                }
            }
            this.background = all;
        }, "Atlas mommy processes");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (this.filter == null) {
            this.filter = new GuiTextField(0, this.fontRendererObj, 0, 0, 200, 12);
            this.filter.setMaxStringLength(60);
            this.filter.setEnableBackgroundDrawing(false);
            this.filter.setFocused(true);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        this.filter.updateCursorCounter();
    }

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        this.hitBoxes.clear();
        this.hitRuns.clear();
        float t = Math.min(1.0F, (System.currentTimeMillis() - this.openedAt) / 200.0F);
        float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        FontRenderer title = FontManager.productSans20;
        FontRenderer body = FontManager.productSans16;
        boolean ttf = title != null && body != null;

        float cardW = Math.min(400.0F, this.width - 24.0F);
        float cardH = Math.min(this.height - 24.0F, 340.0F);
        float cx = (this.width - cardW) / 2.0F;
        float cy = (this.height - cardH) / 2.0F + (1.0F - ease) * 8.0F;
        float pad = 14.0F;
        float w = cardW - pad * 2.0F;
        float x = cx + pad;

        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        RenderUtil.drawRect(0, 0, this.width, this.height, alpha(0x05060A, 0.62F * ease));
        RenderUtil.drawRoundedRect(cx, cy, cardW, cardH, 10.0F, alpha(0x11131A, 0.97F * ease));
        RenderUtil.drawRoundedRectOutline(cx, cy, cardW, cardH, 10.0F, 1.0F, alpha(0xFFFFFF, 0.08F), true, true, true, true);
        RenderUtil.drawRoundedRect(x, cy + 8.0F, 26.0F, 2.0F, 1.0F, alpha(ACCENT, ease));
        text(title, "Programs to close", x, cy + pad, 0xFFF5F6FA, ttf);
        float y = cy + pad + (ttf ? (float) title.getHeight() : 10.0F) + 6.0F;
        text(body, "Ticked ones close when MOMMY IS HERE!!! goes off. " + this.chosen.size() + " ticked.", x, y,
                0xFF8A91A6, ttf);
        y += (ttf ? (float) body.getHeight() : 9.0F) + 8.0F;

        float fieldW = w - 128.0F;
        RenderUtil.drawRoundedRect(x, y, fieldW, BTN, 5.0F, alpha(0x000000, 0.35F));
        RenderUtil.drawRoundedRectOutline(x, y, fieldW, BTN, 5.0F, 1.0F, alpha(ACCENT, 0.35F), true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        this.filter.width = (int) fieldW - 10;
        this.filter.xPosition = (int) x + 5;
        this.filter.yPosition = (int) (y + 5.0F);
        if (this.filter.getText().isEmpty()) {
            text(body, "Search…", x + 6.0F, y + 4.0F, 0xFF5D6478, ttf);
        }
        this.filter.drawTextBox();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        this.button(body, ttf, this.showBackground ? "Background: shown" : "Background too", x + fieldW + 6.0F, y,
                122.0F, this.showBackground, mx, my, false, () -> {
                    this.showBackground = !this.showBackground;
                    this.scroll = 0.0F;
                });
        y += BTN + 8.0F;

        this.viewTop = y;
        this.viewBottom = cy + cardH - pad - BTN - 8.0F;
        RenderUtil.scissor(cx, this.viewTop, cardW, this.viewBottom - this.viewTop);
        float ly = y - this.scroll;
        String q = this.filter.getText().trim().toLowerCase(Locale.ROOT);
        Map<String, String> rows = new LinkedHashMap<String, String>();
        for (String n : this.chosen) {
            rows.put(n, "");
        }
        Map<String, String> f = this.found;
        if (f != null) {
            rows.putAll(f);
        }
        Map<String, String> b = this.background;
        if (this.showBackground && b != null) {
            for (Map.Entry<String, String> e : b.entrySet()) {
                if (!rows.containsKey(e.getKey())) {
                    rows.put(e.getKey(), e.getValue());
                }
            }
        }
        if (f == null) {
            text(body, "Looking at what is running…", x, ly, 0xFF8A91A6, ttf);
            ly += ROW;
        }
        for (final Map.Entry<String, String> e : rows.entrySet()) {
            String name = e.getKey();
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)
                    && !e.getValue().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            boolean on = this.chosen.contains(name);
            boolean over = mx >= x && mx <= x + w && my >= ly && my <= ly + ROW - 2.0F
                    && my >= this.viewTop && my <= this.viewBottom;
            RenderUtil.drawRoundedRect(x, ly, w, ROW - 2.0F, 5.0F, alpha(on ? ACCENT : 0xFFFFFF,
                    on ? (over ? 0.30F : 0.22F) : (over ? 0.09F : 0.04F)));
            RenderUtil.drawRoundedRect(x + 5.0F, ly + 4.0F, 8.0F, 8.0F, 2.0F, on ? alpha(ACCENT, 1.0F) : alpha(0xFFFFFF, 0.18F));
            if (on) {
                text(body, "✓", x + 5.5F, ly + 2.5F, 0xFFFFFFFF, ttf);
            }
            text(body, clip(body, name, 150.0F, ttf), x + 20.0F, ly + 4.0F, 0xFFE6E8F0, ttf);
            String sub = e.getValue().isEmpty() && f != null && !f.containsKey(name) ? "background" : e.getValue();
            if (!sub.isEmpty()) {
                text(body, clip(body, sub, w - 180.0F, ttf), x + 175.0F, ly + 4.0F, 0xFF8A91A6, ttf);
            }
            this.hit(x, ly, w, ROW - 2.0F, true, () -> {
                if (this.chosen.contains(name)) {
                    this.chosen.remove(name);
                } else {
                    this.chosen.add(name);
                }
            });
            ly += ROW;
        }
        RenderUtil.releaseScissor();
        this.contentH = ly + this.scroll - y;

        float by = cy + cardH - pad - BTN;

        this.button(body, ttf, this.setup.closeForce ? "Force close: on" : "Force close: off", x, by, 110.0F,
                this.setup.closeForce, mx, my, false, () -> this.setup.closeForce = !this.setup.closeForce);
        this.button(body, ttf, "Cancel", cx + cardW - pad - 140.0F, by, 64.0F, false, mx, my, false,
                () -> this.mc.displayGuiScreen(this.parent));
        this.button(body, ttf, "Save", cx + cardW - pad - 70.0F, by, 70.0F, true, mx, my, false, this::save);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    private void save() {
        this.setup.close.clear();
        this.setup.close.addAll(this.chosen);
        this.setup.saveQuietly();
        this.mc.displayGuiScreen(this.parent);
    }

    private void button(FontRenderer body, boolean ttf, String label, float x, float y, float w, boolean primary,
                        int mx, int my, boolean clipped, Runnable run) {
        boolean over = mx >= x && mx <= x + w && my >= y && my <= y + BTN;
        RenderUtil.drawRoundedRect(x, y, w, BTN, 5.0F, primary ? alpha(ACCENT, over ? 1.0F : 0.85F)
                : alpha(0xFFFFFF, over ? 0.13F : 0.07F));
        double tw = width(body, label, ttf);
        double th = ttf ? body.getHeight() : 8.0;
        text(body, label, (float) (x + (w - tw) / 2.0), (float) (y + (BTN - th) / 2.0 + (ttf ? 1.0 : 0.0)),
                0xFFFFFFFF, ttf);
        this.hit(x, y, w, BTN, clipped, run);
    }

    private void hit(float x, float y, float w, float h, boolean clipped, Runnable run) {
        this.hitBoxes.add(new float[]{x, y, w, h, clipped ? 1.0F : 0.0F});
        this.hitRuns.add(run);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (button != 0) {
            return;
        }
        for (int i = this.hitBoxes.size() - 1; i >= 0; i--) {
            float[] b = this.hitBoxes.get(i);
            if (b[4] > 0.0F && (my < this.viewTop || my > this.viewBottom)) {
                continue;
            }
            if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
                this.hitRuns.get(i).run();
                return;
            }
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d != 0) {
            float max = Math.max(0.0F, this.contentH - (this.viewBottom - this.viewTop));
            this.scroll = Math.max(0.0F, Math.min(max, this.scroll - Math.signum(d) * 24.0F));
        }
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            this.save();
            return;
        }
        this.filter.textboxKeyTyped(typed, key);
        this.scroll = 0.0F;
    }

    private static double width(FontRenderer font, String s, boolean ttf) {
        return ttf ? font.getStringWidth(s) : net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.getStringWidth(s);
    }

    private static String clip(FontRenderer font, String s, float max, boolean ttf) {
        if (width(font, s, ttf) <= max) {
            return s;
        }
        String t = s;
        while (t.length() > 1 && width(font, t + "…", ttf) > max) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }

    private static void text(FontRenderer font, String s, float x, float y, int colour, boolean ttf) {
        if (ttf) {
            font.drawString(s, x, y, colour, false);
        } else {
            net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.drawString(s, (int) x, (int) y, colour);
        }
    }

    private static int alpha(int rgb, float a) {
        return (Math.max(8, Math.min(255, Math.round(a * 255.0F))) << 24) | (rgb & 0xFFFFFF);
    }
}
