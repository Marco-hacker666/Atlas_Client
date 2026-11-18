package atlas.ui.impl.clickgui.glide;

final class GlideHit {
    enum Kind {
        PANEL, HEADER, FOLD, MODULE, DOTS,

        CATEGORY, GEAR, FRIENDS, PROFILES, SEARCH,

        BIND, BOOL, NUM, MODE, MODE_OPTION, COLOR, COLOR_SV, COLOR_HUE, COLOR_HEX, TEXT, KEY, OTHER
    }

    final Kind kind;
    final GlideFrame frame;
    final Object target;
    final float x1, y1, x2, y2;

    float trackX, trackY, trackW, trackH;

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

    boolean same(GlideHit other) {
        return other != null && other.kind == this.kind && other.frame == this.frame && other.target == this.target
                && other.index == this.index;
    }
}
