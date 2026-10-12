package myau.ui.impl.clickgui.panel;

import myau.Myau;
import myau.config.Config;
import myau.module.Category;
import myau.module.Module;
import myau.module.ModuleCategories;
import myau.module.modules.ClickGUIModule;
import myau.property.Property;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ColorProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.KeyProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.property.properties.TextProperty;
import myau.util.RenderUtil;
import myau.util.font.FontManager;
import myau.util.font.impl.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Panel ClickGUI (2026-10-11, owner request; modelled on Yuri's menu): one
 * dark rounded panel per category side by side, an accent line under each
 * title, modules listed by name (accent when on) with a "+" that opens their
 * settings in place, and a Configs panel to load and save profiles.
 *
 * Every frame the panels are laid out and drawn in one pass, which also
 * records where each clickable thing is ({@link Hit}); clicks are matched
 * against the hits of the frame the player saw.
 *
 * Left click: toggle / set. Right click on a module: open its settings.
 * Right click on a title: fold the panel. Drag a title: move it. Wheel over
 * a panel: scroll it. Panel positions are kept in config/Myau/panelgui.txt.
 */
public class PanelClickGui extends GuiScreen {
    private static PanelClickGui instance;

    private static final float WIDTH = 108.0F;
    private static final float HEADER = 24.0F;
    private static final float MODULE_H = 18.0F;
    private static final float SETTING_H = 15.0F;
    private static final float SLIDER_H = 22.0F;
    private static final float RADIUS = 8.0F;
    /** A panel shows at most this many module rows, then scrolls. */
    private static final int MAX_ROWS = 16;
    private static final File STATE = new File("./config/Myau/panelgui.txt");

    private static final int BG = 0xC8121217;
    private static final int SETTINGS_BG = 0x40000000;
    private static final int TEXT = 0xFFE6E6EC;
    private static final int DIM = 0xFF8E8E9A;
    private static final int FAINT = 0xFF5E5E68;

    private enum Kind { HEADER, MODULE, EXPAND, BIND, BOOL, NUM, MODE, COLOR, TEXT, KEY, PROFILE, NAME, LOAD, SAVE }

    private static final class Hit {
        final Kind kind;
        final Panel panel;
        final Object target;
        final float x1, y1, x2, y2;
        /** Sliders: where the track starts and how wide it is. */
        float trackX, trackW;

        Hit(Kind kind, Panel panel, Object target, float x1, float y1, float x2, float y2) {
            this.kind = kind;
            this.panel = panel;
            this.target = target;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        boolean contains(float x, float y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }

    private static final class Panel {
        final String title;
        final List<Module> modules;
        final boolean configs;
        float x, y;
        boolean folded;
        float scroll;
        float maxScroll;
        /** The scroll drawn: eases toward {@link #scroll}. */
        float shownScroll;
        /* Geometry of this frame (measure). */
        float drawY, body, content, total;
        /** Height drawn last frame, for hit tests of the whole panel. */
        float shownHeight = HEADER;

        Panel(String title, List<Module> modules, boolean configs, float x, float y) {
            this.title = title;
            this.modules = modules;
            this.configs = configs;
            this.x = x;
            this.y = y;
        }

        boolean over(float mx, float my) {
            return mx >= x && mx <= x + WIDTH && my >= y && my <= y + shownHeight;
        }
    }

    private final List<Panel> panels = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Set<Module> expanded = new HashSet<>();

    private Panel dragging;
    private float dragX, dragY;
    private Hit slider;
    private Hit hue;

    private Module listeningModule;
    private KeyProperty listeningKey;
    private TextProperty editingText;
    private boolean editingName;
    private String buffer = "";

    private String configName = "";
    private List<String> configs = new ArrayList<>();
    private long configsRead;
    private String status = "";
    private long statusAt;

    private long openedAt;
    private long lastFrame;
    private float dt = 0.016F;
    /** Eased values: hover glows, on/off colours, opening settings. */
    private final Map<String, Float> anims = new HashMap<>();

    private PanelClickGui() {
        float x = 10.0F;
        Map<String, float[]> saved = readState();
        for (Category category : Category.values()) {
            if (category == Category.LEGIT) {
                continue;
            }
            List<Module> modules = new ArrayList<>(ModuleCategories.modulesIn(category, Myau.moduleManager.modules));
            if (modules.isEmpty()) {
                continue;
            }
            modules.sort(Comparator.comparing(m -> m.getName().toLowerCase()));
            this.panels.add(place(new Panel(category.displayName(), modules, false, x, 10.0F), saved));
            x += WIDTH + 6.0F;
        }
        this.panels.add(place(new Panel("Configs", Collections.emptyList(), true, x, 10.0F), saved));
    }

    private static Panel place(Panel panel, Map<String, float[]> saved) {
        float[] s = saved.get(panel.title);
        if (s != null) {
            panel.x = s[0];
            panel.y = s[1];
            panel.folded = s[2] != 0.0F;
        }
        return panel;
    }

    public static PanelClickGui getInstance() {
        if (instance == null) {
            instance = new PanelClickGui();
        }
        return instance;
    }

    @Override
    public void initGui() {
        super.initGui();
        FontManager.initializeFonts();
        Keyboard.enableRepeatEvents(true);
        this.openedAt = System.currentTimeMillis();
        this.configsRead = 0L;
        this.slider = null;
        this.hue = null;
        this.dragging = null;
        this.lastFrame = 0L;
        for (Panel panel : this.panels) {
            panel.shownScroll = panel.scroll;
        }
        /* Keep every panel's title on screen after a resize. */
        ScaledResolution sr = new ScaledResolution(mc);
        for (Panel panel : this.panels) {
            panel.x = Math.max(0.0F, Math.min(sr.getScaledWidth() - WIDTH, panel.x));
            panel.y = Math.max(0.0F, Math.min(sr.getScaledHeight() - HEADER, panel.y));
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- look ----------------------------------------------------------

    private static int accent() {
        Module module = Myau.moduleManager.getModule("ClickGUI");
        if (module instanceof ClickGUIModule) {
            return ((ClickGUIModule) module).getAccentColor().getRGB() | 0xFF000000;
        }
        return 0xFF9B5CFF;
    }

    private float fade;

    /** {@code color} with its alpha scaled by the opening fade. */
    private int a(int color) {
        int alpha = Math.round((color >>> 24) * this.fade);
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private static FontRenderer titleFont() {
        return FontManager.productSansMedium != null ? FontManager.productSansMedium : FontManager.productSans20;
    }

    private static FontRenderer rowFont() {
        return FontManager.productSans18;
    }

    private static FontRenderer smallFont() {
        return FontManager.productSans16;
    }

    private void text(FontRenderer font, String s, float x, float top, float height, int color) {
        if (font == null) {
            mc.fontRendererObj.drawString(s, x, top + (height - 8.0F) / 2.0F, a(color), false);
            return;
        }
        font.drawString(s, x, top + (height - font.getHeight()) / 2.0F + 1.0F, a(color));
    }

    private void textRight(FontRenderer font, String s, float right, float top, float height, int color) {
        text(font, s, right - width(font, s), top, height, color);
    }

    private static float width(FontRenderer font, String s) {
        return font == null ? net.minecraft.client.Minecraft.getMinecraft().fontRendererObj.getStringWidth(s) : (float) font.getStringWidth(s);
    }

    private static String trim(FontRenderer font, String s, float max) {
        if (width(font, s) <= max) {
            return s;
        }
        while (s.length() > 1 && width(font, s + "..") > max) {
            s = s.substring(0, s.length() - 1);
        }
        return s + "..";
    }

    // ---- drawing -------------------------------------------------------

    /** Eases {@code key} toward {@code target}; {@code speed} is per second. */
    private float ease(String key, float target, float speed) {
        Float now = this.anims.get(key);
        float v = now == null ? target : now;
        v += (target - v) * Math.min(1.0F, this.dt * speed);
        if (Math.abs(target - v) < 0.002F) {
            v = target;
        }
        this.anims.put(key, v);
        return v;
    }

    private float peek(String key, float fallback) {
        Float v = this.anims.get(key);
        return v == null ? fallback : v;
    }

    private static int mix(int from, int to, float t) {
        int a1 = from >>> 24, r1 = from >> 16 & 255, g1 = from >> 8 & 255, b1 = from & 255;
        int a2 = to >>> 24, r2 = to >> 16 & 255, g2 = to >> 8 & 255, b2 = to & 255;
        return Math.round(a1 + (a2 - a1) * t) << 24 | Math.round(r1 + (r2 - r1) * t) << 16
                | Math.round(g1 + (g2 - g1) * t) << 8 | Math.round(b1 + (b2 - b1) * t);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0L ? 0.016F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        long elapsed = System.currentTimeMillis() - this.openedAt;
        this.hits.clear();
        ScaledResolution sr = new ScaledResolution(mc);
        int accent = accent();

        if (this.dragging != null) {
            this.dragging.x = Math.max(0.0F, Math.min(sr.getScaledWidth() - WIDTH, mouseX - this.dragX));
            this.dragging.y = Math.max(0.0F, Math.min(sr.getScaledHeight() - HEADER, mouseY - this.dragY));
        }
        if (this.slider != null && Mouse.isButtonDown(0)) {
            setFromSlider(this.slider, mouseX);
        } else {
            this.slider = null;
        }
        if (this.hue != null && Mouse.isButtonDown(0)) {
            setHue(this.hue, mouseX);
        } else {
            this.hue = null;
        }

        /* The world dims, with a faint wash of the accent from below. */
        this.fade = Math.min(1.0F, elapsed / 180.0F);
        this.drawGradientRect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(),
                a(0x70000000), a(0x30000000 | accent & 0xFFFFFF));

        /* Each panel drops in a little after the one before it. */
        float[] shown = new float[this.panels.size()];
        for (int i = 0; i < this.panels.size(); i++) {
            Panel panel = this.panels.get(i);
            float t = Math.max(0.0F, Math.min(1.0F, (elapsed - i * 22.0F) / 240.0F));
            t = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            shown[i] = t;
            measure(panel, sr, (1.0F - t) * -10.0F);
        }

        /* Frosted glass: the world behind every panel is blurred first. */
        if (myau.module.modules.RenderFixes.shouldUseShaders()) {
            myau.util.shader.BlurUtils.prepareBlur();
            for (int i = 0; i < this.panels.size(); i++) {
                Panel panel = this.panels.get(i);
                if (shown[i] > 0.05F) {
                    myau.util.shader.RoundedUtils.drawRound(panel.x, panel.drawY, WIDTH, panel.total, RADIUS,
                            java.awt.Color.WHITE);
                }
            }
            myau.util.shader.BlurUtils.blurEnd(2, 3.0F);
        }

        for (int i = 0; i < this.panels.size(); i++) {
            this.fade = shown[i];
            if (this.fade > 0.02F) {
                drawPanel(this.panels.get(i), sr, mouseX, mouseY, accent);
            }
        }
        this.fade = Math.min(1.0F, elapsed / 180.0F);
        drawInfoBar(sr, accent);

        if (!this.status.isEmpty() && System.currentTimeMillis() - this.statusAt < 2500L) {
            FontRenderer font = rowFont();
            float w = width(font, this.status) + 20.0F;
            float x = (sr.getScaledWidth() - w) / 2.0F;
            float y = sr.getScaledHeight() - 34.0F;
            RenderUtil.drawRoundedRect(x, y, w, 18.0F, 6.0F, a(0xE0121217));
            RenderUtil.drawRect(x + 6.0F, y + 16.5F, x + w - 6.0F, y + 17.5F, a(accent));
            text(font, this.status, x + 10.0F, y, 18.0F, TEXT);
        }

        handleInvWalk();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Where the panel is this frame, how tall, and how far it scrolls. */
    private void measure(Panel panel, ScaledResolution sr, float offset) {
        panel.drawY = panel.y + offset;
        float content = panel.folded ? 0.0F : contentHeight(panel);
        float room = Math.max(0.0F, sr.getScaledHeight() - panel.y - HEADER - 8.0F);
        float cap = panel.configs ? room : MAX_ROWS * MODULE_H + 4.0F;
        float body = Math.min(content, Math.min(room, cap));
        panel.content = content;
        panel.body = ease("body:" + panel.title, body, 18.0F);
        panel.maxScroll = Math.max(0.0F, content - body);
        panel.scroll = Math.max(0.0F, Math.min(panel.maxScroll, panel.scroll));
        panel.shownScroll += (panel.scroll - panel.shownScroll) * Math.min(1.0F, this.dt * 16.0F);
        panel.shownScroll = Math.max(0.0F, Math.min(panel.maxScroll, panel.shownScroll));
        panel.total = HEADER + (panel.body > 0.5F ? panel.body + 4.0F : 0.0F);
        panel.shownHeight = panel.total;
    }

    private void drawPanel(Panel panel, ScaledResolution sr, float mx, float my, int accent) {
        float x = panel.x;
        float y = panel.drawY;
        float total = panel.total;
        float body = panel.body;

        myau.util.shader.ShadowShader.drawShadow(x, y, WIDTH, total, RADIUS, 10.0F, a(0x70000000));
        RenderUtil.drawRoundedRect(x, y, WIDTH, total, RADIUS, a(BG));
        /* A hairline rim catches the light along the top. */
        RenderUtil.drawRoundedRectOutline(x, y, WIDTH, total, RADIUS, 0.5F, a(0x1EFFFFFF), true, true, true, true);

        text(titleFont(), panel.title, x + 10.0F, y + 1.0F, HEADER - 4.0F, TEXT);
        if (!panel.folded) {
            String count = panel.configs ? String.valueOf(this.configs.size()) : String.valueOf(enabledIn(panel));
            textRight(smallFont(), count, x + WIDTH - 10.0F, y + 1.0F, HEADER - 4.0F, FAINT);
        }
        float line = ease("line:" + panel.title, panel.folded ? 0.0F : 1.0F, 14.0F);
        if (line > 0.01F) {
            float half = (WIDTH - 16.0F) / 2.0F * line;
            float cx = x + WIDTH / 2.0F;
            /* The accent line, with a soft glow under it. */
            RenderUtil.drawRect(cx - half, y + HEADER - 3.0F, cx + half, y + HEADER - 1.5F, a(accent));
            RenderUtil.drawRect(cx - half, y + HEADER - 1.5F, cx + half, y + HEADER + 1.0F, a(0x30000000 | accent & 0xFFFFFF));
        }
        this.hits.add(new Hit(Kind.HEADER, panel, null, x, y, x + WIDTH, y + HEADER));
        if (body <= 0.5F) {
            return;
        }

        float clipTop = y + HEADER + 1.0F;
        float clipBottom = y + HEADER + body + 2.0F;
        RenderUtil.scissor(x, clipTop, WIDTH, clipBottom - clipTop);
        float rowY = clipTop + 2.0F - panel.shownScroll;
        if (panel.configs) {
            drawConfigs(panel, x, rowY, clipTop, clipBottom, mx, my, accent);
        } else {
            for (Module module : panel.modules) {
                rowY = drawModule(panel, module, x, rowY, clipTop, clipBottom, mx, my, accent);
            }
        }
        RenderUtil.releaseScissor();

        if (panel.maxScroll > 0.5F) {
            /* Fades at the cut edges, so rows slide under instead of being chopped. */
            if (panel.shownScroll > 1.0F) {
                this.drawGradientRect((int) x + 2, (int) clipTop, (int) (x + WIDTH) - 2, (int) clipTop + 8,
                        a(0xB0121217), 0x00121217);
            }
            if (panel.shownScroll < panel.maxScroll - 1.0F) {
                this.drawGradientRect((int) x + 2, (int) clipBottom - 8, (int) (x + WIDTH) - 2, (int) clipBottom,
                        0x00121217, a(0xB0121217));
            }
            float track = body - 4.0F;
            float bar = Math.max(14.0F, track * body / panel.content);
            float barY = clipTop + 2.0F + (track - bar) * (panel.shownScroll / panel.maxScroll);
            RenderUtil.drawRoundedRect(x + WIDTH - 3.5F, barY, 2.0F, bar, 1.0F, a(0x50FFFFFF));
        }
    }

    private static int enabledIn(Panel panel) {
        int on = 0;
        for (Module module : panel.modules) {
            if (module.isEnabled()) {
                on++;
            }
        }
        return on;
    }

    /** A small bar at the bottom left: client, server, FPS and the time. */
    private void drawInfoBar(ScaledResolution sr, int accent) {
        FontRenderer font = smallFont();
        String server = mc.getCurrentServerData() == null || mc.isSingleplayer() ? "Singleplayer"
                : mc.getCurrentServerData().serverIP;
        String[] parts = {"Myau Atlas", server, net.minecraft.client.Minecraft.getDebugFPS() + " FPS",
                new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date())};
        float w = 12.0F;
        for (String part : parts) {
            w += width(font, part) + 12.0F;
        }
        float x = 6.0F;
        float y = sr.getScaledHeight() - 22.0F;
        RenderUtil.drawRoundedRect(x, y, w, 16.0F, 8.0F, a(0xD0121217));
        float cx = x + 10.0F;
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                RenderUtil.drawRoundedRect(cx - 7.5F, y + 6.5F, 3.0F, 3.0F, 1.5F, a(accent));
            }
            text(font, parts[i], cx, y, 16.0F, i == 0 ? accent : TEXT);
            cx += width(font, parts[i]) + 12.0F;
        }
    }

    private boolean visible(float top, float height, float clipTop, float clipBottom) {
        return top + height > clipTop && top < clipBottom;
    }

    private void addHit(Kind kind, Panel panel, Object target, float x1, float y1, float x2, float y2,
                        float clipTop, float clipBottom) {
        float top = Math.max(y1, clipTop);
        float bottom = Math.min(y2, clipBottom);
        if (bottom > top) {
            this.hits.add(new Hit(kind, panel, target, x1, top, x2, bottom));
        }
    }

    private float drawModule(Panel panel, Module module, float x, float rowY, float clipTop, float clipBottom,
                             float mx, float my, int accent) {
        String id = module.getName();
        boolean open = this.expanded.contains(module);
        float opened = ease("open:" + id, open ? 1.0F : 0.0F, 16.0F);
        if (visible(rowY, MODULE_H, clipTop, clipBottom)) {
            boolean hovered = mx >= x && mx <= x + WIDTH && my >= Math.max(rowY, clipTop)
                    && my <= Math.min(rowY + MODULE_H, clipBottom);
            float glow = ease("hv:" + id, hovered ? 1.0F : 0.0F, 14.0F);
            float on = ease("on:" + id, module.isEnabled() ? 1.0F : 0.0F, 12.0F);
            if (glow > 0.01F) {
                RenderUtil.drawRoundedRect(x + 4.0F, rowY + 1.0F, WIDTH - 8.0F, MODULE_H - 2.0F, 4.0F,
                        a(Math.round(0x16 * glow) << 24 | 0xFFFFFF));
            }
            if (on > 0.01F) {
                /* A short accent tick at the left of what is on. */
                float h = (MODULE_H - 8.0F) * on;
                RenderUtil.drawRoundedRect(x + 5.0F, rowY + MODULE_H / 2.0F - h / 2.0F, 2.0F, h, 1.0F, a(accent));
            }
            int colour = mix(TEXT, accent, on);
            text(rowFont(), trim(rowFont(), module.getName(), WIDTH - 32.0F), x + 11.0F + on * 1.5F, rowY, MODULE_H, colour);
            int sign = mix(FAINT, TEXT, Math.max(glow * 0.6F, opened));
            textRight(rowFont(), opened > 0.5F ? "-" : "+", x + WIDTH - 10.0F, rowY, MODULE_H, sign);
            addHit(Kind.MODULE, panel, module, x, rowY, x + WIDTH - 22.0F, rowY + MODULE_H, clipTop, clipBottom);
            addHit(Kind.EXPAND, panel, module, x + WIDTH - 22.0F, rowY, x + WIDTH, rowY + MODULE_H, clipTop, clipBottom);
        }
        rowY += MODULE_H;
        if (opened <= 0.01F) {
            return rowY;
        }

        List<Property<?>> settings = propertiesOf(module);
        float start = rowY;
        float full = settingsHeight(settings);
        float height = full * opened;
        /* The settings unroll: drawn inside their own clip, inside the panel's. */
        float innerTop = Math.max(clipTop, start);
        float innerBottom = Math.min(clipBottom, start + height);
        if (innerBottom <= innerTop) {
            return start + height + 2.0F * opened;
        }
        RenderUtil.scissor(x, innerTop, WIDTH, innerBottom - innerTop);
        RenderUtil.drawRoundedRect(x + 4.0F, start, WIDTH - 8.0F, full, 4.0F, a(SETTINGS_BG));
        RenderUtil.drawRect(x + 4.0F, start + 3.0F, x + 5.0F, start + full - 3.0F, a(0x90000000 | accent & 0xFFFFFF));
        clipTop = innerTop;
        clipBottom = innerBottom;
        float left = x + 11.0F;
        float right = x + WIDTH - 10.0F;

        /* The module's own key first. */
        if (visible(rowY, SETTING_H, clipTop, clipBottom)) {
            String key = this.listeningModule == module ? "..." : keyName(module.getKey());
            text(smallFont(), "Bind", left, rowY, SETTING_H, DIM);
            textRight(smallFont(), key, right, rowY, SETTING_H, this.listeningModule == module ? accent : TEXT);
            addHit(Kind.BIND, panel, module, x, rowY, x + WIDTH, rowY + SETTING_H, clipTop, clipBottom);
        }
        rowY += SETTING_H;

        for (Property<?> property : settings) {
            float h = rowHeight(property);
            if (visible(rowY, h, clipTop, clipBottom)) {
                drawSetting(panel, property, x, left, right, rowY, clipTop, clipBottom, mx, my, accent);
            }
            rowY += h;
        }
        /* Back to the panel's own clip. */
        Panel owner = panel;
        float panelTop = owner.drawY + HEADER + 1.0F;
        RenderUtil.scissor(x, panelTop, WIDTH, owner.body + 1.0F);
        return start + height + 2.0F * opened;
    }

    private void drawSetting(Panel panel, Property<?> property, float x, float left, float right, float rowY,
                             float clipTop, float clipBottom, float mx, float my, int accent) {
        FontRenderer font = smallFont();
        String label = property.getLabel();
        if (property instanceof BooleanProperty) {
            boolean on = ((BooleanProperty) property).getValue();
            text(font, trim(font, label, right - left - 20.0F), left, rowY, SETTING_H, on ? TEXT : DIM);
            float t = ease("sw:" + property.getName() + "@" + System.identityHashCode(property), on ? 1.0F : 0.0F, 14.0F);
            float sw = 14.0F;
            float sh = 7.0F;
            float bx = right - sw;
            float by = rowY + (SETTING_H - sh) / 2.0F;
            RenderUtil.drawRoundedRect(bx, by, sw, sh, sh / 2.0F, a(mix(0x30FFFFFF, accent, t)));
            RenderUtil.drawRoundedRect(bx + 1.0F + (sw - sh) * t, by + 1.0F, sh - 2.0F, sh - 2.0F, (sh - 2.0F) / 2.0F, a(0xFFFFFFFF));
            addHit(Kind.BOOL, panel, property, x, rowY, x + WIDTH, rowY + SETTING_H, clipTop, clipBottom);
        } else if (isNumeric(property)) {
            String value = formatNumber(property);
            text(font, trim(font, label, right - left - width(font, value) - 6.0F), left, rowY, 12.0F, DIM);
            textRight(font, value, right, rowY, 12.0F, TEXT);
            float min = minimumOf(property);
            float max = maximumOf(property);
            float t = max > min ? (currentOf(property) - min) / (max - min) : 0.0F;
            t = Math.max(0.0F, Math.min(1.0F, t));
            float trackY = rowY + 14.0F;
            RenderUtil.drawRect(left, trackY, right, trackY + 2.0F, a(0x30FFFFFF));
            RenderUtil.drawRect(left, trackY, left + (right - left) * t, trackY + 2.0F, a(accent));
            RenderUtil.drawRoundedRect(left + (right - left) * t - 2.0F, trackY - 1.0F, 4.0F, 4.0F, 2.0F, a(TEXT));
            Hit hit = new Hit(Kind.NUM, panel, property, x, Math.max(rowY, clipTop), x + WIDTH,
                    Math.min(rowY + SLIDER_H, clipBottom));
            hit.trackX = left;
            hit.trackW = right - left;
            if (hit.y2 > hit.y1) {
                this.hits.add(hit);
            }
        } else if (property instanceof ModeProperty) {
            String value = ((ModeProperty) property).getModeString();
            text(font, trim(font, label, right - left - width(font, value) - 6.0F), left, rowY, SETTING_H, DIM);
            textRight(font, value, right, rowY, SETTING_H, accent);
            addHit(Kind.MODE, panel, property, x, rowY, x + WIDTH, rowY + SETTING_H, clipTop, clipBottom);
        } else if (property instanceof ColorProperty) {
            int rgb = ((ColorProperty) property).getValue() & 0xFFFFFF;
            text(font, trim(font, label, right - left - 14.0F), left, rowY, 12.0F, DIM);
            RenderUtil.drawRoundedRect(right - 8.0F, rowY + 2.5F, 8.0F, 7.0F, 2.0F, a(0xFF000000 | rgb));
            /* A hue bar: drag along it to pick the colour. */
            float trackY = rowY + 13.5F;
            float w = right - left;
            int steps = 24;
            for (int i = 0; i < steps; i++) {
                int c = java.awt.Color.HSBtoRGB(i / (float) steps, 1.0F, 1.0F);
                RenderUtil.drawRect(left + w * i / steps, trackY, left + w * (i + 1) / steps, trackY + 3.0F, a(c | 0xFF000000));
            }
            float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
            RenderUtil.drawRect(left + w * hsb[0] - 0.5F, trackY - 1.0F, left + w * hsb[0] + 0.5F, trackY + 4.0F, a(TEXT));
            Hit hit = new Hit(Kind.COLOR, panel, property, x, Math.max(rowY, clipTop), x + WIDTH,
                    Math.min(rowY + SLIDER_H, clipBottom));
            hit.trackX = left;
            hit.trackW = w;
            if (hit.y2 > hit.y1) {
                this.hits.add(hit);
            }
        } else if (property instanceof TextProperty) {
            boolean editing = this.editingText == property;
            String value = editing ? this.buffer + (blink() ? "_" : "") : ((TextProperty) property).getValue();
            float labelW = Math.min(width(font, label), (right - left) * 0.45F);
            text(font, trim(font, label, labelW), left, rowY, SETTING_H, DIM);
            textRight(font, trim(font, value, right - left - labelW - 6.0F), right, rowY, SETTING_H, editing ? accent : TEXT);
            addHit(Kind.TEXT, panel, property, x, rowY, x + WIDTH, rowY + SETTING_H, clipTop, clipBottom);
        } else if (property instanceof KeyProperty) {
            String value = this.listeningKey == property ? "..." : keyName(((KeyProperty) property).getValue());
            text(font, trim(font, label, right - left - width(font, value) - 6.0F), left, rowY, SETTING_H, DIM);
            textRight(font, value, right, rowY, SETTING_H, this.listeningKey == property ? accent : TEXT);
            addHit(Kind.KEY, panel, property, x, rowY, x + WIDTH, rowY + SETTING_H, clipTop, clipBottom);
        } else {
            /* Lists, files and the like: shown, set from the Atlas menu or a command. */
            String value = String.valueOf(property.getValue());
            text(font, trim(font, label, (right - left) * 0.5F), left, rowY, SETTING_H, FAINT);
            textRight(font, trim(font, value, (right - left) * 0.45F), right, rowY, SETTING_H, FAINT);
        }
    }

    private void drawConfigs(Panel panel, float x, float rowY, float clipTop, float clipBottom,
                             float mx, float my, int accent) {
        refreshConfigs();
        String current = Config.lastConfig == null || "!".equals(Config.lastConfig) ? "default" : Config.lastConfig;
        for (String name : this.configs) {
            if (visible(rowY, MODULE_H, clipTop, clipBottom)) {
                boolean hovered = mx >= x && mx <= x + WIDTH && my >= Math.max(rowY, clipTop)
                        && my <= Math.min(rowY + MODULE_H, clipBottom);
                boolean picked = name.equalsIgnoreCase(this.configName);
                if (hovered || picked) {
                    RenderUtil.drawRect(x + 3.0F, rowY, x + WIDTH - 3.0F, rowY + MODULE_H, a(picked ? 0x22FFFFFF : 0x14FFFFFF));
                }
                int colour = name.equalsIgnoreCase(current) ? accent : TEXT;
                text(rowFont(), trim(rowFont(), name, WIDTH - 18.0F), x + 9.0F, rowY, MODULE_H, colour);
                addHit(Kind.PROFILE, panel, name, x, rowY, x + WIDTH, rowY + MODULE_H, clipTop, clipBottom);
            }
            rowY += MODULE_H;
        }
        rowY += 4.0F;

        FontRenderer font = smallFont();
        text(font, "Name", x + 9.0F, rowY, 10.0F, FAINT);
        rowY += 10.0F;
        RenderUtil.drawRoundedRect(x + 6.0F, rowY, WIDTH - 12.0F, MODULE_H, 4.0F,
                a(this.editingName ? 0x30FFFFFF : 0x1CFFFFFF));
        String shown = this.editingName ? this.buffer + (blink() ? "_" : "") : this.configName;
        text(rowFont(), trim(rowFont(), shown.isEmpty() && !this.editingName ? "..." : shown, WIDTH - 24.0F),
                x + 10.0F, rowY, MODULE_H, shown.isEmpty() ? FAINT : TEXT);
        addHit(Kind.NAME, panel, null, x, rowY, x + WIDTH, rowY + MODULE_H, clipTop, clipBottom);
        rowY += MODULE_H + 4.0F;

        drawButton(panel, Kind.LOAD, "Load", x, rowY, clipTop, clipBottom, mx, my);
        rowY += MODULE_H + 2.0F;
        drawButton(panel, Kind.SAVE, "Save", x, rowY, clipTop, clipBottom, mx, my);
    }

    private void drawButton(Panel panel, Kind kind, String label, float x, float rowY, float clipTop, float clipBottom,
                            float mx, float my) {
        boolean hovered = mx >= x + 6.0F && mx <= x + WIDTH - 6.0F && my >= rowY && my <= rowY + MODULE_H;
        RenderUtil.drawRoundedRect(x + 6.0F, rowY, WIDTH - 12.0F, MODULE_H, 4.0F, a(hovered ? 0x26FFFFFF : 0x12FFFFFF));
        text(rowFont(), label, x + 10.0F, rowY, MODULE_H, TEXT);
        addHit(kind, panel, null, x + 6.0F, rowY, x + WIDTH - 6.0F, rowY + MODULE_H, clipTop, clipBottom);
    }

    private boolean blink() {
        return System.currentTimeMillis() / 500L % 2L == 0L;
    }

    // ---- sizes ---------------------------------------------------------

    private float contentHeight(Panel panel) {
        if (panel.configs) {
            refreshConfigs();
            return this.configs.size() * MODULE_H + 4.0F + 10.0F + MODULE_H + 4.0F + MODULE_H * 2.0F + 2.0F + 2.0F;
        }
        float h = 2.0F;
        for (Module module : panel.modules) {
            h += MODULE_H;
            float opened = peek("open:" + module.getName(), this.expanded.contains(module) ? 1.0F : 0.0F);
            if (opened > 0.01F) {
                h += (settingsHeight(propertiesOf(module)) + 2.0F) * opened;
            }
        }
        return h;
    }

    private float settingsHeight(List<Property<?>> settings) {
        float h = SETTING_H;
        for (Property<?> property : settings) {
            h += rowHeight(property);
        }
        return h;
    }

    private static float rowHeight(Property<?> property) {
        return isNumeric(property) || property instanceof ColorProperty ? SLIDER_H : SETTING_H;
    }

    private static List<Property<?>> propertiesOf(Module module) {
        List<Property<?>> out = new ArrayList<>();
        for (Property<?> property : Myau.propertyManager.properties.getOrDefault(module.getClass(), new ArrayList<>())) {
            if (property.isVisible()) {
                out.add(property);
            }
        }
        return out;
    }

    private void refreshConfigs() {
        long now = System.currentTimeMillis();
        if (now - this.configsRead < 2000L) {
            return;
        }
        this.configsRead = now;
        List<String> out = new ArrayList<>();
        File[] files = new File("./config/Myau/").listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && name.toLowerCase().endsWith(".json")) {
                    out.add(name.substring(0, name.length() - 5));
                }
            }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        this.configs = out;
    }

    // ---- numbers -------------------------------------------------------

    private static boolean isNumeric(Property<?> property) {
        return property instanceof IntProperty || property instanceof FloatProperty || property instanceof PercentProperty;
    }

    private static boolean isIntegral(Property<?> property) {
        return property instanceof IntProperty || property instanceof PercentProperty;
    }

    private static float minimumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMinimum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMinimum();
        }
        return ((FloatProperty) property).getMinimum();
    }

    private static float maximumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMaximum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMaximum();
        }
        return ((FloatProperty) property).getMaximum();
    }

    private static float currentOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getValue();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getValue();
        }
        return ((FloatProperty) property).getValue();
    }

    private static String formatNumber(Property<?> property) {
        float value = currentOf(property);
        if (property instanceof PercentProperty) {
            return Math.round(value) + "%";
        }
        if (isIntegral(property)) {
            return String.valueOf(Math.round(value));
        }
        String s = String.format(java.util.Locale.ROOT, "%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void assign(Property<?> property, float value) {
        value = Math.max(minimumOf(property), Math.min(maximumOf(property), value));
        if (isIntegral(property)) {
            property.setBase(Property.Source.USER, Integer.valueOf(Math.round(value)));
        } else {
            property.setBase(Property.Source.USER, Float.valueOf(Math.round(value * 100.0F) / 100.0F));
        }
    }

    private void setFromSlider(Hit hit, float mx) {
        Property<?> property = (Property<?>) hit.target;
        float t = Math.max(0.0F, Math.min(1.0F, (mx - hit.trackX) / hit.trackW));
        float min = minimumOf(property);
        float max = maximumOf(property);
        assign(property, min + (max - min) * t);
    }

    private void setHue(Hit hit, float mx) {
        ColorProperty property = (ColorProperty) hit.target;
        int rgb = property.getValue() & 0xFFFFFF;
        float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
        float h = Math.max(0.0F, Math.min(0.999F, (mx - hit.trackX) / hit.trackW));
        float s = hsb[1] < 0.15F ? 1.0F : hsb[1];
        float b = hsb[2] < 0.15F ? 1.0F : hsb[2];
        property.setBase(Property.Source.USER, Integer.valueOf(java.awt.Color.HSBtoRGB(h, s, b) & 0xFFFFFF));
    }

    private static String keyName(int key) {
        if (key <= 0) {
            return "None";
        }
        String name = Keyboard.getKeyName(key);
        return name == null ? "#" + key : name;
    }

    private void say(String message) {
        this.status = message;
        this.statusAt = System.currentTimeMillis();
    }

    // ---- input ---------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        super.mouseClicked(mouseX, mouseY, button);
        Hit hit = null;
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            if (this.hits.get(i).contains(mouseX, mouseY)) {
                hit = this.hits.get(i);
                break;
            }
        }
        /* A click anywhere ends typing and key listening. */
        boolean wasEditing = this.editingText != null || this.editingName;
        if (wasEditing && (hit == null || hit.kind != Kind.TEXT && hit.kind != Kind.NAME)) {
            commitText();
        }
        if (hit == null) {
            this.listeningModule = null;
            this.listeningKey = null;
            return;
        }
        bringToFront(hit.panel);
        if (hit.kind != Kind.BIND) {
            this.listeningModule = null;
        }
        if (hit.kind != Kind.KEY) {
            this.listeningKey = null;
        }
        switch (hit.kind) {
            case HEADER:
                if (button == 0) {
                    this.dragging = hit.panel;
                    this.dragX = mouseX - hit.panel.x;
                    this.dragY = mouseY - hit.panel.y;
                } else if (button == 1) {
                    hit.panel.folded = !hit.panel.folded;
                }
                break;
            case MODULE:
                if (button == 0) {
                    ((Module) hit.target).toggle();
                } else if (button == 1) {
                    toggleOpen((Module) hit.target);
                }
                break;
            case EXPAND:
                toggleOpen((Module) hit.target);
                break;
            case BIND:
                if (button == 0) {
                    this.listeningModule = this.listeningModule == hit.target ? null : (Module) hit.target;
                } else if (button == 1) {
                    ((Module) hit.target).setKey(0);
                    this.listeningModule = null;
                }
                break;
            case BOOL: {
                BooleanProperty property = (BooleanProperty) hit.target;
                property.setBase(Property.Source.USER, Boolean.valueOf(!property.getValue()));
                break;
            }
            case NUM:
                if (button == 0) {
                    this.slider = hit;
                    setFromSlider(hit, mouseX);
                }
                break;
            case COLOR:
                if (button == 0) {
                    this.hue = hit;
                    setHue(hit, mouseX);
                }
                break;
            case MODE: {
                ModeProperty property = (ModeProperty) hit.target;
                int[] shown = property.visibleIndices();
                int at = 0;
                for (int i = 0; i < shown.length; i++) {
                    if (shown[i] == property.getValue()) {
                        at = i;
                    }
                }
                int next = button == 1 ? at - 1 : at + 1;
                next = (next % shown.length + shown.length) % shown.length;
                property.setBase(Property.Source.USER, Integer.valueOf(shown[next]));
                break;
            }
            case TEXT:
                if (this.editingText != hit.target) {
                    commitText();
                    this.editingText = (TextProperty) hit.target;
                    this.buffer = this.editingText.getValue();
                }
                break;
            case KEY:
                if (button == 0) {
                    this.listeningKey = this.listeningKey == hit.target ? null : (KeyProperty) hit.target;
                } else if (button == 1) {
                    ((KeyProperty) hit.target).setBase(Property.Source.USER, Integer.valueOf(0));
                    this.listeningKey = null;
                }
                break;
            case PROFILE:
                this.configName = (String) hit.target;
                break;
            case NAME:
                if (!this.editingName) {
                    commitText();
                    this.editingName = true;
                    this.buffer = this.configName;
                }
                break;
            case LOAD:
                loadConfig();
                break;
            case SAVE:
                saveConfig();
                break;
            default:
                break;
        }
    }

    private void toggleOpen(Module module) {
        if (!this.expanded.remove(module)) {
            this.expanded.add(module);
        }
    }

    private void bringToFront(Panel panel) {
        if (panel != null && this.panels.remove(panel)) {
            this.panels.add(panel);
        }
    }

    private void loadConfig() {
        String name = this.configName.trim();
        if (name.isEmpty()) {
            say("Pick a config first");
            return;
        }
        if (!new File("./config/Myau/" + name + ".json").isFile()) {
            say("No config named " + name);
            return;
        }
        new Config(name, false).load();
        say("Loaded " + name);
    }

    private void saveConfig() {
        String name = this.configName.trim().replaceAll("[\\\\/:*?\"<>|]", "");
        if (name.isEmpty()) {
            say("Type a name first");
            return;
        }
        new Config(name, true).save();
        this.configName = name;
        this.configsRead = 0L;
        say("Saved " + name);
    }

    private void commitText() {
        if (this.editingText != null) {
            this.editingText.setBase(Property.Source.USER, this.buffer);
        } else if (this.editingName) {
            this.configName = this.buffer.trim();
        }
        this.editingText = null;
        this.editingName = false;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (this.dragging != null) {
            this.dragging = null;
            writeState();
        }
        this.slider = null;
        this.hue = null;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        float mx = Mouse.getEventX() * sr.getScaledWidth() / (float) mc.displayWidth;
        float my = sr.getScaledHeight() - Mouse.getEventY() * sr.getScaledHeight() / (float) mc.displayHeight - 1;
        for (int i = this.panels.size() - 1; i >= 0; i--) {
            Panel panel = this.panels.get(i);
            if (panel.over(mx, my)) {
                panel.scroll = Math.max(0.0F, Math.min(panel.maxScroll, panel.scroll + (wheel > 0 ? -MODULE_H * 3 : MODULE_H * 3)));
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typed, int key) throws IOException {
        if (this.listeningModule != null) {
            this.listeningModule.setKey(key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_DELETE
                    || key == Keyboard.KEY_BACK ? 0 : key);
            this.listeningModule = null;
            return;
        }
        if (this.listeningKey != null) {
            this.listeningKey.setBase(Property.Source.USER, Integer.valueOf(key == Keyboard.KEY_ESCAPE
                    || key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK ? 0 : key));
            this.listeningKey = null;
            return;
        }
        if (this.editingText != null || this.editingName) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                commitText();
            } else if (key == Keyboard.KEY_ESCAPE) {
                this.editingText = null;
                this.editingName = false;
            } else if (key == Keyboard.KEY_BACK) {
                if (!this.buffer.isEmpty()) {
                    this.buffer = this.buffer.substring(0, this.buffer.length() - 1);
                }
            } else if (GuiScreen.isKeyComboCtrlV(key)) {
                String clip = GuiScreen.getClipboardString();
                if (clip != null) {
                    this.buffer += clip.replaceAll("[\\r\\n]", "");
                }
            } else if (typed >= ' ' && typed != 127 && this.buffer.length() < 64) {
                this.buffer += typed;
            }
            return;
        }
        Module gui = Myau.moduleManager.getModule("ClickGUI");
        if (key == Keyboard.KEY_ESCAPE || gui != null && key == gui.getKey()
                && System.currentTimeMillis() - this.openedAt > 150L) {
            mc.displayGuiScreen(null);
        }
    }

    private void handleInvWalk() {
        Module invWalk = Myau.moduleManager.getModule("InvWalk");
        if (invWalk == null || !invWalk.isEnabled() || this.editingText != null || this.editingName) {
            return;
        }
        KeyBinding[] keys = {
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSprint,
                mc.gameSettings.keyBindSneak
        };
        for (KeyBinding key : keys) {
            KeyBinding.setKeyBindState(key.getKeyCode(), Keyboard.isKeyDown(key.getKeyCode()));
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        commitText();
        this.listeningModule = null;
        this.listeningKey = null;
        writeState();
        Module gui = Myau.moduleManager.getModule("ClickGUI");
        if (gui instanceof ClickGUIModule && ((ClickGUIModule) gui).isSwitchingGuiStyle()) {
            return;
        }
        if (gui != null) {
            gui.setEnabled(false);
        }
    }

    // ---- where the panels are -----------------------------------------

    private static Map<String, float[]> readState() {
        Map<String, float[]> out = new HashMap<>();
        try {
            if (!STATE.isFile()) {
                return out;
            }
            List<String> lines = Files.readAllLines(STATE.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals("#w" + (int) WIDTH)) {
                return out;
            }
            for (String line : lines) {
                String[] parts = line.split("\t");
                if (parts.length == 4) {
                    out.put(parts[0], new float[]{Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeState() {
        try (PrintWriter writer = new PrintWriter(STATE, "UTF-8")) {
            writer.println("#w" + (int) WIDTH);
            for (Panel panel : this.panels) {
                writer.println(panel.title + "\t" + panel.x + "\t" + panel.y + "\t" + (panel.folded ? 1 : 0));
            }
        } catch (Exception ignored) {
        }
    }
}
