package atlas.module.modules;

import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.PercentProperty;

public class HUDStyle extends Module {
    public static final int DEFAULT_COLOR = 0x000000;
    public static final int DEFAULT_OPACITY = 45;
    public static final float DEFAULT_CARD_CORNERS = 6.0F;
    public static final float DEFAULT_SMALL_CORNERS = 3.5F;
    public static final int DEFAULT_SHADOW = 25;
    public static final float DEFAULT_SHADOW_SIZE = 12.0F;

    public final ColorProperty color = new ColorProperty("panel-color", DEFAULT_COLOR);
    public final PercentProperty opacity = new PercentProperty("panel-opacity", DEFAULT_OPACITY);

    public final FloatProperty cardCorners = new FloatProperty("card-corners", DEFAULT_CARD_CORNERS, 0.0F, 14.0F);

    public final FloatProperty smallCorners = new FloatProperty("small-corners", DEFAULT_SMALL_CORNERS, 0.0F, 10.0F);
    public final PercentProperty shadow = new PercentProperty("shadow", DEFAULT_SHADOW);
    public final FloatProperty shadowSize = new FloatProperty("shadow-size", DEFAULT_SHADOW_SIZE, 0.0F, 30.0F,
            () -> this.shadow.getValue() > 0);
    public final BooleanProperty outline = new BooleanProperty("outline", false);
    public final ColorProperty outlineColor = new ColorProperty("outline-color", 0xFFFFFF, this.outline::getValue);
    public final PercentProperty outlineOpacity = new PercentProperty("outline-opacity", 15, this.outline::getValue);

    public HUDStyle() {
        super("HUDStyle", true, true, "One look for every HUD panel: colour, opacity, corners, shadow and outline.");
    }

    public int panelArgb() {
        return argb(this.color.getValue(), this.opacity.getValue());
    }

    public int shadowArgb() {
        return argb(0x000000, this.shadow.getValue());
    }

    public int outlineArgb() {
        return this.outline.getValue() ? argb(this.outlineColor.getValue(), this.outlineOpacity.getValue()) : 0;
    }

    public static int argb(int rgb, int percent) {
        int alpha = Math.max(0, Math.min(255, Math.round(percent * 255.0F / 100.0F)));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
