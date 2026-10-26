package atlas.ui.impl.clickgui.glide;

import atlas.Atlas;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.LongProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.TextProperty;
import atlas.ui.impl.clickgui.atlas.KeyConflicts;
import atlas.ui.impl.clickgui.atlas.SettingFacts;
import atlas.ui.impl.clickgui.atlas.TextInput;
import atlas.util.RenderUtil;
import atlas.util.font.impl.FontRenderer;
import atlas.util.shader.RoundedUtils;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Glide style's settings, opened in place under a module's row (stage
 * G2): what is open, what is being dragged, typed into or bound, how tall it
 * all is, and how each kind of setting is drawn and changed.
 *
 * Rows: the module's key first, then every setting its module shows now.
 * Switch: click. Number: drag the track (Shift for fine steps), double click
 * to type a value. Mode: click for the next, right click for the list.
 * Colour: click to open the picker (shade box, hue bar, hex). Text: click to
 * type. Key: click, then press a key (Esc, Delete or Backspace clears; right
 * click clears too). Middle click on any setting puts it back to its default.
 */
final class GlideSettings {
    static final float ROW = 14.0F;
    static final float SLIDER = 22.0F;
    static final float OPTION = 12.0F;
    static final float SV_H = 46.0F;
    static final float HUE_H = 6.0F;
    /** The colour picker under a colour row: shade box, hue bar, hex field and gaps. */
    static final float PICKER = 4.0F + SV_H + 4.0F + HUE_H + 4.0F + ROW + 3.0F;
    /** Space above and below the rows of one module's settings. */
    static final float INSET = 2.0F;
    private static final long DOUBLE_CLICK_MS = 300L;

    private final GlideClickGui gui;
    private final GlideAnim anim;
    private final Set<Module> expanded = new HashSet<Module>();
    /** Which module each drawn setting belongs to (Property does not say), for key-conflict names. */
    private final Map<Property<?>, Module> owners = new HashMap<Property<?>, Module>();

    private ModeProperty modeOpen;
    private ColorProperty colorOpen;
    /** The open picker's hue, saturation and brightness: kept apart so a grey keeps its hue while dragging. */
    private final float[] hsb = new float[3];

    private Module listeningModule;
    private KeyProperty listeningKey;

    /** A number, text or hex field being typed into, and whether its last Enter was refused. */
    private Property<?> editing;
    private boolean editingHex;
    private final TextInput input = new TextInput(64, false);
    private boolean refused;

    /** The slider or picker part being dragged, and a slider's unrounded value (for Shift steps). */
    private GlideHit drag;
    private float dragValue;
    private float dragLastX;

    private Property<?> lastClicked;
    private long lastClickAt;

    GlideSettings(GlideClickGui gui, GlideAnim anim) {
        this.gui = gui;
        this.anim = anim;
    }

    // ---- state ---------------------------------------------------------

    boolean isExpanded(Module module) {
        return this.expanded.contains(module);
    }

    void toggleExpanded(Module module) {
        if (!this.expanded.remove(module)) {
            this.expanded.add(module);
        }
    }

    /** How far a module's settings are open, eased (0-1). */
    float openness(Module module) {
        return this.anim.ease("open:" + module.getName(), this.expanded.contains(module) ? 1.0F : 0.0F, 14.0F);
    }

    /** Whether keys go to a field or a key binding now, not to the menu. */
    boolean capturesKeys() {
        return this.editing != null || this.listeningModule != null || this.listeningKey != null;
    }

    boolean dragging() {
        return this.drag != null;
    }

    /** Leaves whatever is half done: a field is committed if it parses, else dropped. */
    void close() {
        if (this.editing != null) {
            commit(false);
        }
        this.listeningModule = null;
        this.listeningKey = null;
        this.drag = null;
    }

    static List<Property<?>> visibleOf(Module module) {
        List<Property<?>> all = Atlas.propertyManager.properties.get(module.getClass());
        if (all == null) {
            return Collections.emptyList();
        }
        List<Property<?>> out = new ArrayList<Property<?>>();
        for (Property<?> property : all) {
            if (property.isVisible()) {
                out.add(property);
            }
        }
        return out;
    }

    // ---- sizes ---------------------------------------------------------

    /** Full height of a module's settings, opened all the way. */
    float height(Module module) {
        float h = 2 * INSET + ROW;
        for (Property<?> property : visibleOf(module)) {
            h += rowHeight(property);
        }
        return h;
    }

    private float rowHeight(Property<?> property) {
        if (isNumeric(property)) {
            return SLIDER;
        }
        if (property == this.modeOpen) {
            return ROW + ((ModeProperty) property).visibleIndices().length * OPTION + 2.0F;
        }
        if (property == this.colorOpen) {
            return ROW + PICKER;
        }
        return ROW;
    }

    // ---- drawing -------------------------------------------------------

    /** Draws a module's settings from {@code top}, {@code shown} tall (the rest is clipped); returns nothing. */
    void draw(GlideFrame frame, Module module, float x, float top, float shown, float fade, int accent) {
        float w = GlideTheme.WIDTH;
        GlideDraw.pushClip(x, top, x + w, top + shown);
        RenderUtil.drawRect(x, top, x + w, top + height(module), GlideTheme.alpha(GlideTheme.SETTINGS_BG, fade));
        float left = x + 10.0F;
        float right = x + w - 8.0F;
        float rowY = top + INSET;
        drawBind(frame, module, x, left, right, rowY, fade, accent);
        rowY += ROW;
        for (Property<?> property : visibleOf(module)) {
            this.owners.put(property, module);
            float h = rowHeight(property);
            if (rowY + h > GlideDraw.clipTop() && rowY < GlideDraw.clipBottom()) {
                drawSetting(frame, module, property, x, left, right, rowY, fade, accent);
            }
            rowY += h;
        }
        GlideDraw.popClip();
    }

    private GlideHit hit(GlideHit.Kind kind, GlideFrame frame, Object target, float x1, float y1, float x2, float y2) {
        GlideHit hit = new GlideHit(kind, frame, target, x1, Math.max(y1, GlideDraw.clipTop()), x2,
                Math.min(y2, GlideDraw.clipBottom()));
        if (hit.y2 > hit.y1) {
            this.gui.addHit(hit);
        }
        return hit;
    }

    private void drawBind(GlideFrame frame, Module module, float x, float left, float right, float rowY,
                          float fade, int accent) {
        FontRenderer font = GlideDraw.smallFont();
        boolean listening = this.listeningModule == module;
        String value = listening ? "press a key" : GlideDraw.keyName(module.getKey());
        GlideHit hit = hit(GlideHit.Kind.BIND, frame, module, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
        hover(hit, x, rowY, ROW, fade);
        GlideDraw.text(font, "Key", left, rowY, ROW, GlideTheme.alpha(GlideTheme.DIM, fade));
        GlideDraw.textRight(font, value, right, rowY, ROW,
                GlideTheme.alpha(listening ? accent : module.getKey() > 0 ? GlideTheme.TEXT : GlideTheme.FAINT, fade));
    }

    /** The hover tint of a settings row. */
    private void hover(GlideHit hit, float x, float rowY, float h, float fade) {
        float t = this.anim.ease("shv:" + System.identityHashCode(hit.target) + ":" + hit.kind,
                this.gui.isOver(hit) ? 1.0F : 0.0F, 18.0F);
        if (t > 0.01F) {
            RenderUtil.drawRect(x, rowY, x + GlideTheme.WIDTH, rowY + h, GlideTheme.alpha(GlideTheme.HOVER, fade * t));
        }
    }

    private void label(FontRenderer font, Property<?> property, float left, float rowY, float h, float room,
                       float fade, int accent) {
        if (SettingFacts.changed(property)) {
            RoundedUtils.drawRound(left - 5.0F, rowY + h / 2.0F - 1.0F, 2.0F, 2.0F, 1.0F,
                    GlideTheme.alpha(accent, fade));
        }
        GlideDraw.text(font, GlideDraw.trim(font, property.getLabel(), room), left, rowY, h,
                GlideTheme.alpha(GlideTheme.DIM, fade));
    }

    private void drawSetting(GlideFrame frame, Module module, Property<?> property, float x, float left, float right,
                             float rowY, float fade, int accent) {
        FontRenderer font = GlideDraw.smallFont();
        float span = right - left;
        if (property instanceof BooleanProperty) {
            boolean on = ((BooleanProperty) property).getValue();
            GlideHit hit = hit(GlideHit.Kind.BOOL, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
            hover(hit, x, rowY, ROW, fade);
            label(font, property, left, rowY, ROW, span - 20.0F, fade, accent);
            float t = this.anim.ease("sw:" + System.identityHashCode(property), on ? 1.0F : 0.0F, 14.0F);
            float sw = 14.0F;
            float sh = 7.0F;
            float bx = right - sw;
            float by = rowY + (ROW - sh) / 2.0F;
            RoundedUtils.drawRound(bx, by, sw, sh, sh / 2.0F, GlideTheme.alpha(GlideTheme.mix(GlideTheme.TRACK, accent, t), fade));
            RoundedUtils.drawRound(bx + 1.0F + (sw - sh) * t, by + 1.0F, sh - 2.0F, sh - 2.0F, (sh - 2.0F) / 2.0F,
                    GlideTheme.alpha(GlideTheme.mix(GlideTheme.DIM, 0xFFFFFFFF, t), fade));
        } else if (isNumeric(property)) {
            GlideHit hit = hit(GlideHit.Kind.NUM, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + SLIDER);
            hit.trackX = left;
            hit.trackW = span;
            hover(hit, x, rowY, SLIDER, fade);
            String value = this.editing == property ? fieldText() : formatNumber(property);
            int valueColor = this.editing == property ? (this.refused ? 0xFFFF5A5A : accent) : GlideTheme.TEXT;
            float valueW = Math.min(GlideDraw.width(font, value), span * 0.55F);
            label(font, property, left, rowY, 13.0F, span - valueW - 6.0F, fade, accent);
            GlideDraw.textRight(font, GlideDraw.tail(font, value, span * 0.55F), right, rowY, 13.0F,
                    GlideTheme.alpha(valueColor, fade));
            float min = minimumOf(property);
            float max = maximumOf(property);
            float t = max > min ? (currentOf(property) - min) / (max - min) : 0.0F;
            t = this.anim.ease("sl:" + System.identityHashCode(property), Math.max(0.0F, Math.min(1.0F, t)), 20.0F);
            float trackY = rowY + 15.0F;
            RoundedUtils.drawRound(left, trackY, span, 2.0F, 1.0F, GlideTheme.alpha(GlideTheme.TRACK, fade));
            if (t > 0.0F) {
                RoundedUtils.drawRound(left, trackY, span * t, 2.0F, 1.0F, GlideTheme.alpha(accent, fade));
            }
            float knob = this.drag != null && this.drag.same(hit) ? 6.0F : 5.0F;
            RoundedUtils.drawRound(left + span * t - knob / 2.0F, trackY + 1.0F - knob / 2.0F, knob, knob, knob / 2.0F,
                    GlideTheme.alpha(0xFFFFFFFF, fade));
        } else if (property instanceof ModeProperty) {
            ModeProperty mode = (ModeProperty) property;
            GlideHit hit = hit(GlideHit.Kind.MODE, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
            hover(hit, x, rowY, ROW, fade);
            String value = mode.getModeString();
            float valueW = Math.min(GlideDraw.width(font, value), span * 0.55F);
            label(font, property, left, rowY, ROW, span - valueW - 6.0F, fade, accent);
            GlideDraw.textRight(font, GlideDraw.trim(font, value, span * 0.55F), right, rowY, ROW,
                    GlideTheme.alpha(accent, fade));
            if (mode == this.modeOpen) {
                float optY = rowY + ROW;
                RoundedUtils.drawRound(left - 2.0F, optY, span + 4.0F, mode.visibleIndices().length * OPTION + 1.0F,
                        2.5F, GlideTheme.alpha(0xFF101010, fade));
                for (int index : mode.visibleIndices()) {
                    GlideHit option = hit(GlideHit.Kind.MODE_OPTION, frame, property, x, optY, x + GlideTheme.WIDTH,
                            optY + OPTION);
                    option.index = index;
                    boolean picked = index == mode.getValue();
                    boolean over = this.gui.isOver(option);
                    if (over) {
                        RenderUtil.drawRect(left - 2.0F, optY, right + 2.0F, optY + OPTION,
                                GlideTheme.alpha(GlideTheme.HOVER, fade));
                    }
                    GlideDraw.text(font, GlideDraw.trim(font, mode.modeName(index), span - 4.0F), left + 3.0F, optY,
                            OPTION, GlideTheme.alpha(picked ? accent : over ? GlideTheme.TEXT : GlideTheme.DIM, fade));
                    optY += OPTION;
                }
            }
        } else if (property instanceof ColorProperty) {
            drawColor(frame, (ColorProperty) property, x, left, right, rowY, fade, accent);
        } else if (property instanceof TextProperty) {
            GlideHit hit = hit(GlideHit.Kind.TEXT, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
            hover(hit, x, rowY, ROW, fade);
            boolean typing = this.editing == property;
            String value = typing ? fieldText() : ((TextProperty) property).getValue();
            float labelW = Math.min(GlideDraw.width(font, property.getLabel()), span * 0.45F);
            label(font, property, left, rowY, ROW, labelW, fade, accent);
            float room = span - labelW - 6.0F;
            String shown = typing ? GlideDraw.tail(font, value, room) : GlideDraw.trim(font, value, room);
            GlideDraw.textRight(font, shown, right, rowY, ROW,
                    GlideTheme.alpha(typing ? (this.refused ? 0xFFFF5A5A : accent) : GlideTheme.TEXT, fade));
        } else if (property instanceof KeyProperty) {
            GlideHit hit = hit(GlideHit.Kind.KEY, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
            hover(hit, x, rowY, ROW, fade);
            boolean listening = this.listeningKey == property;
            int key = ((KeyProperty) property).getValue();
            String value = listening ? "press a key" : GlideDraw.keyName(key);
            label(font, property, left, rowY, ROW, span - GlideDraw.width(font, value) - 6.0F, fade, accent);
            GlideDraw.textRight(font, value, right, rowY, ROW,
                    GlideTheme.alpha(listening ? accent : key > 0 ? GlideTheme.TEXT : GlideTheme.FAINT, fade));
        } else {
            /* Lists, files and the like: shown here, set from the Atlas menu or a command. */
            GlideHit hit = hit(GlideHit.Kind.OTHER, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
            hover(hit, x, rowY, ROW, fade);
            String value = String.valueOf(property.getValue());
            GlideDraw.text(font, GlideDraw.trim(font, property.getLabel(), span * 0.5F), left, rowY, ROW,
                    GlideTheme.alpha(GlideTheme.FAINT, fade));
            GlideDraw.textRight(font, GlideDraw.trim(font, value, span * 0.45F), right, rowY, ROW,
                    GlideTheme.alpha(GlideTheme.FAINT, fade));
        }
    }

    private void drawColor(GlideFrame frame, ColorProperty property, float x, float left, float right, float rowY,
                           float fade, int accent) {
        FontRenderer font = GlideDraw.smallFont();
        float span = right - left;
        int rgb = property.getValue() & 0xFFFFFF;
        GlideHit hit = hit(GlideHit.Kind.COLOR, frame, property, x, rowY, x + GlideTheme.WIDTH, rowY + ROW);
        hover(hit, x, rowY, ROW, fade);
        label(font, property, left, rowY, ROW, span - 16.0F, fade, accent);
        RoundedUtils.drawRound(right - 12.0F, rowY + 3.5F, 12.0F, ROW - 7.0F, 2.0F, GlideTheme.alpha(0xFF000000 | rgb, fade));
        if (property != this.colorOpen) {
            return;
        }
        if (this.drag == null || this.drag.target != property) {
            Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, this.hsb);
        }
        float y = rowY + ROW + 4.0F;
        /* Shade box: the hue at full strength, washed to white leftward and to black downward. */
        int pure = Color.HSBtoRGB(this.hsb[0], 1.0F, 1.0F) | 0xFF000000;
        RenderUtil.drawRect(left, y, right, y + SV_H, GlideTheme.alpha(pure, fade));
        GlideDraw.gradientH(left, y, right, y + SV_H, GlideTheme.alpha(0xFFFFFFFF, fade), 0x00FFFFFF);
        GlideDraw.gradientV(left, y, right, y + SV_H, 0x00000000, GlideTheme.alpha(0xFF000000, fade));
        GlideHit sv = hit(GlideHit.Kind.COLOR_SV, frame, property, left, y, right, y + SV_H);
        sv.trackX = left;
        sv.trackY = y;
        sv.trackW = span;
        sv.trackH = SV_H;
        float cx = left + span * this.hsb[1];
        float cy = y + SV_H * (1.0F - this.hsb[2]);
        RoundedUtils.drawRound(cx - 2.5F, cy - 2.5F, 5.0F, 5.0F, 2.5F, GlideTheme.alpha(0xFFFFFFFF, fade));
        RoundedUtils.drawRound(cx - 1.5F, cy - 1.5F, 3.0F, 3.0F, 1.5F, GlideTheme.alpha(0xFF000000 | rgb, fade));
        y += SV_H + 4.0F;
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            int c = Color.HSBtoRGB(i / (float) steps, 1.0F, 1.0F) | 0xFF000000;
            int d = Color.HSBtoRGB((i + 1) / (float) steps, 1.0F, 1.0F) | 0xFF000000;
            GlideDraw.gradientH(left + span * i / steps, y, left + span * (i + 1) / steps, y + HUE_H,
                    GlideTheme.alpha(c, fade), GlideTheme.alpha(d, fade));
        }
        GlideHit hue = hit(GlideHit.Kind.COLOR_HUE, frame, property, left, y - 1.0F, right, y + HUE_H + 1.0F);
        hue.trackX = left;
        hue.trackW = span;
        float hx = left + span * this.hsb[0];
        RenderUtil.drawRect(hx - 1.0F, y - 1.0F, hx + 1.0F, y + HUE_H + 1.0F, GlideTheme.alpha(0xFFFFFFFF, fade));
        y += HUE_H + 4.0F;
        boolean typing = this.editing == property && this.editingHex;
        GlideHit hex = hit(GlideHit.Kind.COLOR_HEX, frame, property, left, y, right, y + ROW);
        RoundedUtils.drawRound(left, y, span, ROW, 3.0F,
                GlideTheme.alpha(typing ? 0x30FFFFFF : this.gui.isOver(hex) ? 0x24FFFFFF : 0x18FFFFFF, fade));
        String value = typing ? fieldText() : String.format("#%06X", rgb);
        GlideDraw.text(font, value, left + 4.0F, y, ROW,
                GlideTheme.alpha(typing ? (this.refused ? 0xFFFF5A5A : accent) : GlideTheme.TEXT, fade));
    }

    private String fieldText() {
        String text = this.input.text();
        if (this.input.allSelected()) {
            return text;
        }
        boolean blink = System.currentTimeMillis() / 500L % 2L == 0L;
        int caret = Math.min(this.input.caret(), text.length());
        return text.substring(0, caret) + (blink ? "|" : " ") + text.substring(caret);
    }

    /** The hover help of a settings hit, or null. */
    String help(GlideHit hit) {
        if (hit.kind == GlideHit.Kind.BIND) {
            return "The key that switches " + ((Module) hit.target).getName()
                    + ". Click, then press a key; Esc, Delete or right click clears it.";
        }
        if (!(hit.target instanceof Property) || hit.kind == GlideHit.Kind.MODE_OPTION) {
            return null;
        }
        Property<?> property = (Property<?>) hit.target;
        StringBuilder out = new StringBuilder();
        String help = property.getHelp();
        if (help != null && !help.trim().isEmpty()) {
            out.append(help.trim()).append('\n');
        }
        if (SettingFacts.resettable(property)) {
            out.append("Default ").append(SettingFacts.defaultText(property));
            String range = SettingFacts.range(property);
            if (!range.isEmpty()) {
                out.append(" · ").append(range);
            }
            if (SettingFacts.changed(property)) {
                out.append(" · middle click resets");
            }
        }
        if (property.isOverridden()) {
            out.append("\nHeld by ").append(property.getSourceOwner()).append(" right now.");
        }
        return out.length() == 0 ? null : out.toString().trim();
    }

    // ---- input ---------------------------------------------------------

    /** A click on one of this class's hits. True when it was handled. */
    boolean click(GlideHit hit, float mx, float my, int button) {
        if (this.editing != null && !(hit.target == this.editing
                && (hit.kind == GlideHit.Kind.NUM || hit.kind == GlideHit.Kind.TEXT || hit.kind == GlideHit.Kind.COLOR_HEX))) {
            commit(false);
        }
        if (button == 2 && hit.target instanceof Property && hit.kind != GlideHit.Kind.MODE_OPTION) {
            Property<?> property = (Property<?>) hit.target;
            if (SettingFacts.reset(property)) {
                this.gui.say(property.getLabel() + " back to " + SettingFacts.defaultText(property), false);
            }
            return true;
        }
        switch (hit.kind) {
            case BIND:
                this.listeningKey = null;
                if (button == 0) {
                    this.listeningModule = this.listeningModule == hit.target ? null : (Module) hit.target;
                } else if (button == 1) {
                    ((Module) hit.target).setKey(0);
                    this.listeningModule = null;
                }
                return true;
            case KEY:
                this.listeningModule = null;
                if (button == 0) {
                    this.listeningKey = this.listeningKey == hit.target ? null : (KeyProperty) hit.target;
                } else if (button == 1) {
                    ((KeyProperty) hit.target).setBase(Property.Source.USER, Integer.valueOf(0));
                    this.listeningKey = null;
                }
                return true;
            case BOOL:
                if (button == 0) {
                    BooleanProperty property = (BooleanProperty) hit.target;
                    property.setBase(Property.Source.USER, Boolean.valueOf(!property.getValue()));
                }
                return true;
            case NUM: {
                Property<?> property = (Property<?>) hit.target;
                long now = System.currentTimeMillis();
                boolean twice = this.lastClicked == property && now - this.lastClickAt < DOUBLE_CLICK_MS;
                this.lastClicked = property;
                this.lastClickAt = now;
                if (button == 0 && (twice || this.editing == property)) {
                    this.drag = null;
                    if (this.editing != property) {
                        startEditing(property, formatNumber(property), false);
                    }
                } else if (button == 0) {
                    this.drag = hit;
                    this.dragValue = currentOf(property);
                    this.dragLastX = mx;
                    dragTo(mx, my);
                }
                return true;
            }
            case MODE: {
                ModeProperty mode = (ModeProperty) hit.target;
                if (button == 1) {
                    this.modeOpen = this.modeOpen == mode ? null : mode;
                } else if (button == 0) {
                    int[] shown = mode.visibleIndices();
                    int at = 0;
                    for (int i = 0; i < shown.length; i++) {
                        if (shown[i] == mode.getValue()) {
                            at = i;
                        }
                    }
                    mode.setBase(Property.Source.USER, Integer.valueOf(shown[(at + 1) % shown.length]));
                }
                return true;
            }
            case MODE_OPTION:
                if (button == 0) {
                    ((ModeProperty) hit.target).setBase(Property.Source.USER, Integer.valueOf(hit.index));
                    this.modeOpen = null;
                }
                return true;
            case COLOR:
                if (button == 0 || button == 1) {
                    this.colorOpen = this.colorOpen == hit.target ? null : (ColorProperty) hit.target;
                }
                return true;
            case COLOR_SV:
            case COLOR_HUE:
                if (button == 0) {
                    this.drag = hit;
                    dragTo(mx, my);
                }
                return true;
            case COLOR_HEX:
                if (button == 0 && this.editing != hit.target) {
                    startEditing((Property<?>) hit.target,
                            String.format("#%06X", ((ColorProperty) hit.target).getValue() & 0xFFFFFF), true);
                }
                return true;
            case TEXT:
                if (button == 0 && this.editing != hit.target) {
                    startEditing((Property<?>) hit.target, ((TextProperty) hit.target).getValue(), false);
                }
                return true;
            case OTHER:
                return true;
            default:
                return false;
        }
    }

    /** A click on nothing of this class's: a field being typed into is committed. */
    void clickElsewhere() {
        if (this.editing != null) {
            commit(false);
        }
        this.listeningModule = null;
        this.listeningKey = null;
    }

    private void startEditing(Property<?> property, String text, boolean hex) {
        this.listeningModule = null;
        this.listeningKey = null;
        this.editing = property;
        this.editingHex = hex;
        this.refused = false;
        this.input.set(text);
        /* Select it all, so typing replaces the value (Ctrl+A in TextInput). */
        this.input.key('\u0001', Keyboard.KEY_A, true, () -> "");
    }

    /** Called every frame while a mouse button is held. */
    void dragTo(float mx, float my) {
        GlideHit hit = this.drag;
        if (hit == null) {
            return;
        }
        if (hit.kind == GlideHit.Kind.NUM) {
            Property<?> property = (Property<?>) hit.target;
            float min = minimumOf(property);
            float max = maximumOf(property);
            if (GuiScreen.isShiftKeyDown()) {
                this.dragValue += (mx - this.dragLastX) / Math.max(1.0F, hit.trackW) * (max - min) * 0.1F;
            } else {
                this.dragValue = min + (max - min) * Math.max(0.0F, Math.min(1.0F, (mx - hit.trackX) / hit.trackW));
            }
            this.dragValue = Math.max(min, Math.min(max, this.dragValue));
            this.dragLastX = mx;
            assign(property, this.dragValue);
        } else if (hit.kind == GlideHit.Kind.COLOR_SV) {
            this.hsb[1] = Math.max(0.0F, Math.min(1.0F, (mx - hit.trackX) / hit.trackW));
            this.hsb[2] = 1.0F - Math.max(0.0F, Math.min(1.0F, (my - hit.trackY) / hit.trackH));
            setColor((ColorProperty) hit.target);
        } else if (hit.kind == GlideHit.Kind.COLOR_HUE) {
            this.hsb[0] = Math.max(0.0F, Math.min(0.999F, (mx - hit.trackX) / hit.trackW));
            setColor((ColorProperty) hit.target);
        }
    }

    void release() {
        this.drag = null;
    }

    private void setColor(ColorProperty property) {
        property.setBase(Property.Source.USER, Integer.valueOf(Color.HSBtoRGB(this.hsb[0], this.hsb[1], this.hsb[2]) & 0xFFFFFF));
    }

    /** A key while {@link #capturesKeys()}. */
    void key(char typed, int key) {
        if (this.listeningModule != null) {
            int bound = clears(key) ? 0 : key;
            Module module = this.listeningModule;
            this.listeningModule = null;
            module.setKey(bound);
            warnConflicts(bound, module.getName());
            return;
        }
        if (this.listeningKey != null) {
            int bound = clears(key) ? 0 : key;
            KeyProperty property = this.listeningKey;
            this.listeningKey = null;
            property.setBase(Property.Source.USER, Integer.valueOf(bound));
            Module owner = this.owners.get(property);
            if (owner != null) {
                warnConflicts(bound, KeyConflicts.nameOf(owner, property));
            }
            return;
        }
        if (this.editing != null) {
            TextInput.Result result = this.input.key(typed, key, GuiScreen.isCtrlKeyDown(), GuiScreen::getClipboardString);
            if (result == TextInput.Result.SUBMIT) {
                commit(true);
            } else if (result == TextInput.Result.CANCEL) {
                this.editing = null;
            } else if (result == TextInput.Result.EDITED) {
                this.refused = false;
            }
        }
    }

    private static boolean clears(int key) {
        return key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_DELETE || key == Keyboard.KEY_BACK;
    }

    private void warnConflicts(int key, String self) {
        if (key == 0) {
            return;
        }
        List<String> others = KeyConflicts.find(key, self, KeyConflicts.bindings());
        if (!others.isEmpty()) {
            this.gui.say(GlideDraw.keyName(key) + " is also bound to " + KeyConflicts.join(others), true);
        }
    }

    /**
     * Applies the field. With {@code keep}, a value the setting refuses keeps
     * the field open in red; otherwise it is dropped.
     */
    private void commit(boolean keep) {
        Property<?> property = this.editing;
        if (property == null) {
            return;
        }
        String text = this.input.text().trim();
        boolean ok;
        try {
            if (this.editingHex) {
                String hex = text.startsWith("#") ? text.substring(1) : text;
                ok = hex.length() == 6 && property.setBase(Property.Source.USER, Integer.valueOf(Integer.parseInt(hex, 16)));
            } else if (isNumeric(property)) {
                float value = Float.parseFloat(text.replace(',', '.').replace("%", ""));
                ok = value >= minimumOf(property) && value <= maximumOf(property);
                if (ok) {
                    assign(property, value);
                }
            } else {
                ok = property.parseString(text);
            }
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok || !keep) {
            this.editing = null;
            this.editingHex = false;
            if (!ok) {
                this.gui.say("Not a value for " + property.getLabel() + ": kept " + SettingFacts.format(property,
                        property.getValue()), true);
            }
        } else {
            this.refused = true;
            String range = SettingFacts.range(property);
            this.gui.say("Not a value for " + property.getLabel() + (range.isEmpty() ? "" : " (" + range + ")"), true);
        }
    }

    // ---- numbers -------------------------------------------------------

    static boolean isNumeric(Property<?> property) {
        return property instanceof IntProperty || property instanceof FloatProperty
                || property instanceof PercentProperty || property instanceof LongProperty;
    }

    private static float minimumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMinimum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMinimum();
        }
        if (property instanceof LongProperty) {
            return ((LongProperty) property).getMinimum();
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
        if (property instanceof LongProperty) {
            return ((LongProperty) property).getMaximum();
        }
        return ((FloatProperty) property).getMaximum();
    }

    private static float currentOf(Property<?> property) {
        return ((Number) property.getValue()).floatValue();
    }

    private static String formatNumber(Property<?> property) {
        float value = currentOf(property);
        if (property instanceof PercentProperty) {
            return Math.round(value) + "%";
        }
        if (!(property instanceof FloatProperty)) {
            return String.valueOf(Math.round((double) value));
        }
        String s = String.format(Locale.ROOT, "%.2f", value);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static void assign(Property<?> property, float value) {
        value = Math.max(minimumOf(property), Math.min(maximumOf(property), value));
        if (property instanceof LongProperty) {
            property.setBase(Property.Source.USER, Long.valueOf(Math.round((double) value)));
        } else if (property instanceof FloatProperty) {
            property.setBase(Property.Source.USER, Float.valueOf(Math.round(value * 100.0F) / 100.0F));
        } else {
            property.setBase(Property.Source.USER, Integer.valueOf(Math.round(value)));
        }
    }
}
