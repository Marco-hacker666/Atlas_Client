package atlas.ui.hud.list;

public interface ListPainter {
    int TOP_LEFT = 1;
    int TOP_RIGHT = 2;
    int BOTTOM_LEFT = 4;
    int BOTTOM_RIGHT = 8;
    int ALL = 15;

    void rect(float x1, float y1, float x2, float y2, float radius, int corners, int argb);

    void text(String text, float x, float y, int argb, boolean shadow);
}
