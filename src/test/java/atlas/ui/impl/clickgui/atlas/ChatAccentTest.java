package atlas.ui.impl.clickgui.atlas;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatAccentTest {

    @Test
    public void theAccentsGetAColourNotGrey() {
        assertEquals("Violet", '9', ChatAccent.codeFor(0xA78BFA));
        assertEquals("Sky", 'b', ChatAccent.codeFor(0x4FC3F7));
        assertEquals("Mint, hue 157", 'b', ChatAccent.codeFor(0x5FFFC1));
        assertEquals("Rose, hue 339", 'c', ChatAccent.codeFor(0xFF6B9D));
        assertEquals("green", 'a', ChatAccent.codeFor(0x41D982));
        assertEquals("magenta", 'd', ChatAccent.codeFor(0xE040FB));
        assertEquals("Amber", '6', ChatAccent.codeFor(0xFFC857));
        assertEquals("Coral", 'c', ChatAccent.codeFor(0xFF8A65));
        assertEquals("Lime", 'e', ChatAccent.codeFor(0xC6FF00));
    }

    @Test
    public void greyAndBlackHaveNone() {
        assertEquals(0, ChatAccent.codeFor(0xA0A0A0));
        assertEquals(0, ChatAccent.codeFor(0x101216));
    }
}
