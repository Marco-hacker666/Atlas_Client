package atlas.module.modules;

import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * GODBRIDGE rewritten (2026-11-08), with no crouch (second 2026-11-08 change): GodBridge's plan, tick by tick, with the world as Scaffold would read it. A run
 * walks south (camera yaw 0, keys W), so the back is north (180) and the look 135 or -135.
 */
public class GodBridgeTest {

    private static GodBridge.Input walking(long now, float sentYaw, float sentPitch) {
        GodBridge.Input in = new GodBridge.Input();
        in.x = 0.5;
        in.z = 0.5;
        in.onGround = true;
        in.forward = true;
        in.moveYaw = 0.0F;
        in.sentYaw = sentYaw;
        in.sentPitch = sentPitch;
        in.hasTarget = true;
        in.lookPlaces = true;
        in.jumpEvery = 7;
        in.now = now;
        return in;
    }

    /** Runs the plan as the server would get it: the look follows at most maxTurn a tick. */
    private static final class Sim {
        final GodBridge bridge = new GodBridge(new Random(11));
        float yaw = 0.0F;
        float pitch = 20.0F;
        long now = 100000L;
        float biggestStep;
        float biggestPitchStepSettled;

        GodBridge.Plan step(GodBridge.Input in) {
            GodBridge.Plan plan = this.bridge.tick(in);
            if (plan.look) {
                float dy = MathHelper.wrapAngleTo180_float(plan.yaw - this.yaw);
                float dp = plan.pitch - this.pitch;
                float cap = plan.maxTurn;
                float stepYaw = Math.max(-cap, Math.min(cap, dy));
                float stepPitch = Math.max(-cap, Math.min(cap, dp));
                this.biggestStep = Math.max(this.biggestStep, Math.abs(stepYaw));
                if (cap >= 180.0F) {
                    this.biggestPitchStepSettled = Math.max(this.biggestPitchStepSettled, Math.abs(stepPitch));
                }
                this.yaw += stepYaw;
                this.pitch += stepPitch;
            }
            this.now += 50L;
            return plan;
        }

        GodBridge.Plan walk() {
            return step(walking(this.now, this.yaw, this.pitch));
        }
    }

    @Test
    public void theLookIsBackAndFortyFiveToASideOffTheGrid() {
        Sim sim = new Sim();
        GodBridge.Plan plan = null;
        for (int i = 0; i < 40; i++) {
            plan = sim.walk();
        }
        float fromBack = Math.abs(MathHelper.wrapAngleTo180_float(plan.yaw - 180.0F));
        assertTrue("about 45 off straight back: " + fromBack, fromBack > 43.0F && fromBack < 47.5F);
        assertFalse("never on the 45 degree grid", TellyLane.onGrid(plan.yaw));
        assertEquals(GodBridge.STRAIGHT_PITCH, plan.pitch, GodBridge.PITCH_SPREAD + GodBridge.DRIFT_MAX);
        assertTrue("below Intave BlockRotation's 85", plan.pitch < 85.0F);
    }

    @Test
    public void theTurnRoundTakesSeveralTicksAndThenTheLookHoldsStill() {
        Sim sim = new Sim();
        int ticksToSettle = -1;
        for (int i = 0; i < 60; i++) {
            GodBridge.Plan plan = sim.walk();
            if (ticksToSettle < 0 && plan.maxTurn >= 180.0F) {
                ticksToSettle = i;
            }
        }
        assertTrue("135 degrees at most 45 a tick: three ticks or more, " + ticksToSettle, ticksToSettle >= 3);
        assertTrue("no single step over the cap", sim.biggestStep <= GodBridge.TURN_STEP + 1.0E-3F);
        assertTrue("held: pitch moves under 3 a tick (Intave RotationFlick counts 3-20)",
                sim.biggestPitchStepSettled < 1.0F);
    }

    @Test
    public void neverACrouchAndNoStopOnceTheLookIsThere() {
        /* The plan has no crouch to give (no sneak field since 2026-11-08); a stop only while turning at an edge. */
        Sim sim = new Sim();
        int stops = 0;
        for (int i = 0; i < 200; i++) {
            GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
            in.edge = i % 5 == 0;
            in.lookPlaces = i % 3 != 0;
            in.paceJump = i % 17 == 0;
            GodBridge.Plan plan = sim.step(in);
            if (plan.stop) {
                stops++;
                assertTrue("a stop only while the look still turns", plan.maxTurn <= GodBridge.TURN_STEP);
                assertFalse("no jump on a stop", plan.jump);
            }
        }
        assertTrue("only around the start and the side swaps: " + stops, stops <= 12);
    }

    @Test
    public void anEdgeReachedWhileTurningRoundIsAStopNotACrouch() {
        Sim sim = new Sim();
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.edge = true;
        GodBridge.Plan plan = sim.step(in);
        assertTrue(plan.stop);
        assertTrue(plan.maxTurn <= GodBridge.TURN_STEP);
    }

    /** 2026-11-08 (owner: "change the pitch"): at an edge, the pitch that places from here, same side, no stop. */
    @Test
    public void anEdgeTheLookMissesTakesThePitchThatPlacesAndKeepsTheSide() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.walk();
        }
        int side = sim.bridge.side();
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.edge = true;
        in.lookPlaces = false;
        in.placePitch = sim.pitch + 3.0F;
        float want = in.placePitch;
        GodBridge.Plan plan = sim.step(in);
        assertEquals(side, sim.bridge.side());
        assertEquals(want, plan.pitch, 0.01F);
        assertTrue("it clicks along that pitch", plan.click);
        assertFalse("no stop for a pitch fix", plan.stop);
        assertFalse("no jump at the edge", plan.jump);
        assertEquals(want, sim.bridge.learnedPitch(), GodBridge.DRIFT_MAX);
    }

    @Test
    public void aBigPitchFixIsSpreadOverTicks() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.walk();
        }
        float start = sim.pitch;
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.edge = true;
        in.lookPlaces = false;
        in.placePitch = start + 10.0F;
        GodBridge.Plan plan = sim.step(in);
        assertEquals(start + GodBridge.PITCH_FIX_MAX, plan.pitch, 0.3F);
        assertTrue("not learned before it is reached", Float.isNaN(sim.bridge.learnedPitch()));
    }

    @Test
    public void theNextRunStartsAtThePitchThatPlaced() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.walk();
        }
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.edge = true;
        in.lookPlaces = false;
        in.placePitch = sim.pitch + 4.0F;
        sim.step(in);
        float learned = sim.bridge.learnedPitch();
        GodBridge.Input stop = walking(sim.now, sim.yaw, sim.pitch);
        stop.forward = false;
        sim.step(stop);
        GodBridge.Plan plan = null;
        for (int i = 0; i < 40; i++) {
            plan = sim.walk();
        }
        assertEquals(learned, plan.pitch, GodBridge.PITCH_SPREAD + GodBridge.DRIFT_MAX);
    }

    /** 2026-11-10 (owner: "the jump places no block"): in the air the pitch that places is taken, faster, not learned. */
    @Test
    public void inTheAirThePlacingPitchIsTakenAndNotLearned() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.walk();
        }
        float start = sim.pitch;
        int side = sim.bridge.side();
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.onGround = false;
        in.lookPlaces = false;
        in.placePitch = start + 9.0F;
        GodBridge.Plan plan = sim.step(in);
        assertEquals(start + 9.0F, plan.pitch, 0.01F);
        assertTrue("it clicks along that pitch", plan.click);
        assertFalse("no jump in the air", plan.jump);
        assertEquals(side, sim.bridge.side());
        assertTrue("an air pitch is not the ground one", Float.isNaN(sim.bridge.learnedPitch()));
    }

    @Test
    public void afterTheLandingThePitchEasesBackWithoutAStop() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.walk();
        }
        float start = sim.pitch;
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.onGround = false;
        in.lookPlaces = false;
        in.placePitch = start + 10.0F;
        sim.step(in);
        float previous = sim.pitch;
        for (int i = 0; i < 20; i++) {
            GodBridge.Input landed = walking(sim.now, sim.yaw, sim.pitch);
            landed.edge = i % 4 == 3;
            GodBridge.Plan plan = sim.step(landed);
            assertFalse("no stop on the way back", plan.stop);
            assertTrue("a small step: " + (previous - plan.pitch),
                    Math.abs(previous - plan.pitch) <= GodBridge.AIR_RETURN_STEP + GodBridge.DRIFT_STEP);
            previous = plan.pitch;
        }
        assertEquals(start, sim.pitch, GodBridge.DRIFT_MAX);
    }

    @Test
    public void anEdgeTheLookMissesIsTheOtherSideWithNoCrouch() {
        Sim sim = new Sim();
        for (int i = 0; i < 40; i++) {
            sim.walk();
        }
        int side = sim.bridge.side();
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.edge = true;
        in.lookPlaces = false;
        GodBridge.Plan plan = sim.step(in);
        assertEquals(-side, sim.bridge.side());
        assertTrue("turning to it, capped", plan.maxTurn <= GodBridge.TURN_STEP);
        assertTrue("the turn at an edge lets go of the keys", plan.stop);
    }

    @Test
    public void jumpsEverySevenOrEightBlocksAndWhenThePaceAsks() {
        Sim sim = new Sim();
        for (int i = 0; i < 30; i++) {
            sim.walk();
        }
        int jumps = 0;
        int blocks = 0;
        for (int i = 0; i < 400; i++) {
            if (i % 5 == 0) {
                sim.bridge.placed();
                blocks++;
            }
            if (sim.walk().jump) {
                jumps++;
            }
        }
        assertTrue(blocks + " blocks, " + jumps + " jumps", jumps >= blocks / 9 && jumps <= blocks / 7 + 1);
        GodBridge paced = new GodBridge(new Random(2));
        GodBridge.Input in = walking(0L, 180.0F, 75.0F);
        in.jumpEvery = 0;
        int pacedJumps = 0;
        float yaw = 180.0F;
        for (int i = 0; i < 60; i++) {
            in.now = i * 50L;
            in.sentYaw = yaw;
            in.paceJump = true;
            GodBridge.Plan plan = paced.tick(in);
            yaw = plan.yaw;
            in.sentPitch = plan.pitch;
            if (plan.jump) {
                pacedJumps++;
                assertTrue(in.onGround);
            }
        }
        assertTrue("the pace's jump goes, on the ground and settled: " + pacedJumps, pacedJumps > 5);
    }

    @Test
    public void thePaceAsksForAJumpBeforeAWalkSpeedBlockWithNoCrouch() {
        PlacePace pace = new PlacePace();
        long t = 10000L;
        /* Ten blocks, so the first gap (from time 0, as the server counts it) is out of the last 8. */
        for (int i = 0; i < 10; i++) {
            pace.placed(t, true, true, 1, 0, 64, i);
            t += 230L;
        }
        assertTrue("230 ms a block in a line, never crouched: Speed's 350", pace.jumpWanted(t, pace.lastGap(230L), 135.0F, 0));
        PlacePace slow = new PlacePace();
        t = 10000L;
        for (int i = 0; i < 10; i++) {
            slow.placed(t, true, true, 1, 0, 64, i);
            t += 450L;
        }
        assertFalse("450 ms a block passes", slow.jumpWanted(t, slow.lastGap(230L), 135.0F, 0));
        assertFalse("not bridging any more", slow.jumpWanted(t + 5000L, 230L, 135.0F, 0));
    }

    @Test
    public void thePaceAsksForAJumpBeforeTheSneakPartsTwentyFourFill() {
        PlacePace pace = new PlacePace();
        long t = 10000L;
        for (int i = 0; i < PlacePace.SNEAK_LENGTH - PlacePace.SNEAK_ROOM; i++) {
            pace.placed(t, true, true, 1, 0, 64, i);
            t += 520L;
        }
        assertTrue(pace.jumpWanted(t, 520L, 135.0F, 0));
        /* A block with the feet high in the jump: not under the player, so the Sneak part starts again. */
        pace.placed(t + 300L, false, true, 1, 0, 64, 40);
        assertFalse(pace.jumpWanted(t + 520L, 520L, 135.0F, 0));
    }

    @Test
    public void clicksAtAHumanRateAndAlwaysAtAnEdgeItPlaces() {
        Sim sim = new Sim();
        int clicks = 0;
        for (int i = 0; i < 200; i++) {
            if (sim.walk().click) {
                clicks++;
            }
        }
        double cps = clicks / 10.0;
        assertTrue("about 15-19 a second, at most one counted a tick: " + cps, cps >= 12.0 && cps <= 20.0);
        GodBridge.Input in = walking(sim.now, sim.yaw, sim.pitch);
        in.edge = true;
        in.lookPlaces = true;
        for (int i = 0; i < 20; i++) {
            in.now = sim.now + i * 50L;
            assertTrue(sim.bridge.tick(in).click);
        }
    }

    @Test
    public void noKeysLooksAtTheCornerAndEndsTheRun() {
        GodBridge bridge = new GodBridge(new Random(5));
        GodBridge.Input in = walking(0L, 180.0F, 75.0F);
        bridge.tick(in);
        assertTrue(bridge.running());
        in.forward = false;
        GodBridge.Plan plan = bridge.tick(in);
        assertFalse(bridge.running());
        assertTrue(plan.look);
        assertEquals(225.0F, plan.yaw, 1.0E-3F);
        in.hasTarget = false;
        assertFalse(bridge.tick(in).look);
    }

    @Test
    public void aDiagonalRunLooksStraightBack() {
        GodBridge bridge = new GodBridge(new Random(9));
        GodBridge.Input in = walking(0L, -135.0F, 75.0F);
        in.moveYaw = 45.0F;
        GodBridge.Plan plan = bridge.tick(in);
        assertEquals(GodBridge.DIAGONAL_PITCH, plan.pitch, GodBridge.PITCH_SPREAD + GodBridge.DRIFT_MAX);
        assertTrue(Math.abs(MathHelper.wrapAngleTo180_float(plan.yaw + 135.0F)) < 2.0F);
        assertTrue(GodBridge.diagonal(-135.0F));
        assertFalse(GodBridge.diagonal(180.0F));
    }
}
