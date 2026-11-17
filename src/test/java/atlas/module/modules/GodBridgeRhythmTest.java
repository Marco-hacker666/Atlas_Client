package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** GODBRIDGE2's rhythm (2026-11-18): the start from standing, the jumps, the reset, the hand's jump key. */
public class GodBridgeRhythmTest {

    private static GodBridgeRhythm.Input walking() {
        GodBridgeRhythm.Input in = new GodBridgeRhythm.Input();
        in.forward = true;
        in.onGround = true;
        in.settled = true;
        return in;
    }

    private static GodBridgeRhythm.Settings fixed() {
        GodBridgeRhythm.Settings s = new GodBridgeRhythm.Settings();
        s.startDelayMin = 10;
        s.startDelayMax = 10;
        s.resetAfterMin = 3;
        s.resetAfterMax = 3;
        return s;
    }

    /** Ticks until the start lets the walk go. */
    private static int startTicks(GodBridgeRhythm r, GodBridgeRhythm.Settings s) {
        for (int i = 1; i < 100; i++) {
            GodBridgeRhythm.Keys k = r.tick(walking(), s);
            if (!k.hold) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void aRunStartsStandingAndCrouchedForTheFirstTicks() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(3));
        GodBridgeRhythm.Settings s = fixed();
        int crouched = 0;
        int held = 0;
        for (int i = 0; i < 10; i++) {
            GodBridgeRhythm.Keys k = r.tick(walking(), s);
            assertTrue("held still for the start delay, tick " + i, k.hold);
            assertTrue(k.starting);
            held++;
            if (k.sneak) {
                crouched++;
                assertEquals("the crouch is the first ticks only", crouched, held);
            }
        }
        assertTrue("5-7 ticks crouched: " + crouched, crouched >= GodBridgeRhythm.CROUCH_MIN
                && crouched <= GodBridgeRhythm.CROUCH_MAX);
        GodBridgeRhythm.Keys k = r.tick(walking(), s);
        assertFalse("then the walk goes", k.hold);
        assertFalse(k.sneak);
        assertTrue("a straight run has no side key", k.noStrafe);
    }

    @Test
    public void theStartWaitsForTheLookButNotForever() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(3));
        GodBridgeRhythm.Settings s = fixed();
        int ticks = 0;
        GodBridgeRhythm.Keys k;
        do {
            GodBridgeRhythm.Input in = walking();
            in.settled = false;
            k = r.tick(in, s);
            ticks++;
        } while (k.hold && ticks < 200);
        assertEquals("start delay plus the most it waits", 10 + GodBridgeRhythm.MAX_WAIT + 1, ticks);
    }

    @Test
    public void aJumpEveryEightBlocksAndAFreshStartAfterTheSetJumps() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(5));
        GodBridgeRhythm.Settings s = fixed();
        assertTrue(startTicks(r, s) > 0);
        int jumps = 0;
        int blocks = 0;
        int blocksAtJump = -1;
        boolean restarted = false;
        for (int i = 0; i < 400 && !restarted; i++) {
            if (i % 5 == 0) {
                r.placed();
                blocks++;
            }
            GodBridgeRhythm.Keys k = r.tick(walking(), s);
            if (k.jump) {
                jumps++;
                if (blocksAtJump < 0) {
                    blocksAtJump = blocks;
                }
            }
            if (k.starting) {
                restarted = true;
                assertTrue("held and crouched again", k.hold && k.sneak);
            }
        }
        assertEquals("first jump at the eighth block", 8, blocksAtJump);
        assertTrue("started over", restarted);
        assertEquals("the third reaching of the count starts over instead of jumping", 2, jumps);
    }

    @Test
    public void theJumpWaitsTheDelayAfterTheLastBlock() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(5));
        GodBridgeRhythm.Settings s = fixed();
        s.jumpDelayMin = 3;
        s.jumpDelayMax = 3;
        startTicks(r, s);
        for (int i = 0; i < 8; i++) {
            r.placed();
            assertFalse(r.tick(walking(), s).jump);
        }
        assertFalse(r.tick(walking(), s).jump);
        assertFalse(r.tick(walking(), s).jump);
        assertTrue("the third tick after the eighth block", r.tick(walking(), s).jump);
    }

    @Test
    public void theHandsJumpKeyIsTheirsUntilLandedAgain() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(5));
        GodBridgeRhythm.Settings s = fixed();
        startTicks(r, s);
        GodBridgeRhythm.Input jump = walking();
        jump.jumpKey = true;
        GodBridgeRhythm.Keys k = r.tick(jump, s);
        assertFalse(k.hold || k.sneak || k.jump || k.noStrafe);
        GodBridgeRhythm.Input air = walking();
        air.onGround = false;
        for (int i = 0; i < 5; i++) {
            k = r.tick(air, s);
            assertFalse("nothing while in the air after it", k.hold || k.sneak || k.jump || k.noStrafe);
        }
        for (int i = 0; i < GodBridgeRhythm.MANUAL_GROUND_TICKS; i++) {
            assertFalse(r.tick(walking(), s).hold);
        }
        assertTrue("then a fresh start", r.tick(walking(), s).starting);
    }

    @Test
    public void aSlideKeepsTheCrouchWithStartCrouchOff() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(5));
        GodBridgeRhythm.Settings s = fixed();
        s.startCrouch = false;
        GodBridgeRhythm.Input sliding = walking();
        sliding.sliding = true;
        for (int i = 0; i < 30; i++) {
            GodBridgeRhythm.Keys k = r.tick(sliding, s);
            assertTrue("crouched while it slides", k.sneak);
            assertTrue("and the start does not end on a slide", k.hold);
        }
        GodBridgeRhythm.Keys still = r.tick(walking(), s);
        assertFalse("no crouch once still, with start-crouch off", still.sneak);
    }

    @Test
    public void aDiagonalRunKeepsTheSideKeyAndAChangeOfWayStartsOver() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(5));
        GodBridgeRhythm.Settings s = fixed();
        GodBridgeRhythm.Input diagonal = walking();
        diagonal.diagonal = true;
        GodBridgeRhythm.Keys k;
        do {
            k = r.tick(diagonal, s);
        } while (k.hold);
        assertFalse("W and a side key walk the diagonal", k.noStrafe);
        assertTrue("straight now: a fresh start", r.tick(walking(), s).starting);
    }

    @Test
    public void noWalkNoKeysTouched() {
        GodBridgeRhythm r = new GodBridgeRhythm(new Random(5));
        GodBridgeRhythm.Input idle = walking();
        idle.forward = false;
        GodBridgeRhythm.Keys k = r.tick(idle, fixed());
        assertFalse(k.hold || k.sneak || k.jump || k.noStrafe);
    }
}
