package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoSlowModeNamesTest {

    @Test
    public void oldNgNamesLoadTheNewOnes() {
        NoSlow noSlow = new NoSlow();
        String[][] renamed = {{"NG-Reuse", "Reuse"}, {"NG-Switch", "Switch"}, {"NG-Interact", "Interact"},
                {"NG-Blink", "Blink"}, {"NG-Grim2364", "Grim2364"}, {"NG-Grim2371", "Grim2371"}};
        for (String[] pair : renamed) {
            assertTrue(pair[0], noSlow.swordMode.parseString(pair[0]));
            assertEquals(pair[1], noSlow.swordMode.getModeString());
        }
    }

    @Test
    public void theMenuKeepsTheseAndNoNgPrefix() {
        String prompt = new NoSlow().swordMode.getValuePrompt();
        for (String kept : new String[]{"Reuse", "Switch", "Interact", "Blink", "Grim2364", "Grim2371",
                "Hit-Window", "Grim-Alternate"}) {
            assertTrue(kept, prompt.contains(kept));
        }
        assertFalse(prompt.contains("NG"));
        assertFalse(prompt.contains("Watchdog"));
    }

    @Test
    public void hiddenModesStillLoadFromAConfig() {
        NoSlow noSlow = new NoSlow();
        assertTrue(noSlow.swordMode.parseString("Watchdog"));
        assertEquals("Watchdog", noSlow.swordMode.getModeString());
    }
}
