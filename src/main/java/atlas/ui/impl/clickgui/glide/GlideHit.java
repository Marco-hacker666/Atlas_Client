package atlas.ui.impl.clickgui.glide;

/**
 * Something clickable drawn this frame. Hits are recorded in drawing order,
 * so the last one containing the pointer is the one on top; each frame adds
 * a PANEL hit under its own parts, so a click on a panel never reaches the
 * panel behind it.
 */
final class GlideHit {
    enum Kind {
        PANEL, HEADER, FOLD, MODULE, DOTS,
        /* The main window and the search box. */
        CATEGORY, GEAR, FRIENDS, PROFILES, SEARCH,
        /* Settings rows (G2). */
        BIND, BOOL, NUM, MODE, MODE_OPTION, COLOR, COLOR_SV, COLOR_HUE, COLOR_HEX, TEXT, KEY, OTHER
    }

    final Kind kind;
    final GlideFrame frame;
    final Object target;
    final float x1, y1, x2, y2;
    /** Sliders and the colour picker: the area a drag maps onto. */
    float trackX, trackY, trackW, trackH;
    /** MODE_OPTION: the mode's index. */
    int index;

    GlideHit(Kind kind, GlideFrame frame, Object target, float x1, float y1, float x2, float y2) {
        this.kind = kind;
        this.frame = frame;
        this.target = target;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    boolean contains(float x, float y) {
        return x >= this.x1 && x <= this.x2 && y >= this.y1 && y <= this.y2;
    }

    /** Whether this is the same thing as {@code other} drawn on another frame. */
    boolean same(GlideHit other) {
        return other != null && other.kind == this.kind && other.frame == this.frame && other.target == this.target
                && other.index == this.index;
    }
}
