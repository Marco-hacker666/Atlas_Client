package atlas.management;

import org.junit.Test;

import static org.junit.Assert.*;

public class PlaceRotationsTest {
    @Test
    public void aRepeatedStepAtTheNextPlacementIsCaught() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 100.0F);
        s.place();
        s.flying(true, 106.5F);
        assertTrue(s.wouldDuplicate(113.0F));
        assertFalse(s.wouldDuplicate(113.2F));
    }

    @Test
    public void aPlacementWithoutALookLeavesTheMemoryAsItWas() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 0.0F);
        s.place();
        s.flying(true, 6.5F);
        s.flying(true, 9.7F);
        s.place();
        s.flying(false, 9.7F);
        s.place();
        s.flying(false, 9.7F);
        assertTrue(s.wouldDuplicate(12.9F));
        assertFalse(s.wouldDuplicate(16.2F));
    }

    @Test
    public void smallStepsNeverCount() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 0.0F);
        s.place();
        s.flying(true, 1.5F);
        assertFalse(s.wouldDuplicate(3.0F));
    }

    @Test
    public void nothingIsKnownAfterAReset() {
        PlaceRotations.State s = new PlaceRotations.State();
        s.flying(true, 0.0F);
        s.place();
        s.flying(true, 6.5F);
        s.reset();
        assertFalse(s.wouldDuplicate(13.0F));
    }
}
