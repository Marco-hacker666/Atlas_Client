package atlas.ui.impl.clickgui.atlas;

import atlas.Atlas;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;
import atlas.module.modules.HUD;
import atlas.module.modules.HUDStyle;
import atlas.module.modules.Theme;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Theme window (2026-11-09): everything about how the client looks, in a
 * window of its own that pops out over the menu.
 *
 * Before this the looks were spread over four places -- the menu's
 * Appearance page, the Theme tab's fifteen modules, the HUD module's colour
 * settings and HUDStyle -- each edited as a list of settings with no way to
 * see the result without closing the menu. Here they are in six sections
 * with a live preview beside them (a module list, a target card, a
 * notification and a piece of the menu, all drawn with the real settings),
 * and the whole look can be saved under a name and put back in one click
 * (ThemeFiles).
 *
 * The window edits the same Property objects the menus do, so a change here
 * is a change everywhere, saved the way each setting is always saved: the
 * menu's in atlas-theme.json, the modules' in the config. Right-click a
 * setting to put it back to its default.
 */
public final class ThemeStudio extends GuiScreen {

    private static final String[] SECTIONS = {"Saved", "Colours", "Menu", "Motion", "HUD", "World"};
    private static final String[] ICONS = {"folder", "colors", "glass", "motion", "layout", "world"};
    private static final String[] BLURBS = {
            "Save the whole look under a name and switch between looks in one click.",
            "The menu accent, the glass colour, the HUD's colours and the colour every themed render follows.",
            "How the menu's glass blurs, bends and lights up, how far it floats, and the space behind it.",
            "How things move, the typeface, and how tightly the menu is packed.",
            "One look for every HUD panel, and the module list's own style.",
            "Players, tracers, chests, items and the rest: each group on or off, and its colours."};

    /** Quick colours offered under every colour picker. */
    private static final int[] SWATCHES = {0xCCFF00, 0x4FC3F7, 0x5FFFC1, 0xA78BFA, 0xFF6B9D,
            0xFFC857, 0xFF8A65, 0xFFFFFF, 0x9AA3B2, 0x101216};

    /** The HUD module's colour settings (Colours section) and the rest of its look (HUD section). */
    private static final String[] HUD_COLOURS = {"color", "color-speed", "color-saturation", "color-brightness",
            "custom-color-1", "custom-color-2", "custom-color-3", "color-dist"};
    private static final String[] HUD_LIST = {"hud-theme", "font-mode", "background", "bg-alpha", "rounded",
            "corner-radius", "padding", "bar", "sidebar-mode", "shadow", "suffixes", "separator-mode", "lower-case",
            "blur", "blur-radius", "Shaders", "glow", "glow-color", "glow-custom-color", "glow-alpha", "glow-radius"};

    private static final float ROW = 22.0F;
    private static final float HEAD = 20.0F;
    private static final float PICKER = 92.0F;
    private static final float HEADER = 40.0F;
    private static final float FOOTER = 20.0F;
    private static final float SIDEBAR = 118.0F;
    private static final float PREVIEW = 168.0F;
    private static final float RADIUS = 14.0F;

    /** One line of a section: a heading (optionally with a module's switch), a setting, or a note. */
    private static final class Row {
        final String heading;
        final Module module;
        final String group;
        final Property<?> property;
        final String note;

        private Row(String heading, Module module, String group, Property<?> property, String note) {
            this.heading = heading;
            this.module = module;
            this.group = group;
            this.property = property;
            this.note = note;
        }

        static Row heading(String text) {
            return new Row(text, null, null, null, null);
        }

        static Row module(String text, Module module, String group) {
            return new Row(text, module, group, null, null);
        }

        static Row setting(Property<?> property) {
            return new Row(null, null, null, property, null);
        }

        static Row note(String text) {
            return new Row(null, null, null, null, text);
        }
    }

    private final AtlasClickGui gui;
    /** Where Esc goes: the menu that opened the window, or null for the game (ThemeGUI's key). */
    private final GuiScreen back;
    private final AtlasTheme theme;
    private final UiContext ui = new UiContext();
    /* The window pops from 94% on a spring that overshoots a little, fading in over its first fifth of a second. */
    private final Spring open = new Spring(0.94F, 300.0F, 22.0F);
    private long lastFrame;
    private float delta = 0.016F;
    private float openedFor;
    private boolean closing;
    private float viewScale = 1.0F;
    private float pivotX;
    private float pivotY;

    private int section = 1;
    private final float[] scroll = new float[SECTIONS.length];
    private float maxScroll;
    private float listTop;
    private float listBottom;
    private float listLeft;
    private float listRight;
    /** World groups whose settings are showing. */
    private final Set<String> expanded = new HashSet<String>();

    /* What a held button is dragging. */
    private Property<?> sliding;
    private float slideLeft;
    private float slideWidth;
    private ColorProperty picking;
    private String pickPart;
    private float pickX;
    private float pickY;
    private float pickW;
    private float pickH;
    /** The open colour picker, and the colour it is showing as hue, saturation, brightness. */
    private ColorProperty picker;
    private final float[] hsb = new float[3];
    /** How far the open picker has unfolded this frame, read once a frame from its spring. */
    private float pickerHeight;
    /** The open list of a choice with many options. */
    private ModeProperty dropdown;
    private float dropX;
    private float dropY;
    private float dropW;

    /* Typing: a colour's hex, or a new theme's name. */
    private final TextInput hexInput = new TextInput(9, false);
    private ColorProperty hexFor;
    private final TextInput nameInput = new TextInput(ThemeFiles.MAX_NAME, false);
    private boolean naming;

    /** The look before the last load or preset, for Undo; and what it undoes. */
    private JsonObject undo;
    private String undoLabel;
    /** A theme whose Delete was clicked once: the second click within three seconds deletes it. */
    private String armedDelete;
    private long armedAt;

    private String toast;
    private boolean toastBad;
    private long toastAt;
    private String hint = "";

    private List<String> savedThemes = new ArrayList<String>();
    private long savedReadAt;

    public ThemeStudio(AtlasClickGui gui) {
        this(gui, gui);
    }

    /** Opened on its own (ThemeGUI): {@code back} is the screen to return to, null for the game. */
    public ThemeStudio(AtlasClickGui gui, GuiScreen back) {
        this.gui = gui;
        this.back = back;
        this.theme = gui.theme();
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        this.lastFrame = 0L;
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        this.theme.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- small helpers ----------------------------------------------------

    private static LiquidFont font(float size, boolean bold) {
        return LiquidFont.of(Math.round(size * AtlasClickGui.textScale() * 4.0F) / 4.0F, bold);
    }

    private static int alpha(int colour, float factor) {
        int a = Math.round(((colour >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, factor)));
        return (a << 24) | (colour & 0x00FFFFFF);
    }

    private static int ink(int alpha) {
        return (alpha << 24) | (AtlasStyle.ink & 0x00FFFFFF);
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private int accent() {
        return this.gui.accentColour();
    }

    private void hit(String kind, Object payload, float x, float y, float x2, float y2) {
        this.ui.hit(kind, payload, x, y, x2, y2);
    }

    private float ease(Object key, boolean on, float rate) {
        return this.ui.ease(key, on, rate);
    }

    private static boolean inside(float mx, float my, float x, float y, float x2, float y2) {
        return mx >= x && mx <= x2 && my >= y && my <= y2;
    }

    private float layoutX(float screenX) {
        return this.pivotX + (screenX - this.pivotX) / this.viewScale;
    }

    private float layoutY(float screenY) {
        return this.pivotY + (screenY - this.pivotY) / this.viewScale;
    }

    private void tell(String message, boolean bad) {
        this.toast = message;
        this.toastBad = bad;
        this.toastAt = System.currentTimeMillis();
    }

    private static Module module(Class<? extends Module> type) {
        return Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(type);
    }

    private static Property<?> propertyOf(Module module, String name) {
        if (module == null) {
            return null;
        }
        for (Property<?> property : ThemeFiles.propertiesOf(module)) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    // ---- numbers, whatever class they are ------------------------------------

    private static boolean isNumeric(Property<?> p) {
        return p instanceof IntProperty || p instanceof FloatProperty || p instanceof PercentProperty;
    }

    private static float minimumOf(Property<?> p) {
        if (p instanceof IntProperty) {
            return ((IntProperty) p).getMinimum();
        }
        if (p instanceof PercentProperty) {
            return ((PercentProperty) p).getMinimum();
        }
        return ((FloatProperty) p).getMinimum();
    }

    private static float maximumOf(Property<?> p) {
        if (p instanceof IntProperty) {
            return ((IntProperty) p).getMaximum();
        }
        if (p instanceof PercentProperty) {
            return ((PercentProperty) p).getMaximum();
        }
        return ((FloatProperty) p).getMaximum();
    }

    private static float valueOf(Property<?> p) {
        Object value = p.getBaseValue();
        return value instanceof Number ? ((Number) value).floatValue() : 0.0F;
    }

    private static void assign(Property<?> p, float value) {
        value = Math.max(minimumOf(p), Math.min(maximumOf(p), value));
        if (p instanceof FloatProperty) {
            p.setValue(Float.valueOf(Math.round(value * 100.0F) / 100.0F));
        } else {
            p.setValue(Integer.valueOf(Math.round(value)));
        }
    }

    private static String format(Property<?> p) {
        float value = valueOf(p);
        if (p instanceof PercentProperty) {
            return Math.round(value) + "%";
        }
        if (p instanceof FloatProperty) {
            return String.format(java.util.Locale.ROOT, Math.abs(value) >= 10.0F ? "%.1f" : "%.2f", value);
        }
        return String.valueOf(Math.round(value));
    }

    // ---- what each section lists ------------------------------------------------

    private List<Row> rows(int index) {
        List<Row> rows = new ArrayList<Row>();
        switch (index) {
            case 1: {
                rows.add(Row.heading("Menu accent"));
                add(rows, this.theme.mode, this.theme.accent, this.theme.accentHue, this.theme.accentSaturation,
                        this.theme.accentEverywhere);
                rows.add(Row.heading("Glass colour"));
                add(rows, this.theme.tint, this.theme.tintHue, this.theme.opacity);
                Module hud = module(HUD.class);
                rows.add(Row.heading("HUD colours"));
                for (String name : HUD_COLOURS) {
                    add(rows, propertyOf(hud, name));
                }
                if (HudPanel.active()) {
                    /* 2026-11-14: the owner tuned these and the module list stayed white. */
                    rows.add(Row.note("The JELLO HUD look draws the module list in white, so these colours show only "
                            + "where something follows the HUD colour. HUD section > hud-theme CLASSIC colours the list."));
                }
                Module master = module(Theme.class);
                if (master != null) {
                    rows.add(Row.module("Themed renders", master, null));
                    rows.add(Row.note("The colour ESP, tracers, chests and the other World groups follow."));
                    for (Property<?> property : ThemeFiles.propertiesOf(master)) {
                        add(rows, property);
                    }
                }
                break;
            }
            case 2:
                group(rows, "Glass");
                group(rows, "Depth");
                group(rows, "Selection");
                group(rows, "Cosmos");
                break;
            case 3:
                group(rows, "Motion");
                group(rows, "Text");
                group(rows, "Layout");
                break;
            case 4: {
                Module style = module(HUDStyle.class);
                if (style != null) {
                    rows.add(Row.module("HUD panels", style, null));
                    for (Property<?> property : ThemeFiles.propertiesOf(style)) {
                        add(rows, property);
                    }
                }
                Module hud = module(HUD.class);
                rows.add(Row.heading("Module list"));
                for (String name : HUD_LIST) {
                    add(rows, propertyOf(hud, name));
                }
                rows.add(Row.note("Positions and sizes live in the HUD editor (button above)."));
                break;
            }
            case 5:
                for (Class<? extends Module> type : ModuleCategories.typesIn(Category.THEME)) {
                    Module group = module(type);
                    if (group == null || type == Theme.class || type == HUDStyle.class) {
                        continue;
                    }
                    String name = group.getName();
                    String shown = name.endsWith("Colors") ? name.substring(0, name.length() - 6) : name;
                    rows.add(Row.module(shown, group, name));
                    if (this.expanded.contains(name)) {
                        for (Property<?> property : ThemeFiles.propertiesOf(group)) {
                            add(rows, property);
                        }
                    }
                }
                break;
            default:
                break;
        }
        return rows;
    }

    private void group(List<Row> rows, String name) {
        List<Property<?>> properties = this.theme.groups.get(name);
        if (properties == null) {
            return;
        }
        rows.add(Row.heading(name));
        for (Property<?> property : properties) {
            add(rows, property);
        }
    }

    private static void add(List<Row> rows, Property<?>... properties) {
        for (Property<?> property : properties) {
            if (property != null && property.isVisible() && isEditable(property)) {
                rows.add(Row.setting(property));
            }
        }
    }

    private static boolean isEditable(Property<?> p) {
        return p instanceof BooleanProperty || p instanceof ModeProperty || p instanceof ColorProperty || isNumeric(p);
    }

    private float heightOf(Row row) {
        if (row.heading != null) {
            return HEAD + 4.0F;
        }
        if (row.note != null) {
            return 14.0F;
        }
        return row.property != null && row.property == this.picker ? ROW + this.pickerHeight : ROW;
    }

    // ---- frame ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(this.mc);
        long now = System.nanoTime();
        this.delta = this.lastFrame == 0L ? 0.016F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        this.openedFor += this.delta;
        this.ui.delta = this.delta;
        this.ui.beginFrame();
        this.hint = "";
        this.gui.applyLook();
        this.ui.animations = this.theme.animations.getValue();
        this.ui.smoothScroll = this.theme.smoothScroll.getValue();
        this.ui.springSpeed = this.ui.animations ? this.theme.springSpeed() : 1.0F;
        this.pickerHeight = this.picker == null ? 0.0F
                : Math.max(0.0F, this.ui.spring("pickerOpen", PICKER, 420.0F, 32.0F).value);
        Liquid.beginFrame();

        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        float width = Math.min(660.0F, sw - 24.0F);
        float height = Math.min(390.0F, sh - 24.0F);
        float x = Math.round((sw - width) / 2.0F);
        float y = Math.round((sh - height) / 2.0F);

        this.open.target = this.closing ? 0.94F : 1.0F;
        float progress = this.ui.animations ? this.open.step(this.delta * this.ui.springSpeed) : this.open.target;
        float fade = this.closing ? clamp01(1.0F - this.openedFor / 0.14F)
                : this.ui.animations ? clamp01(this.openedFor / 0.18F) : 1.0F;
        if (this.closing && fade <= 0.0F) {
            this.mc.displayGuiScreen(this.back);
            return;
        }
        fade = fade * fade * (3.0F - 2.0F * fade);
        this.viewScale = progress;
        this.pivotX = x + width / 2.0F;
        this.pivotY = y + height / 2.0F;
        float mx = layoutX(mouseX);
        float my = layoutY(mouseY);
        boolean follow = this.theme.followCursor.getValue();
        Liquid.lightX = this.ui.toward("lightX", follow ? mouseX : x + width * 0.25F, 7.0F);
        Liquid.lightY = this.ui.toward("lightY", follow ? mouseY : y - 120.0F, 7.0F);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Liquid.alpha = fade;
        LiquidFont.alpha = fade;
        GL11.glPushMatrix();
        try {
            float dim = this.theme.dim.getValue() / 100.0F;
            Liquid.rect(0, 0, sw, sh, 0.0F, alpha(0xFF050810, Math.min(1.0F, dim + 0.18F)),
                    alpha(0xFF050810, Math.min(1.0F, dim * 1.25F + 0.2F)));

            GL11.glTranslatef(this.pivotX, this.pivotY, 0.0F);
            GL11.glScalef(this.viewScale, this.viewScale, 1.0F);
            GL11.glTranslatef(-this.pivotX, -this.pivotY, 0.0F);
            Liquid.setView(this.viewScale, this.pivotX * (1.0F - this.viewScale), this.pivotY * (1.0F - this.viewScale));

            int shade = Math.max(this.theme.shadow.getValue(), 40);
            Liquid.shadow(x, y, x + width, y + height, RADIUS, Math.max(18.0F, this.theme.shadowSize.getValue()),
                    alpha(0xFF000000, shade / 100.0F), 8.0F);
            Liquid.pane(x, y, x + width, y + height, RADIUS, this.theme.pane());
            Liquid.rect(x, y, x + width, y + 70, RADIUS, 0x14FFFFFF, 0x00FFFFFF);
            /* Clicks outside the window do nothing but close an open list. */
            hit("window", null, x, y, x + width, y + height);

            drawHeader(x, y, width, mx, my);
            float bodyTop = y + HEADER;
            float bodyBottom = y + height - FOOTER;
            Liquid.rect(x + 14, bodyTop - 0.5F, x + width - 14, bodyTop, 0.0F, ink(0x12));
            drawSidebar(x, bodyTop, bodyBottom, mx, my);
            float contentLeft = x + SIDEBAR;
            float contentRight = x + width - PREVIEW;
            Liquid.rect(contentLeft, bodyTop + 10, contentLeft + 0.5F, bodyBottom - 6, 0.0F, ink(0x0F));
            Liquid.rect(contentRight, bodyTop + 10, contentRight + 0.5F, bodyBottom - 6, 0.0F, ink(0x0F));
            drawContent(sr, contentLeft, bodyTop, contentRight, bodyBottom, mx, my);
            drawPreview(contentRight, bodyTop, x + width, bodyBottom);
            drawFooter(x, y + height - FOOTER, width);
            drawToast(x, y, width, height);
            if (this.dropdown != null) {
                drawDropdown(mx, my);
            }
        } finally {
            GL11.glPopMatrix();
            Liquid.setView(1.0F, 0.0F, 0.0F);
            Liquid.alpha = 1.0F;
            LiquidFont.alpha = 1.0F;
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        followDrag(mx, my);
        this.theme.autosave();
        GlStateManager.disableBlend();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawHeader(float x, float y, float width, float mx, float my) {
        float cy = y + HEADER / 2.0F;
        int accent = accent();
        Liquid.dot(x + 22, cy, 7.5F, alpha(accent, 0.22F));
        AtlasClickGui.icon("palette", x + 22, cy, accent);
        font(12.5F, true).draw("Theme", x + 36, cy - 4.5F, AtlasStyle.text);

        float right = x + width - 12;
        float closeX = right - 20;
        boolean overClose = inside(mx, my, closeX, cy - 10, closeX + 20, cy + 10);
        float lit = ease("close", overClose, AtlasStyle.HOVER_RATE);
        Liquid.dot(closeX + 10, cy, 9.0F, alpha(AtlasStyle.danger, 0.12F + 0.5F * lit));
        Liquid.line(closeX + 6.5F, cy - 3.5F, closeX + 13.5F, cy + 3.5F, 1.3F, AtlasStyle.text);
        Liquid.line(closeX + 13.5F, cy - 3.5F, closeX + 6.5F, cy + 3.5F, 1.3F, AtlasStyle.text);
        hit("close", null, closeX, cy - 10, closeX + 20, cy + 10);
        if (overClose) {
            this.hint = (this.back == null ? "Back to the game" : "Back to the menu") + "  ·  Esc";
        }

        float edge = closeX - 8;
        edge = button("hudEditor", null, "HUD editor", edge, cy, mx, my, false);
        if (this.undo != null) {
            edge = button("undo", null, "Undo " + this.undoLabel, edge - 6, cy, mx, my, true);
        }
        /* What the open section is for, as far as it fits before the buttons. */
        LiquidFont blurb = font(7.5F, false);
        blurb.draw(blurb.trim(BLURBS[this.section], Math.max(0.0F, edge - 10 - (x + 36))), x + 36, cy + 6.0F,
                AtlasStyle.faint);
    }

    /** A pill button ending at {@code right}; returns its left edge. */
    private float button(String kind, Object payload, String label, float right, float cy, float mx, float my,
                         boolean strong) {
        LiquidFont bold = font(8.0F, true);
        float w = bold.width(label) + 20;
        float x = right - w;
        boolean over = inside(mx, my, x, cy - 9, right, cy + 9);
        float lit = ease("btn:" + kind + payload, over, AtlasStyle.HOVER_RATE);
        float press = this.ui.pulseAt("btn:" + kind + payload, 260L);
        float squash = press >= 0.0F ? (float) Math.sin(press * Math.PI) * 1.2F : 0.0F;
        int colour = strong ? accent() : 0xFFFFFFFF;
        float fill = strong ? 0.28F + 0.14F * lit : 0.07F + 0.06F * lit;
        Liquid.rect(x + squash, cy - 9 + squash * 0.5F, right - squash, cy + 9 - squash * 0.5F, 9.0F,
                alpha(colour, fill), alpha(colour, fill * 0.7F));
        Liquid.rim(x + squash, cy - 9 + squash * 0.5F, right - squash, cy + 9 - squash * 0.5F, 9.0F, 1.0F,
                alpha(colour, strong ? 0.6F : 0.18F), ink(0x08));
        bold.drawCentred(label, x + w / 2.0F, cy, AtlasStyle.text);
        hit(kind, payload, x, cy - 9, right, cy + 9);
        return x;
    }

    private void drawSidebar(float x, float top, float bottom, float mx, float my) {
        float rowH = 26.0F;
        float y = top + 10;
        int accent = accent();
        float pillY = this.ui.spring("sectionPill", y + this.section * (rowH + 2), 380.0F, 26.0F).value;
        Liquid.rect(x + 10, pillY, x + SIDEBAR - 10, pillY + rowH, 10.0F, alpha(accent, 0.24F), alpha(accent, 0.14F));
        Liquid.rim(x + 10, pillY, x + SIDEBAR - 10, pillY + rowH, 10.0F, 1.0F, alpha(accent, 0.55F), alpha(accent, 0.2F));
        for (int i = 0; i < SECTIONS.length; i++) {
            float ry = y + i * (rowH + 2);
            boolean active = i == this.section;
            boolean over = inside(mx, my, x + 10, ry, x + SIDEBAR - 10, ry + rowH);
            float lit = ease("section:" + i, over && !active, AtlasStyle.HOVER_RATE);
            if (lit > 0.01F) {
                Liquid.rect(x + 10, ry, x + SIDEBAR - 10, ry + rowH, 10.0F, ink(Math.round(0x10 * lit)));
            }
            float cy = ry + rowH / 2.0F;
            int colour = active ? AtlasStyle.text : AtlasStyle.dim;
            AtlasClickGui.icon(ICONS[i], x + 25, cy, active ? accent : colour);
            font(9.0F, active).draw(SECTIONS[i], x + 37, cy, colour);
            hit("section", Integer.valueOf(i), x + 10, ry, x + SIDEBAR - 10, ry + rowH);
        }
        /* The quick starts, under the sections: the menu's own presets. */
        float py = y + SECTIONS.length * (rowH + 2) + 14;
        if (py + 18 < bottom) {
            font(7.0F, true).drawTracked("START FROM", x + 16, py, AtlasStyle.faint, 0.6F);
            py += 10;
            for (String preset : AtlasTheme.PRESETS) {
                if (py + 16 > bottom) {
                    break;
                }
                boolean over = inside(mx, my, x + 10, py, x + SIDEBAR - 10, py + 16);
                float lit = ease("preset:" + preset, over, AtlasStyle.HOVER_RATE);
                if (lit > 0.01F) {
                    Liquid.rect(x + 10, py, x + SIDEBAR - 10, py + 16, 8.0F, ink(Math.round(0x10 * lit)));
                }
                font(8.0F, false).draw(preset, x + 18, py + 8, over ? AtlasStyle.text : AtlasStyle.dim);
                hit("preset", preset, x + 10, py, x + SIDEBAR - 10, py + 16);
                if (over) {
                    this.hint = "Start from the " + preset + " menu look  ·  Undo puts yours back";
                }
                py += 17;
            }
        }
    }

    // ---- the settings list --------------------------------------------------------

    private void drawContent(ScaledResolution sr, float left, float top, float right, float bottom, float mx,
                             float my) {
        this.listLeft = left;
        this.listRight = right;
        this.listTop = top + 4;
        this.listBottom = bottom;
        /* A section arrives sliding up a little as it fades in. */
        float arrive = this.ui.toward("arrive", 1.0F, 9.0F);
        float lift = (1.0F - arrive) * 10.0F;
        float saved = Liquid.alpha;
        Liquid.alpha = saved * arrive;
        LiquidFont.alpha = saved * arrive;
        clip(sr, left, this.listTop, right - left, bottom - this.listTop);
        try {
            if (this.section == 0) {
                drawSaved(left + 14, this.listTop + 6 + lift, right - 14, mx, my);
            } else {
                drawRows(left + 14, this.listTop + 4 + lift, right - 14, mx, my);
            }
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            Liquid.alpha = saved;
            LiquidFont.alpha = saved;
        }
        if (this.maxScroll > 0.0F) {
            float view = bottom - this.listTop;
            float total = view + this.maxScroll;
            float barH = Math.max(18.0F, view * view / total);
            float barY = this.listTop + (view - barH) * (this.scroll[this.section] / this.maxScroll);
            Liquid.rect(right - 5, barY, right - 3, barY + barH, 1.0F, ink(0x30));
        }
    }

    private void drawRows(float left, float top, float right, float mx, float my) {
        List<Row> rows = rows(this.section);
        float total = 0.0F;
        for (Row row : rows) {
            total += heightOf(row);
        }
        this.maxScroll = Math.max(0.0F, total + 10.0F - (this.listBottom - this.listTop));
        this.scroll[this.section] = Math.max(0.0F, Math.min(this.maxScroll, this.scroll[this.section]));
        float offset = this.ui.scrollEase("scroll:" + this.section, this.scroll[this.section]);
        float y = top - offset;
        boolean pointerIn = inside(mx, my, this.listLeft, this.listTop, this.listRight, this.listBottom);
        for (Row row : rows) {
            float h = heightOf(row);
            if (y + h >= this.listTop - 4 && y <= this.listBottom + 4) {
                if (row.heading != null) {
                    drawHeading(row, left, y, right, pointerIn ? mx : -1, my);
                } else if (row.note != null) {
                    font(7.5F, false).draw(row.note, left, y + 6, AtlasStyle.faint);
                } else {
                    drawSetting(row.property, left, y, right, pointerIn ? mx : -1, my);
                }
            }
            y += h;
        }
    }

    private void drawHeading(Row row, float left, float y, float right, float mx, float my) {
        float cy = y + 4 + HEAD / 2.0F;
        if (row.module == null) {
            font(7.0F, true).drawTracked(row.heading.toUpperCase(java.util.Locale.ROOT), left, cy,
                    alpha(accent(), 0.85F), 0.6F);
            Liquid.rect(left, y + HEAD + 2, right, y + HEAD + 2.5F, 0.0F, ink(0x0C));
            return;
        }
        boolean on = row.module.isEnabled();
        boolean collapsible = row.group != null;
        boolean over = inside(mx, my, left - 6, y + 2, right + 6, y + HEAD + 4);
        float lit = ease("head:" + row.heading, over, AtlasStyle.HOVER_RATE);
        Liquid.rect(left - 6, y + 2, right + 6, y + HEAD + 4, 8.0F, ink(Math.round(0x08 + 0x0C * lit)));
        float tx = left;
        if (collapsible) {
            boolean open = this.expanded.contains(row.group);
            AtlasClickGui.icon(open ? "chevronup" : "chevron", left + 4, cy, AtlasStyle.dim);
            tx += 12;
            hit("expand", row.group, left - 6, y + 2, right - 36, y + HEAD + 4);
        }
        font(9.0F, true).draw(row.heading, tx, cy, on ? AtlasStyle.text : AtlasStyle.dim);
        drawSwitch("mod:" + row.module.getName(), right - 26, cy, on);
        hit("module", row.module, right - 34, y + 2, right + 6, y + HEAD + 4);
        if (over) {
            this.hint = (on ? "On" : "Off") + "  ·  " + (collapsible ? "click to show its colours, the switch turns it "
                    + (on ? "off" : "on") : "the switch turns it " + (on ? "off" : "on"));
        }
    }

    private void drawSetting(Property<?> p, float left, float y, float right, float mx, float my) {
        float cy = y + ROW / 2.0F;
        boolean over = inside(mx, my, left - 6, y, right + 6, y + ROW);
        float lit = ease(p, over, AtlasStyle.HOVER_RATE);
        if (lit > 0.01F) {
            Liquid.rect(left - 6, y + 1, right + 6, y + ROW - 1, 8.0F, ink(Math.round(0x0E * lit)));
        }
        hit("row", p, left - 6, y, right + 6, y + ROW);
        LiquidFont label = font(8.5F, false);
        float controlLeft = left + (right - left) * 0.48F;
        String text = label.trim(p.getLabel(), controlLeft - left - 6);
        label.draw(text, left, cy, AtlasStyle.text);
        if (over) {
            String help = p.getHelp();
            this.hint = (help != null && !help.isEmpty() ? help + "  ·  " : "") + "right-click resets";
        }
        if (p instanceof BooleanProperty) {
            boolean on = ((BooleanProperty) p).getValue();
            drawSwitch(p, right - 26, cy, on);
            hit("switch", p, controlLeft, y, right + 6, y + ROW);
        } else if (isNumeric(p)) {
            drawSlider(p, controlLeft, cy, right, mx, my);
        } else if (p instanceof ModeProperty) {
            drawChoice((ModeProperty) p, controlLeft, y, cy, right, mx, my);
        } else if (p instanceof ColorProperty) {
            drawColour((ColorProperty) p, controlLeft, y, cy, right, mx, my);
        }
    }

    private void drawSwitch(Object key, float x, float cy, boolean on) {
        int accent = accent();
        String id = key instanceof String ? (String) key : "p" + System.identityHashCode(key);
        float knob = this.ui.spring("switch:" + id, on ? 1.0F : 0.0F, 420.0F, 24.0F).value;
        float w = 24.0F;
        float h = 13.0F;
        int track = on ? alpha(accent, 0.9F) : ink(0x26);
        Liquid.rect(x, cy - h / 2, x + w, cy + h / 2, h / 2, track, on ? alpha(accent, 0.75F) : ink(0x1C));
        float kx = x + 6.5F + (w - 13.0F) * knob;
        /* The knob stretches a little while it travels. */
        float stretch = 1.0F + 0.35F * (float) Math.sin(clamp01(knob) * Math.PI);
        Liquid.rect(kx - 4.5F * stretch, cy - 4.5F, kx + 4.5F * stretch, cy + 4.5F, 4.5F,
                on ? 0xFF0B0C0E : 0xFFE6E9EF);
    }

    private void drawSlider(Property<?> p, float left, float cy, float right, float mx, float my) {
        int accent = accent();
        LiquidFont small = font(8.0F, true);
        String value = format(p);
        float valueW = Math.max(small.width(value), small.width("0.00")) + 4;
        float trackLeft = left;
        float trackRight = right - valueW - 6;
        float min = minimumOf(p);
        float max = maximumOf(p);
        float t = max > min ? clamp01((valueOf(p) - min) / (max - min)) : 0.0F;
        float shown = this.ui.spring("slide:" + System.identityHashCode(p), t, 500.0F, 34.0F).value;
        boolean dragging = this.sliding == p;
        boolean over = inside(mx, my, trackLeft - 4, cy - 8, trackRight + 4, cy + 8);
        float grow = ease("grow:" + System.identityHashCode(p), dragging || over, 14.0F);
        float h = 3.0F + 1.5F * grow;
        Liquid.rect(trackLeft, cy - h / 2, trackRight, cy + h / 2, h / 2, ink(0x22));
        float fillX = trackLeft + (trackRight - trackLeft) * clamp01(shown);
        Liquid.rectH(trackLeft, cy - h / 2, Math.max(trackLeft + h, fillX), cy + h / 2, h / 2,
                alpha(accent, 0.7F), accent);
        float knob = 4.5F + 1.5F * grow;
        if (dragging) {
            Liquid.dot(fillX, cy, knob + 4.0F, alpha(accent, 0.22F));
        }
        Liquid.dot(fillX, cy, knob, 0xFFF4F6FA);
        Liquid.ring(fillX, cy, knob, 1.6F, accent);
        small.drawRight(value, right, cy, dragging ? accent : AtlasStyle.text);
        hit("slider", p, trackLeft - 6, cy - 9, trackRight + 6, cy + 9);
        if (over || dragging) {
            this.slideLeftFor(p, trackLeft, trackRight);
        }
    }

    /** Where a slider's track is, for a drag that starts on it. */
    private void slideLeftFor(Property<?> p, float trackLeft, float trackRight) {
        if (this.sliding == null || this.sliding == p) {
            this.slideLeft = trackLeft;
            this.slideWidth = Math.max(1.0F, trackRight - trackLeft);
        }
    }

    private void drawChoice(ModeProperty p, float left, float y, float cy, float right, float mx, float my) {
        String[] modes = p.visibleModes();
        int[] indices = p.visibleIndices();
        int current = -1;
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] == p.getValue()) {
                current = i;
            }
        }
        LiquidFont small = font(7.5F, true);
        float total = 0.0F;
        for (String mode : modes) {
            total += small.width(mode) + 12;
        }
        int accent = accent();
        if (modes.length <= 4 && total <= right - left) {
            /* Few options: all of them side by side, the chosen one under a sliding pill. */
            float x = right - total;
            Liquid.rect(x - 2, cy - 8, right + 2, cy + 8, 8.0F, ink(0x14));
            float pillX = x;
            float pillW = 0.0F;
            float cursor = x;
            for (int i = 0; i < modes.length; i++) {
                float w = small.width(modes[i]) + 12;
                if (i == current) {
                    pillX = cursor;
                    pillW = w;
                }
                cursor += w;
            }
            String key = "seg:" + System.identityHashCode(p);
            float px = this.ui.spring(key + "x", pillX, 420.0F, 28.0F).value;
            float pw = this.ui.spring(key + "w", pillW, 420.0F, 28.0F).value;
            if (current >= 0) {
                Liquid.rect(px, cy - 6.5F, px + pw, cy + 6.5F, 6.5F, accent);
            }
            cursor = x;
            for (int i = 0; i < modes.length; i++) {
                float w = small.width(modes[i]) + 12;
                boolean chosen = i == current;
                boolean over = inside(mx, my, cursor, cy - 8, cursor + w, cy + 8);
                small.drawCentred(modes[i], cursor + w / 2.0F, cy,
                        chosen ? 0xFF0B0C0E : over ? AtlasStyle.text : AtlasStyle.dim);
                hit("seg", new Object[]{p, Integer.valueOf(i)}, cursor, cy - 8, cursor + w, cy + 8);
                cursor += w;
            }
            return;
        }
        /* Many options: the chosen one, and a list that opens under it. */
        LiquidFont bold = font(8.0F, true);
        String shown = p.getModeString();
        float w = Math.min(right - left, Math.max(70.0F, bold.width(shown) + 26));
        float x = right - w;
        boolean open = this.dropdown == p;
        boolean over = inside(mx, my, x, cy - 8, right, cy + 8);
        float lit = ease("drop:" + System.identityHashCode(p), over || open, AtlasStyle.HOVER_RATE);
        Liquid.rect(x, cy - 8, right, cy + 8, 8.0F, ink(Math.round(0x14 + 0x10 * lit)));
        if (open) {
            Liquid.rim(x, cy - 8, right, cy + 8, 8.0F, 1.0F, alpha(accent, 0.8F), alpha(accent, 0.4F));
            this.dropX = x;
            this.dropY = cy + 10;
            this.dropW = Math.max(w, 96.0F);
        }
        bold.draw(bold.trim(shown, w - 24), x + 8, cy, AtlasStyle.text);
        AtlasClickGui.icon(open ? "chevronup" : "chevron", right - 9, cy, AtlasStyle.dim);
        hit("drop", p, x, cy - 8, right, cy + 8);
    }

    private void drawDropdown(float mx, float my) {
        ModeProperty p = this.dropdown;
        String[] modes = p.visibleModes();
        int[] indices = p.visibleIndices();
        float itemH = 15.0F;
        float open = this.ui.spring("dropOpen", 1.0F, 420.0F, 30.0F).value;
        float h = (modes.length * itemH + 6) * clamp01(open);
        float x = this.dropX;
        float x2 = x + this.dropW;
        float y = this.dropY;
        float bottomLimit = this.listBottom - 4;
        if (y + modes.length * itemH + 6 > bottomLimit) {
            /* No room below: it opens upward from the row instead. */
            y = Math.max(this.listTop, this.dropY - 20 - modes.length * itemH - 6);
        }
        Liquid.shadow(x, y, x2, y + h, 9.0F, 10.0F, 0x66000000, 2.0F);
        Liquid.rect(x, y, x2, y + h, 9.0F, 0xF21A1C22);
        Liquid.rim(x, y, x2, y + h, 9.0F, 1.0F, ink(0x26), ink(0x0C));
        hit("dropPanel", null, x, y, x2, y + h);
        LiquidFont small = font(8.0F, false);
        int accent = accent();
        for (int i = 0; i < modes.length; i++) {
            float iy = y + 3 + i * itemH;
            if (iy + itemH > y + h) {
                break;
            }
            boolean chosen = indices[i] == p.getValue();
            boolean over = inside(mx, my, x + 3, iy, x2 - 3, iy + itemH);
            if (chosen || over) {
                Liquid.rect(x + 3, iy, x2 - 3, iy + itemH, 6.0F, chosen ? alpha(accent, 0.22F) : ink(0x12));
            }
            small.draw(modes[i], x + 10, iy + itemH / 2.0F, chosen ? accent : AtlasStyle.text);
            if (chosen) {
                AtlasClickGui.icon("check", x2 - 12, iy + itemH / 2.0F, accent);
            }
            hit("dropItem", new Object[]{p, Integer.valueOf(i)}, x + 3, iy, x2 - 3, iy + itemH);
        }
    }

    private void drawColour(ColorProperty p, float left, float y, float cy, float right, float mx, float my) {
        int rgb = p.getValue() & 0x00FFFFFF;
        boolean open = this.picker == p;
        LiquidFont small = font(8.0F, true);
        String hex = "#" + ThemeColours.hex(rgb);
        float swatchX = right - 22;
        Liquid.shadow(swatchX, cy - 6, right, cy + 6, 4.0F, 4.0F, alpha(opaque(rgb), 0.45F), 0.0F);
        Liquid.rect(swatchX, cy - 6, right, cy + 6, 4.0F, opaque(rgb));
        Liquid.rim(swatchX, cy - 6, right, cy + 6, 4.0F, 1.0F, ink(0x40), ink(0x20));
        if (this.hexFor == p) {
            String typed = "#" + this.hexInput.text();
            float tw = small.width(typed);
            float fx = swatchX - 8 - Math.max(tw, small.width("#000000")) - 8;
            Liquid.rect(fx, cy - 7.5F, swatchX - 6, cy + 7.5F, 6.0F, ink(0x18));
            Liquid.rim(fx, cy - 7.5F, swatchX - 6, cy + 7.5F, 6.0F, 1.0F, alpha(accent(), 0.8F), alpha(accent(), 0.4F));
            small.draw(typed, fx + 4, cy, AtlasStyle.text);
            if (AtlasStyle.caretOn()) {
                float caret = fx + 4 + small.width(typed.substring(0, Math.min(typed.length(), this.hexInput.caret() + 1)));
                Liquid.rect(caret, cy - 4.5F, caret + 0.8F, cy + 4.5F, 0.0F, accent());
            }
        } else {
            small.drawRight(hex, swatchX - 8, cy, open ? accent() : AtlasStyle.dim);
            hit("hex", p, swatchX - 8 - small.width(hex), cy - 8, swatchX - 4, cy + 8);
        }
        hit("colour", p, swatchX - 2, cy - 8, right + 2, cy + 8);
        if (!open) {
            return;
        }
        float reveal = this.pickerHeight / PICKER;
        if (reveal < 0.05F) {
            return;
        }
        float saved = Liquid.alpha;
        Liquid.alpha = saved * clamp01(reveal);
        try {
            drawPicker(p, this.listLeft + 14, y + ROW + 2, right, mx, my);
        } finally {
            Liquid.alpha = saved;
        }
    }

    private void drawPicker(ColorProperty p, float left, float top, float right, float mx, float my) {
        float svW = Math.min(150.0F, (right - left) * 0.58F);
        float svH = 58.0F;
        float hue = this.hsb[0];
        int pure = opaque(Color.HSBtoRGB(hue, 1.0F, 1.0F));
        /* Saturation across, brightness down: white to the hue, then clear to black over it. */
        Liquid.rectH(left, top, left + svW, top + svH, 6.0F, 0xFFFFFFFF, pure);
        Liquid.rect(left, top, left + svW, top + svH, 6.0F, 0x00000000, 0xFF000000);
        Liquid.rim(left, top, left + svW, top + svH, 6.0F, 1.0F, ink(0x30), ink(0x18));
        float dotX = left + svW * this.hsb[1];
        float dotY = top + svH * (1.0F - this.hsb[2]);
        Liquid.ring(dotX, dotY, 4.0F, 1.6F, 0xFFFFFFFF);
        Liquid.ring(dotX, dotY, 5.2F, 0.8F, 0x80000000);
        hit("sv", p, left, top, left + svW, top + svH);

        float hueTop = top + svH + 7;
        float hueH = 8.0F;
        int segments = 6;
        float segW = svW / segments;
        for (int i = 0; i < segments; i++) {
            int from = opaque(Color.HSBtoRGB(i / (float) segments, 1.0F, 1.0F));
            int to = opaque(Color.HSBtoRGB((i + 1) / (float) segments, 1.0F, 1.0F));
            float r = i == 0 || i == segments - 1 ? 4.0F : 0.0F;
            Liquid.rectH(left + i * segW - (i == 0 ? 0 : 0.5F), hueTop, left + (i + 1) * segW, hueTop + hueH, r, from, to);
        }
        float hueX = left + svW * hue;
        Liquid.rect(hueX - 2, hueTop - 2, hueX + 2, hueTop + hueH + 2, 2.0F, 0xFFFFFFFF);
        Liquid.rim(hueX - 2, hueTop - 2, hueX + 2, hueTop + hueH + 2, 2.0F, 0.8F, 0x90000000, 0x90000000);
        hit("hue", p, left, hueTop - 3, left + svW, hueTop + hueH + 3);
        this.pickX = left;
        this.pickY = top;
        this.pickW = svW;
        this.pickH = svH;

        /* Quick colours, two rows, beside the square. */
        float sx = left + svW + 12;
        float size = Math.min(14.0F, (right - sx - 4 * 4) / 5.0F);
        if (size >= 8.0F) {
            for (int i = 0; i < SWATCHES.length; i++) {
                float qx = sx + (i % 5) * (size + 4);
                float qy = top + (i / 5) * (size + 4);
                boolean over = inside(mx, my, qx, qy, qx + size, qy + size);
                float grow = ease("swatch:" + i, over, 16.0F) * 1.2F;
                Liquid.rect(qx - grow, qy - grow, qx + size + grow, qy + size + grow, 4.0F, opaque(SWATCHES[i]));
                if ((p.getValue() & 0xFFFFFF) == SWATCHES[i]) {
                    Liquid.rim(qx - 2, qy - 2, qx + size + 2, qy + size + 2, 5.0F, 1.2F, accent(), accent());
                }
                hit("swatch", new Object[]{p, Integer.valueOf(SWATCHES[i])}, qx, qy, qx + size, qy + size);
            }
            LiquidFont tiny = font(7.0F, false);
            tiny.draw("Click the hex to type one", sx, top + (size + 4) * 2 + 8, AtlasStyle.faint);
            tiny.draw("Default " + "#" + ThemeColours.hex(((Integer) p.getDefault()) & 0xFFFFFF), sx,
                    top + (size + 4) * 2 + 19, AtlasStyle.faint);
        }
    }

    // ---- the Saved section ----------------------------------------------------------

    private List<String> savedThemes() {
        long now = System.currentTimeMillis();
        if (now - this.savedReadAt > 500L) {
            this.savedThemes = ThemeFiles.list();
            this.savedReadAt = now;
        }
        return this.savedThemes;
    }

    private void drawSaved(float left, float top, float right, float mx, float my) {
        this.maxScroll = 0.0F;
        int accent = accent();
        float y = top;
        font(7.0F, true).drawTracked("SAVE THIS LOOK", left, y + 4, alpha(accent, 0.85F), 0.6F);
        y += 14;
        /* The name field and Save. */
        float fieldRight = right - 62;
        boolean focused = this.naming;
        float lit = ease("nameField", focused || inside(mx, my, left, y, fieldRight, y + 20), AtlasStyle.HOVER_RATE);
        Liquid.rect(left, y, fieldRight, y + 20, 8.0F, ink(Math.round(0x12 + 0x0C * lit)));
        if (focused) {
            Liquid.rim(left, y, fieldRight, y + 20, 8.0F, 1.0F, alpha(accent, 0.8F), alpha(accent, 0.4F));
        }
        LiquidFont field = font(8.5F, false);
        String text = this.nameInput.text();
        if (text.isEmpty() && !focused) {
            field.draw("Name, e.g. Lime night", left + 8, y + 10, AtlasStyle.faint);
        } else {
            field.draw(field.trim(text, fieldRight - left - 16), left + 8, y + 10, AtlasStyle.text);
            if (focused && AtlasStyle.caretOn()) {
                float cx = left + 8 + field.width(text.substring(0, Math.min(text.length(), this.nameInput.caret())));
                Liquid.rect(cx, y + 5, cx + 0.8F, y + 15, 0.0F, accent);
            }
        }
        hit("nameField", null, left, y, fieldRight, y + 20);
        String clean = ThemeFiles.cleanName(text);
        boolean overwrite = ThemeFiles.exists(clean);
        button("save", null, overwrite ? "Replace" : "Save", right, y + 10, mx, my, true);
        y += 30;
        font(7.5F, false).draw("Holds the menu, HUD and World looks. Positions and module settings are not touched.",
                left, y, AtlasStyle.faint);
        y += 18;

        font(7.0F, true).drawTracked("SAVED THEMES", left, y + 4, alpha(accent, 0.85F), 0.6F);
        y += 14;
        List<String> names = savedThemes();
        if (names.isEmpty()) {
            font(8.0F, false).draw("Nothing saved yet. Name the look above and press Save.", left, y + 8, AtlasStyle.dim);
            return;
        }
        boolean armedLive = this.armedDelete != null && System.currentTimeMillis() - this.armedAt < 3000L;
        if (!armedLive) {
            this.armedDelete = null;
        }
        for (String name : names) {
            if (y + 22 > this.listBottom) {
                break;
            }
            boolean over = inside(mx, my, left - 6, y, right + 6, y + 22);
            float rowLit = ease("saved:" + name, over, AtlasStyle.HOVER_RATE);
            Liquid.rect(left - 6, y + 1, right + 6, y + 21, 8.0F, ink(Math.round(0x08 + 0x0E * rowLit)));
            AtlasClickGui.icon("file", left + 4, y + 11, AtlasStyle.dim);
            font(8.5F, true).draw(name, left + 16, y + 11, AtlasStyle.text);
            boolean armed = name.equals(this.armedDelete);
            float edge = button("delete", name, armed ? "Sure?" : "Delete", right, y + 11, mx, my, false);
            button("load", name, "Use", edge - 6, y + 11, mx, my, true);
            y += 24;
        }
    }

    // ---- preview -------------------------------------------------------------

    private void drawPreview(float left, float top, float right, float bottom) {
        float x = left + 12;
        float x2 = right - 12;
        float y = top + 10;
        font(7.0F, true).drawTracked("PREVIEW", x, y + 3, AtlasStyle.faint, 0.6F);
        y += 12;
        long now = System.currentTimeMillis();
        HUD hud = (HUD) module(HUD.class);

        /* A piece of the menu: the glass, a selected row, a switch and a slider. */
        float menuH = 52.0F;
        Liquid.pane(x, y, x2, y + menuH, 10.0F, this.theme.pane());
        int accent = accent();
        Liquid.rect(x + 5, y + 5, x2 - 5, y + 21, 8.0F, alpha(accent, 0.26F), alpha(accent, 0.14F));
        font(8.0F, true).draw("Silent Aura", x + 12, y + 13, AtlasStyle.text);
        boolean flip = (now / 1600L) % 2L == 0L;
        drawSwitch("previewSwitch", x2 - 34, y + 13, flip);
        font(8.0F, false).draw("Range", x + 12, y + 35, AtlasStyle.dim);
        float wave = (float) (0.5 + 0.35 * Math.sin(now / 900.0));
        float trackL = x + 50;
        float trackR = x2 - 12;
        Liquid.rect(trackL, y + 33.5F, trackR, y + 36.5F, 1.5F, ink(0x22));
        float fx = trackL + (trackR - trackL) * wave;
        Liquid.rectH(trackL, y + 33.5F, fx, y + 36.5F, 1.5F, alpha(accent, 0.7F), accent);
        Liquid.dot(fx, y + 35, 4.0F, 0xFFF4F6FA);
        Liquid.ring(fx, y + 35, 4.0F, 1.4F, accent);
        y += menuH + 10;

        /* The world behind the HUD: a strip of sky and grass, so the panels' glass reads as it does in game. */
        float worldH = Math.max(60.0F, bottom - y - 8);
        Liquid.rect(x, y, x2, y + worldH, 10.0F, 0xFF2E4A6B, 0xFF4E7BA0);
        Liquid.rect(x, y + worldH * 0.72F, x2, y + worldH, 10.0F, 0xFF4A7A34, 0xFF3A5E2A);
        drawHudPreview(hud, x, y, x2, y + worldH, now);
    }

    /** The game's font draws from a texture, which the shapes leave switched off. */
    private static void textState() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawHudPreview(HUD hud, float x, float y, float x2, float y2, long now) {
        net.minecraft.client.gui.FontRenderer fr = this.mc.fontRendererObj;
        boolean panels = HudPanel.active();
        /* The module list, top right, coloured as the HUD colours it. */
        String[] names = {"Silent Aura", "Velocity", "NoSlow", "Sprint"};
        float ry = y + 6;
        int unit = 0;
        for (String name : names) {
            float w = fr.getStringWidth(name) + 8;
            float rx = x2 - 6 - w;
            /* 2026-11-14 (owner: the preview's colours were unlike the game's): as HUD draws the list -- a JELLO
               list has white names and no edge bar, whatever the HUD colour. */
            int background = hud == null ? 0x70000000 : hud.listRowBackground();
            if (panels) {
                HudPanel.row(rx, ry, x2 - 6, ry + 11);
            } else if (background != 0) {
                Liquid.rect(rx, ry, x2 - 6, ry + 11, 0.0F, background);
            }
            long offset = hud == null ? 0L : (long) (unit * hud.colorDistance.getValue());
            int colour = hud == null ? accent() : hud.listNameColour(now, offset);
            textState();
            fr.drawStringWithShadow(name, rx + 4, ry + 2, colour);
            if (hud == null || hud.listEdgeBar()) {
                int bar = hud == null ? accent() : hud.getColor(now, offset).getRGB();
                Liquid.rect(x2 - 7.5F, ry + 1.5F, x2 - 6, ry + 9.5F, 0.75F, opaque(bar));
            }
            ry += 12;
            unit++;
        }
        GlStateManager.enableBlend();

        /* A target card with a health bar that loses health and gets it back. */
        float cardW = Math.min(110.0F, x2 - x - 12);
        float cx = x + 6;
        float cy = y + 6 + 4 * 12 + 6;
        if (cy + 34 < y2 - 4) {
            if (panels) {
                HudPanel.panel(cx, cy, cx + cardW, cy + 30, true, 1.0F);
                HudPanel.fill(cx + 4, cy + 4, cx + 26, cy + 26, HudPanel.cardRadius() * 0.6F, 0x40FFFFFF);
            } else {
                Liquid.rect(cx, cy, cx + cardW, cy + 30, 4.0F, 0x90000000);
            }
            textState();
            fr.drawStringWithShadow("Opponent", cx + 30, cy + 5, 0xFFFFFFFF);
            float health = (float) (0.55 + 0.4 * Math.sin(now / 1100.0));
            float trail = this.ui.toward("previewTrail", health, 2.5F);
            float barL = cx + 30;
            float barR = cx + cardW - 6;
            Liquid.rect(barL, cy + 19, barR, cy + 23, 2.0F, 0x33000000);
            Liquid.rect(barL, cy + 19, barL + (barR - barL) * Math.max(health, trail), cy + 23, 2.0F, 0xFF646464);
            /* The bar in the colour the chosen TargetHUD style draws it (JELLO: light grey). */
            atlas.module.modules.TargetHUD target = (atlas.module.modules.TargetHUD) module(
                    atlas.module.modules.TargetHUD.class);
            int colour = target != null ? target.previewBarColour(health)
                    : hud == null ? accent() : hud.getColor(now, 0L).getRGB();
            Liquid.rect(barL, cy + 19, barL + (barR - barL) * health, cy + 23, 2.0F, opaque(colour));
            GlStateManager.enableBlend();
        }

        /* A notification sliding in from the right every four seconds. */
        long phase = now % 4000L;
        float in = clamp01(phase / 300.0F);
        float out = clamp01((3200L - phase) / 300.0F);
        float shown = Math.min(in, out);
        shown = shown * shown * (3.0F - 2.0F * shown);
        if (shown > 0.01F) {
            String text = "Silent Aura enabled";
            float w = fr.getStringWidth(text) + 14;
            float nx = x2 - 6 - w + (1.0F - shown) * 18.0F;
            float ny = y2 - 26;
            if (ny > cy + 36) {
                /* 2026-11-14: as HUD.drawModernNotification draws it -- with accent everywhere, the glass in the
                   Appearance glass colour and the bar in the accent (owner: the preview did not match the game). */
                int sharedAccent = AtlasClickGui.sharedAccent();
                int sharedGlass = AtlasClickGui.sharedGlass();
                /* Notifications never use the HUD panels: always this glass. */
                Liquid.rect(nx, ny, nx + w, ny + 18, 6.0F, sharedGlass != 0
                        ? alpha((150 << 24) | (sharedGlass & 0xFFFFFF), shown) : alpha(0x5C0A0C10, shown));
                Liquid.rim(nx, ny, nx + w, ny + 18, 6.0F, 1.0F, alpha(0x18FFFFFF, shown), 0);
                int alpha = Math.max(4, Math.round(shown * 255.0F)) << 24;
                textState();
                fr.drawStringWithShadow("Silent Aura ", nx + 7, ny + 5, alpha | 0xEEF1F5);
                fr.drawStringWithShadow("enabled", nx + 7 + fr.getStringWidth("Silent Aura "), ny + 5,
                        alpha | 0x41D982);
                float progress = clamp01(1.0F - phase / 3200.0F);
                Liquid.rect(nx + 6, ny + 15, nx + 6 + (w - 12) * progress, ny + 16, 0.5F,
                        alpha(sharedAccent != 0 ? sharedAccent : 0xFF41D982, shown));
                GlStateManager.enableBlend();
            }
        }
    }

    private void drawFooter(float x, float y, float width) {
        String text = this.hint.isEmpty()
                ? "Changes apply at once  ·  right-click a setting to reset it  ·  Esc goes back"
                : this.hint;
        LiquidFont small = font(7.5F, false);
        small.draw(small.trim(text, width - 28), x + 14, y + FOOTER / 2.0F - 1, AtlasStyle.faint);
    }

    private void drawToast(float x, float y, float width, float height) {
        if (this.toast == null) {
            return;
        }
        long age = System.currentTimeMillis() - this.toastAt;
        if (age > 2600L) {
            this.toast = null;
            return;
        }
        float shown = Math.min(clamp01(age / 180.0F), clamp01((2600L - age) / 300.0F));
        shown = shown * shown * (3.0F - 2.0F * shown);
        LiquidFont bold = font(8.5F, true);
        float w = bold.width(this.toast) + 28;
        float tx = x + (width - w) / 2.0F;
        float ty = y + height - FOOTER - 30 + (1.0F - shown) * 8.0F;
        float saved = Liquid.alpha;
        Liquid.alpha = saved * shown;
        LiquidFont.alpha = saved * shown;
        Liquid.shadow(tx, ty, tx + w, ty + 20, 10.0F, 10.0F, 0x70000000, 2.0F);
        Liquid.rect(tx, ty, tx + w, ty + 20, 10.0F, AtlasStyle.toast);
        int mark = this.toastBad ? AtlasStyle.danger : AtlasStyle.ok;
        Liquid.dot(tx + 11, ty + 10, 3.0F, mark);
        bold.draw(this.toast, tx + 19, ty + 10, AtlasStyle.text);
        Liquid.alpha = saved;
        LiquidFont.alpha = saved;
    }

    /** Restricts drawing to a rectangle in layout pixels, through the window's opening scale. */
    private void clip(ScaledResolution sr, float x, float y, float width, float height) {
        float sx = this.pivotX + (x - this.pivotX) * this.viewScale;
        float sy = this.pivotY + (y - this.pivotY) * this.viewScale;
        float sw = width * this.viewScale;
        float sh = height * this.viewScale;
        int factor = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) Math.floor(sx * factor), (int) Math.floor((sr.getScaledHeight() - (sy + sh)) * factor),
                Math.max(0, (int) Math.ceil(sw * factor)), Math.max(0, (int) Math.ceil(sh * factor)));
    }

    // ---- input ---------------------------------------------------------------

    private void followDrag(float mx, float my) {
        if (!Mouse.isButtonDown(0)) {
            this.sliding = null;
            this.picking = null;
            return;
        }
        if (this.sliding != null) {
            float t = clamp01((mx - this.slideLeft) / this.slideWidth);
            float value = minimumOf(this.sliding) + t * (maximumOf(this.sliding) - minimumOf(this.sliding));
            assign(this.sliding, value);
        }
        if (this.picking != null) {
            if ("sv".equals(this.pickPart)) {
                this.hsb[1] = clamp01((mx - this.pickX) / this.pickW);
                this.hsb[2] = 1.0F - clamp01((my - this.pickY) / this.pickH);
            } else {
                this.hsb[0] = Math.min(0.9999F, clamp01((mx - this.pickX) / this.pickW));
            }
            setColour(this.picking, Color.HSBtoRGB(this.hsb[0], this.hsb[1], this.hsb[2]));
        }
    }

    /** Sets a colour's red, green and blue, keeping whatever alpha byte the setting was declared with. */
    private static void setColour(ColorProperty p, int rgb) {
        int keep = p.getValue() & 0xFF000000;
        p.setValue(Integer.valueOf(keep | (rgb & 0x00FFFFFF)));
    }

    private void openPicker(ColorProperty p) {
        if (this.picker == p) {
            this.picker = null;
            this.ui.removeSpring("pickerOpen");
            return;
        }
        this.picker = p;
        this.ui.putSpring("pickerOpen", new Spring(0.0F, 420.0F, 32.0F));
        int rgb = p.getValue();
        Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, this.hsb);
    }

    private void showSection(int index) {
        if (index == this.section) {
            return;
        }
        this.section = index;
        this.dropdown = null;
        this.picker = null;
        this.hexFor = null;
        this.naming = index == 0 && this.naming;
        this.ui.set("arrive", 0.0F);
    }

    private void remember(String label) {
        this.undo = ThemeFiles.capture(this.theme);
        this.undoLabel = label;
    }

    private void close() {
        if (!this.closing) {
            this.closing = true;
            this.openedFor = 0.0F;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        float mx = layoutX(mouseX);
        float my = layoutY(mouseY);
        UiContext.Hit target = this.ui.hitAt(mx, my);
        if (this.dropdown != null) {
            /* While a list is open, a click picks from it or only closes it. */
            this.dropdown = null;
            if (target != null && "dropItem".equals(target.kind) && button == 0) {
                Object[] pick = (Object[]) target.payload;
                ((ModeProperty) pick[0]).setVisible(((Integer) pick[1]).intValue());
            }
            return;
        }
        if (this.hexFor != null && (target == null || !"hex".equals(target.kind))) {
            commitHex();
        }
        if (target == null) {
            return;
        }
        if (button == 1) {
            /* Right-click: the setting back to its default. */
            Property<?> p = settingOf(target);
            if (p != null) {
                p.setValue(p.getDefault());
                if (p == this.picker) {
                    int rgb = this.picker.getValue();
                    Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, this.hsb);
                }
                tell(p.getLabel() + " reset", false);
            }
            return;
        }
        if (button != 0) {
            return;
        }
        if (!"nameField".equals(target.kind) && !"save".equals(target.kind)) {
            this.naming = false;
        }
        this.ui.pulse("btn:" + target.kind + target.payload);
        String kind = target.kind;
        Object payload = target.payload;
        if ("close".equals(kind)) {
            close();
        } else if ("section".equals(kind)) {
            showSection(((Integer) payload).intValue());
        } else if ("hudEditor".equals(kind)) {
            this.mc.displayGuiScreen(new atlas.ui.hud.HudEditorScreen(this));
        } else if ("undo".equals(kind)) {
            if (this.undo != null) {
                ThemeFiles.apply(this.undo, this.theme);
                tell("Put back the look before " + this.undoLabel.toLowerCase(java.util.Locale.ROOT), false);
                this.undo = null;
            }
        } else if ("preset".equals(kind)) {
            remember((String) payload);
            this.theme.apply((String) payload);
            tell((String) payload + " applied to the menu", false);
        } else if ("switch".equals(kind)) {
            BooleanProperty p = (BooleanProperty) payload;
            p.setValue(!p.getValue());
        } else if ("module".equals(kind)) {
            Module module = (Module) payload;
            module.setEnabled(!module.isEnabled());
        } else if ("expand".equals(kind)) {
            String group = (String) payload;
            if (!this.expanded.remove(group)) {
                this.expanded.add(group);
            }
        } else if ("slider".equals(kind)) {
            this.sliding = (Property<?>) payload;
            followDrag(mx, my);
        } else if ("seg".equals(kind)) {
            Object[] pick = (Object[]) payload;
            ((ModeProperty) pick[0]).setVisible(((Integer) pick[1]).intValue());
        } else if ("drop".equals(kind)) {
            this.dropdown = (ModeProperty) payload;
            this.ui.putSpring("dropOpen", new Spring(0.0F, 420.0F, 30.0F));
        } else if ("colour".equals(kind)) {
            openPicker((ColorProperty) payload);
        } else if ("sv".equals(kind) || "hue".equals(kind)) {
            this.picking = (ColorProperty) payload;
            this.pickPart = kind;
            followDrag(mx, my);
        } else if ("swatch".equals(kind)) {
            Object[] pick = (Object[]) payload;
            ColorProperty p = (ColorProperty) pick[0];
            int rgb = ((Integer) pick[1]).intValue();
            setColour(p, rgb);
            Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, this.hsb);
        } else if ("hex".equals(kind)) {
            this.hexFor = (ColorProperty) payload;
            this.hexInput.set(ThemeColours.hex(this.hexFor.getValue() & 0xFFFFFF));
        } else if ("nameField".equals(kind)) {
            this.naming = true;
        } else if ("save".equals(kind)) {
            saveTheme();
        } else if ("load".equals(kind)) {
            JsonObject look = ThemeFiles.read((String) payload);
            if (look == null) {
                tell("Could not read " + payload, true);
            } else {
                remember("loading " + payload);
                ThemeFiles.apply(look, this.theme);
                tell(payload + " is on", false);
            }
        } else if ("delete".equals(kind)) {
            String name = (String) payload;
            if (name.equals(this.armedDelete) && System.currentTimeMillis() - this.armedAt < 3000L) {
                this.armedDelete = null;
                tell(ThemeFiles.delete(name) ? name + " deleted" : "Could not delete " + name, false);
                this.savedReadAt = 0L;
            } else {
                this.armedDelete = name;
                this.armedAt = System.currentTimeMillis();
            }
        }
    }

    /** The setting a hit belongs to, if any: its payload, or the first part of a choice's. */
    private static Property<?> settingOf(UiContext.Hit hit) {
        if (hit.payload instanceof Property<?>) {
            return (Property<?>) hit.payload;
        }
        if (hit.payload instanceof Object[] && ((Object[]) hit.payload).length > 0
                && ((Object[]) hit.payload)[0] instanceof Property<?>) {
            return (Property<?>) ((Object[]) hit.payload)[0];
        }
        return null;
    }

    private void saveTheme() {
        String name = ThemeFiles.cleanName(this.nameInput.text());
        if (name.isEmpty()) {
            this.naming = true;
            tell("Type a name first", true);
            return;
        }
        if (ThemeFiles.save(name, this.theme)) {
            tell("Saved " + name, false);
            this.nameInput.set("");
            this.naming = false;
            this.savedReadAt = 0L;
        } else {
            tell("Could not save " + name, true);
        }
    }

    private void commitHex() {
        if (this.hexFor == null) {
            return;
        }
        Integer rgb = ThemeColours.parse(this.hexInput.text());
        if (rgb != null) {
            setColour(this.hexFor, rgb);
            if (this.hexFor == this.picker) {
                Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, this.hsb);
            }
        } else {
            tell("Not a colour: " + this.hexInput.text(), true);
        }
        this.hexFor = null;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.sliding = null;
        this.picking = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || this.section == 0) {
            return;
        }
        if (this.dropdown != null) {
            this.dropdown = null;
        }
        float step = 30.0F;
        this.scroll[this.section] = Math.max(0.0F, Math.min(this.maxScroll,
                this.scroll[this.section] + (wheel > 0 ? -step : step)));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        boolean ctrl = isCtrlKeyDown();
        if (this.hexFor != null) {
            TextInput.Result result = this.hexInput.key(typedChar, keyCode, ctrl, GuiScreen::getClipboardString);
            if (result == TextInput.Result.SUBMIT) {
                commitHex();
            } else if (result == TextInput.Result.CANCEL) {
                this.hexFor = null;
            }
            return;
        }
        if (this.naming) {
            TextInput.Result result = this.nameInput.key(typedChar, keyCode, ctrl, GuiScreen::getClipboardString);
            if (result == TextInput.Result.SUBMIT) {
                saveTheme();
            } else if (result == TextInput.Result.CANCEL) {
                this.naming = false;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (this.dropdown != null) {
                this.dropdown = null;
            } else if (this.picker != null) {
                this.picker = null;
            } else {
                close();
            }
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            showSection((this.section + (isShiftKeyDown() ? SECTIONS.length - 1 : 1)) % SECTIONS.length);
        }
    }
}
