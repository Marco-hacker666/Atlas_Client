package atlas.ui.impl.clickgui.glide;

import atlas.Atlas;
import atlas.config.Config;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;
import atlas.module.modules.ClickGUIModule;
import atlas.ui.impl.clickgui.atlas.AtlasIcons;
import atlas.ui.impl.clickgui.atlas.TextInput;
import atlas.util.RenderUtil;
import atlas.util.font.FontManager;
import atlas.util.font.impl.FontRenderer;
import atlas.util.shader.BlurUtils;
import atlas.util.shader.RoundedUtils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glide ClickGUI (2026-10-26, owner request): a main window listing the
 * categories, one window per category with its modules, and a search box at
 * the top. Written for Atlas from scratch; it sits beside the Atlas, Normal,
 * Panel and Slinky styles (ClickGUI > Style).
 *
 * Every frame lays out and draws the windows in one pass and records what is
 * clickable ({@link GlideHit}); a click is matched against the hits of the
 * frame the player saw.
 *
 * Main window: a category shows or hides its window; the gear opens the
 * menu's own settings (ClickGUI). Window title: drag to move, right click or
 * the arrow to fold. Module: left click switches it, right click or the
 * three dots open its settings under it ({@link GlideSettings}). Search box:
 * click and type to keep only matching modules. Wheel over a window scrolls
 * it. Resting the pointer on a module or a setting shows its description.
 */
public class GlideClickGui extends GuiScreen {
    private static GlideClickGui instance;

    private static final float SEARCH_W = 140.0F;
    private static final float SEARCH_H = 18.0F;
    private static final float SECTION_H = 14.0F;

    private final List<GlideFrame> frames = new ArrayList<GlideFrame>();
    private final GlideFrame mainFrame;
    /** Frames with no saved place yet: laid out in a row on the first open. */
    private final List<GlideFrame> unplaced = new ArrayList<GlideFrame>();
    private final List<GlideHit> hits = new ArrayList<GlideHit>();
    private final GlideAnim anim = new GlideAnim();
    private final GlideSettings settings = new GlideSettings(this, this.anim);

    private GlideFrame dragging;
    private float dragX;
    private float dragY;
    private long openedAt;

    private final TextInput search = new TextInput(32, false);
    private boolean searchFocused;

    /** What was on top under the pointer last frame: hover tints follow it, so a covered row never lights up. */
    private GlideHit lastTop;
    /** The row under the pointer, and since when, for its description. */
    private GlideHit hovered;
    private long hoveredSince;

    /** A one-line message at the foot of the screen (bind conflicts, refused values, resets). */
    private String status = "";
    private boolean statusWarn;
    private long statusAt;

    private GlideClickGui() {
        Map<String, float[]> saved = GlideState.read();
        this.mainFrame = new GlideFrame("Atlas", "client", new ArrayList<Module>(), true);
        add(this.mainFrame, saved);
        for (Category category : Category.values()) {
            List<Module> modules = new ArrayList<Module>(ModuleCategories.modulesIn(category,
                    Atlas.moduleManager.modules));
            if (modules.isEmpty()) {
                continue;
            }
            modules.sort(Comparator.comparing(m -> m.getName().toLowerCase()));
            add(new GlideFrame(category.displayName(), category.displayName().toLowerCase(Locale.ROOT), modules,
                    false), saved);
        }
    }

    private void add(GlideFrame frame, Map<String, float[]> saved) {
        if (saved.containsKey(frame.title)) {
            GlideState.place(frame, saved);
        } else {
            this.unplaced.add(frame);
        }
        this.frames.add(frame);
    }

    public static GlideClickGui getInstance() {
        if (instance == null) {
            instance = new GlideClickGui();
        }
        return instance;
    }

    @Override
    public void initGui() {
        super.initGui();
        FontManager.initializeFonts();
        Keyboard.enableRepeatEvents(true);
        this.openedAt = System.currentTimeMillis();
        this.dragging = null;
        this.hovered = null;
        this.lastTop = null;
        this.searchFocused = false;
        this.anim.reset();
        ScaledResolution sr = new ScaledResolution(mc);
        layOutUnplaced(sr);
        /* Keep every title on screen after a resize or a GUI scale change. */
        for (GlideFrame frame : this.frames) {
            clamp(frame, sr);
            frame.shownScroll = frame.scroll;
        }
        /* A first layout or a clamp after a resize is kept even if the game is closed with the menu open. */
        saveState();
    }

    /** Puts windows that were never placed side by side below the search box, wrapping at the screen's edge. */
    private void layOutUnplaced(ScaledResolution sr) {
        if (this.unplaced.isEmpty()) {
            return;
        }
        float step = GlideTheme.WIDTH + GlideTheme.GAP;
        float top = GlideTheme.MARGIN + SEARCH_H + 8.0F;
        int perRow = Math.max(1, (int) ((sr.getScaledWidth() - 2 * GlideTheme.MARGIN + GlideTheme.GAP) / step));
        for (int i = 0; i < this.unplaced.size(); i++) {
            GlideFrame frame = this.unplaced.get(i);
            int slot = this.frames.indexOf(frame);
            frame.x = GlideTheme.MARGIN + (slot % perRow) * step;
            frame.y = top + (slot / perRow) * (sr.getScaledHeight() / 2.0F);
        }
        this.unplaced.clear();
    }

    private static void clamp(GlideFrame frame, ScaledResolution sr) {
        frame.x = Math.max(0.0F, Math.min(sr.getScaledWidth() - GlideTheme.WIDTH, frame.x));
        frame.y = Math.max(0.0F, Math.min(sr.getScaledHeight() - GlideTheme.HEADER, frame.y));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- for GlideSettings ---------------------------------------------

    void addHit(GlideHit hit) {
        this.hits.add(hit);
    }

    /** Whether {@code hit} was on top under the pointer last frame. */
    boolean isOver(GlideHit hit) {
        return this.dragging == null && !this.settings.dragging() && hit.same(this.lastTop);
    }

    void say(String message, boolean warn) {
        this.status = message;
        this.statusWarn = warn;
        this.statusAt = System.currentTimeMillis();
    }

    // ---- search --------------------------------------------------------

    private String query() {
        return this.search.text().trim().toLowerCase(Locale.ROOT);
    }

    /** The modules a window lists now: all of them, or those whose name has the search text. */
    private List<Module> listed(GlideFrame frame) {
        String query = query();
        if (query.isEmpty()) {
            return frame.modules;
        }
        String flat = query.replace(" ", "");
        List<Module> out = new ArrayList<Module>();
        for (Module module : frame.modules) {
            if (module.getName().toLowerCase(Locale.ROOT).contains(flat)) {
                out.add(module);
            }
        }
        return out;
    }

    /** Whether a window is on screen: the main window always; a category when shown, or when the search finds something in it. */
    private boolean visible(GlideFrame frame) {
        if (frame.main) {
            return true;
        }
        return query().isEmpty() ? !frame.hidden : !listed(frame).isEmpty();
    }

    // ---- drawing -------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.anim.frame();
        this.hits.clear();
        ScaledResolution sr = new ScaledResolution(mc);
        long elapsed = System.currentTimeMillis() - this.openedAt;
        int accent = GlideTheme.accent();

        if (this.dragging != null) {
            this.dragging.x = mouseX - this.dragX;
            this.dragging.y = mouseY - this.dragY;
            clamp(this.dragging, sr);
        }
        if (this.settings.dragging()) {
            if (Mouse.isButtonDown(0)) {
                this.settings.dragTo(mouseX, mouseY);
            } else {
                this.settings.release();
            }
        }

        /* The world behind is blurred whole, as in the reference; without blur it is dimmed instead. */
        float fade = GlideTheme.easeOut(elapsed / GlideTheme.FADE_MS);
        boolean blur = GlideTheme.blur();
        if (blur) {
            BlurUtils.prepareBlur();
            RoundedUtils.drawRound(0.0F, 0.0F, sr.getScaledWidth(), sr.getScaledHeight(), 0.0F, 0xFFFFFFFF);
            BlurUtils.blurEnd(2, 2.0F + 2.0F * fade);
        }
        RenderUtil.drawRect(0.0F, 0.0F, sr.getScaledWidth(), sr.getScaledHeight(),
                GlideTheme.alpha(blur ? 0x18000000 : 0x60000000, fade));

        float[] shown = new float[this.frames.size()];
        for (int i = 0; i < this.frames.size(); i++) {
            GlideFrame frame = this.frames.get(i);
            float target = visible(frame) ? 1.0F : 0.0F;
            float pop = this.anim.ease("vis:" + frame.title, target, 16.0F);
            shown[i] = Math.min(pop, GlideTheme.easeOut((elapsed - i * GlideTheme.STAGGER_MS) / GlideTheme.PANEL_MS));
            measure(frame, shown[i], sr);
        }

        try {
            for (int i = 0; i < this.frames.size(); i++) {
                if (shown[i] > 0.02F) {
                    drawFrame(this.frames.get(i), shown[i], accent);
                }
            }
        } finally {
            GlideDraw.resetClips();
        }
        drawSearch(sr, fade, accent);

        GlideHit top = this.dragging == null && !this.settings.dragging() ? hitAt(mouseX, mouseY) : null;
        this.lastTop = top;
        GlideHit tip = top != null && (top.kind == GlideHit.Kind.MODULE || top.kind.ordinal() >= GlideHit.Kind.BIND.ordinal())
                ? top : null;
        if (tip == null ? this.hovered != null : !tip.same(this.hovered)) {
            this.hovered = tip;
            this.hoveredSince = System.currentTimeMillis();
        }
        if (this.hovered != null && System.currentTimeMillis() - this.hoveredSince >= GlideTheme.TOOLTIP_MS) {
            String text = this.hovered.kind == GlideHit.Kind.MODULE ? ((Module) this.hovered.target).getDescription()
                    : this.settings.help(this.hovered);
            drawTooltip(text, mouseX, mouseY, sr);
        }
        drawStatus(sr, accent);

        handleInvWalk();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** This frame's geometry: the drop-in offset, the eased fold, open settings, and how far the list scrolls. */
    private void measure(GlideFrame frame, float shown, ScaledResolution sr) {
        frame.drawY = frame.y + (1.0F - shown) * -6.0F;
        float content = GlideTheme.PAD;
        if (frame.main) {
            content += mainRows() * GlideTheme.ROW + SECTION_H;
        } else {
            for (Module module : listed(frame)) {
                content += GlideTheme.ROW;
                float open = this.settings.openness(module);
                if (open > 0.001F) {
                    content += this.settings.height(module) * open;
                }
            }
        }
        frame.contentH = content;
        frame.maxBody = Math.max(GlideTheme.MIN_ROWS * GlideTheme.ROW + GlideTheme.PAD,
                sr.getScaledHeight() - frame.y - GlideTheme.HEADER - GlideTheme.MARGIN);
        frame.body = this.anim.ease("body:" + frame.title, frame.folded ? 0.0F : frame.openBody(), 16.0F);
        frame.maxScroll = Math.max(0.0F, frame.contentH - frame.openBody());
        frame.scroll = Math.max(0.0F, Math.min(frame.maxScroll, frame.scroll));
        frame.shownScroll = this.anim.ease("scroll:" + frame.title, frame.scroll, 16.0F);
        frame.shownScroll = Math.min(frame.shownScroll, frame.maxScroll);
        frame.shownHeight = GlideTheme.HEADER + frame.body;
    }

    /** Rows in the main window: one per category window, then Friends and Profiles. */
    private int mainRows() {
        return this.frames.size() - 1 + 2;
    }

    /** The hit on top under the pointer, or null. */
    private GlideHit hitAt(float mx, float my) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            if (this.hits.get(i).contains(mx, my)) {
                return this.hits.get(i);
            }
        }
        return null;
    }

    /** An Atlas icon scaled to {@code size} px, centred on (cx, cy). */
    private static void icon(String name, float cx, float cy, float size, int color) {
        float s = size / 12.0F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        GlStateManager.scale(s, s, 1.0F);
        AtlasIcons.draw(name, 0.0F, 0.0F, color);
        GlStateManager.popMatrix();
    }

    /** Three dots one above the other, centred on (cx, cy). */
    private static void dots(float cx, float cy, int color) {
        for (int i = -1; i <= 1; i++) {
            float y = cy + i * 2.6F;
            RenderUtil.drawRect(cx - 0.6F, y - 0.6F, cx + 0.6F, y + 0.6F, color);
        }
    }

    private void drawFrame(GlideFrame frame, float shown, int accent) {
        float x = frame.x;
        float y = frame.drawY;
        float w = GlideTheme.WIDTH;
        float h = GlideTheme.HEADER;

        this.hits.add(new GlideHit(GlideHit.Kind.PANEL, frame, null, x, y, x + w, y + frame.shownHeight));
        GlideHit header = new GlideHit(GlideHit.Kind.HEADER, frame, null, x, y, x + w, y + h);
        this.hits.add(header);

        RoundedUtils.drawRound(x, y, w, frame.shownHeight, GlideTheme.RADIUS, GlideTheme.alpha(GlideTheme.PANEL, shown));
        int headerBg = frame.main ? GlideTheme.MAIN_HEADER : GlideTheme.HEADER_BG;
        RoundedUtils.drawRound(x, y, w, h, GlideTheme.RADIUS, GlideTheme.alpha(headerBg, shown));
        if (frame.body > 0.5F) {
            /* Square off the header's lower corners where the body hangs from it. */
            RenderUtil.drawRect(x, y + h - GlideTheme.RADIUS, x + w, y + h, GlideTheme.alpha(headerBg, shown));
            RenderUtil.drawRect(x, y + h - 0.5F, x + w, y + h, GlideTheme.alpha(GlideTheme.SEPARATOR, shown));
        }

        if (frame.main) {
            /* Wordmark and version, then the gear for the menu's own settings. */
            FontRenderer bold = GlideDraw.titleFont();
            GlideDraw.text(bold, "ATLAS", x + 7.0F, y, h, GlideTheme.alpha(GlideTheme.TEXT, shown));
            String version = Atlas.version == null ? "" : Atlas.version;
            if (!version.isEmpty()) {
                GlideDraw.text(GlideDraw.smallFont(), version, x + 9.0F + GlideDraw.width(bold, "ATLAS"), y + 1.0F, h,
                        GlideTheme.alpha(accent, shown));
            }
            GlideHit gear = new GlideHit(GlideHit.Kind.GEAR, frame, null, x + w - 18.0F, y, x + w, y + h);
            this.hits.add(gear);
            icon("client", x + w - 10.0F, y + h / 2.0F, 9.0F,
                    GlideTheme.alpha(isOver(gear) ? GlideTheme.TEXT : GlideTheme.DIM, shown));
        } else {
            icon(frame.icon, x + 10.0F, y + h / 2.0F, 8.5F, GlideTheme.alpha(GlideTheme.TITLE, shown));
            GlideDraw.text(GlideDraw.smallFont(), frame.title, x + 19.0F, y, h, GlideTheme.alpha(GlideTheme.TITLE, shown));
            GlideHit fold = new GlideHit(GlideHit.Kind.FOLD, frame, null, x + w - 18.0F, y, x + w, y + h);
            this.hits.add(fold);
            icon(frame.folded ? "chevron" : "chevronup", x + w - 10.0F, y + h / 2.0F, 7.0F,
                    GlideTheme.alpha(isOver(fold) ? GlideTheme.TEXT : GlideTheme.FAINT, shown));
        }

        if (frame.body <= 0.5F) {
            return;
        }
        float clipTop = y + h;
        float clipBottom = y + frame.shownHeight;
        GlideDraw.pushClip(x, clipTop, x + w, clipBottom);
        float rowY = clipTop - frame.shownScroll;
        if (frame.main) {
            drawMain(frame, x, rowY, shown, accent);
        } else {
            List<Module> listed = listed(frame);
            for (int i = 0; i < listed.size(); i++) {
                Module module = listed.get(i);
                float open = this.settings.openness(module);
                boolean last = i == listed.size() - 1 && open <= 0.001F;
                if (rowY + GlideTheme.ROW > clipTop && rowY < clipBottom) {
                    drawModule(frame, module, x, rowY, last, shown, accent);
                }
                rowY += GlideTheme.ROW;
                if (open > 0.001F) {
                    float full = this.settings.height(module);
                    if (rowY + full * open > clipTop && rowY < clipBottom) {
                        this.settings.draw(frame, module, x, rowY, full * open, shown, accent);
                    }
                    rowY += full * open;
                }
            }
        }
        GlideDraw.popClip();

        if (frame.maxScroll > 0.0F) {
            float track = frame.body - 2 * GlideTheme.PAD;
            float thumb = Math.max(12.0F, track * frame.openBody() / frame.contentH);
            float at = clipTop + GlideTheme.PAD + (track - thumb) * (frame.shownScroll / frame.maxScroll);
            RoundedUtils.drawRound(x + w - 2.5F, at, 1.5F, thumb, 0.75F, GlideTheme.alpha(0x50FFFFFF, shown));
        }
    }

    /** One row of the main window: icon, name, an optional chip, and the arrow at the right. */
    private float mainRow(GlideFrame frame, GlideHit.Kind kind, Object target, String iconName, String label,
                          String chip, boolean lit, float x, float rowY, float shown, int accent) {
        float w = GlideTheme.WIDTH;
        float h = GlideTheme.ROW;
        GlideHit hit = new GlideHit(kind, frame, target, x, Math.max(rowY, GlideDraw.clipTop()), x + w,
                Math.min(rowY + h, GlideDraw.clipBottom()));
        this.hits.add(hit);
        float hover = this.anim.ease("mh:" + label, isOver(hit) ? 1.0F : 0.0F, 18.0F);
        if (hover > 0.01F) {
            RenderUtil.drawRect(x, rowY, x + w, rowY + h, GlideTheme.alpha(GlideTheme.HOVER, shown * hover));
        }
        float lt = this.anim.ease("ml:" + label, lit ? 1.0F : 0.0F, 14.0F);
        int color = GlideTheme.alpha(GlideTheme.mix(GlideTheme.mix(GlideTheme.DIM, GlideTheme.TEXT, hover * 0.5F),
                accent, lt), shown);
        icon(iconName, x + 10.0F, rowY + h / 2.0F, 8.5F, color);
        FontRenderer font = GlideDraw.smallFont();
        GlideDraw.text(font, label, x + 19.0F, rowY, h, color);
        float right = x + w - 7.0F;
        icon("chevron", right - 3.0F, rowY + h / 2.0F, 6.0F,
                GlideTheme.alpha(GlideTheme.mix(GlideTheme.FAINT, GlideTheme.DIM, hover), shown));
        if (chip != null) {
            float chipW = GlideDraw.width(font, chip) + 8.0F;
            float chipX = right - 10.0F - chipW;
            RoundedUtils.drawRound(chipX, rowY + 5.0F, chipW, h - 10.0F, 2.5F, GlideTheme.alpha(GlideTheme.CHIP, shown));
            GlideDraw.text(font, chip, chipX + 4.0F, rowY, h, GlideTheme.alpha(GlideTheme.DIM, shown));
        }
        return rowY + h;
    }

    private void drawMain(GlideFrame frame, float x, float rowY, float shown, int accent) {
        for (GlideFrame window : this.frames) {
            if (!window.main) {
                rowY = mainRow(frame, GlideHit.Kind.CATEGORY, window, window.icon, window.title, null, !window.hidden,
                        x, rowY, shown, accent);
            }
        }
        GlideDraw.text(GlideDraw.smallFont(), "CLIENT", x + 7.0F, rowY + 1.0F, SECTION_H,
                GlideTheme.alpha(GlideTheme.SECTION, shown));
        rowY += SECTION_H;
        int friends = Atlas.friendManager == null || Atlas.friendManager.players == null ? 0
                : Atlas.friendManager.players.size();
        rowY = mainRow(frame, GlideHit.Kind.FRIENDS, null, "player", "Friends", String.valueOf(friends), false,
                x, rowY, shown, accent);
        String profile = Config.lastConfig == null || "!".equals(Config.lastConfig) ? "default" : Config.lastConfig;
        mainRow(frame, GlideHit.Kind.PROFILES, null, "folder", "Profiles",
                GlideDraw.trim(GlideDraw.smallFont(), profile, 38.0F), false, x, rowY, shown, accent);
    }

    private void drawModule(GlideFrame frame, Module module, float x, float rowY, boolean last, float shown,
                            int accent) {
        float w = GlideTheme.WIDTH;
        float h = GlideTheme.ROW;
        GlideHit hit = new GlideHit(GlideHit.Kind.MODULE, frame, module, x, Math.max(rowY, GlideDraw.clipTop()),
                x + w - 14.0F, Math.min(rowY + h, GlideDraw.clipBottom()));
        GlideHit dotsHit = new GlideHit(GlideHit.Kind.DOTS, frame, module, x + w - 14.0F,
                Math.max(rowY, GlideDraw.clipTop()), x + w, Math.min(rowY + h, GlideDraw.clipBottom()));
        this.hits.add(hit);
        this.hits.add(dotsHit);

        String key = module.getName();
        float on = this.anim.ease("on:" + key, module.isEnabled() ? 1.0F : 0.0F, 14.0F);
        boolean over = isOver(hit) || isOver(dotsHit);
        float hover = this.anim.ease("hv:" + key, over ? 1.0F : 0.0F, 18.0F);

        int fill = GlideTheme.mix(GlideTheme.mix(GlideTheme.PANEL, GlideTheme.ROW_HOVER, hover),
                GlideTheme.mix(accent, 0xFFFFFFFF, hover * 0.08F), on);
        if (on > 0.01F || hover > 0.01F) {
            if (last) {
                RenderUtil.drawRoundedRect(x, rowY, w, h, GlideTheme.RADIUS, GlideTheme.alpha(fill, shown),
                        false, false, true, true);
            } else {
                RenderUtil.drawRect(x, rowY, x + w, rowY + h, GlideTheme.alpha(fill, shown));
            }
        }

        float dotsX = x + w - 8.0F;
        int dotColor = GlideTheme.mix(GlideTheme.mix(GlideTheme.FAINT, GlideTheme.DIM, isOver(dotsHit) ? 1.0F : 0.0F),
                GlideTheme.TEXT, on);
        dots(dotsX, rowY + h / 2.0F, GlideTheme.alpha(dotColor, shown));

        FontRenderer font = GlideDraw.smallFont();
        float textX = x + 7.0F;
        float room = dotsX - 6.0F - textX;
        if (module.getKey() > 0) {
            String bind = GlideDraw.keyName(module.getKey());
            float chipW = Math.max(10.0F, GlideDraw.width(font, bind) + 6.0F);
            float chipX = dotsX - 6.0F - chipW;
            int chipBg = GlideTheme.mix(GlideTheme.CHIP, GlideTheme.CHIP_ON, on);
            RoundedUtils.drawRound(chipX, rowY + 5.0F, chipW, h - 10.0F, 2.0F, GlideTheme.alpha(chipBg, shown));
            GlideDraw.text(font, bind, chipX + (chipW - GlideDraw.width(font, bind)) / 2.0F, rowY, h,
                    GlideTheme.alpha(GlideTheme.mix(GlideTheme.DIM, GlideTheme.TEXT, on), shown));
            room = chipX - 4.0F - textX;
        }
        int text = GlideTheme.mix(GlideTheme.mix(GlideTheme.DIM, GlideTheme.TEXT, hover * 0.5F), GlideTheme.TEXT, on);
        GlideDraw.text(font, GlideDraw.trim(font, module.getName(), room), textX, rowY, h,
                GlideTheme.alpha(text, shown));
    }

    /** The search box, centred at the top. */
    private void drawSearch(ScaledResolution sr, float fade, int accent) {
        float x = (sr.getScaledWidth() - SEARCH_W) / 2.0F;
        float y = 6.0F;
        GlideHit hit = new GlideHit(GlideHit.Kind.SEARCH, null, null, x, y, x + SEARCH_W, y + SEARCH_H);
        this.hits.add(hit);
        float hover = this.anim.ease("search", isOver(hit) || this.searchFocused ? 1.0F : 0.0F, 18.0F);
        RoundedUtils.drawRound(x, y, SEARCH_W, SEARCH_H, GlideTheme.RADIUS,
                GlideTheme.alpha(GlideTheme.mix(GlideTheme.SEARCH_BG, 0xFF1E1E1E, hover), fade));
        if (this.searchFocused) {
            RoundedUtils.drawRound(x + 4.0F, y + SEARCH_H - 1.5F, SEARCH_W - 8.0F, 1.0F, 0.5F,
                    GlideTheme.alpha(accent, fade));
        }
        icon("search", x + SEARCH_W - 10.0F, y + SEARCH_H / 2.0F, 8.0F, GlideTheme.alpha(GlideTheme.DIM, fade));
        FontRenderer font = GlideDraw.smallFont();
        String text = this.search.text();
        if (text.isEmpty() && !this.searchFocused) {
            GlideDraw.text(font, "Search", x + 7.0F, y, SEARCH_H, GlideTheme.alpha(GlideTheme.FAINT, fade));
        } else {
            boolean blink = System.currentTimeMillis() / 500L % 2L == 0L;
            String shown = this.searchFocused && blink ? text + "|" : text;
            GlideDraw.text(font, GlideDraw.tail(font, shown, SEARCH_W - 26.0F), x + 7.0F, y, SEARCH_H,
                    GlideTheme.alpha(GlideTheme.TEXT, fade));
        }
    }

    /** A description in a box by the pointer, wrapped, kept on screen. */
    private void drawTooltip(String description, float mx, float my, ScaledResolution sr) {
        if (description == null || description.trim().isEmpty()) {
            return;
        }
        FontRenderer font = GlideDraw.smallFont();
        List<String> lines = GlideDraw.wrap(font, description.trim(), GlideTheme.TOOLTIP_WIDTH);
        float lineH = GlideDraw.lineHeight(font);
        float w = 0.0F;
        for (String line : lines) {
            w = Math.max(w, GlideDraw.width(font, line));
        }
        w += 12.0F;
        float h = lines.size() * lineH + 8.0F;
        float x = Math.min(mx + 10.0F, sr.getScaledWidth() - w - 2.0F);
        float y = Math.min(my + 10.0F, sr.getScaledHeight() - h - 2.0F);
        RoundedUtils.drawRound(x, y, w, h, GlideTheme.RADIUS, GlideTheme.TOOLTIP);
        for (int i = 0; i < lines.size(); i++) {
            GlideDraw.text(font, lines.get(i), x + 6.0F, y + 4.0F + i * lineH, lineH, GlideTheme.TEXT);
        }
    }

    private void drawStatus(ScaledResolution sr, int accent) {
        long age = System.currentTimeMillis() - this.statusAt;
        long life = this.statusWarn ? 4000L : 2500L;
        if (this.status.isEmpty() || age > life) {
            return;
        }
        float fade = Math.min(1.0F, Math.min(age / 120.0F, (life - age) / 300.0F));
        FontRenderer font = GlideDraw.smallFont();
        float w = Math.min(GlideDraw.width(font, this.status) + 20.0F, sr.getScaledWidth() - 20.0F);
        float x = (sr.getScaledWidth() - w) / 2.0F;
        float y = sr.getScaledHeight() - 30.0F;
        RoundedUtils.drawRound(x, y, w, 18.0F, GlideTheme.RADIUS, GlideTheme.alpha(GlideTheme.TOOLTIP, fade));
        RenderUtil.drawRect(x, y + 17.0F, x + w, y + 18.0F,
                GlideTheme.alpha(this.statusWarn ? GlideTheme.WARN : accent, fade));
        GlideDraw.text(font, GlideDraw.trim(font, this.status, w - 20.0F), x + 10.0F, y, 18.0F,
                GlideTheme.alpha(GlideTheme.TEXT, fade));
    }

    // ---- input ---------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        GlideHit hit = hitAt(mouseX, mouseY);
        if (hit == null || hit.kind != GlideHit.Kind.SEARCH) {
            this.searchFocused = false;
        }
        if (hit == null) {
            this.settings.clickElsewhere();
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        if (hit.frame != null) {
            bringToFront(hit.frame);
        }
        if (this.settings.click(hit, mouseX, mouseY, button)) {
            return;
        }
        this.settings.clickElsewhere();
        switch (hit.kind) {
            case HEADER:
                if (button == 0) {
                    this.dragging = hit.frame;
                    this.dragX = mouseX - hit.frame.x;
                    this.dragY = mouseY - hit.frame.y;
                } else if (button == 1) {
                    hit.frame.folded = !hit.frame.folded;
                    saveState();
                }
                break;
            case FOLD:
                hit.frame.folded = !hit.frame.folded;
                saveState();
                break;
            case MODULE:
                if (button == 0) {
                    ((Module) hit.target).toggle();
                } else if (button == 1) {
                    this.settings.toggleExpanded((Module) hit.target);
                }
                break;
            case DOTS:
                this.settings.toggleExpanded((Module) hit.target);
                break;
            case CATEGORY: {
                GlideFrame window = (GlideFrame) hit.target;
                window.hidden = !window.hidden;
                if (!window.hidden) {
                    bringToFront(window);
                }
                saveState();
                break;
            }
            case GEAR:
                openMenuSettings();
                break;
            case FRIENDS:
                say("Add or remove friends with .friend add <name> / .friend remove <name>", false);
                break;
            case PROFILES:
                say("Profiles: .config load <name> / .config save <name> (a profiles window comes next)", false);
                break;
            case SEARCH:
                if (button == 1) {
                    this.search.set("");
                }
                this.searchFocused = true;
                break;
            default:
                break;
        }
    }

    /** Shows the window holding ClickGUI and opens its settings there (accent, blur, style). */
    private void openMenuSettings() {
        Module gui = Atlas.moduleManager.getModule("ClickGUI");
        for (GlideFrame frame : this.frames) {
            if (!frame.main && frame.modules.contains(gui)) {
                frame.hidden = false;
                frame.folded = false;
                if (!this.settings.isExpanded(gui)) {
                    this.settings.toggleExpanded(gui);
                }
                bringToFront(frame);
                saveState();
                return;
            }
        }
    }

    private void bringToFront(GlideFrame frame) {
        this.frames.remove(frame);
        this.frames.add(frame);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (this.dragging != null) {
            this.dragging = null;
            saveState();
        }
        this.settings.release();
    }

    /**
     * Writes where the windows are. Called after every move, fold and hide,
     * not only on close: closing the game window with the menu open never
     * calls onGuiClosed (seen in the G2 test), and the layout was lost.
     */
    private void saveState() {
        GlideState.write(this.frames);
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
        for (int i = this.frames.size() - 1; i >= 0; i--) {
            GlideFrame frame = this.frames.get(i);
            if (visible(frame) && frame.over(mx, my)) {
                float step = GlideTheme.ROW * 3;
                frame.scroll = Math.max(0.0F, Math.min(frame.maxScroll, frame.scroll + (wheel > 0 ? -step : step)));
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typed, int key) throws IOException {
        if (this.settings.capturesKeys()) {
            this.settings.key(typed, key);
            return;
        }
        if (this.searchFocused) {
            TextInput.Result result = this.search.key(typed, key, GuiScreen.isCtrlKeyDown(), GuiScreen::getClipboardString);
            if (result == TextInput.Result.CANCEL) {
                if (this.search.text().isEmpty()) {
                    this.searchFocused = false;
                } else {
                    this.search.set("");
                }
            } else if (result == TextInput.Result.SUBMIT) {
                this.searchFocused = false;
            }
            return;
        }
        if (key == Keyboard.KEY_F && GuiScreen.isCtrlKeyDown()) {
            this.searchFocused = true;
            return;
        }
        Module gui = Atlas.moduleManager.getModule("ClickGUI");
        if (key == Keyboard.KEY_ESCAPE || gui != null && key == gui.getKey()
                && System.currentTimeMillis() - this.openedAt > 150L) {
            mc.displayGuiScreen(null);
        }
    }

    private void handleInvWalk() {
        Module invWalk = Atlas.moduleManager.getModule("InvWalk");
        if (invWalk == null || !invWalk.isEnabled() || this.settings.capturesKeys() || this.searchFocused) {
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
        this.settings.close();
        this.dragging = null;
        this.hovered = null;
        this.searchFocused = false;
        saveState();
        Module gui = Atlas.moduleManager.getModule("ClickGUI");
        if (gui instanceof ClickGUIModule && ((ClickGUIModule) gui).isSwitchingGuiStyle()) {
            return;
        }
        if (gui != null) {
            gui.setEnabled(false);
        }
    }
}
