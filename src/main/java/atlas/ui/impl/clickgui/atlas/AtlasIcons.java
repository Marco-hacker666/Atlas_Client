package atlas.ui.impl.clickgui.atlas;

/**
 * The Atlas menu's icons (drawn from primitives, sharp at any scale) for other
 * menus: category names ("combat", "render", ...) and a few others ("search",
 * "chevron", "chevronup", "client", "folder", "key"). Unknown names get a grid.
 * About 12 px across, centred on (x, y).
 */
public final class AtlasIcons {
    private AtlasIcons() {
    }

    public static void draw(String name, float x, float y, int colour) {
        AtlasClickGui.icon(name, x, y, colour);
    }
}
