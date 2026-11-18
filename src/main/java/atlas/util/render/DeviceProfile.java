package atlas.util.render;

public final class DeviceProfile {
    public static final String SETTING = "device-layout";
    public static final String[] MODES = {"Auto", "Phone", "PC"};

    private static volatile int setting = -1;

    private DeviceProfile() {
    }

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

    public static String describe() {
        boolean mobile = isMobile();
        return (mobile ? "phone" : "PC") + (setting == GlesDetect.AUTO ? " (auto)" : " (setting)");
    }
}
