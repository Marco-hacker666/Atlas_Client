package myau.module.modules;

import java.lang.reflect.Field;
import myau.property.properties.ModeProperty;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the wearer picked has to be what the module reports and what the menu
 * offers (CrewX port, 2026-10-11): the suffix names the headwear and says when
 * wings are on, and a colour setting only appears once the thing it colours is
 * switched on. The models themselves are drawn in world space and need a
 * running client.
 */
public class AccessoriesTest {

    @Test
    public void nothingIsOfferedWhileEverythingIsOff() {
        Accessories accessories = new Accessories();

        assertEquals("Off", accessories.getSuffix()[0]);
        assertFalse("no headwear, nothing to colour", accessories.headColor.isVisible());
        assertFalse("no wings, nothing to colour", accessories.wingColor.isVisible());
    }

    @Test
    public void headwearNamesItselfAndUnlocksItsColour() {
        Accessories accessories = new Accessories();

        accessories.headwear.setValue(2); // CROWN

        assertEquals("CROWN", accessories.getSuffix()[0]);
        assertTrue(accessories.headColor.isVisible());
        assertFalse("wings are still off", accessories.wingColor.isVisible());
    }

    @Test
    public void wingsAddASecondSuffixEntry() {
        Accessories accessories = new Accessories();

        accessories.wings.setValue(true);

        assertEquals("wings are named on their own", 1, accessories.getSuffix().length);
        assertEquals("Wings", accessories.getSuffix()[0]);
        assertTrue(accessories.wingColor.isVisible());

        accessories.headwear.setValue(1); // HALO
        assertEquals("headwear and wings are both reported", 2, accessories.getSuffix().length);
        assertEquals("HALO", accessories.getSuffix()[0]);
        assertEquals("Wings", accessories.getSuffix()[1]);
    }

    @Test
    public void everyHeadwearStyleIsNamed() throws Exception {
        Accessories accessories = new Accessories();
        Field field = ModeProperty.class.getDeclaredField("modes");
        field.setAccessible(true);
        String[] styles = (String[]) field.get(accessories.headwear);

        assertEquals("the styles the port brought over", 8, styles.length);
        for (int style = 1; style < styles.length; style++) {
            accessories.headwear.setValue(style);
            assertEquals("style " + style, styles[style], accessories.getSuffix()[0]);
        }
    }
}
