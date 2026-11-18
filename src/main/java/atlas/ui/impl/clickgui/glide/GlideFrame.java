package atlas.ui.impl.clickgui.glide;

import atlas.module.Module;

import java.util.List;

final class GlideFrame {
    final String title;

    final String icon;
    final List<Module> modules;

    final boolean main;
    float x;
    float y;
    boolean folded;

    boolean hidden;

    float scroll;
    float shownScroll;
    float maxScroll;

    float drawY;
    float body;

    float shownHeight = GlideTheme.HEADER;

    float contentH;
    float maxBody;

    GlideFrame(String title, String icon, List<Module> modules, boolean main) {
        this.title = title;
        this.icon = icon;
        this.modules = modules;
        this.main = main;
    }

    boolean over(float mx, float my) {
        return mx >= this.x && mx <= this.x + GlideTheme.WIDTH && my >= this.y && my <= this.y + this.shownHeight;
    }

    float openBody() {
        return Math.min(this.contentH, this.maxBody);
    }
}
