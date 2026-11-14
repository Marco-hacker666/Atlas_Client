package atlas.ui.hud.list;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The module list laid out: where each rectangle and line of text goes (2026-11-14). Letters are 5 wide, 8 high. */
public class ListLayoutTest {
    private static final float TEXT = 8.0F;

    private static ListRow row(String name, String suffix, int category) {
        return new ListRow(name, name, suffix, category, name.length() * 5.0F, suffix.length() * 5.0F);
    }

    /** Widest first: 65, 40 and 20 of text. */
    private static List<ListRow> rows() {
        List<ListRow> rows = new ArrayList<ListRow>();
        rows.add(row("Scaffold", " Fast", 1));
        rows.add(row("Velocity", "", 0));
        rows.add(row("Step", "", 2));
        return settled(rows);
    }

    private static List<ListRow> settled(List<ListRow> rows) {
        ListStyle still = new ListStyle();
        still.anim = ListStyle.Anim.NONE;
        return new ListAnim().step(rows, still, 0.0F);
    }

    private static ListStyle plain() {
        ListStyle style = new ListStyle();
        style.paint = ListStyle.Paint.STATIC;
        style.colors[0] = 0x3366FF;
        style.bar = ListStyle.Bar.NONE;
        style.anim = ListStyle.Anim.NONE;
        return style;
    }

    private static List<ListFrame.Op> backings(ListFrame frame) {
        List<ListFrame.Op> out = new ArrayList<ListFrame.Op>();
        for (ListFrame.Op op : frame.ops) {
            if (op.backing) {
                out.add(op);
            }
        }
        return out;
    }

    private static List<ListFrame.Op> texts(ListFrame frame) {
        List<ListFrame.Op> out = new ArrayList<ListFrame.Op>();
        for (ListFrame.Op op : frame.ops) {
            if (op.text != null) {
                out.add(op);
            }
        }
        return out;
    }

    @Test
    public void rowsHangFromTheAnchoredCorner() {
        ListStyle style = plain();
        ListFrame frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        List<ListFrame.Op> rows = backings(frame);
        assertEquals(3, rows.size());
        /* Right and top: every row ends at x 0, and they follow each other down, 8 of text and 2 above and below. */
        float[] widths = {71.0F, 46.0F, 26.0F};
        for (int i = 0; i < 3; i++) {
            assertEquals(0.0F, rows.get(i).x2, 0.0F);
            assertEquals(-widths[i], rows.get(i).x1, 1.0E-4F);
            assertEquals(i * 12.0F, rows.get(i).y1, 1.0E-4F);
            assertEquals(i * 12.0F + 12.0F, rows.get(i).y2, 1.0E-4F);
        }
        assertEquals(-71.0F, frame.minX, 1.0E-4F);
        assertEquals(0.0F, frame.maxX, 0.0F);
        assertEquals(0.0F, frame.minY, 0.0F);
        assertEquals(36.0F, frame.maxY, 1.0E-4F);
        /* The text: the name three in from the row's free end, the tag right after it, two down. */
        List<ListFrame.Op> texts = texts(frame);
        assertEquals("Scaffold", texts.get(0).text);
        assertEquals(-68.0F, texts.get(0).x1, 1.0E-4F);
        assertEquals(2.0F, texts.get(0).y1, 1.0E-4F);
        assertEquals(" Fast", texts.get(1).text);
        assertEquals(-28.0F, texts.get(1).x1, 1.0E-4F);
        assertEquals(0xFF3366FF, texts.get(0).argb);
        assertEquals(0xFFAAAAAA, texts.get(1).argb);

        /* Left and bottom: the mirror of it. */
        style.right = false;
        style.bottom = true;
        frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        rows = backings(frame);
        for (int i = 0; i < 3; i++) {
            assertEquals(0.0F, rows.get(i).x1, 0.0F);
            assertEquals(widths[i], rows.get(i).x2, 1.0E-4F);
            assertEquals(-i * 12.0F, rows.get(i).y2, 1.0E-4F);
        }
        assertEquals(3.0F, texts(frame).get(0).x1, 1.0E-4F);
        assertEquals(-10.0F, texts(frame).get(0).y1, 1.0E-4F);
        assertEquals(-36.0F, frame.minY, 1.0E-4F);
    }

    @Test
    public void aGapAndPaddingSpaceTheRows() {
        ListStyle style = plain();
        style.gap = 3.0F;
        style.padX = 5.0F;
        style.padY = 1.0F;
        assertEquals(10.0F, ListLayout.rowHeight(style, TEXT), 0.0F);
        List<ListFrame.Op> rows = backings(ListLayout.frame(style, rows(), TEXT, 0L, null));
        assertEquals(13.0F, rows.get(1).y1, 1.0E-4F);
        assertEquals(23.0F, rows.get(1).y2, 1.0E-4F);
        assertEquals(-50.0F, rows.get(1).x1, 1.0E-4F);
    }

    @Test
    public void onlyTheCornersThatStickOutAreRounded() {
        ListStyle style = plain();
        style.corners = ListStyle.Corners.OUTER;
        List<ListFrame.Op> rows = backings(ListLayout.frame(style, rows(), TEXT, 0L, null));
        /* Right-anchored, widest first: each row's lower free corner shows, the upper one only on the first. */
        assertEquals(ListPainter.TOP_LEFT | ListPainter.TOP_RIGHT | ListPainter.BOTTOM_LEFT, rows.get(0).corners);
        assertEquals(ListPainter.BOTTOM_LEFT, rows.get(1).corners);
        assertEquals(ListPainter.BOTTOM_LEFT | ListPainter.BOTTOM_RIGHT, rows.get(2).corners);
        assertEquals(3.0F, rows.get(0).radius, 0.0F);
        /* Apart, every row is on its own. */
        style.gap = 2.0F;
        rows = backings(ListLayout.frame(style, rows(), TEXT, 0L, null));
        assertEquals(ListPainter.ALL, rows.get(1).corners);
        /* A radius never past half the row. */
        style.radius = 40.0F;
        assertEquals(6.0F, backings(ListLayout.frame(style, rows(), TEXT, 0L, null)).get(0).radius, 0.0F);
        style.corners = ListStyle.Corners.NONE;
        assertEquals(0, backings(ListLayout.frame(style, rows(), TEXT, 0L, null)).get(0).corners);
    }

    @Test
    public void aSideBarLiesBetweenTheEdgeAndTheRows() {
        ListStyle style = plain();
        style.bar = ListStyle.Bar.SIDE;
        style.barWidth = 2.0F;
        style.corners = ListStyle.Corners.OUTER;
        ListFrame frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        List<ListFrame.Op> rows = backings(frame);
        assertEquals(-2.0F, rows.get(0).x2, 1.0E-4F);
        assertEquals(-73.0F, rows.get(0).x1, 1.0E-4F);
        /* The side the bar is on stays square. */
        assertEquals(ListPainter.TOP_LEFT | ListPainter.BOTTOM_LEFT, rows.get(0).corners);
        int bars = 0;
        for (ListFrame.Op op : frame.ops) {
            if (op.text == null && !op.backing) {
                bars++;
                assertEquals(-2.0F, op.x1, 1.0E-4F);
                assertEquals(0.0F, op.x2, 1.0E-4F);
                assertEquals(0xFF3366FF, op.argb);
            }
        }
        assertEquals(3, bars);
        assertEquals(-70.0F, texts(frame).get(0).x1, 1.0E-4F);
        /* An inner bar: past each row's free end. */
        style.bar = ListStyle.Bar.INNER;
        frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        assertEquals(-73.0F, frame.minX, 1.0E-4F);
        assertEquals(0.0F, backings(frame).get(0).x2, 0.0F);
    }

    @Test
    public void theOtherBars() {
        ListStyle style = plain();
        style.bar = ListStyle.Bar.CAP;
        style.barWidth = 2.0F;
        ListFrame frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        assertEquals(-2.0F, frame.minY, 1.0E-4F);
        assertEquals(4, frame.ops.size() - texts(frame).size());
        style.bottom = true;
        assertEquals(2.0F, ListLayout.frame(style, rows(), TEXT, 0L, null).maxY, 1.0E-4F);
        style.bottom = false;
        style.bar = ListStyle.Bar.UNDERLINE;
        frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        assertEquals(36.0F, frame.maxY, 1.0E-4F);
        assertEquals(6, frame.ops.size() - texts(frame).size());
        /* An outline is a line's width outside the rows all round. */
        style.bar = ListStyle.Bar.OUTLINE;
        style.barWidth = 1.0F;
        frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        assertEquals(-72.0F, frame.minX, 1.0E-4F);
        assertEquals(1.0F, frame.maxX, 1.0E-4F);
        assertEquals(-1.0F, frame.minY, 1.0E-4F);
        assertEquals(37.0F, frame.maxY, 1.0E-4F);
        /* Two uprights a row, the top, the bottom, and a piece along each of the two steps. */
        assertEquals(3 + 3 * 2 + 2 + 2, frame.ops.size() - texts(frame).size());
    }

    @Test
    public void aBlockIsOneBoxBehindTheWholeList() {
        ListStyle style = plain();
        style.background = ListStyle.Background.BLOCK;
        style.corners = ListStyle.Corners.OUTER;
        style.radius = 5.0F;
        ListFrame frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        List<ListFrame.Op> block = backings(frame);
        assertEquals(1, block.size());
        assertEquals(-71.0F, block.get(0).x1, 1.0E-4F);
        assertEquals(36.0F, block.get(0).y2, 1.0E-4F);
        assertEquals(ListPainter.ALL, block.get(0).corners);
        /* The text still ends at the anchored side. */
        assertEquals(-43.0F, texts(frame).get(2).x1, 1.0E-4F);
        style.background = ListStyle.Background.NONE;
        assertFalse(ListLayout.frame(style, rows(), TEXT, 0L, null).hasBacking());
    }

    @Test
    public void theBackgroundAndTheText() {
        ListStyle style = plain();
        style.backgroundColor = 0x000000;
        style.backgroundOpacity = 50;
        assertEquals(0x80000000, backings(ListLayout.frame(style, rows(), TEXT, 0L, null)).get(0).argb);
        style.backgroundTint = 100;
        style.backgroundOpacity = 100;
        style.text = ListStyle.Text.DARK;
        style.suffix = ListStyle.Suffix.DIM;
        ListFrame frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        assertEquals(0xFF3366FF, backings(frame).get(0).argb);
        assertEquals(0xFF14161A, texts(frame).get(0).argb);
        assertEquals(0x9914161A, texts(frame).get(1).argb);
        assertFalse("no shadow under dark text", texts(frame).get(0).shadow);
        style.text = ListStyle.Text.WHITE;
        style.suffix = ListStyle.Suffix.COLOR;
        frame = ListLayout.frame(style, rows(), TEXT, 0L, null);
        assertEquals(0xFFFFFFFF, texts(frame).get(0).argb);
        assertEquals(0xFF3366FF, texts(frame).get(1).argb);
        assertTrue(texts(frame).get(0).shadow);
        style.textShadow = false;
        assertFalse(texts(ListLayout.frame(style, rows(), TEXT, 0L, null)).get(0).shadow);
    }

    @Test
    public void theHudsColourIsAskedForByRow() {
        ListStyle style = plain();
        style.paint = ListStyle.Paint.HUD;
        ListFrame frame = ListLayout.frame(style, rows(), TEXT, 0L, new ListColors.Source() {
            @Override
            public int color(int index) {
                return 0xFF000010 * (index + 1);
            }
        });
        assertEquals(0xFF000010, texts(frame).get(0).argb);
        assertEquals(0xFF000020, texts(frame).get(2).argb);
        /* With nobody to ask it is colour 1. */
        assertEquals(0xFF3366FF, texts(ListLayout.frame(style, rows(), TEXT, 0L, null)).get(0).argb);
    }

    @Test
    public void aRowOnItsWayInIsFainterAndAside() {
        ListStyle style = plain();
        style.anim = ListStyle.Anim.SLIDE_FADE;
        List<ListRow> rows = rows();
        rows.get(1).appear = 0.5F;
        ListFrame frame = ListLayout.frame(style, rows, TEXT, 0L, null);
        /* Half way: an eighth of the slide left to go, toward the edge it comes from. */
        assertEquals(25.0F / 8.0F, backings(frame).get(1).x2, 1.0E-4F);
        assertEquals(0x40, backings(frame).get(1).argb >>> 24);
        assertEquals(0x80, texts(frame).get(2).argb >>> 24);
        assertEquals(0.0F, backings(frame).get(0).x2, 0.0F);
        /* Gone but for the last frame: the text keeps the little alpha the game's font needs. */
        rows.get(1).appear = 0.0F;
        assertEquals(4, texts(ListLayout.frame(style, rows, TEXT, 0L, null)).get(2).argb >>> 24);
        /* A slide alone goes the row's whole width and does not fade. */
        style.anim = ListStyle.Anim.SLIDE;
        frame = ListLayout.frame(style, rows, TEXT, 0L, null);
        assertEquals(50.0F, backings(frame).get(1).x2, 1.0E-4F);
        assertEquals(0x80, backings(frame).get(1).argb >>> 24);
    }

    @Test
    public void nothingToListIsNothingToDraw() {
        ListFrame frame = ListLayout.frame(plain(), new ArrayList<ListRow>(), TEXT, 0L, null);
        assertTrue(frame.ops.isEmpty());
        assertEquals(0.0F, frame.maxX - frame.minX, 0.0F);
    }

    @Test
    public void everyPresetLaysOutInEveryCorner() {
        for (String name : ListPresets.NAMES) {
            ListStyle style = plain();
            assertEquals(name, !"CUSTOM".equals(name), ListPresets.apply(name, style));
            for (int corner = 0; corner < 4; corner++) {
                style.right = (corner & 1) != 0;
                style.bottom = (corner & 2) != 0;
                ListFrame frame = ListLayout.frame(style, rows(), TEXT, 500L, null);
                assertEquals(name, 4, texts(frame).size());
                /* Nothing reaches more than a line's width past the anchored corner. */
                float line = style.barWidth + 0.01F;
                assertTrue(name, style.right ? frame.maxX <= line : frame.minX >= -line);
                assertTrue(name, style.bottom ? frame.maxY <= line : frame.minY >= -line);
                for (ListFrame.Op op : frame.ops) {
                    assertTrue(name, op.x2 > op.x1 && op.y2 > op.y1);
                }
            }
        }
        assertFalse(ListPresets.apply("NO SUCH", plain()));
    }
}
