package atlas.module.modules;

import atlas.Atlas;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.ColorUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

import java.awt.Color;

public abstract class ThemeStyle extends Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    static final String[] GROUP_MODES = {"THEME", "ORIGINAL", "STATIC", "RAINBOW", "GRADIENT", "TRIPLE",
            "PULSE", "ASTOLFO", "TEAM", "HEALTH", "DISTANCE", "HUD"};
    static final String[] MASTER_MODES = {"ORIGINAL", "STATIC", "RAINBOW", "GRADIENT", "TRIPLE",
            "PULSE", "ASTOLFO", "TEAM", "HEALTH", "DISTANCE", "HUD"};

    private static boolean resolvingHud;

    private final boolean master;

    public final ModeProperty mode;
    public final ColorProperty color1;
    public final ColorProperty color2;
    public final ColorProperty color3;

    public final FloatProperty speed;

    public final PercentProperty spread;
    public final PercentProperty saturation;

    public final IntProperty shade;

    public final BooleanProperty breathe;
    public final PercentProperty breatheDepth;

    public final PercentProperty fillAlpha;

    public final PercentProperty lineAlpha;
    public final FloatProperty lineWidth;

    protected ThemeStyle(String name, boolean master, int defaultFill, int defaultLine, float defaultWidth) {
        super(name, false, true);
        this.master = master;
        this.mode = new ModeProperty("mode", 0, master ? MASTER_MODES : GROUP_MODES);
        this.color1 = new ColorProperty("color-1", 0x55CDFC, () -> this.uses("STATIC", "GRADIENT", "TRIPLE", "PULSE",
                "TEAM", "HEALTH", "DISTANCE"));
        this.color2 = new ColorProperty("color-2", 0xF7A8B8, () -> this.uses("GRADIENT", "TRIPLE"));
        this.color3 = new ColorProperty("color-3", 0xFFFFFF, () -> this.uses("TRIPLE"));
        this.breathe = new BooleanProperty("breathe", false, () -> !this.uses("THEME"));
        this.speed = new FloatProperty("speed", 1.0F, 0.05F, 5.0F, () -> this.uses("RAINBOW", "GRADIENT", "TRIPLE",
                "PULSE", "ASTOLFO") || this.breathe.getValue());
        this.spread = new PercentProperty("spread", 25, () -> this.uses("RAINBOW", "GRADIENT", "TRIPLE", "PULSE",
                "ASTOLFO", "HUD"));
        this.saturation = new PercentProperty("saturation", 100, () -> !this.uses("THEME"));
        this.shade = new IntProperty("shade", 0, -100, 100, () -> !this.uses("THEME"));
        this.breatheDepth = new PercentProperty("breathe-depth", 50, () -> !this.uses("THEME") && this.breathe.getValue());
        this.fillAlpha = new PercentProperty("fill-alpha", defaultFill);
        this.lineAlpha = new PercentProperty("line-alpha", defaultLine);
        this.lineWidth = new FloatProperty("line-width", defaultWidth, 0.5F, 5.0F);
    }

    private boolean uses(String... modes) {
        String current = this.mode.getModeString();
        for (String m : modes) {
            if (m.equals(current)) {
                return true;
            }
        }
        return false;
    }

    public static ThemeStyle active(Class<? extends ThemeStyle> type) {
        if (Atlas.moduleManager == null) {
            return null;
        }
        Module module = Atlas.moduleManager.modules.get(type);
        if (!(module instanceof ThemeStyle) || !module.isEnabled()) {
            return null;
        }
        Theme theme = Theme.instance();
        return theme != null && theme.isEnabled() ? (ThemeStyle) module : null;
    }

    public Color color(Color original, Entity entity, int index) {
        ThemeStyle source = this;
        if (!this.master && "THEME".equals(this.mode.getModeString())) {
            Theme theme = Theme.instance();
            if (theme != null) {
                source = theme;
            }
        }
        return source.shape(original == null ? Color.WHITE : original, entity, index);
    }

    public Color fill(Color original, Entity entity, int index) {
        return withAlpha(color(original, entity, index), this.fillAlpha.getValue());
    }

    public Color line(Color original, Entity entity, int index) {
        return withAlpha(color(original, entity, index), this.lineAlpha.getValue());
    }

    public boolean recolours() {
        String current = this.mode.getModeString();
        if (!this.master && "THEME".equals(current)) {
            Theme theme = Theme.instance();
            return theme != null && !"ORIGINAL".equals(theme.mode.getModeString());
        }
        return !"ORIGINAL".equals(current);
    }

    public int fillAlpha255() {
        return scaleAlpha(this.fillAlpha.getValue());
    }

    public int lineAlpha255() {
        return scaleAlpha(this.lineAlpha.getValue());
    }

    public float width() {
        return this.lineWidth.getValue();
    }

    private static Color withAlpha(Color color, int percent) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), scaleAlpha(percent));
    }

    private static int scaleAlpha(int percent) {
        Theme theme = Theme.instance();
        float scale = theme == null ? 1.0F : theme.alphaScale.getValue() / 100.0F;
        return MathHelper.clamp_int(Math.round(percent / 100.0F * scale * 255.0F), 0, 255);
    }

    private Color shape(Color original, Entity entity, int index) {
        double seconds = (System.currentTimeMillis() % 3_600_000L) / 1000.0;
        double offset;
        if (index >= 0) {
            offset = index * this.spread.getValue() / 100.0 * 0.12;
        } else if (entity != null) {
            offset = frac(entity.getEntityId() * 0.6180339887) * this.spread.getValue() / 100.0;
        } else {
            offset = 0.0;
        }
        double phase = seconds * this.speed.getValue() * 0.35 + offset;
        Color c1 = new Color(this.color1.getValue());
        Color color;
        switch (this.mode.getModeString()) {
            case "STATIC":
                color = c1;
                break;
            case "RAINBOW":
                color = Color.getHSBColor((float) frac(phase), 1.0F, 1.0F);
                break;
            case "GRADIENT":
                color = ColorUtil.interpolate((float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0)), c1,
                        new Color(this.color2.getValue()));
                break;
            case "TRIPLE": {
                double p = frac(phase) * 3.0;
                Color c2 = new Color(this.color2.getValue());
                Color c3 = new Color(this.color3.getValue());
                if (p < 1.0) {
                    color = ColorUtil.interpolate((float) p, c1, c2);
                } else if (p < 2.0) {
                    color = ColorUtil.interpolate((float) (p - 1.0), c2, c3);
                } else {
                    color = ColorUtil.interpolate((float) (p - 2.0), c3, c1);
                }
                break;
            }
            case "PULSE":
                color = scaleBrightness(c1, (float) (0.3 + 0.7 * (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0))));
                break;
            case "ASTOLFO": {
                double h = frac(phase);
                h = h < 0.5 ? h : 1.0 - h;
                color = Color.getHSBColor((float) (0.5 + h), 0.5F, 1.0F);
                break;
            }
            case "TEAM":
                color = entity instanceof EntityPlayer ? TeamUtil.getTeamColor((EntityPlayer) entity, 1.0F) : c1;
                break;
            case "HEALTH":
                if (entity instanceof EntityLivingBase && ((EntityLivingBase) entity).getMaxHealth() > 0.0F) {
                    EntityLivingBase living = (EntityLivingBase) entity;
                    color = ColorUtil.getHealthBlend(MathHelper.clamp_float(living.getHealth() / living.getMaxHealth(),
                            0.0F, 1.0F));
                } else {
                    color = c1;
                }
                break;
            case "DISTANCE":
                if (entity != null && mc.thePlayer != null) {
                    float near = MathHelper.clamp_float(mc.thePlayer.getDistanceToEntity(entity) / 24.0F, 0.0F, 1.0F);
                    color = Color.getHSBColor(near * 0.33F, 1.0F, 1.0F);
                } else {
                    color = c1;
                }
                break;
            case "HUD":
                color = hudColor(original, Math.max(0, index));
                break;
            default:

                color = original;
                break;
        }
        return adjust(color, phase);
    }

    private Color hudColor(Color original, int index) {
        if (resolvingHud || this instanceof InterfaceColors) {
            return original;
        }
        Module hud = Atlas.moduleManager.modules.get(HUD.class);
        if (!(hud instanceof HUD)) {
            return original;
        }
        resolvingHud = true;
        try {
            return ((HUD) hud).getColor(System.currentTimeMillis(), index);
        } finally {
            resolvingHud = false;
        }
    }

    private Color adjust(Color color, double phase) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float brightness = hsb[2];
        if (this.breathe.getValue()) {
            float depth = this.breatheDepth.getValue() / 100.0F;
            brightness *= 1.0F - depth * (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2.0));
        }
        Color out = Color.getHSBColor(hsb[0], hsb[1] * this.saturation.getValue() / 100.0F, brightness);
        int shade = this.shade.getValue();
        if (shade < 0) {
            out = ColorUtil.interpolate(-shade / 100.0F, out, Color.BLACK);
        } else if (shade > 0) {
            out = ColorUtil.interpolate(shade / 100.0F, out, Color.WHITE);
        }
        return out;
    }

    private static Color scaleBrightness(Color color, float factor) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1], hsb[2] * factor);
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    void applyPreset(String mode, int c1, int c2, int c3, float speed, int spread, int saturation, int shade,
                     boolean breathe) {
        this.mode.parseString(mode);
        this.color1.setValue(c1);
        this.color2.setValue(c2);
        this.color3.setValue(c3);
        this.speed.setValue(speed);
        this.spread.setValue(spread);
        this.saturation.setValue(saturation);
        this.shade.setValue(shade);
        this.breathe.setValue(breathe);
    }

    void follow(String mode) {
        this.mode.parseString(mode);
    }
}
