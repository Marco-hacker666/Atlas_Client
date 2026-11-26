package atlas.util.render;

public final class Effects {
    public static final int AUTO = 0;
    public static final int FULL = 1;
    public static final int LITE = 2;
    public static final String[] MODES = {"Auto", "Full", "Lite"};

    private static volatile int mode = AUTO;
    private static volatile boolean autoLite;
    private static volatile long lastDrawnMs;
    private static volatile boolean fastBlur = true;
    private static volatile Boolean fastBlurOverride;

    private Effects() {
    }

    public static boolean lite() {
        int m = mode;
        return m == LITE || m == AUTO && autoLite;
    }

    public static boolean liteInGame() {
        if (!lite()) {
            return false;
        }
        net.minecraft.client.gui.GuiScreen screen = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        return screen == null || screen instanceof net.minecraft.client.gui.GuiChat;
    }

    public static boolean fastBlur() {
        Boolean forced = fastBlurOverride;
        return forced != null ? forced : fastBlur;
    }

    public static void overrideFastBlur(Boolean on) {
        fastBlurOverride = on;
    }

    public static void setFastBlur(boolean on) {
        fastBlur = on;
    }

    public static boolean auto() {
        return mode == AUTO;
    }

    public static void setMode(int value) {
        mode = Math.max(AUTO, Math.min(LITE, value));
    }

    public static void goLiteForThisSession() {
        autoLite = true;
    }

    public static boolean autoLite() {
        return autoLite;
    }

    public static void drawn() {
        lastDrawnMs = System.currentTimeMillis();
    }

    public static boolean drawnWithin(long ms) {
        long last = lastDrawnMs;
        return last > 0L && System.currentTimeMillis() - last <= ms;
    }

    public static String describe() {
        int m = mode;
        if (m == LITE) {
            return "lite (setting)";
        }
        if (m == FULL) {
            return "full (setting)";
        }
        return autoLite ? "lite (auto: the game stayed slow with blur and glow on)" : "full (auto)";
    }
}
