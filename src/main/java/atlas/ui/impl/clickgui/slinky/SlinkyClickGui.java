package atlas.ui.impl.clickgui.slinky;

import atlas.Atlas;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;
import atlas.module.ModuleDocs;
import atlas.module.modules.ClickGUIModule;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.TextProperty;
import atlas.util.KeyBindUtil;
import atlas.util.RenderUtil;
import atlas.util.font.impl.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Slinky-style ClickGUI (2026-10-19, owner request: "make it look exactly
 * like Slinky's, with animated switches").
 *
 * The look: a dark pill of tabs at the top (Combat, Move, Visual, Block,
 * Utility, Profiles and a red Unload), and under it the modules as dark
 * rounded cards in two masonry columns. A card has the name, a key badge, a
 * switch, and a line of description with a small "+" box that unrolls the
 * settings inside the card. Profiles are cards too, with load, save, folder
 * and delete buttons and a key that loads them in game.
 *
 * Measured from the reference: everything is laid out in screen pixels, not
 * Minecraft's scaled units, so the menu is the same size at every GUI scale
 * (a 900 px wide page, 42 px tabs, 20 px rows 8 px apart, 12 px padding,
 * 20 px corner radius). Text is Inter at 16/15/14/12 px. Colours are greys of
 * 0.08 (cards), 0.25 (tracks), 0.6 (secondary text) and 0.87 (text), with an
 * accent (red by default, ClickGUI "slinky-accent").
 *
 * Animations: the page fades and slides down on open (0.3 s, ease-out
 * quint); the tab highlight slides between tabs and the page slides
 * sideways and fades on a tab change; switches move their knob and blend
 * their colour over 0.1 s (smoothstep); cards ease to their new height when
 * settings open or close; sliders ease to their value; dropdowns unroll;
 * tooltips grow in; dialogs fade and slide down.
 *
 * Drawing and input are one pass (immediate mode): a click is stored when it
 * happens and claimed during the next frame by the first control under it,
 * so what is clicked is always what was drawn there.
 */
public class SlinkyClickGui extends GuiScreen {
    private static SlinkyClickGui instance;

    private static final String[] TABS = {"Combat", "Move", "Visual", "Block", "Utility", "Profiles", "Unload"};
    private static final int PROFILES = 5;
    private static final int UNLOAD = 6;

    private static final int CARD = 0xFF141414;
    private static final int TRACK = 0xFF404040;
    private static final int TRACK_HOVER = 0xFF505050;
    private static final int TEXT = 0xFFDDDDDD;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int DIM = 0xFF999999;
    private static final int DIM_HOVER = 0xFFBBBBBB;
    private static final int RED = 0xFFDD4747;
    private static final int RED_HOVER = 0xFFEE5858;
    private static final int OVERLAY = 0xBF303030;
    private static final int EDGE = 0xFF545454;
    private static final int EDGE_HOVER = 0xFF8C8C8C;

    private static final float PAGE_WIDTH = 900.0F;
    private static final float CARD_MIN = 430.0F;
    private static final float GAP = 8.0F;
    private static final float PAD = 12.0F;
    private static final float ROW = 20.0F;
    private static final float RADIUS = 20.0F;
    private static final float CARD_HEIGHT = PAD + ROW + GAP + ROW + PAD;

    private static final int MODAL_NONE = 0;
    private static final int MODAL_TEXT = 1;
    private static final int MODAL_COLOR = 2;
    private static final int MODAL_PROMPT = 3;
    private static final int MODAL_OPTIONS = 4;

    // ---- frame -------------------------------------------------------------

    private float k;
    private float screenW;
    private float screenH;
    private double now;
    private double lastNow;
    private float dt;
    private float tx;
    private float ty;
    private float alpha = 1.0F;
    private float mx;
    private float my;

    // ---- input -------------------------------------------------------------

    private boolean pending;
    private boolean consumed;
    private float clickX;
    private float clickY;
    private int clickButton;
    private boolean drawingModal;

    // ---- page --------------------------------------------------------------

    private int selected;
    private float selectedShown;
    private float scroll;
    private float scrollMax;
    private float contentBottom;
    private float openProgress;

    private final Map<Module, Float> heights = new HashMap<Module, Float>();
    private final Set<Module> expanded = new HashSet<Module>();
    private final Map<Object, float[]> toggles = new HashMap<Object, float[]>();
    private final Map<Object, Float> sliders = new HashMap<Object, Float>();
    private final Map<Object, Float> choiceHeights = new HashMap<Object, Float>();
    private final Set<Object> openChoices = new HashSet<Object>();

    private Object listening;
    private IntConsumer listenSetter;
    private Property<?> dragging;
    private float dragTrackX;
    private float dragTrackW;

    // ---- tooltip -----------------------------------------------------------

    private String tipText;
    private float tipX;
    private float tipY;
    private float tipProgress;
    private boolean tipRequested;

    // ---- profiles ----------------------------------------------------------

    private List<SlinkyProfiles.Row> rows = Collections.emptyList();
    private double rowsRead = -10.0;
    private boolean profileConfirmed;
    private boolean creating;
    private final StringBuilder createText = new StringBuilder();

    // ---- dialogs -----------------------------------------------------------

    private int modal;
    private float modalProgress;
    private float modalHeight = 120.0F;
    private String modalTitle = "";
    private String modalPrompt = "";
    private Runnable modalConfirm;
    private final StringBuilder modalText = new StringBuilder();
    private Consumer<String> modalTextDone;
    private List<String> options = Collections.emptyList();
    private Consumer<Integer> optionChosen;
    /** Colour dialog: hue, saturation, lightness, each 0..1. */
    private final float[] hsl = new float[3];
    private Consumer<Integer> colorChosen;
    private boolean editingHex;
    private int colorSlider = -1;
    private final int[] history = {0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF, 0xFFFFFF};
    private int historyAt;

    private SlinkyClickGui() {
    }

    public static SlinkyClickGui getInstance() {
        if (instance == null) {
            instance = new SlinkyClickGui();
        }
        return instance;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        this.openProgress = 0.0F;
        this.lastNow = 0.0;
        this.pending = false;
        this.dragging = null;
        this.colorSlider = -1;
        this.rowsRead = -10.0;
        this.selectedShown = this.selected;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- look --------------------------------------------------------------

    private static int accent() {
        Module module = Atlas.moduleManager.modules.get(ClickGUIModule.class);
        if (module instanceof ClickGUIModule) {
            return 0xFF000000 | ((ClickGUIModule) module).slinkyAccent.getValue();
        }
        return RED;
    }

    private static int gray(float v) {
        int g = Math.round(v * 255.0F);
        return 0xFF000000 | g << 16 | g << 8 | g;
    }

    private static int withAlpha(int color, float a) {
        return Math.round((color >>> 24) * a) << 24 | color & 0xFFFFFF;
    }

    private static int blend(int from, int to, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int a1 = from >>> 24, r1 = from >> 16 & 255, g1 = from >> 8 & 255, b1 = from & 255;
        int a2 = to >>> 24, r2 = to >> 16 & 255, g2 = to >> 8 & 255, b2 = to & 255;
        return Math.round(a1 + (a2 - a1) * t) << 24 | Math.round(r1 + (r2 - r1) * t) << 16
                | Math.round(g1 + (g2 - g1) * t) << 8 | Math.round(b1 + (b2 - b1) * t);
    }

    /** The colour with the frame's opacity applied. */
    private int a(int color) {
        return withAlpha(color, this.alpha);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeOut(float t) {
        float u = 1.0F - t;
        return 1.0F - u * u * u * u * u;
    }

    private float follow(float from, float to, float seconds) {
        return from + (to - from) * Math.min(1.0F, this.dt / seconds);
    }

    // ---- drawing (all in screen pixels) -------------------------------------

    private void fill(float x, float y, float w, float h, float r, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        r = Math.min(r, Math.min(w, h) / 2.0F);
        RenderUtil.drawRoundedRect((x + this.tx) * this.k, (y + this.ty) * this.k, w * this.k, h * this.k, r * this.k,
                a(color));
    }

    private void stroke(float x, float y, float w, float h, float r, float width, int color) {
        if (w <= 0.0F || h <= 0.0F) {
            return;
        }
        RenderUtil.drawRoundedRectOutline((x + this.tx) * this.k, (y + this.ty) * this.k, w * this.k, h * this.k,
                Math.min(r, Math.min(w, h) / 2.0F) * this.k, width, a(color), true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void circle(float cx, float cy, float r, int color) {
        fill(cx - r, cy - r, r * 2.0F, r * 2.0F, r, color);
    }

    private void line(float x1, float y1, float x2, float y2, float width, int color) {
        int c = a(color);
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F, (c >>> 24) / 255.0F);
        GL11.glLineWidth(width);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f((x1 + this.tx) * this.k, (y1 + this.ty) * this.k);
        GL11.glVertex2f((x2 + this.tx) * this.k, (y2 + this.ty) * this.k);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void clip(float x, float y, float w, float h) {
        RenderUtil.scissor((x + this.tx) * this.k, (y + this.ty) * this.k, w * this.k, h * this.k);
    }

    private void unclip() {
        RenderUtil.releaseScissor();
    }

    private float textWidth(String s, int style, float size) {
        FontRenderer font = SlinkyFonts.get(style, size);
        if (font == null) {
            return mc.fontRendererObj.getStringWidth(s) / this.k;
        }
        return (float) font.getStringWidth(s) / this.k;
    }

    /** Text left-aligned in a row, centred vertically; returns where it ends. */
    private float text(String s, int style, float size, float x, float y, float h, int color) {
        FontRenderer font = SlinkyFonts.get(style, size);
        float centre = (y + this.ty + h / 2.0F) * this.k;
        if (font == null) {
            mc.fontRendererObj.drawString(s, (x + this.tx) * this.k, centre - 4.0F, a(color), false);
        } else {
            font.drawString(s, (x + this.tx) * this.k, centre - font.getHeight() / 2.0F + 2.0F * this.k, a(color));
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        return x + textWidth(s, style, size);
    }

    private void textCentered(String s, int style, float size, float x, float y, float w, float h, int color) {
        text(s, style, size, x + (w - textWidth(s, style, size)) / 2.0F, y, h, color);
    }

    private void textRight(String s, int style, float size, float right, float y, float h, int color) {
        text(s, style, size, right - textWidth(s, style, size), y, h, color);
    }

    /**
     * Shortened texts, by font and width (2026-10-19): a long description
     * was cut a letter at a time, measuring the whole string each time, for
     * every card on every frame. Widths do not depend on the GUI scale.
     */
    private final Map<String, String> trimmed = new HashMap<String, String>();

    private String trim(String s, int style, float size, float max) {
        String key = style + "|" + size + "|" + (int) max + "|" + s;
        String cached = this.trimmed.get(key);
        if (cached != null) {
            return cached;
        }
        String out = s;
        if (s.length() > 1 && textWidth(s, style, size) > max) {
            /* The longest prefix that fits with "...", by halving. */
            int low = 1;
            int high = s.length() - 1;
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (textWidth(s.substring(0, mid) + "...", style, size) <= max) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            out = s.substring(0, Math.max(1, low)) + "...";
        }
        if (this.trimmed.size() > 4096) {
            this.trimmed.clear();
        }
        this.trimmed.put(key, out);
        return out;
    }

    // ---- input helpers -----------------------------------------------------

    private boolean blocked() {
        return this.modal != MODAL_NONE && !this.drawingModal;
    }

    private boolean hover(float x, float y, float w, float h) {
        if (blocked()) {
            return false;
        }
        float px = this.mx - this.tx;
        float py = this.my - this.ty;
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    private boolean click(float x, float y, float w, float h) {
        return clickWith(0, x, y, w, h);
    }

    private boolean clickWith(int button, float x, float y, float w, float h) {
        if (!this.pending || this.consumed || this.clickButton != button || blocked()) {
            return false;
        }
        float px = this.clickX - this.tx;
        float py = this.clickY - this.ty;
        if (px >= x && px < x + w && py >= y && py < y + h) {
            this.consumed = true;
            return true;
        }
        return false;
    }

    private void tooltip(String text, float x, float y) {
        if (!this.tipRequested) {
            this.tipText = text;
            this.tipX = x + this.tx;
            this.tipY = y + this.ty;
            this.tipRequested = true;
        }
    }

    // ---- frame -------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution sr = new ScaledResolution(mc);
        this.k = 1.0F / sr.getScaleFactor();
        this.screenW = mc.displayWidth;
        this.screenH = mc.displayHeight;
        this.now = System.nanoTime() / 1.0E9;
        this.dt = this.lastNow == 0.0 ? 0.016F : (float) Math.min(0.1, this.now - this.lastNow);
        this.lastNow = this.now;
        this.mx = Mouse.getX();
        this.my = mc.displayHeight - Mouse.getY() - 1;

        if (this.dragging != null) {
            if (Mouse.isButtonDown(0)) {
                setFromTrack(this.dragging, (this.mx - this.dragTrackX) / this.dragTrackW);
            } else {
                this.dragging = null;
            }
        }
        if (this.colorSlider >= 0 && !Mouse.isButtonDown(0)) {
            this.colorSlider = -1;
        }

        this.scroll = Math.max(0.0F, Math.min(this.scrollMax, this.scroll));
        this.openProgress = Math.min(1.0F, this.openProgress + this.dt / 0.3F);
        float opacity = easeOut(this.openProgress);

        GlStateManager.disableDepth();
        this.tx = 0.0F;
        this.ty = 0.0F;
        this.alpha = opacity;
        fill(0.0F, 0.0F, this.screenW, this.screenH, 0.0F, OVERLAY);

        float pageW = Math.min(this.screenW, PAGE_WIDTH);
        float pageX = (this.screenW - pageW) / 2.0F;
        float x0 = pageX + PAD;
        float w0 = pageW - PAD * 2.0F;
        this.ty = -50.0F + opacity * 50.0F - this.scroll;

        drawTabs(x0, PAD, w0);

        /* A tab change slides the page sideways and fades it through. */
        float target = this.selected;
        this.selectedShown = follow(this.selectedShown, target, 0.05F);
        if (Math.abs(target - this.selectedShown) < 0.001F) {
            this.selectedShown = target;
        }
        float difference = target - this.selectedShown;
        float pageAlpha = 1.0F;
        if (difference != 0.0F) {
            float magnitude = Math.abs(difference);
            pageAlpha = magnitude > 0.5F ? 0.0F : 1.0F - magnitude * 2.0F;
            this.tx = difference * this.screenW * -0.1F;
        }
        this.alpha = opacity * pageAlpha;
        float top = PAD + 42.0F + GAP;
        if (this.selected == PROFILES) {
            this.contentBottom = drawProfiles(x0, top, w0);
        } else {
            this.contentBottom = drawCategory(x0, top, w0);
        }
        this.tx = 0.0F;
        this.alpha = opacity;
        this.scrollMax = Math.max(0.0F, this.contentBottom - this.screenH + PAD);

        drawTooltip();

        if (this.modal != MODAL_NONE) {
            drawModal(pageX, pageW);
        }

        /* A click nothing claimed: stop listening for a key, close an open dropdown. */
        if (this.pending && !this.consumed) {
            if (this.listening != null && this.clickButton == 0) {
                this.listening = null;
            }
            if (this.creating && this.clickButton == 0) {
                this.creating = false;
            }
        }
        this.pending = false;
        this.consumed = false;
        this.tx = 0.0F;
        this.ty = 0.0F;
        this.alpha = 1.0F;
        GlStateManager.enableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        handleInvWalk();
    }

    private void drawTabs(float x, float y, float w) {
        float h = 42.0F;
        fill(x, y, w, h, RADIUS, CARD);
        float tabW = w / TABS.length;
        float shown = this.selectedShown;
        /* The highlight slides; it sits under the tab being shown. */
        fill(x + shown * tabW + 6.0F, y + 6.0F, tabW - 12.0F, h - 12.0F, 14.0F, TRACK);
        for (int i = 0; i < TABS.length; i++) {
            float tx0 = x + i * tabW;
            boolean over = hover(tx0 + 6.0F, y + 6.0F, tabW - 12.0F, h - 12.0F);
            if (click(tx0, y, tabW, h)) {
                if (i == UNLOAD) {
                    prompt("Are you sure?", "Unload Atlas?", new Runnable() {
                        @Override
                        public void run() {
                            unload();
                        }
                    });
                } else if (i != this.selected) {
                    this.selected = i;
                    this.creating = false;
                    this.listening = null;
                    this.openChoices.clear();
                }
            }
            int color = i == UNLOAD ? (over ? RED_HOVER : RED) : (over ? WHITE : TEXT);
            textCentered(TABS[i], 0, 16.0F, tx0, y, tabW, h, color);
        }
    }

    // ---- module pages --------------------------------------------------------

    /** Cards placed in columns, each under the card above it in its column. */
    private static final class Masonry {
        final float x;
        final float colW;
        final float[] bottoms;
        int count;

        Masonry(float x, float y, float w) {
            int cols = (int) ((w + GAP) / (CARD_MIN + GAP));
            cols = Math.max(1, Math.min(4, cols));
            this.x = x;
            this.colW = (w - (cols - 1) * GAP) / cols;
            this.bottoms = new float[cols];
            java.util.Arrays.fill(this.bottoms, y);
        }

        float[] next(float h) {
            int col = this.count++ % this.bottoms.length;
            float[] r = {this.x + col * (this.colW + GAP), this.bottoms[col], this.colW, h};
            this.bottoms[col] += h + GAP;
            return r;
        }

        float bottom() {
            float max = 0.0F;
            for (float b : this.bottoms) {
                max = Math.max(max, b);
            }
            return max - GAP;
        }
    }

    private static List<Category> categoriesOf(int tab) {
        List<Category> out = new ArrayList<Category>();
        switch (tab) {
            case 0:
                out.add(Category.COMBAT);
                break;
            case 1:
                out.add(Category.MOVEMENT);
                break;
            case 2:
                out.add(Category.RENDER);
                out.add(Category.LEGIT);
                out.add(Category.THEME);
                break;
            case 3:
                out.add(Category.WORLD);
                break;
            default:
                out.add(Category.PLAYER);
                out.add(Category.MISC);
                out.add(Category.EXPLOIT);
                out.add(Category.CLIENT);
                break;
        }
        return out;
    }

    private List<Module> modulesOf(int tab) {
        List<Module> out = new ArrayList<Module>();
        for (Category category : categoriesOf(tab)) {
            out.addAll(ModuleCategories.modulesIn(category, Atlas.moduleManager.modules));
        }
        Collections.sort(out, new Comparator<Module>() {
            @Override
            public int compare(Module a, Module b) {
                return displayName(a).compareToIgnoreCase(displayName(b));
            }
        });
        return out;
    }

    private static final Map<String, String> NAMES = new HashMap<String, String>();

    /** "KillAura" -> "Kill Aura", "TNTTimer" -> "TNT Timer". */
    static String displayName(Module module) {
        String name = module.getName();
        String cached = NAMES.get(name);
        if (cached != null) {
            return cached;
        }
        String out;
        if ("FPScounter".equals(name)) {
            out = "FPS Counter";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (i > 0 && Character.isUpperCase(c)) {
                    char before = name.charAt(i - 1);
                    boolean nextLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
                    if (Character.isLowerCase(before) || Character.isDigit(before)
                            || Character.isUpperCase(before) && nextLower) {
                        sb.append(' ');
                    }
                }
                sb.append(c);
            }
            out = sb.toString().replace("  ", " ").trim();
        }
        NAMES.put(name, out);
        return out;
    }

    private float drawCategory(float x, float y, float w) {
        Masonry layout = new Masonry(x, y, w);
        for (Module module : modulesOf(this.selected)) {
            drawModule(module, layout);
        }
        return layout.bottom();
    }

    private boolean onScreen(float y, float h) {
        float top = y + this.ty;
        return top + h > 0.0F && top < this.screenH;
    }

    private void drawModule(Module module, Masonry layout) {
        Float stored = this.heights.get(module);
        float height = stored == null ? CARD_HEIGHT : stored;
        float[] r = layout.next(height);
        if (!onScreen(r[1], r[3])) {
            return;
        }
        fill(r[0], r[1], r[2], r[3], RADIUS, CARD);
        clip(r[0], r[1], r[2], r[3]);
        float ix = r[0] + PAD;
        float iy = r[1] + PAD;
        float iw = r[2] - PAD * 2.0F;

        /* Name, key badge and switch. */
        float end = text(trim(displayName(module), 0, 16.0F, iw - 150.0F), 0, 16.0F, ix, iy, ROW, TEXT);
        final Module target = module;
        badge(module, module.getKey(), end + 5.0F, iy, new IntConsumer() {
            @Override
            public void accept(int key) {
                target.setKey(key);
            }
        });
        if (toggle(module, ix + iw - 40.0F, iy, module.isEnabled())) {
            module.toggle();
        }

        /* Description, with the box that opens the settings. */
        List<Property<?>> settings = settingsOf(module);
        boolean open = this.expanded.contains(module);
        float y = iy + ROW + GAP;
        String description = module.getDescription() == null ? "" : module.getDescription();
        if (settings.isEmpty()) {
            text(trim(description, 0, 14.0F, iw), 0, 14.0F, ix, y, ROW, DIM);
            open = false;
        } else if (expander(open, description, ix, y, iw)) {
            if (!this.expanded.remove(module)) {
                this.expanded.add(module);
            }
            open = !open;
        }
        y += ROW;
        if (open) {
            y = drawSettings(settings, ix, y + GAP, iw);
        }
        float wanted = y + PAD - r[1];
        this.heights.put(module, stored == null ? wanted : follow(stored, wanted, 0.05F));
        unclip();
    }

    private static List<Property<?>> settingsOf(Module module) {
        List<Property<?>> out = new ArrayList<Property<?>>();
        List<Property<?>> all = Atlas.propertyManager.properties.get(module.getClass());
        if (all != null) {
            for (Property<?> property : all) {
                if (property.isVisible()) {
                    out.add(property);
                }
            }
        }
        return out;
    }

    /** The "+" box and the description beside it; true when clicked. */
    private boolean expander(boolean open, String label, float x, float y, float w) {
        float labelX = x + ROW + 8.0F;
        String shown = trim(label, 0, 14.0F, w - ROW - 8.0F);
        float combined = ROW + 8.0F + textWidth(shown, 0, 14.0F);
        boolean over = hover(x, y, combined, ROW);
        boolean clicked = click(x, y, combined, ROW);
        text(shown, 0, 14.0F, labelX, y, ROW, over ? DIM_HOVER : DIM);
        fill(x, y, ROW, ROW, 5.0F, over ? TRACK_HOVER : TRACK);
        int sign = over ? WHITE : TEXT;
        fill(x + 6.0F, y + 9.0F, 8.0F, 2.0F, 0.0F, sign);
        if (!open) {
            fill(x + 9.0F, y + 6.0F, 2.0F, 8.0F, 0.0F, sign);
        }
        return clicked;
    }

    /** A switch, 40 x 20; returns true when clicked. */
    private boolean toggle(Object id, float x, float y, boolean value) {
        float[] state = this.toggles.get(id);
        if (state == null) {
            state = new float[]{value ? 1.0F : 0.0F};
            this.toggles.put(id, state);
        }
        float step = this.dt / 0.1F;
        state[0] = value ? Math.min(1.0F, state[0] + step) : Math.max(0.0F, state[0] - step);
        float t = smooth(state[0]);
        boolean over = hover(x, y, 40.0F, ROW);
        fill(x, y, 40.0F, ROW, 10.0F, blend(over ? TRACK_HOVER : TRACK, accent(), t));
        float knobX = x + 10.0F + 20.0F * t;
        circle(knobX, y + 10.0F, 8.0F, CARD);
        return click(x, y, 40.0F, ROW);
    }

    private static String keyName(int key) {
        return key == 0 ? "NONE" : KeyBindUtil.getKeyName(key).toUpperCase(Locale.ROOT);
    }

    /** A key badge at (x, y); clicking it waits for a key. Returns its width. */
    private float badge(Object id, int key, float x, float y, IntConsumer set) {
        boolean active = this.listening == id;
        String label = active ? "PRESS A KEY..." : keyName(key);
        float w = (float) Math.ceil(textWidth(label, 1, 12.0F)) + 12.0F;
        int base = key != 0 || active ? accent() : gray(0.6F);
        boolean over = hover(x, y, w, ROW);
        fill(x, y, w, ROW, 5.0F, withAlpha(base, 0.15F));
        textCentered(label, 1, 12.0F, x, y, w, ROW, over ? WHITE : base);
        if (click(x, y, w, ROW)) {
            this.listening = id;
            this.listenSetter = set;
        }
        return w;
    }

    // ---- settings ------------------------------------------------------------

    private float drawSettings(List<Property<?>> settings, float x, float y, float w) {
        String group = null;
        boolean first = true;
        for (Property<?> property : settings) {
            String heading = property.getGroup();
            if (heading != null && !heading.equals(group)) {
                if (!first) {
                    y += GAP;
                }
                group = heading;
                text(ModuleDocs.heading(heading), 0, 15.0F, x, y + 6.0F, 19.0F, DIM);
                y += 19.0F;
                first = false;
            }
            if (!first) {
                y += GAP;
            }
            first = false;
            y += drawSetting(property, x, y, w);
        }
        return y;
    }

    private float drawSetting(final Property<?> property, float x, float y, float w) {
        String label = property.getLabel();
        float half = w / 2.0F;
        String shownLabel = trim(label, 0, 15.0F, half - 8.0F);
        if (property instanceof BooleanProperty) {
            BooleanProperty bool = (BooleanProperty) property;
            text(trim(label, 0, 15.0F, w - 48.0F), 0, 15.0F, x, y, ROW, TEXT);
            if (toggle(property, x + w - 40.0F, y, bool.getValue())) {
                bool.setBase(Property.Source.USER, Boolean.valueOf(!bool.getValue()));
            }
            return ROW;
        }
        if (isNumeric(property)) {
            text(shownLabel, 0, 15.0F, x, y, ROW, TEXT);
            float trackX = x + half;
            float trackY = y + 8.0F;
            float trackW = half - 2.0F;
            boolean over = hover(x, y, w, ROW);
            fill(trackX, trackY, trackW, 4.0F, 2.0F, over ? TRACK_HOVER : TRACK);
            float min = minimumOf(property);
            float max = maximumOf(property);
            float fraction = max > min ? Math.max(0.0F, Math.min(1.0F, (currentOf(property) - min) / (max - min))) : 0.0F;
            Float shown = this.sliders.get(property);
            float eased = shown == null ? fraction : follow(shown, fraction, 0.1F);
            this.sliders.put(property, eased);
            float end = trackX + trackW * eased;
            fill(trackX, trackY, end - trackX, 4.0F, 2.0F, accent());
            circle(end, trackY + 2.0F, 6.0F, accent());
            if (click(trackX - 6.0F, y, trackW + 12.0F, ROW)) {
                this.dragging = property;
                this.dragTrackX = trackX + this.tx;
                this.dragTrackW = trackW;
                setFromTrack(property, (this.clickX - this.dragTrackX) / trackW);
            }
            if (this.dragging == property || over && !Mouse.isButtonDown(0)) {
                tooltip(formatNumber(property), end, trackY);
            }
            return ROW;
        }
        if (property instanceof ModeProperty) {
            return drawChoice((ModeProperty) property, shownLabel, x, y, w);
        }
        if (property instanceof ColorProperty) {
            final ColorProperty color = (ColorProperty) property;
            text(shownLabel, 0, 15.0F, x, y, ROW, TEXT);
            float px = x + w - 40.0F;
            fill(px, y, 40.0F, ROW, 10.0F, 0xFF000000 | color.getValue());
            stroke(px, y, 40.0F, ROW, 10.0F, 1.0F, EDGE);
            if (click(x + half, y, half, ROW)) {
                openColor(label, color.getValue(), new Consumer<Integer>() {
                    @Override
                    public void accept(Integer rgb) {
                        color.setBase(Property.Source.USER, Integer.valueOf(rgb & 0xFFFFFF));
                    }
                });
            }
            return ROW;
        }
        if (property instanceof KeyProperty) {
            final KeyProperty key = (KeyProperty) property;
            text(shownLabel, 0, 15.0F, x, y, ROW, TEXT);
            String name = this.listening == property ? "PRESS A KEY..." : keyName(key.getValue());
            float bw = (float) Math.ceil(textWidth(name, 1, 12.0F)) + 12.0F;
            badge(property, key.getValue(), x + w - bw, y, new IntConsumer() {
                @Override
                public void accept(int value) {
                    key.setBase(Property.Source.USER, Integer.valueOf(value));
                }
            });
            return ROW;
        }
        if (property instanceof TextProperty) {
            final TextProperty textProperty = (TextProperty) property;
            text(shownLabel, 0, 15.0F, x, y, ROW, TEXT);
            boolean over = hover(x + half, y, half, ROW);
            stroke(x + half, y, half, ROW, 10.0F, 2.0F, TRACK);
            String value = textProperty.getValue() == null ? "" : textProperty.getValue();
            textCentered(trim(value.isEmpty() ? "..." : value, 0, 15.0F, half - 16.0F), 0, 15.0F, x + half, y, half, ROW,
                    over ? WHITE : TEXT);
            if (click(x + half, y, half, ROW)) {
                openText(label, value, new Consumer<String>() {
                    @Override
                    public void accept(String typed) {
                        textProperty.setBase(Property.Source.USER, typed);
                    }
                });
            }
            return ROW;
        }
        /* Lists, files and positions: shown; set with a command or the Atlas menu. */
        text(shownLabel, 0, 15.0F, x, y, ROW, TEXT);
        String value = String.valueOf(property.getValue());
        textRight(trim(value, 0, 15.0F, half - 8.0F), 0, 15.0F, x + w, y, ROW, DIM);
        return ROW;
    }

    /** A dropdown: the box opens in place for a few options, a search dialog for many. */
    private float drawChoice(final ModeProperty property, String label, float x, float y, float w) {
        final int[] shown = property.visibleIndices();
        int count = shown.length;
        boolean open = this.openChoices.contains(property);
        float targetHeight = open ? count * 28.0F : ROW;
        Float stored = this.choiceHeights.get(property);
        float height = stored == null ? targetHeight : follow(stored, targetHeight, 0.1F);
        if (Math.abs(height - targetHeight) < 0.25F) {
            height = targetHeight;
        }
        this.choiceHeights.put(property, height);
        text(label, 0, 15.0F, x, y, ROW, TEXT);
        float sx = x + w / 2.0F;
        float sw = w / 2.0F;
        stroke(sx, y, sw, height, 10.0F, 2.0F, TRACK);
        int current = 0;
        for (int i = 0; i < count; i++) {
            if (shown[i] == property.getValue()) {
                current = i;
            }
        }
        if (open && count > 0) {
            float rowH = height / count;
            for (int i = 0; i < count; i++) {
                float ry = y + i * rowH;
                boolean over = hover(sx, ry, sw, rowH);
                int color = i == current ? accent() : over ? WHITE : TEXT;
                if (rowH > 6.0F) {
                    textCentered(trim(property.modeName(shown[i]), 0, 15.0F, sw - 12.0F), 0, 15.0F, sx, ry, sw, rowH, color);
                }
                if (click(sx, ry, sw, rowH)) {
                    property.setBase(Property.Source.USER, Integer.valueOf(shown[i]));
                    this.openChoices.remove(property);
                }
                if (i != 0) {
                    fill(sx, ry, sw, 1.0F, 0.0F, withAlpha(TRACK, 0.5F));
                }
            }
            return height;
        }
        boolean over = hover(sx, y, sw, ROW);
        textCentered(trim(property.getModeString(), 0, 15.0F, sw - 16.0F), 0, 15.0F, sx, y, sw, ROW, over ? WHITE : TEXT);
        if (click(sx, y, sw, ROW)) {
            if (count < 6) {
                this.openChoices.add(property);
            } else {
                List<String> names = new ArrayList<String>();
                for (int index : shown) {
                    names.add(property.modeName(index));
                }
                openOptions(names, new Consumer<Integer>() {
                    @Override
                    public void accept(Integer picked) {
                        property.setBase(Property.Source.USER, Integer.valueOf(shown[picked]));
                    }
                });
            }
        }
        return height;
    }

    // ---- numbers -------------------------------------------------------------

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
        String s = String.format(Locale.ROOT, "%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void setFromTrack(Property<?> property, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        float min = minimumOf(property);
        float max = maximumOf(property);
        float value = min + (max - min) * t;
        if (isIntegral(property)) {
            property.setBase(Property.Source.USER, Integer.valueOf(Math.round(value)));
        } else {
            property.setBase(Property.Source.USER, Float.valueOf(Math.round(value * 100.0F) / 100.0F));
        }
    }

    // ---- profiles --------------------------------------------------------------

    private float drawProfiles(float x, float y, float w) {
        if (this.now - this.rowsRead > 2.0 && !this.creating) {
            this.rows = SlinkyProfiles.list();
            this.rowsRead = this.now;
        }
        Masonry layout = new Masonry(x, y, w);
        drawProfile(null, layout);
        for (SlinkyProfiles.Row row : this.rows) {
            drawProfile(row, layout);
        }
        float[] r = layout.next(CARD_HEIGHT);
        fill(r[0], r[1], r[2], r[3], RADIUS, withAlpha(CARD, 0.5F));
        boolean over = hover(r[0], r[1], r[2], r[3]);
        if (click(r[0], r[1], r[2], r[3])) {
            this.creating = true;
            this.createText.setLength(0);
        }
        if (this.creating) {
            float entryY = r[1] + r[3] / 2.0F - 8.0F;
            textEntry(this.createText.toString(), r[0], entryY, r[2], 16.0F, 16.0F);
        } else {
            textCentered("CREATE NEW PROFILE", 0, 15.0F, r[0], r[1], r[2], r[3], over ? WHITE : TEXT);
        }
        return layout.bottom();
    }

    private static final String[] PROFILE_TIPS = {"Click to load", "Click to save", "Click to open folder", "Click to delete"};
    private static final String[] PROFILE_PROMPTS = {"Load profile '%s'?", "Save profile '%s'?",
            "Open folder for profile '%s'?", "Delete profile '%s'?"};

    private void drawProfile(SlinkyProfiles.Row row, Masonry layout) {
        float[] r = layout.next(CARD_HEIGHT);
        if (!onScreen(r[1], r[3])) {
            return;
        }
        fill(r[0], r[1], r[2], r[3], RADIUS, CARD);
        clip(r[0], r[1], r[2], r[3]);
        float ix = r[0] + PAD;
        float iy = r[1] + PAD;
        float iw = r[2] - PAD * 2.0F;
        final String name = row == null ? null : row.name;
        float end = text(trim(row == null ? "Default" : row.name, 0, 16.0F, iw - 220.0F), 0, 16.0F, ix, iy, ROW, TEXT);
        if (row != null) {
            badge("profile:" + name, SlinkyProfiles.INSTANCE.getKey(name), end + 5.0F, iy, new IntConsumer() {
                @Override
                public void accept(int key) {
                    SlinkyProfiles.INSTANCE.setKey(name, key);
                }
            });
        }
        for (int slot = 0; slot < 4; slot++) {
            float bx = ix + iw - (80.0F - 20.0F * slot) - (36.0F - 12.0F * slot);
            boolean enabled = row != null || (slot & 1) == 0;
            boolean over = enabled && hover(bx, iy, ROW, ROW);
            drawProfileIcon(slot, bx, iy, enabled, over);
            if (over) {
                tooltip(PROFILE_TIPS[slot], bx + ROW / 2.0F, iy);
            }
            if (enabled && click(bx, iy, ROW, ROW)) {
                profileAction(slot, name);
            }
        }
        text(row == null ? "Contains the default configuration." : SlinkyProfiles.used(row.modified), 0, 14.0F, ix,
                iy + ROW + GAP, ROW, DIM);
        unclip();
    }

    /** Load, save, folder and delete, drawn as small glyphs in a 20 x 20 box. */
    private void drawProfileIcon(int slot, float x, float y, boolean enabled, boolean over) {
        int color;
        if (slot == 3) {
            color = !enabled ? 0xFF78302D : over ? 0xFFEF5F5F : 0xFFDD4847;
        } else {
            color = !enabled ? 0xFF565656 : over ? 0xFFDBDBDB : 0xFF999999;
        }
        switch (slot) {
            case 0:
                /* A tray with an arrow leaving it upwards. */
                fill(x + 4.0F, y + 9.0F, 12.0F, 9.0F, 2.5F, color);
                fill(x + 8.5F, y + 9.0F, 3.0F, 2.5F, 0.0F, CARD);
                fill(x + 9.0F, y + 2.0F, 2.0F, 9.5F, 0.0F, color);
                line(x + 6.5F, y + 5.0F, x + 10.0F, y + 1.5F, 2.0F, color);
                line(x + 13.5F, y + 5.0F, x + 10.0F, y + 1.5F, 2.0F, color);
                break;
            case 1:
                /* A tray with an arrow going into it. */
                fill(x + 4.0F, y + 9.0F, 12.0F, 9.0F, 2.5F, color);
                fill(x + 9.0F, y + 1.0F, 2.0F, 9.0F, 0.0F, color);
                line(x + 7.0F, y + 9.5F, x + 10.0F, y + 12.5F, 2.0F, CARD);
                line(x + 13.0F, y + 9.5F, x + 10.0F, y + 12.5F, 2.0F, CARD);
                break;
            case 2:
                /* A folder. */
                fill(x + 3.0F, y + 4.0F, 7.0F, 4.0F, 1.5F, color);
                fill(x + 3.0F, y + 6.0F, 14.0F, 11.5F, 2.5F, color);
                break;
            default:
                /* A bin: handle, lid, body with two slots. */
                stroke(x + 7.5F, y + 1.0F, 5.0F, 3.0F, 1.0F, 1.5F, color);
                fill(x + 3.0F, y + 3.5F, 14.0F, 2.0F, 1.0F, color);
                fill(x + 4.5F, y + 6.0F, 11.0F, 12.0F, 2.5F, color);
                fill(x + 7.5F, y + 8.5F, 1.5F, 7.0F, 0.75F, CARD);
                fill(x + 11.0F, y + 8.5F, 1.5F, 7.0F, 0.75F, CARD);
                break;
        }
    }

    private void profileAction(final int action, final String name) {
        if (this.profileConfirmed) {
            performProfileAction(action, name);
            return;
        }
        prompt("Are you sure?", PROFILE_PROMPTS[action].replace("%s", name == null ? "Default" : name), new Runnable() {
            @Override
            public void run() {
                SlinkyClickGui.this.profileConfirmed = true;
                performProfileAction(action, name);
            }
        });
    }

    private void performProfileAction(int action, String name) {
        switch (action) {
            case 0:
                SlinkyProfiles.load(name);
                break;
            case 1:
                if (name != null) {
                    SlinkyProfiles.save(name);
                }
                break;
            case 2:
                SlinkyProfiles.openFolder();
                break;
            default:
                if (name != null) {
                    SlinkyProfiles.delete(name);
                }
                break;
        }
        this.rowsRead = -10.0;
    }

    private void createProfile() {
        String name = SlinkyProfiles.clean(this.createText.toString());
        this.creating = false;
        if (name.isEmpty() || "default".equalsIgnoreCase(name)) {
            SlinkyProfiles.say("Type a name for the profile first");
            return;
        }
        if (SlinkyProfiles.exists(name)) {
            SlinkyProfiles.say("A profile named &f" + name + " &7already exists");
            return;
        }
        SlinkyProfiles.save(name);
        this.rowsRead = -10.0;
    }

    /** Typed text centred in a box, with a blinking caret after it. */
    private void textEntry(String typed, float x, float y, float w, float h, float size) {
        float tw = textWidth(typed, 0, size);
        float start = x + (w - tw) / 2.0F;
        text(typed, 0, size, start, y, h, WHITE);
        float caret = 0.5F + (float) Math.sin(8.0 * this.now) * 0.5F;
        fill(start + tw + 1.0F, y, 1.0F, h, 0.0F, withAlpha(TEXT, caret));
    }

    // ---- unload ------------------------------------------------------------

    /**
     * A Forge mod cannot be taken out of a running game, so "unload" here is
     * Panic: every behaviour module off, held packets let go or dropped, the
     * menu closed.
     */
    private void unload() {
        Module panic = Atlas.moduleManager.getModule("Panic");
        if (panic != null) {
            panic.setEnabled(true);
        }
        mc.displayGuiScreen(null);
    }

    // ---- tooltip -------------------------------------------------------------

    private void drawTooltip() {
        if (!this.tipRequested) {
            this.tipProgress = 0.0F;
            return;
        }
        this.tipRequested = false;
        this.tipProgress = Math.min(1.0F, this.tipProgress + this.dt / 0.1F);
        float t = smooth(this.tipProgress);
        float saveTx = this.tx;
        float saveTy = this.ty;
        float saveAlpha = this.alpha;
        this.tx = 0.0F;
        this.ty = 0.0F;
        this.alpha = saveAlpha * t;
        GlStateManager.pushMatrix();
        GlStateManager.translate(this.tipX * this.k, this.tipY * this.k, 0.0F);
        GlStateManager.scale(t, t, 1.0F);
        GlStateManager.translate(-this.tipX * this.k, -this.tipY * this.k, 0.0F);
        float w = textWidth(this.tipText, 0, 14.0F);
        float bx = this.tipX - w / 2.0F - 5.0F;
        float by = this.tipY - 28.0F;
        fill(bx, by, w + 10.0F, 20.0F, 5.0F, TRACK);
        textCentered(this.tipText, 0, 14.0F, bx, by, w + 10.0F, 20.0F, TEXT);
        GlStateManager.popMatrix();
        this.tx = saveTx;
        this.ty = saveTy;
        this.alpha = saveAlpha;
    }

    // ---- dialogs -------------------------------------------------------------

    private void openModal(int kind, String title) {
        this.modal = kind;
        this.modalTitle = title;
        this.modalProgress = 0.0F;
        this.listening = null;
        this.dragging = null;
        this.modalText.setLength(0);
    }

    private void prompt(String title, String prompt, Runnable confirm) {
        openModal(MODAL_PROMPT, title);
        this.modalPrompt = prompt;
        this.modalConfirm = confirm;
    }

    private void openText(String title, String value, Consumer<String> done) {
        openModal(MODAL_TEXT, title);
        this.modalText.append(value);
        this.modalTextDone = done;
    }

    private void openOptions(List<String> names, Consumer<Integer> chosen) {
        openModal(MODAL_OPTIONS, "Select item");
        this.options = names;
        this.optionChosen = chosen;
    }

    private void openColor(String title, int rgb, Consumer<Integer> chosen) {
        openModal(MODAL_COLOR, title);
        float[] values = toHsl(rgb);
        System.arraycopy(values, 0, this.hsl, 0, 3);
        this.colorChosen = chosen;
        this.editingHex = false;
    }

    private void closeModal() {
        if (this.modal == MODAL_COLOR) {
            int rgb = fromHsl(this.hsl[0], this.hsl[1], this.hsl[2]) & 0xFFFFFF;
            if (this.history[(this.historyAt + 7) % 8] != rgb) {
                this.history[this.historyAt] = rgb;
                this.historyAt = (this.historyAt + 1) % 8;
            }
        }
        this.modal = MODAL_NONE;
        this.colorSlider = -1;
        this.editingHex = false;
    }

    private void drawModal(float pageX, float pageW) {
        this.drawingModal = true;
        this.modalProgress = Math.min(1.0F, this.modalProgress + this.dt / 0.3F);
        float opacity = easeOut(this.modalProgress);
        this.tx = 0.0F;
        this.ty = 0.0F;
        this.alpha = opacity;
        fill(0.0F, 0.0F, this.screenW, this.screenH, 0.0F, OVERLAY);
        this.ty = -50.0F + opacity * 50.0F;

        float mw = pageW;
        float mxp = pageX;
        if (mw > 600.0F) {
            mxp = pageX + (mw - 600.0F) / 2.0F;
            mw = 600.0F;
        }
        float x = mxp + PAD;
        float y = PAD;
        float w = mw - PAD * 2.0F;
        fill(x, y, w, this.modalHeight, RADIUS, CARD);
        float ix = x + PAD;
        float iy = y + PAD;
        float iw = w - PAD * 2.0F;

        text(this.modalTitle, 0, 16.0F, ix, iy, ROW, TEXT);
        float cx = ix + iw - ROW;
        boolean overClose = hover(cx, iy, ROW, ROW);
        int closeColor = overClose ? RED_HOVER : DIM;
        line(cx + 4.0F, iy + 4.0F, cx + ROW - 4.0F, iy + ROW - 4.0F, 2.0F, closeColor);
        line(cx + ROW - 4.0F, iy + 4.0F, cx + 4.0F, iy + ROW - 4.0F, 2.0F, closeColor);
        boolean closePressed = click(cx, iy, ROW, ROW);
        float cy = iy + ROW + GAP;

        if (this.modal == MODAL_PROMPT) {
            text(this.modalPrompt, 0, 15.0F, ix, cy, 30.0F, TEXT);
            cy += 30.0F + GAP;
            boolean over = hover(ix, cy, iw, 30.0F);
            fill(ix, cy, iw, 30.0F, 5.0F, over ? RED_HOVER : RED);
            textCentered("Continue", 0, 15.0F, ix, cy, iw, 30.0F, WHITE);
            if (click(ix, cy, iw, 30.0F)) {
                Runnable confirm = this.modalConfirm;
                closeModal();
                if (confirm != null) {
                    confirm.run();
                }
            }
            cy += 30.0F;
        } else if (this.modal == MODAL_TEXT || this.modal == MODAL_OPTIONS) {
            stroke(ix, cy, iw, 30.0F, 8.0F, 2.0F, TRACK);
            textEntry(this.modalText.toString(), ix + 4.0F, cy + 4.0F, iw - 8.0F, 22.0F, 15.0F);
            cy += 30.0F;
            if (this.modal == MODAL_OPTIONS) {
                cy = drawOptions(ix, cy, iw);
            }
        } else if (this.modal == MODAL_COLOR) {
            cy = drawColor(ix, cy, iw);
        }

        this.modalHeight = cy + PAD - y;
        this.drawingModal = false;
        if (closePressed && this.modal != MODAL_NONE) {
            closeModal();
        }
    }

    private float drawOptions(float x, float y, float w) {
        String filter = this.modalText.toString().toLowerCase(Locale.ROOT);
        List<Integer> matches = new ArrayList<Integer>();
        for (int i = 0; i < this.options.size(); i++) {
            if (filter.isEmpty() || this.options.get(i).toLowerCase(Locale.ROOT).contains(filter)) {
                matches.add(i);
            }
        }
        int limit = Math.min(matches.size(), 40);
        Integer chosen = null;
        float rowY = y;
        for (int i = 0; i < limit; i++) {
            if ((i & 1) == 0) {
                rowY = y + GAP;
                y = rowY + 25.0F;
            }
            float bx = x + (i & 1) * w / 2.0F;
            boolean over = hover(bx, rowY, w / 2.0F, 25.0F);
            text(trim(this.options.get(matches.get(i)), 0, 16.0F, w / 2.0F - 8.0F), 0, 16.0F, bx, rowY, 25.0F,
                    over ? WHITE : TEXT);
            if (click(bx, rowY, w / 2.0F, 25.0F)) {
                chosen = matches.get(i);
            }
        }
        if (matches.size() > limit) {
            y += GAP;
            text("+" + (matches.size() - limit) + " more...", 0, 15.0F, x, y, 25.0F, DIM);
            y += 25.0F;
        }
        if (chosen != null) {
            Consumer<Integer> done = this.optionChosen;
            closeModal();
            if (done != null) {
                done.accept(chosen);
            }
        }
        return y;
    }

    private float drawColor(float x, float y, float w) {
        int rgb = fromHsl(this.hsl[0], this.hsl[1], this.hsl[2]);
        fill(x, y, 24.0F, 24.0F, 5.0F, 0xFF000000 | rgb);
        stroke(x, y, 24.0F, 24.0F, 5.0F, 1.0F, EDGE);
        String hex = this.editingHex ? this.modalText.toString() : String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
        text("#" + hex, 0, 20.0F, x + 32.0F, y, 24.0F, DIM);
        if (click(x, y, w, 24.0F)) {
            this.editingHex = true;
            this.modalText.setLength(0);
        }
        y += 24.0F;
        String[] names = {"Hue", "Saturation", "Lightness"};
        for (int c = 0; c < 3; c++) {
            y += GAP;
            text(names[c], 0, 15.0F, x, y, 19.0F, TEXT);
            y += 19.0F + GAP;
            float trackX = x + 1.0F;
            float trackW = w - 2.0F;
            int[] stops = new int[c == 0 ? 7 : 2];
            for (int i = 0; i < stops.length; i++) {
                float t = i / (float) (stops.length - 1);
                stops[i] = c == 0 ? fromHsl(t, this.hsl[1], this.hsl[2])
                        : c == 1 ? fromHsl(this.hsl[0], t, this.hsl[2]) : fromHsl(this.hsl[0], this.hsl[1], t);
            }
            gradientPill(trackX, y, trackW, 15.0F, stops);
            stroke(trackX, y, trackW, 15.0F, 5.0F, 2.0F, TRACK);
            float outer = 11.0F;
            float px = x + outer + (w - outer * 2.0F) * this.hsl[c];
            float py = y + 7.5F;
            circle(px, py, 10.0F, 0xFF000000 | sample(stops, this.hsl[c]));
            strokeCircle(px, py, 10.0F, TRACK);
            if (click(x, y - 4.0F, w, 23.0F)) {
                this.colorSlider = c;
            }
            if (this.colorSlider == c) {
                this.hsl[c] = Math.max(0.0F, Math.min(1.0F, (this.mx - this.tx - x - outer) / (w - outer * 2.0F)));
                applyColor();
            }
            y += 15.0F;
        }
        y += GAP;
        text("Recently used", 0, 15.0F, x, y, 19.0F, TEXT);
        y += 19.0F + GAP;
        float sw = (w - 56.0F) / 8.0F;
        for (int back = 8; back > 0; back--) {
            int index = (this.historyAt - back + 8) % 8;
            float bx = x + (8 - back) * (sw + 8.0F);
            fill(bx, y, sw, 25.0F, 5.0F, 0xFF000000 | this.history[index]);
            boolean over = hover(bx, y, sw, 25.0F);
            stroke(bx, y, sw, 25.0F, 5.0F, 1.0F, over ? EDGE_HOVER : EDGE);
            if (click(bx, y, sw, 25.0F)) {
                float[] values = toHsl(this.history[index]);
                System.arraycopy(values, 0, this.hsl, 0, 3);
                applyColor();
            }
        }
        return y + 25.0F;
    }

    private void applyColor() {
        if (this.colorChosen != null) {
            this.colorChosen.accept(fromHsl(this.hsl[0], this.hsl[1], this.hsl[2]) & 0xFFFFFF);
        }
    }

    private void gradientPill(float x, float y, float w, float h, int[] stops) {
        float r = 5.0F;
        fill(x, y, r * 2.0F, h, r, 0xFF000000 | stops[0]);
        fill(x + w - r * 2.0F, y, r * 2.0F, h, r, 0xFF000000 | stops[stops.length - 1]);
        int steps = 48;
        float inner = w - r * 2.0F;
        for (int i = 0; i < steps; i++) {
            float t = (i + 0.5F) / steps;
            fill(x + r + inner * i / steps, y, inner / steps + 0.5F, h, 0.0F, 0xFF000000 | sample(stops, t));
        }
    }

    private void strokeCircle(float cx, float cy, float r, int color) {
        stroke(cx - r, cy - r, r * 2.0F, r * 2.0F, r, 2.0F, color);
    }

    private static int sample(int[] stops, float t) {
        if (stops.length == 1) {
            return stops[0];
        }
        float position = Math.max(0.0F, Math.min(1.0F, t)) * (stops.length - 1);
        int index = Math.min(stops.length - 2, (int) position);
        return blend(0xFF000000 | stops[index], 0xFF000000 | stops[index + 1], position - index) & 0xFFFFFF;
    }

    private static float[] toHsl(int rgb) {
        float r = (rgb >> 16 & 255) / 255.0F;
        float g = (rgb >> 8 & 255) / 255.0F;
        float b = (rgb & 255) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2.0F;
        float h = 0.0F;
        float s = 0.0F;
        if (max != min) {
            float d = max - min;
            s = l > 0.5F ? d / (2.0F - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6.0F : 0.0F);
            } else if (max == g) {
                h = (b - r) / d + 2.0F;
            } else {
                h = (r - g) / d + 4.0F;
            }
            h /= 6.0F;
        }
        return new float[]{h, s, l};
    }

    private static int fromHsl(float h, float s, float l) {
        float r;
        float g;
        float b;
        if (s == 0.0F) {
            r = g = b = l;
        } else {
            float q = l < 0.5F ? l * (1.0F + s) : l + s - l * s;
            float p = 2.0F * l - q;
            r = hue(p, q, h + 1.0F / 3.0F);
            g = hue(p, q, h);
            b = hue(p, q, h - 1.0F / 3.0F);
        }
        return Math.round(r * 255.0F) << 16 | Math.round(g * 255.0F) << 8 | Math.round(b * 255.0F);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0.0F) {
            t += 1.0F;
        }
        if (t > 1.0F) {
            t -= 1.0F;
        }
        if (t < 1.0F / 6.0F) {
            return p + (q - p) * 6.0F * t;
        }
        if (t < 0.5F) {
            return q;
        }
        if (t < 2.0F / 3.0F) {
            return p + (q - p) * (2.0F / 3.0F - t) * 6.0F;
        }
        return p;
    }

    // ---- input ---------------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        float x = Mouse.getEventX();
        float y = mc.displayHeight - Mouse.getEventY() - 1;
        /* A mouse button while waiting for a key binds that button (middle and side buttons). */
        if (this.listening != null && button >= 2) {
            if (this.listenSetter != null) {
                this.listenSetter.accept(button - 100);
            }
            this.listening = null;
            return;
        }
        this.pending = true;
        this.consumed = false;
        this.clickX = x;
        this.clickY = y;
        this.clickButton = button;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        this.dragging = null;
        this.colorSlider = -1;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && this.modal == MODAL_NONE) {
            this.scroll = Math.max(0.0F, Math.min(this.scrollMax, this.scroll + (wheel > 0 ? -30.0F : 30.0F)));
        }
    }

    @Override
    protected void keyTyped(char typed, int key) throws IOException {
        if (this.listening != null) {
            int value = key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK ? 0 : key;
            if (this.listenSetter != null) {
                this.listenSetter.accept(value);
            }
            this.listening = null;
            return;
        }
        if (this.modal == MODAL_TEXT || this.modal == MODAL_OPTIONS || this.modal == MODAL_COLOR && this.editingHex) {
            if (key == Keyboard.KEY_ESCAPE) {
                closeModal();
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                submitModalText();
            } else {
                edit(this.modalText, typed, key, this.modal == MODAL_COLOR ? 6 : 64);
            }
            return;
        }
        if (this.creating) {
            if (key == Keyboard.KEY_ESCAPE) {
                this.creating = false;
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                createProfile();
            } else {
                edit(this.createText, typed, key, 32);
            }
            return;
        }
        if (this.modal != MODAL_NONE && key == Keyboard.KEY_ESCAPE) {
            closeModal();
            return;
        }
        Module gui = Atlas.moduleManager.getModule("ClickGUI");
        if (key == Keyboard.KEY_ESCAPE || gui != null && key == gui.getKey() && this.openProgress > 0.5F) {
            mc.displayGuiScreen(null);
        }
    }

    private void submitModalText() {
        String typed = this.modalText.toString();
        if (this.modal == MODAL_COLOR) {
            try {
                int rgb = Integer.parseInt(typed, 16) & 0xFFFFFF;
                float[] values = toHsl(rgb);
                System.arraycopy(values, 0, this.hsl, 0, 3);
                applyColor();
            } catch (NumberFormatException ignored) {
                // Not hex: left as it was.
            }
            this.editingHex = false;
            return;
        }
        if (this.modal == MODAL_TEXT) {
            Consumer<String> done = this.modalTextDone;
            closeModal();
            if (done != null) {
                done.accept(typed);
            }
        }
    }

    private static void edit(StringBuilder buffer, char typed, int key, int max) {
        if (key == Keyboard.KEY_BACK) {
            if (buffer.length() > 0) {
                buffer.setLength(buffer.length() - 1);
            }
        } else if (GuiScreen.isKeyComboCtrlV(key)) {
            String clip = GuiScreen.getClipboardString();
            if (clip != null) {
                buffer.append(clip.replaceAll("[\\r\\n]", ""));
                if (buffer.length() > max) {
                    buffer.setLength(max);
                }
            }
        } else if (typed >= ' ' && typed != 127 && buffer.length() < max) {
            buffer.append(typed);
        }
    }

    private void handleInvWalk() {
        Module invWalk = Atlas.moduleManager.getModule("InvWalk");
        if (invWalk == null || !invWalk.isEnabled() || this.creating || this.modal != MODAL_NONE) {
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
        this.listening = null;
        this.dragging = null;
        this.creating = false;
        if (this.modal != MODAL_NONE) {
            closeModal();
        }
        Module gui = Atlas.moduleManager.getModule("ClickGUI");
        if (gui instanceof ClickGUIModule && ((ClickGUIModule) gui).isSwitchingGuiStyle()) {
            return;
        }
        if (gui != null) {
            gui.setEnabled(false);
        }
    }
}
