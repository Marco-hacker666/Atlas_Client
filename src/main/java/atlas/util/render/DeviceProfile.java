package atlas.util.render;

/**
 * Phone or PC (2026-11-11, owner: "know if the device the client is used on
 * is mobile or a pc and then adjust the hud and clickgui accordingly").
 *
 * A phone is what MobileSafe's detection finds: an OpenGL-to-GLES layer
 * (gl4es, Krypton Wrapper, MobileGlues) or Android itself (the Pojav-family
 * launchers' environment, /system/build.prop). The setting ("device-layout":
 * Auto / Phone / PC, Client Settings > Performance) lets a player overrule it
 * either way.
 *
 * It is separate from mobile-safe on purpose: that one is about what the GPU
 * can draw, and a PC player may turn it on for speed without wanting the
 * touch layout; a phone with a good GPU may turn it off and still have a
 * touch screen.
 *
 * On a phone, AtlasClickGui scrolls with a finger drag, makes text and rows
 * bigger, fills the screen and keeps hints shown; MobileHud moves HUD
 * elements still at their default place away from the touch buttons, makes
 * them a little smaller and turns KeyStrokes off (once each).
 */
public final class DeviceProfile {
    public static final String SETTING = "device-layout";
    public static final String[] MODES = {"Auto", "Phone", "PC"};

    private static volatile int setting = -1;

    private DeviceProfile() {
    }

    /** True on a phone (or when the setting says Phone). Client thread on first call (detection reads GL). */
    public static boolean isMobile() {
        int mode = setting;
        if (mode < 0) {
            mode = MobileSafe.readMode(SETTING, MODES);
            setting = mode;
        }
        if (mode != GlesDetect.AUTO) {
            return GlesDetect.active(mode, false);
        }
        return MobileSafe.deviceDetected();
    }

    public static void setSetting(int mode) {
        setting = Math.max(0, Math.min(MODES.length - 1, mode));
    }

    /** For the bug report and the menu: "phone (auto)", "PC (setting)". */
    public static String describe() {
        boolean mobile = isMobile();
        return (mobile ? "phone" : "PC") + (setting == GlesDetect.AUTO ? " (auto)" : " (setting)");
    }
}
