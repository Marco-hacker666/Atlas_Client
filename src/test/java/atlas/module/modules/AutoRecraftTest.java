package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AutoRecraft's settings have to read as what they do (CrewX port,
 * 2026-10-11). The recipe drives both the suffix and whether the two
 * ingredient habits of the port are still in place (mushroom by default), and
 * the manual key is only worth offering once manual crafting is switched on --
 * the clicking itself needs an open inventory and a live client.
 */
public class AutoRecraftTest {

    @Test
    public void theRecipeAndModeDefaultsMatchThePort() {
        AutoRecraft recraft = new AutoRecraft();

        assertEquals("mushroom soup is the vanilla recipe", 0, (int) recraft.recipe.getValue());
        assertEquals("it watches the supply by itself", 0, (int) recraft.mode.getValue());
        assertEquals("three soups left is the trigger", 3, (int) recraft.startWith.getValue());
    }

    @Test
    public void theSuffixNamesTheRecipe() {
        AutoRecraft recraft = new AutoRecraft();
        assertEquals("MUSHROOM", recraft.getSuffix()[0]);

        recraft.recipe.setValue(1);
        assertEquals("COCOA", recraft.getSuffix()[0]);

        recraft.recipe.setValue(2);
        assertEquals("CACTUS", recraft.getSuffix()[0]);
    }

    @Test
    public void theManualKeyIsHiddenUntilManualCraftingIsOn() {
        AutoRecraft recraft = new AutoRecraft();
        assertFalse("nothing to bind while automatic", recraft.manualBind.isVisible());

        recraft.mode.setValue(1); // MANUAL
        assertTrue("manual crafting needs its key", recraft.manualBind.isVisible());

        recraft.mode.setValue(2); // BOTH
        assertTrue(recraft.manualBind.isVisible());

        recraft.mode.setValue(0); // AUTOMATIC
        assertFalse(recraft.manualBind.isVisible());
    }

    @Test
    public void theClickDelaysReadAsAMinimumAndAMaximum() {
        AutoRecraft recraft = new AutoRecraft();

        assertTrue("a click window needs somewhere to start",
                recraft.clickMinDelay.getValue() <= recraft.clickMaxDelay.getValue());
        assertTrue("and the same for the start window",
                recraft.startMinDelay.getValue() <= recraft.startMaxDelay.getValue());
    }
}
