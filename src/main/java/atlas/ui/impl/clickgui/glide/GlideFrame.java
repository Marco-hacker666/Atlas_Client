package atlas.ui.impl.clickgui.glide;

import atlas.module.Module;

import java.util.List;

/**
 * One window in the Glide style: a category's panel, or the main window that
 * lists the categories. Where it is, whether it is folded or hidden, and how
 * far it is scrolled.
 */
final class GlideFrame {
    final String title;
    /** The Atlas icon drawn before the title (a category name). */
    final String icon;
    final List<Module> modules;
    /** The main window: categories and client entries instead of modules. */
    final boolean main;
    float x;
    float y;
    boolean folded;
    /** Hidden from the main window's category list. The main window is always shown. */
    boolean hidden;
    /** Where the wheel put the list, and the scroll drawn (easing toward it). */
    float scroll;
    float shownScroll;
    float maxScroll;
    /* Geometry of this frame, from measure(): top drawn (with the drop-in), body height drawn, full height. */
    float drawY;
    float body;
    /** Height drawn last frame, for hit tests of the whole panel. */
    float shownHeight = GlideTheme.HEADER;
    /** Height of every row and open settings, unclipped, and the most the body may take on screen (measure()). */
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

    /** Height the body opens to: everything, or as much as fits on screen and a scroll. */
    float openBody() {
        return Math.min(this.contentH, this.maxBody);
    }
}
