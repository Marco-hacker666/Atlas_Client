package atlas.ui.hud.list;

import java.awt.Color;

public final class ListColors {
    public interface Source {
        int color(int index);
    }

    static final int[] CATEGORY = {0xFF6B6B, 0x5CB8FF, 0x6BE38F, 0xC58CFF, 0xFFB45C, 0xFFE066, 0x5CE0D8, 0xFF8FCB};

    static final double CYCLE_MS = 4000.0;

    static final double SPREAD = 0.2;

    private ListColors() {
    }

    public static int color(ListStyle style, long timeMs, int index, String name, int category) {
        double phase = phase(style, timeMs, index);
        int[] c = style.colors;
        int rgb;
        switch (style.paint) {
            case GRADIENT:
                rgb = mix(c[0], c[1], triangle(phase));
                break;
            case TRIPLE: {
                float t = triangle(phase) * 2.0F;
                rgb = t <= 1.0F ? mix(c[0], c[1], t) : mix(c[1], c[2], t - 1.0F);
                break;
            }
            case PALETTE: {
                double p = frac(phase) * 4.0;
                int from = (int) p % 4;
                rgb = mix(c[from], c[(from + 1) % 4], smooth((float) (p - Math.floor(p))));
                break;
            }
            case RAINBOW:
                rgb = Color.HSBtoRGB((float) frac(phase), 1.0F, 1.0F);
                break;
            case ASTOLFO:
                rgb = Color.HSBtoRGB(0.5F + 0.5F * triangle(phase), 0.55F, 1.0F);
                break;
            case PULSE:

                rgb = mix(c[0], c[1], triangle(phase(style, timeMs, 0)));
                break;
            case FADE:
                rgb = scale(c[0], 1.0F - 0.45F * triangle(phase));
                break;
            case CATEGORY:
                rgb = CATEGORY[Math.floorMod(category, CATEGORY.length)];
                break;
            case RANDOM:
                rgb = Color.HSBtoRGB(hue(name), 0.6F, 1.0F);
                break;
            case STATIC:
            case HUD:
            default:
                rgb = c[0];
                break;
        }
        return adjust(rgb & 0xFFFFFF, style.saturation, style.brightness);
    }

    static double phase(ListStyle style, long timeMs, int index) {
        double turned = (double) timeMs * (double) style.speed / CYCLE_MS;
        turned -= Math.floor(turned);
        return turned - (double) index * (double) style.spread / 100.0 * SPREAD;
    }

    static double frac(double value) {
        return value - Math.floor(value);
    }

    static float triangle(double phase) {
        return (float) (1.0 - Math.abs(2.0 * frac(phase) - 1.0));
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    static float hue(String name) {
        int hash = name == null ? 0 : name.hashCode();
        hash ^= hash >>> 16;
        hash *= 0x45D9F3B;
        hash ^= hash >>> 16;
        return (float) (hash & 0xFFFF) / 65536.0F;
    }

    public static int mix(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    public static int scale(int rgb, float factor) {
        return mix(0x000000, rgb, factor);
    }

    static int adjust(int rgb, int saturation, int brightness) {
        if (saturation >= 100 && brightness >= 100) {
            return rgb;
        }
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        return Color.HSBtoRGB(hsb[0], hsb[1] * Math.max(0, saturation) / 100.0F,
                hsb[2] * Math.max(0, brightness) / 100.0F) & 0xFFFFFF;
    }

    public static int argb(int rgb, float opacity) {
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255.0F)));
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
