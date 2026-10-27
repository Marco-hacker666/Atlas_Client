package atlas.lb.combat;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Rhythm's danger (2026-10-27): an enemy whose hit can land now. */
public class RhythmDangerTest {
    private static final double EPS = 1e-9;
    /** Our body at the origin. */
    private static final double[] US = {-0.3, 0.0, -0.3, 0.3, 1.8, 0.3};

    @Test
    public void lookMatchesMinecraft() {
        assertArrayEquals(new double[]{0, 0, 1}, RhythmDanger.look(0.0F, 0.0F), EPS);
        assertArrayEquals(new double[]{-1, 0, 0}, RhythmDanger.look(90.0F, 0.0F), EPS);
        assertArrayEquals(new double[]{0, -1, 0}, RhythmDanger.look(0.0F, 90.0F), 1e-6);
    }

    @Test
    public void lookingStraightAtUsWithinReach() {
        // Eyes 2.5 away along -Z, looking along +Z (yaw 0).
        double[] eyes = {0.0, 1.62, -2.8};
        assertTrue(RhythmDanger.lookHits(eyes, RhythmDanger.look(0.0F, 0.0F), 3.0, US));
    }

    @Test
    public void tooFarForItsHit() {
        double[] eyes = {0.0, 1.62, -3.6};
        assertFalse(RhythmDanger.lookHits(eyes, RhythmDanger.look(0.0F, 0.0F), 3.0, US));
        assertTrue(RhythmDanger.lookHits(eyes, RhythmDanger.look(0.0F, 0.0F), 3.4, US));
    }

    @Test
    public void lookingPastUs() {
        double[] eyes = {0.0, 1.62, -2.0};
        assertFalse(RhythmDanger.lookHits(eyes, RhythmDanger.look(30.0F, 0.0F), 3.0, US));
        assertFalse(RhythmDanger.lookHits(eyes, RhythmDanger.look(180.0F, 0.0F), 3.0, US));
    }

    @Test
    public void toleranceTakesASlightlyOffAim() {
        double[] eyes = {0.0, 1.62, -2.0};
        // About 0.4 to the side at our distance: misses the body, meets it grown by 0.3.
        double[] look = RhythmDanger.look(-17.0F, 0.0F);
        assertFalse(RhythmDanger.lookHits(eyes, look, 3.0, US));
        assertTrue(RhythmDanger.lookHits(eyes, look, 3.0, RhythmDanger.expand(US, 0.3)));
    }

    @Test
    public void eyesInsideUsCount() {
        assertTrue(RhythmDanger.lookHits(new double[]{0.0, 1.0, 0.0}, RhythmDanger.look(0.0F, 0.0F), 3.0, US));
    }

    @Test
    public void clickingIsASwingWithinTheMemory() {
        assertFalse(RhythmDanger.clicking(RhythmDanger.NEVER, 100, 8));
        assertTrue(RhythmDanger.clicking(95, 100, 8));
        assertFalse(RhythmDanger.clicking(91, 100, 8));
    }
}
