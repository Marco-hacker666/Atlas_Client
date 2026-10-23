package atlas.management;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Mechanical conflicts between modules (2026-10-23, GUI redesign M5). */
public class ConflictsTest {

    private static final Map<String, Integer> NO_KEYS = Collections.emptyMap();

    @After
    public void forget() {
        Conflicts.resetForTest(null);
    }

    @Test
    public void twoHoldersOnAreAConflictAndOneIsNot() {
        List<String> on = Conflicts.holdersOn(Arrays.asList(
                new String[]{"FakeLag", "FakeLag"}, new String[]{"Backtrack", "BackTrack"},
                new String[]{"NoSlow", "NoSlow"}, new String[]{"Speed", "Speed"}));
        assertEquals(Arrays.asList("FakeLag", "Backtrack"), on);
        List<Conflicts.Conflict> found = Conflicts.find(on, NO_KEYS, k -> "K" + k);
        assertEquals(1, found.size());
        assertTrue(found.get(0).message.startsWith("FakeLag and Backtrack both hold"));
        assertTrue(found.get(0).involves("Backtrack"));
        assertTrue(Conflicts.find(Arrays.asList("FakeLag"), NO_KEYS, k -> "").isEmpty());
    }

    @Test
    public void theKeyDoesNotDependOnOrder() {
        String a = Conflicts.find(Arrays.asList("FakeLag", "Backtrack"), NO_KEYS, k -> "").get(0).key;
        String b = Conflicts.find(Arrays.asList("Backtrack", "FakeLag"), NO_KEYS, k -> "").get(0).key;
        assertEquals(a, b);
        String three = Conflicts.find(Arrays.asList("Backtrack", "FakeLag", "Blink"), NO_KEYS, k -> "").get(0).key;
        assertNotEquals("a third holder warns again", a, three);
    }

    @Test
    public void sharedKeysAreConflictsAndNoKeyIsNot() {
        Map<String, Integer> keys = new LinkedHashMap<String, Integer>();
        keys.put("Scaffold", 19);
        keys.put("Clutch (Hold Key)", 19);
        keys.put("Fly", 0);
        keys.put("Speed", 0);
        keys.put("KillAura", 33);
        List<Conflicts.Conflict> found = Conflicts.find(Collections.<String>emptyList(), keys, k -> k == 19 ? "R" : "?");
        assertEquals(1, found.size());
        assertEquals("Scaffold and Clutch (Hold Key) are both bound to R: one press does both.",
                found.get(0).message);
    }

    @Test
    public void ignoredKeysAreRemembered() {
        Conflicts.resetForTest(new HashSet<String>(Arrays.asList("holders:Backtrack,FakeLag")));
        assertTrue(Conflicts.isIgnored("holders:Backtrack,FakeLag"));
        assertFalse(Conflicts.isIgnored("holders:Blink,FakeLag"));
    }

    @Test
    public void everyHolderIsAModuleClass() {
        for (String name : Conflicts.HOLDERS) {
            assertTrue(name, new java.io.File("src/main/java/atlas/module/modules/" + name + ".java").exists());
        }
    }
}
