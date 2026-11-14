package atlas.ui.hud.list;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The colour of a row of the module list (2026-11-14). */
public class ListColorsTest {

    private static ListStyle style(ListStyle.Paint paint) {
        ListStyle style = new ListStyle();
        style.paint = paint;
        style.colors[0] = 0xFF0000;
        style.colors[1] = 0x0000FF;
        style.colors[2] = 0x00FF00;
        style.colors[3] = 0xFFFFFF;
        return style;
    }

    private static int at(ListStyle style, long time, int index) {
        return ListColors.color(style, time, index, "Module", 0);
    }

    @Test
    public void theCycleHasNoJumpInIt() {
        assertEquals(0.0F, ListColors.triangle(0.0), 0.0F);
        assertEquals(1.0F, ListColors.triangle(0.5), 0.0F);
        assertEquals(0.0F, ListColors.triangle(1.0), 0.0F);
        assertEquals(ListColors.triangle(0.999), ListColors.triangle(-0.001), 1.0E-5F);
        /* Every moving mode is where it was a whole cycle later, and close to it a millisecond later. */
        for (ListStyle.Paint paint : ListStyle.Paint.values()) {
            ListStyle style = style(paint);
            for (int index = 0; index < 4; index++) {
                assertEquals(paint.name(), at(style, 1234L, index), at(style, 1234L + 4000L, index));
                int now = at(style, 3999L, index);
                int next = at(style, 4000L, index);
                for (int shift = 0; shift <= 16; shift += 8) {
                    assertTrue(paint.name(), Math.abs(((now >> shift) & 0xFF) - ((next >> shift) & 0xFF)) <= 3);
                }
            }
        }
    }

    @Test
    public void aGradientRunsDownTheList() {
        ListStyle style = style(ListStyle.Paint.GRADIENT);
        style.speed = 0.0F;
        assertEquals(0xFF0000, at(style, 0L, 0));
        /* Each row is a tenth of a cycle on at the default spread: a fifth of the way to colour 2. */
        assertEquals(ListColors.mix(0xFF0000, 0x0000FF, 0.2F), at(style, 0L, 1));
        assertEquals(ListColors.mix(0xFF0000, 0x0000FF, 0.4F), at(style, 0L, 2));
        /* Standing still, the time does not matter. */
        assertEquals(at(style, 0L, 3), at(style, 99999L, 3));
        style.spread = 0;
        assertEquals(at(style, 0L, 0), at(style, 0L, 7));
        /* Moving, a row shows what the one before it showed a little earlier. */
        style.speed = 1.0F;
        style.spread = 50;
        assertEquals(at(style, 1000L, 0), at(style, 1400L, 1));
    }

    @Test
    public void theStopsOfTheLongerGradients() {
        ListStyle triple = style(ListStyle.Paint.TRIPLE);
        triple.speed = 0.0F;
        triple.spread = 125;
        /* A quarter cycle a row: colour 1, colour 2, colour 3, colour 2. */
        assertEquals(0xFF0000, at(triple, 0L, 0));
        assertEquals(0x0000FF, at(triple, 0L, 1));
        assertEquals(0x00FF00, at(triple, 0L, 2));
        assertEquals(0x0000FF, at(triple, 0L, 3));
        ListStyle palette = style(ListStyle.Paint.PALETTE);
        palette.speed = 0.0F;
        palette.spread = 125;
        /* The palette loops: 1, then back through 4, 3, 2 as the rows go on. */
        assertEquals(0xFF0000, at(palette, 0L, 0));
        assertEquals(0xFFFFFF, at(palette, 0L, 1));
        assertEquals(0x00FF00, at(palette, 0L, 2));
        assertEquals(0x0000FF, at(palette, 0L, 3));
    }

    @Test
    public void aPulseIsTheWholeListTogether() {
        ListStyle style = style(ListStyle.Paint.PULSE);
        assertEquals(at(style, 700L, 0), at(style, 700L, 9));
        assertEquals(0xFF0000, at(style, 0L, 5));
        assertEquals(0x0000FF, at(style, 2000L, 5));
    }

    @Test
    public void aModuleKeepsItsOwnColour() {
        ListStyle random = style(ListStyle.Paint.RANDOM);
        int aura = ListColors.color(random, 0L, 0, "KillAura", 0);
        assertEquals(aura, ListColors.color(random, 5555L, 7, "KillAura", 3));
        assertNotEquals(aura, ListColors.color(random, 0L, 0, "Velocity", 0));
        ListStyle category = style(ListStyle.Paint.CATEGORY);
        assertEquals(ListColors.CATEGORY[2], ListColors.color(category, 0L, 0, "x", 2));
        assertEquals(ListColors.CATEGORY[1], ListColors.color(category, 0L, 0, "x", ListColors.CATEGORY.length + 1));
        assertEquals(ListColors.CATEGORY[ListColors.CATEGORY.length - 1], ListColors.color(category, 0L, 0, "x", -1));
    }

    @Test
    public void saturationAndBrightnessScaleTheColour() {
        ListStyle style = style(ListStyle.Paint.STATIC);
        assertEquals(0xFF0000, at(style, 0L, 0));
        style.saturation = 0;
        assertEquals(0xFFFFFF, at(style, 0L, 0));
        style.saturation = 100;
        style.brightness = 50;
        assertEquals(0x800000, at(style, 0L, 0));
        assertEquals(0x80FF0000, ListColors.argb(0xFF0000, 0.5F));
        assertEquals(0x00FF0000, ListColors.argb(0xFF0000, -1.0F));
    }
}
