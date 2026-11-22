package atlas.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DiagNotesTest {
    @Test
    public void colourCodesAreStrippedFromNotes() {
        assertEquals("[Atlas] AntiBot: Removed Bob", DiagNotes.strip("&7[&bAtlas&7] AntiBot: &fRemoved &rBob"));
        assertEquals("Polar jump", DiagNotes.strip("§cPolar §ljump"));
        assertEquals("100% & more", DiagNotes.strip("100% & more"));
    }
}
