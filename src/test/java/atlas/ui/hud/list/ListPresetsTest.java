package atlas.ui.hud.list;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The ready-made shapes, which one a style has, and the game's own colours and glow (2026-11-14). */
public class ListPresetsTest {

    @Test
    public void aNewStyleIsTheSidebar() {
        assertEquals("SIDEBAR", ListPresets.match(new ListStyle()));
    }

    @Test
    public void everyPresetIsFoundAgainAndNoTwoAreAlike() {
        Set<String> seen = new HashSet<String>();
        for (int i = 1; i < ListPresets.NAMES.length; i++) {
            ListStyle style = new ListStyle();
            /* Whatever the colours, order and animation, the shape decides. */
            style.paint = ListStyle.Paint.RAINBOW;
            style.sort = ListStyle.Sort.ALPHABET;
            style.anim = ListStyle.Anim.NONE;
            style.glow = true;
            ListPresets.apply(ListPresets.NAMES[i], style);
            assertEquals(ListPresets.NAMES[i], ListPresets.match(style));
            assertTrue(seen.add(ListPresets.NAMES[i]));
        }
        assertEquals(ListPresets.NAMES.length - 1, seen.size());
    }

    @Test
    public void aShapeChangedByHandIsCustom() {
        ListStyle style = new ListStyle();
        ListPresets.apply("PILL", style);
        style.padX += 1.0F;
        assertEquals("CUSTOM", ListPresets.match(style));
        style.padX -= 1.0F;
        assertEquals("PILL", ListPresets.match(style));
        style.backgroundTint = 30;
        assertEquals("CUSTOM", ListPresets.match(style));
        /* A preset leaves alone what is not its shape. */
        style.glow = true;
        style.paint = ListStyle.Paint.PALETTE;
        ListPresets.apply("GLASS", style);
        assertTrue(style.glow);
        assertTrue(style.blur);
        assertEquals(ListStyle.Paint.PALETTE, style.paint);
        assertEquals("CUSTOM", ListPresets.NAMES[0]);
    }

    @Test
    public void theGamesColoursAndTheGlow() {
        List<ListRow> rows = new ArrayList<ListRow>();
        rows.add(new ListRow("a", "Alpha", "", 0, 30.0F, 0.0F));
        rows.add(new ListRow("b", "Beta", "", 0, 20.0F, 0.0F));
        ListStyle style = new ListStyle();
        style.anim = ListStyle.Anim.NONE;
        style.bar = ListStyle.Bar.NONE;
        new ListAnim().step(rows, style, 0.0F);
        ListColors.Source source = new ListColors.Source() {
            @Override
            public int color(int index) {
                return index == 0 ? 0xFF112233 : 0xFF445566;
            }
        };
        for (ListStyle.Paint paint : new ListStyle.Paint[]{ListStyle.Paint.HUD, ListStyle.Paint.THEME}) {
            style.paint = paint;
            ListFrame frame = ListLayout.frame(style, rows, 8.0F, 0L, source);
            assertEquals(0xFF445566, frame.ops.get(frame.ops.size() - 1).argb);
        }
        /* The glow's shapes: a row each, in its colour, never drawn with the rest. */
        ListFrame frame = ListLayout.frame(style, rows, 8.0F, 0L, source);
        assertEquals(2, frame.bodies.size());
        assertEquals(0xFF112233, frame.bodies.get(0).argb);
        assertEquals(-36.0F, frame.bodies.get(0).x1, 1.0E-4F);
        final List<Integer> drawn = new ArrayList<Integer>();
        frame.paintGlow(new ListPainter() {
            @Override
            public void rect(float x1, float y1, float x2, float y2, float radius, int corners, int argb) {
                drawn.add(argb);
            }

            @Override
            public void text(String text, float x, float y, int argb, boolean shadow) {
                throw new AssertionError("no text in a glow");
            }
        }, 0.5F);
        assertEquals(2, drawn.size());
        assertEquals(0x80112233, (int) drawn.get(0));
        /* A row on its way out glows less. */
        rows.get(1).appear = 0.5F;
        style.anim = ListStyle.Anim.FADE;
        assertEquals(0x80, ListLayout.frame(style, rows, 8.0F, 0L, source).bodies.get(1).argb >>> 24);
    }
}
