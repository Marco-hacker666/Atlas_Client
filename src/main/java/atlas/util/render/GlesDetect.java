package atlas.util.render;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

public final class GlesDetect {
    public static final int AUTO = 0;
    public static final int ON = 1;
    public static final int OFF = 2;

    private GlesDetect() {
    }

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
            }
        }
        return null;
    }

    public static boolean active(int setting, boolean detected) {
        if (setting == ON) {
            return true;
        }
        if (setting == OFF) {
            return false;
        }
        return detected;
    }

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
