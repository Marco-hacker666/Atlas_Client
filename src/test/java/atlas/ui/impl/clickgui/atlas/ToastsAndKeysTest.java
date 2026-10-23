package atlas.ui.impl.clickgui.atlas;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** The menu's toasts and its key-conflict check (2026-10-23, GUI redesign M1). */
public class ToastsAndKeysTest {

    @Test
    public void toastsExpireProblemsLast() {
        Toasts toasts = new Toasts();
        toasts.add("saved", Toasts.Kind.OK, 0L);
        toasts.add("bad value", Toasts.Kind.ERROR, 0L);
        assertEquals(2, toasts.visible(2499L).size());
        assertEquals(1, toasts.visible(2500L).size());
        assertEquals("bad value", toasts.visible(4499L).get(0).text);
        assertTrue(toasts.visible(4500L).isEmpty());
    }

    @Test
    public void theSameTextRestartsItsToast() {
        Toasts toasts = new Toasts();
        toasts.add("x", Toasts.Kind.OK, 0L);
        toasts.add("y", Toasts.Kind.OK, 100L);
        toasts.add("x", Toasts.Kind.OK, 2000L);
        List<Toasts.Toast> shown = toasts.visible(2000L);
        assertEquals(2, shown.size());
        assertEquals("y", shown.get(0).text);
        assertEquals("x", shown.get(1).text);
        assertEquals(1, toasts.visible(4000L).size());
    }

    @Test
    public void atMostThreeAndTheOldestGoes() {
        Toasts toasts = new Toasts();
        for (int i = 0; i < 5; i++) {
            toasts.add("t" + i, Toasts.Kind.INFO, i);
        }
        List<Toasts.Toast> shown = toasts.visible(10L);
        assertEquals(3, shown.size());
        assertEquals("t2", shown.get(0).text);
        toasts.dismiss(shown.get(0));
        assertEquals(2, toasts.visible(10L).size());
        toasts.add("", Toasts.Kind.INFO, 10L);
        assertEquals(2, toasts.visible(10L).size());
    }

    @Test
    public void aToastWithAnActionStaysLongEnoughToClick() {
        Toasts toasts = new Toasts();
        final int[] undone = {0};
        toasts.add("Appearance set to Neon", Toasts.Kind.OK, 0L, () -> undone[0]++);
        Toasts.Toast toast = toasts.visible(5999L).get(0);
        assertNotNull(toast.action);
        toast.action.run();
        assertEquals(1, undone[0]);
        assertTrue(toasts.visible(6000L).isEmpty());
        toasts.add("plain", Toasts.Kind.OK, 0L);
        assertNull(toasts.visible(0L).get(0).action);
    }

    @Test
    public void conflictsNameEveryoneElseOnTheKey() {
        Map<String, Integer> bindings = new LinkedHashMap<String, Integer>();
        bindings.put("Scaffold", 19);
        bindings.put("Clutch", 19);
        bindings.put("FakeLag", 0);
        bindings.put(KeyConflicts.nameOf("Clutch", "Hold Key"), 19);
        bindings.put("Fly", 33);
        assertEquals(Arrays.asList("Clutch", "Clutch (Hold Key)"), KeyConflicts.find(19, "Scaffold", bindings));
        assertEquals(Collections.emptyList(), KeyConflicts.find(33, "Fly", bindings));
        assertEquals(Collections.emptyList(), KeyConflicts.find(0, "Speed", bindings));
    }

    @Test
    public void namesAreJoinedLikeASentence() {
        assertEquals("A", KeyConflicts.join(Arrays.asList("A")));
        assertEquals("A and B", KeyConflicts.join(Arrays.asList("A", "B")));
        assertEquals("A, B and C", KeyConflicts.join(Arrays.asList("A", "B", "C")));
    }
}
