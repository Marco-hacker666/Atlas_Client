package atlas.ui.hud;

import atlas.property.properties.DragProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HudLayout {
    public interface Mover {
        int[] moveBy(int dx, int dy);

        default boolean atDefault() {
            return false;
        }
    }

    public interface Scaler {
        float get();

        void set(float value);

        default float defaultValue() {
            return Float.NaN;
        }
    }

    public static final class Element {
        public final String id;
        public String label;
        public float x;
        public float y;
        public float width;
        public float height;
        public Mover mover;

        public Scaler scaler;
        long seenAt;

        Element(String id) {
            this.id = id;
        }

        public boolean contains(float px, float py) {
            return px >= this.x && px <= this.x + this.width && py >= this.y && py <= this.y + this.height;
        }
    }

    private static final long VISIBLE_MS = 250L;
    private static final Map<String, Element> ELEMENTS = new LinkedHashMap<String, Element>();
    private static boolean editing;

    private HudLayout() {
    }

    public static boolean isEditing() {
        return editing;
    }

    static void setEditing(boolean value) {
        editing = value;
    }

    public static void report(String id, String label, float x, float y, float width, float height, Mover mover) {
        report(id, label, x, y, width, height, mover, null);
    }

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

        net.minecraft.client.gui.ScaledResolution sr =
                new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft());
        HudAnchors.track(element, sr.getScaledWidth(), sr.getScaledHeight());

        if (!editing) {
            MobileHud.consider(element, sr.getScaledWidth(), sr.getScaledHeight());
        }
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

    public static Mover ints(final IntProperty xSetting, final int xSign, final IntProperty ySetting, final int ySign) {
        return new Mover() {
            @Override
            public int[] moveBy(int dx, int dy) {
                return new int[]{shift(xSetting, xSign, dx), shift(ySetting, ySign, dy)};
            }

            @Override
            public boolean atDefault() {
                return (xSetting == null || xSetting.getValue().equals(xSetting.getDefault()))
                        && (ySetting == null || ySetting.getValue().equals(ySetting.getDefault()));
            }
        };
    }

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

            @Override
            public boolean atDefault() {
                atlas.util.vector.Vector2d declared = setting.getDefault();
                return declared != null && Math.abs(setting.position.x - declared.x) < 0.5
                        && Math.abs(setting.position.y - declared.y) < 0.5;
            }
        };
    }

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

            @Override
            public float defaultValue() {
                Float declared = setting.getDefault();
                return declared == null ? Float.NaN : declared;
            }
        };
    }

    public static void beginScaled(float px, float py, float s) {
        net.minecraft.client.renderer.GlStateManager.pushMatrix();
        net.minecraft.client.renderer.GlStateManager.translate(px, py, 0.0F);
        net.minecraft.client.renderer.GlStateManager.scale(s, s, 1.0F);
        net.minecraft.client.renderer.GlStateManager.translate(-px, -py, 0.0F);
    }

    public static void endScaled() {
        net.minecraft.client.renderer.GlStateManager.popMatrix();
    }

    public static float[] scaledRect(float px, float py, float s, float x, float y, float w, float h) {
        return new float[]{px + (x - px) * s, py + (y - py) * s, w * s, h * s};
    }

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
