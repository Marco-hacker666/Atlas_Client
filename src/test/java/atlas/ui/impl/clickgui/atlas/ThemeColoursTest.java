package atlas.ui.impl.clickgui.atlas;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Colours typed into the Theme window, and names for saved themes (2026-11-09). */
public class ThemeColoursTest {

    @Test
    public void hexIsSixCapitalDigitsWithoutAlpha() {
        assertEquals("CCFF00", ThemeColours.hex(0xCCFF00));
        assertEquals("808080", ThemeColours.hex(0xFF808080));
        assertEquals("000000", ThemeColours.hex(0));
    }

    @Test
    public void typedColoursInEveryUsualForm() {
        assertEquals(Integer.valueOf(0xCCFF00), ThemeColours.parse("#CCFF00"));
        assertEquals(Integer.valueOf(0xCCFF00), ThemeColours.parse("ccff00"));
        assertEquals(Integer.valueOf(0xFFCC00), ThemeColours.parse("#fc0"));
        assertEquals(Integer.valueOf(0x404040), ThemeColours.parse("FF404040"));
        assertEquals(Integer.valueOf(0x123456), ThemeColours.parse("  # 123456 "));
    }

    @Test
    public void notColours() {
        assertNull(ThemeColours.parse(null));
        assertNull(ThemeColours.parse(""));
        assertNull(ThemeColours.parse("#12345"));
        assertNull(ThemeColours.parse("GGGGGG"));
    }

    @Test
    public void themeNamesKeepOnlySafeCharacters() {
        assertEquals("Lime night", ThemeFiles.cleanName("  Lime night  "));
        assertEquals("evil", ThemeFiles.cleanName("../evil"));
        assertEquals("a-b_c 2", ThemeFiles.cleanName("a-b_c 2!?"));
        assertEquals("", ThemeFiles.cleanName("///"));
        assertEquals(ThemeFiles.MAX_NAME, ThemeFiles.cleanName("abcdefghijklmnopqrstuvwxyz0123").length());
    }

    @Test
    public void hudLookKeepsPositionsOut() {
        assertEquals(false, ThemeFiles.HUD_LOOK.contains("offset-x"));
        assertEquals(false, ThemeFiles.HUD_LOOK.contains("position-x"));
        assertEquals(false, ThemeFiles.HUD_LOOK.contains("scale"));
        assertEquals(true, ThemeFiles.HUD_LOOK.contains("custom-color-1"));
    }
}
