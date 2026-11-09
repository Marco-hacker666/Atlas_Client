package atlas.ui.hud;

import atlas.property.properties.DragProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each HUD element was drawn on the last frame, and how to move it.
 *
 * A HUD module calls {@link #report} after drawing, with the rectangle it
 * covered (in scaled screen pixels) and a {@link Mover} built over the settings
 * that store its position. The HUD editor reads these back to draw the frames
 * and to drag the elements. Moving is done by whole-pixel deltas, so an element
 * follows the pointer exactly whatever its own position formula is (an anchor
 * and an offset, a right-hand margin, a free position), as long as the setting
 * moves the element one pixel per unit -- which is true of every element that
 * reports here.
 *
 * One set of rules for every element (2026-11-09): each can be dragged, and
 * each can be scaled -- an element that reports a {@link Scaler} is resized in
 * the editor with the mouse wheel, through the same setting its menu shows.
 */
public final class HudLayout {
    /** Moves an element; returns how far it really moved, since a setting may be at its limit. */
    public interface Mover {
        int[] moveBy(int dx, int dy);
    }

    /** Reads and changes an element's size; set keeps the value inside the setting's range. */
    public interface Scaler {
        float get();

        void set(float value);
    }

    public static final class Element {
        public final String id;
        public String label;
        public float x;
        public float y;
        public float width;
        public float height;
        public Mover mover;
        /** Null for an element that cannot be resized. */
        public Scaler scaler;
        long seenAt;

        Element(String id) {
            this.id = id;
        }

        public boolean contains(float px, float py) {
            return px >= this.x && px <= this.x + this.width && py >= this.y && py <= this.y + this.height;
        }
    }

    /** An element counts as on screen if it reported this recently. */
    private static final long VISIBLE_MS = 250L;
    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<String, Element>();
    private static boolean editing;

    private HudLayout() {
    }

    /** True while the HUD editor is open: elements that hide behind menus should keep drawing. */
    public static boolean isEditing() {
        return editing;
    }

    static void setEditing(boolean value) {
        editing = value;
    }

    public static void report(String id, String label, float x, float y, float width, float height, Mover mover) {
        report(id, label, x, y, width, height, mover, null);
    }

    /** As {@link #report(String, String, float, float, float, float, Mover)}, for an element that can be resized. */
    public static void report(String id, String label, float x, float y, float width, float height, Mover mover,
                              Scaler scaler) {
        if (width <= 0.0F || height <= 0.0F || mover == null) {
            return;
        }
        Element element = ELEMENTS.get(id);
        if (element == null) {
            element = new Element(id);
            ELEMENTS.put(id, element);
        }
        element.label = label;
        element.x = x;
        element.y = y;
        element.width = width;
        element.height = height;
        element.mover = mover;
        element.scaler = scaler;
        element.seenAt = System.currentTimeMillis();
        /* Anchors (2026-10-18): kept in place through a screen change. */
        net.minecraft.client.gui.ScaledResolution sr =
                new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft());
        HudAnchors.track(element, sr.getScaledWidth(), sr.getScaledHeight());
    }

    static List<Element> visible() {
        long now = System.currentTimeMillis();
        List<Element> out = new ArrayList<Element>();
        for (Element element : ELEMENTS.values()) {
            if (now - element.seenAt <= VISIBLE_MS) {
                out.add(element);
            }
        }
        return out;
    }

    /**
     * A mover over two integer settings. A sign of 1 means a larger value moves
     * the element right (or down); -1 means it moves it left (or up), as with a
     * right-hand margin. Either setting may be null to lock that axis.
     */
    public static Mover ints(final IntProperty xSetting, final int xSign, final IntProperty ySetting, final int ySign) {
        return new Mover() {
            @Override
            public int[] moveBy(int dx, int dy) {
                return new int[]{shift(xSetting, xSign, dx), shift(ySetting, ySign, dy)};
            }
        };
    }

    /** A mover over a {@link DragProperty}, which stores a free position. */
    public static Mover drag(final DragProperty setting) {
        return new Mover() {
            @Override
            public int[] moveBy(int dx, int dy) {
                setting.position.x += dx;
                setting.position.y += dy;
                setting.targetPosition.x = setting.position.x;
                setting.targetPosition.y = setting.position.y;
                return new int[]{dx, dy};
            }
        };
    }

    /** A scaler over a size setting. */
    public static Scaler scale(final FloatProperty setting) {
        if (setting == null) {
            return null;
        }
        return new Scaler() {
            @Override
            public float get() {
                return setting.getValue();
            }

            @Override
            public void set(float value) {
                setting.setValue(clampScale(value, setting.getMinimum(), setting.getMaximum()));
            }
        };
    }

    /**
     * Draws what follows scaled by {@code s} about the screen point (px, py),
     * so code written in screen pixels keeps working; pair with
     * {@link #endScaled()}. Text, items and HudPanel shapes follow the
     * transform; shaders placed in raw screen pixels (RoundedUtils, BlurUtils)
     * do not, so those are given {@link #scaledRect} coordinates instead.
     */
    public static void beginScaled(float px, float py, float s) {
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(px, py, 0.0F);
        net.minecraft.client.renderer.GlStateManager.scale(s, s, 1.0F);
        net.minecraft.client.renderer.GlStateManager.translate(-px, -py, 0.0F);
    }

    public static void endScaled() {
        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }

    /** Where the rectangle (x, y, w, h) ends up when scaled by s about (px, py): {x, y, w, h}. */
    public static float[] scaledRect(float px, float py, float s, float x, float y, float w, float h) {
        return new float[]{px + (x - px) * s, py + (y - py) * s, w * s, h * s};
    }

    /** A size kept inside its range and rounded to a hundredth, so the wheel lands on round numbers. */
    static float clampScale(float value, float minimum, float maximum) {
        float rounded = Math.round(value * 100.0F) / 100.0F;
        return Math.max(minimum, Math.min(maximum, rounded));
    }

    private static int shift(IntProperty setting, int sign, int delta) {
        if (setting == null || sign == 0 || delta == 0) {
            return 0;
        }
        int old = setting.getValue();
        int wanted = old + sign * delta;
        int clamped = Math.max(setting.getMinimum(), Math.min(setting.getMaximum(), wanted));
        if (clamped == old || !setting.setValue(clamped)) {
            return 0;
        }
        return (clamped - old) * sign;
    }
}
