package atlas.ui.hud.list;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Rows coming, going and changing places, and the order they are listed in (2026-11-14). */
public class ListAnimTest {

    private static ListRow row(String name, float width, int category) {
        return new ListRow(name, name, "", category, width, 0.0F);
    }

    @Test
    public void aRowComesInOverItsTimeAndGoesOutAgain() {
        ListStyle style = new ListStyle();
        style.animTime = 0.2F;
        ListAnim anim = new ListAnim();
        ListRow a = row("a", 30.0F, 0);
        List<ListRow> drawn = anim.step(Arrays.asList(a), style, 0.05F);
        assertEquals(0.25F, drawn.get(0).appear, 1.0E-5F);
        assertEquals(0.0F, drawn.get(0).slot, 0.0F);
        for (int i = 0; i < 4; i++) {
            anim.step(Arrays.asList(a), style, 0.05F);
        }
        assertEquals(1.0F, a.appear, 0.0F);
        /* Switched off: still drawn, on its way out, then gone. */
        drawn = anim.step(new ArrayList<ListRow>(), style, 0.05F);
        assertEquals(1, drawn.size());
        assertEquals(0.75F, drawn.get(0).appear, 1.0E-5F);
        for (int i = 0; i < 3; i++) {
            drawn = anim.step(new ArrayList<ListRow>(), style, 0.05F);
        }
        assertTrue(drawn.isEmpty());
        /* A long frame counts as a tenth of a second at most: nothing skips its coming entirely. */
        drawn = anim.step(Arrays.asList(row("b", 10.0F, 0)), style, 5.0F);
        assertEquals(0.5F, drawn.get(0).appear, 1.0E-5F);
    }

    @Test
    public void aRowEasesToItsNewPlace() {
        ListStyle style = new ListStyle();
        ListAnim anim = new ListAnim();
        ListRow a = row("a", 30.0F, 0);
        ListRow b = row("b", 20.0F, 0);
        anim.step(Arrays.asList(a, b), style, 0.016F);
        assertEquals(1.0F, b.slot, 0.0F);
        /* b is now first: it moves there, not jumps. */
        anim.step(Arrays.asList(b, a), style, 0.016F);
        assertTrue(b.slot > 0.0F && b.slot < 1.0F);
        assertTrue(a.slot > 0.0F && a.slot < 1.0F);
        for (int i = 0; i < 60; i++) {
            anim.step(Arrays.asList(b, a), style, 0.016F);
        }
        assertEquals(0.0F, b.slot, 1.0E-3F);
        assertEquals(1.0F, a.slot, 1.0E-3F);
    }

    @Test
    public void withNoAnimationRowsAreSimplyThere() {
        ListStyle style = new ListStyle();
        style.anim = ListStyle.Anim.NONE;
        ListAnim anim = new ListAnim();
        ListRow a = row("a", 30.0F, 0);
        ListRow b = row("b", 20.0F, 0);
        List<ListRow> drawn = anim.step(Arrays.asList(a, b), style, 0.016F);
        assertEquals(1.0F, a.appear, 0.0F);
        assertEquals(1.0F, b.slot, 0.0F);
        assertEquals(1, anim.step(Arrays.asList(b), style, 0.016F).size());
        assertEquals(0.0F, b.slot, 0.0F);
        assertEquals(2, drawn.size());
        assertEquals(0.0F, ListAnim.ease(0.0F), 0.0F);
        assertEquals(1.0F, ListAnim.ease(1.0F), 0.0F);
        assertEquals(0.875F, ListAnim.ease(0.5F), 1.0E-6F);
    }

    @Test
    public void theOrderOfTheList() {
        List<ListRow> rows = Arrays.asList(row("Step", 20.0F, 2), row("aura", 40.0F, 0), row("Velocity", 40.0F, 0),
                row("Blink", 25.0F, 1));
        assertEquals("aura Velocity Blink Step", names(ListRow.sorted(rows, ListStyle.Sort.WIDTH)));
        /* Ties keep the order they came in, either way round. */
        assertEquals("Step Blink aura Velocity", names(ListRow.sorted(rows, ListStyle.Sort.WIDTH_REVERSED)));
        assertEquals("aura Blink Step Velocity", names(ListRow.sorted(rows, ListStyle.Sort.ALPHABET)));
        assertEquals("aura Velocity Blink Step", names(ListRow.sorted(rows, ListStyle.Sort.CATEGORY)));
        assertEquals("Step aura Velocity Blink", names(ListRow.sorted(rows, ListStyle.Sort.NONE)));
        /* A tag with nothing in it takes no room. */
        assertEquals(0.0F, new ListRow("k", "n", "", 0, 10.0F, 9.0F).suffixWidth, 0.0F);
        assertEquals(19.0F, new ListRow("k", "n", " x", 0, 10.0F, 9.0F).textWidth(), 0.0F);
        assertEquals("OUTER", ListStyle.names(ListStyle.Corners.values())[1]);
        assertEquals(ListStyle.Bar.NONE, ListStyle.pick(ListStyle.Bar.values(), 99));
        assertEquals(ListStyle.Bar.CAP, ListStyle.pick(ListStyle.Bar.values(), 3));
    }

    private static String names(List<ListRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (ListRow row : rows) {
            sb.append(sb.length() > 0 ? " " : "").append(row.name);
        }
        return sb.toString();
    }
}
