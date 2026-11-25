package atlas.util.render;

/**
 * Lite effects: Atlas's in-game blur and glow (HUD, hotbar, target HUD, chat and scoreboard) are skipped,
 * drawn through the same masked path used where framebuffers are missing. On by the RenderFixes "effects"
 * setting, or for this session when the game stayed slow while they were drawn.
 */
public final class Effects {
    public static final int AUTO = 0;
    public static final int FULL = 1;
    public static final int LITE = 2;
    public static final String[] MODES = {"Auto", "Full", "Lite"};

    private static volatile int mode = AUTO;
    private static volatile boolean autoLite;
    private static volatile long lastDrawnMs;

    private Effects() {
    }

    public static boolean lite() {
        int m = mode;
        return m == LITE || m == AUTO && autoLite;
    }

    /**
     * Lite only while playing (no screen, or the chat): the ClickGUIs and the menus keep their look.
     */
    public static boolean liteInGame() {
        if (!lite()) {
            return false;
        }
        net.minecraft.client.gui.GuiScreen screen = net.minecraft.client.Minecraft.getMinecraft().currentScreen;
        return screen == null || screen instanceof net.minecraft.client.gui.GuiChat;
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

    /** Called when blur or glow is about to be drawn for real. */
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
