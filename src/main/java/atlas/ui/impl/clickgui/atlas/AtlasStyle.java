package atlas.ui.impl.clickgui.atlas;

final class AtlasStyle {
    static int text = 0xFFF5F8FF;

    static int dim = 0xD1C3CEDF;

    static int faint = 0x9E93A3BA;

    static int ink = 0xFFFFFFFF;

    static int ok = 0xFF6BD49A;
    static int warn = 0xFFFFC857;
    static int danger = 0xFFFF5A5F;

    static int foreign = 0xFFB59CFF;
    static int foreignText = 0xFFE9E1FF;

    static int toast = 0xF2181C24;

    static final float META = 8.0F;
    static final float BODY = 8.5F;

    static final float R_CONTROL = 10.0F;

    static final float HOVER_RATE = 12.0F;

    static final long CARET_BLINK_MS = 500L;

    private AtlasStyle() {
    }

    static void paint(boolean light) {
        text = light ? 0xFF161A21 : 0xFFF5F8FF;
        dim = light ? 0xD1404958 : 0xD1C3CEDF;
        faint = light ? 0xA6667080 : 0x9E93A3BA;
        ink = light ? 0xFF0E1726 : 0xFFFFFFFF;
        ok = light ? 0xFF1E9E5A : 0xFF6BD49A;
        warn = light ? 0xFFB7791F : 0xFFFFC857;
        danger = light ? 0xFFD93A40 : 0xFFFF5A5F;
        foreign = light ? 0xFF6A4BD6 : 0xFFB59CFF;
        foreignText = light ? 0xFF3E2A99 : 0xFFE9E1FF;
        toast = light ? 0xF5FBFCFE : 0xF2181C24;
    }

    static boolean caretOn() {
        return (System.currentTimeMillis() / CARET_BLINK_MS) % 2L == 0L;
    }
}
