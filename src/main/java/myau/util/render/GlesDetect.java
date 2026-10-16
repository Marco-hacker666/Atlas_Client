package myau.util.render;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Whether the game runs through an OpenGL-to-GLES translation layer, as the
 * Android launchers (PojavLauncher and its forks: FCL, Zalith, ...) do.
 * Pure string logic, no GL: MobileSafe feeds it and the tests drive it
 * (2026-10-15).
 *
 * The strings below were read from each renderer's own source, not from a
 * device (nobody here has one):
 * <pre>
 *  gl4es (ptitSeb)        vendor "GL4ES wrapping ..."   renderer "GL4ES using ..."
 *                         version "2.1 gl4es wrapper 1.1.x"   GLSL "1.20 via gl4es"
 *  Holy GL4ES (Pojav)     vendor "ptitSeb"   renderer "GL4ES wrapper"
 *  Krypton Wrapper        vendor "ptitSeb &amp; BZLZHH"   renderer "&lt;gpu&gt; | OpenGL ES 3.2"
 *  (NG-GL4ES)             version "x.y Krypton Wrapper ..." (may carry § colour codes)
 *                         GLSL "4.50 Krypton Wrapper with glslang and SPIRV-Cross"
 *  MobileGlues            vendor "Swung0x48, BZLZHH, Tungsten"   version "... MobileGlues ..."
 * </pre>
 * Any renderer or version naming "OpenGL ES" counts too: a desktop driver never
 * reports that through a desktop GL context.
 */
public final class GlesDetect {
    /** What the mode setting says: decide by detection, always on, always off. */
    public static final int AUTO = 0;
    public static final int ON = 1;
    public static final int OFF = 2;

    private GlesDetect() {
    }

    /**
     * The translation layer the GL strings name, or null for a desktop driver.
     * Any argument may be null (a call before a context existed).
     */
    public static String translationLayer(String vendor, String renderer, String version, String glsl) {
        String all = (clean(vendor) + "\n" + clean(renderer) + "\n" + clean(version) + "\n" + clean(glsl))
                .toLowerCase(Locale.ROOT);
        if (all.contains("krypton")) {
            return "Krypton Wrapper";
        }
        if (all.contains("mobileglues")) {
            return "MobileGlues";
        }
        if (all.contains("gl4es") || all.contains("ptitseb")) {
            return "gl4es";
        }
        if (all.contains("opengl es")) {
            return "OpenGL ES";
        }
        return null;
    }

    /**
     * Signs of Android outside GL: the launchers' environment variables, the
     * Android runtime's properties, or the Android system partition.
     */
    public static String android(Function<String, String> env, Function<String, String> props,
                                 Predicate<String> fileExists) {
        for (String key : new String[]{"POJAV_RENDERER", "POJAV_NATIVEDIR", "FCL_NATIVEDIR", "ANDROID_ROOT"}) {
            String value = safe(env, key);
            if (value != null && !value.isEmpty()) {
                return "env " + key;
            }
        }
        String vendor = safe(props, "java.vendor");
        String vm = safe(props, "java.vm.vendor");
        if ((vendor != null && vendor.toLowerCase(Locale.ROOT).contains("android"))
                || (vm != null && vm.toLowerCase(Locale.ROOT).contains("android"))) {
            return "java vendor";
        }
        String os = safe(props, "os.name");
        if (os != null && os.toLowerCase(Locale.ROOT).contains("linux")) {
            try {
                if (fileExists.test("/system/build.prop")) {
                    return "/system/build.prop";
                }
            } catch (RuntimeException ignored) {
                // Unreadable: not a sign either way.
            }
        }
        return null;
    }

    /** The mode setting applied to what detection found. */
    public static boolean active(int setting, boolean detected) {
        if (setting == ON) {
            return true;
        }
        if (setting == OFF) {
            return false;
        }
        return detected;
    }

    /** Drops Minecraft colour codes (Krypton's LIBGL_USE_MC_COLOR adds them to its version). */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String safe(Function<String, String> source, String key) {
        try {
            return source.apply(key);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
