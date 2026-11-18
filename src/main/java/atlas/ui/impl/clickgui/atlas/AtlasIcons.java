package atlas.ui.impl.clickgui.atlas;

public final class AtlasIcons {
    private AtlasIcons() {
    }

    public static void draw(String name, float x, float y, int colour) {
        AtlasClickGui.icon(name, x, y, colour);
    }
}
