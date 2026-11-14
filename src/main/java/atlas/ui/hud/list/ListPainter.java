package atlas.ui.hud.list;

/**
 * What draws a module list (2026-11-14): the game with OpenGL, or a picture for a test. Coordinates are the list's
 * own: (0, 0) is the corner it is anchored to, x to the right and y down, in the units the text was measured in.
 */
public interface ListPainter {
    /** Top left, top right, bottom left, bottom right: the corners a rectangle may have rounded. */
    int TOP_LEFT = 1;
    int TOP_RIGHT = 2;
    int BOTTOM_LEFT = 4;
    int BOTTOM_RIGHT = 8;
    int ALL = 15;

    /** A filled rectangle; {@code corners} says which are rounded by {@code radius}. */
    void rect(float x1, float y1, float x2, float y2, float radius, int corners, int argb);

    /** A line of text with its top left at (x, y). */
    void text(String text, float x, float y, int argb, boolean shadow);
}
