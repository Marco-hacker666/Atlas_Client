package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The self check run tick after tick in the order of the game's tick (2026-11-12). The "game" here is the simulation
 * itself, driven the way the client is: between ticks the camera turns and the keys change, then the tick's move is
 * made with that yaw, then the packet goes out with it -- and only then is the move checked. A plain player doing
 * anything a keyboard can do must never be off; that is the test MoveCheck's own wiring could not have, and the one
 * that fails when a move is held against the packet of another tick.
 */
public class MoveAuditTest {

    private static final double RESTING_FALL = -0.0784000015258789;

    /** Ground at y = 0, a slab to step up at x 6..8 and a wall to run into at x 12. */
    private static final class Course implements PlayerSim.World {
        private final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();

        Course() {
            this.solid.add(new AxisAlignedBB(-200, -1, -200, 200, 0, 200));
            this.solid.add(new AxisAlignedBB(6, 0, -3, 8, 0.5, 3));
            this.solid.add(new AxisAlignedBB(12, 0, -200, 13, 4, 200));
        }

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            for (AxisAlignedBB box : this.solid) {
                if (box.intersectsWith(area)) {
                    out.add(box);
                }
            }
            return out;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6F;
        }
    }

    private static final PlayerSim.World WORLD = new Course();

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    /** The keys of tick {@code t} of a player who does a bit of everything, as movementInput would hold them. */
    private static PlayerSim.Input keysOf(int t, float yaw) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        int phase = (t / 25) % 8;
        float forward = phase == 5 ? -1.0F : phase == 6 ? 0.0F : 1.0F;
        float strafe = phase == 2 ? 1.0F : phase == 3 ? -1.0F : phase == 6 ? 1.0F : 0.0F;
        in.sneak = phase == 4;
        in.jump = phase == 1 || phase == 3 || t % 37 == 0;
        in.sprint = phase != 7;
        float scale = in.sneak ? 0.3F : 1.0F;
        in.forward = forward * scale;
        in.strafe = strafe * scale;
        return in;
    }

    /** The camera's yaw at tick {@code t}: it turns every tick, slowly, fast and back. */
    private static float yawOf(int t) {
        return -90.0F + 70.0F * (float) Math.sin(t * 0.13) + (t % 50 < 10 ? 9.5F * (t % 50) : 0.0F);
    }

    @Test
    public void aPlainPlayerTurningWhileItMovesIsNeverOff() {
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        int checked = 0;
        double worst = 0.0;
        boolean sprinted = false;
        boolean jumped = false;
        boolean hitWall = false;
        for (int t = 0; t < 400; t++) {
            float yaw = yawOf(t);
            PlayerSim.Input keys = keysOf(t, yaw);
            double feet = game.y();
            /* The tick: the move, by this tick's yaw... */
            game.tick(WORLD, keys);
            sprinted |= game.sprinting;
            jumped |= game.y() > feet + 0.3;
            hitWall |= game.collidedHorizontally;
            /* ...then the packet, with the same yaw and the sprint and sneak as they now are; then the check. */
            MoveAudit.Verdict verdict = audit.endOfTick(t, game.copy(), WORLD, yaw, game.sprinting, keys.sneak, false);
            if (t == 0) {
                assertNull("nothing to check the first move from", verdict.result);
                continue;
            }
            assertNotNull("tick " + t, verdict.result);
            checked++;
            worst = Math.max(worst, verdict.result.offset);
            assertTrue("tick " + t + " off " + verdict.result.offset + " keys " + verdict.result.keys(),
                    verdict.result.offset <= 1.0E-9);
        }
        assertEquals(399, checked);
        assertEquals(0.0, worst, 1.0E-9);
        /* And it was a real walk: it sprinted, left the ground, and ran into the wall. */
        assertTrue(sprinted && jumped && hitWall);
    }

    @Test
    public void aMoveHeldAgainstTheNextPacketsLookIsOff() {
        /* What MoveCheck did until 2026-11-12: the move of one tick checked with the yaw of the packet after it.
           Walking straight nothing shows; turning, most moves are off. This is why the order is tested. */
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        int off = 0;
        int checked = 0;
        for (int t = 0; t < 200; t++) {
            PlayerSim.Input keys = keysOf(t, yawOf(t));
            game.tick(WORLD, keys);
            MoveAudit.Verdict verdict = audit.endOfTick(t, game.copy(), WORLD, yawOf(t + 1), game.sprinting,
                    keys.sneak, false);
            if (verdict.result != null) {
                checked++;
                off += verdict.result.offset > SelfCheck.OFF ? 1 : 0;
            }
        }
        assertTrue(off + " of " + checked + " off", off > checked / 2);
    }

    @Test
    public void aMoveIsCheckedOnlyFromTheTickRightBeforeIt() {
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        PlayerSim.Input idle = new PlayerSim.Input();
        game.tick(WORLD, idle);
        assertNull(audit.endOfTick(10, game.copy(), WORLD, 0.0F, false, false, false).result);
        game.tick(WORLD, idle);
        assertNotNull(audit.endOfTick(11, game.copy(), WORLD, 0.0F, false, false, false).result);
        /* A tick the game skipped (12): the move seen at 13 is two moves, and is not checked... */
        game.tick(WORLD, idle);
        game.tick(WORLD, idle);
        assertNull(audit.endOfTick(13, game.copy(), WORLD, 0.0F, false, false, false).result);
        /* ...but it is the start of the next. The same tick twice (a tick run ahead) is not checked either. */
        game.tick(WORLD, idle);
        assertNotNull(audit.endOfTick(14, game.copy(), WORLD, 0.0F, false, false, false).result);
        game.tick(WORLD, idle);
        assertNull(audit.endOfTick(14, game.copy(), WORLD, 0.0F, false, false, false).result);
    }

    @Test
    public void aStateTheSimulationDoesNotCoverIsNeitherCheckedNorCheckedFrom() {
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        PlayerSim.Input idle = new PlayerSim.Input();
        audit.endOfTick(1, game.copy(), WORLD, 0.0F, false, false, false);
        /* Flying, riding: no state. */
        assertNull(audit.endOfTick(2, null, WORLD, 0.0F, false, false, false).result);
        game.tick(WORLD, idle);
        assertNull("no start: the tick before had no state", audit.endOfTick(3, game.copy(), WORLD, 0.0F, false,
                false, false).result);
        game.tick(WORLD, idle);
        assertNotNull(audit.endOfTick(4, game.copy(), WORLD, 0.0F, false, false, false).result);
        /* Paused (the check switched off for a while): the same. */
        audit.pause();
        game.tick(WORLD, idle);
        assertNull(audit.endOfTick(5, game.copy(), WORLD, 0.0F, false, false, false).result);
    }

    @Test
    public void aKnockbackAppliedBeforeTheMoveIsTakenAndTheMoveFits() {
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        PlayerSim.Input idle = new PlayerSim.Input();
        game.tick(WORLD, idle);
        audit.endOfTick(1, game.copy(), WORLD, 0.0F, false, false, false);
        /* Between two ticks: the packet arrives, the game sets the motion. */
        audit.impulses().velocity(0.3, 0.36, -0.2);
        game.motionX = 0.3;
        game.motionY = 0.36;
        game.motionZ = -0.2;
        game.tick(WORLD, idle);
        MoveAudit.Verdict verdict = audit.endOfTick(2, game.copy(), WORLD, 0.0F, false, false, false);
        assertEquals(0.0, verdict.result.offset, 1.0E-9);
        assertTrue(verdict.result.velocity);
        assertEquals(Impulses.Knockback.TAKEN, verdict.knockback);
        assertNotNull(verdict.velocity);
        /* The flight that follows is plain again. */
        game.tick(WORLD, idle);
        MoveAudit.Verdict after = audit.endOfTick(3, game.copy(), WORLD, 0.0F, false, false, false);
        assertEquals(0.0, after.result.offset, 1.0E-9);
        assertEquals(Impulses.Knockback.NONE, after.knockback);
    }

    @Test
    public void aKnockbackTheClientNeverTookIsIgnoredOnItsSecondMove() {
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        PlayerSim.Input idle = new PlayerSim.Input();
        game.tick(WORLD, idle);
        audit.endOfTick(1, game.copy(), WORLD, 0.0F, false, false, false);
        audit.impulses().velocity(0.3, 0.36, -0.2);
        game.tick(WORLD, idle);
        assertEquals(Impulses.Knockback.WAITING, audit.endOfTick(2, game.copy(), WORLD, 0.0F, false, false,
                false).knockback);
        game.tick(WORLD, idle);
        MoveAudit.Verdict verdict = audit.endOfTick(3, game.copy(), WORLD, 0.0F, false, false, false);
        assertEquals(Impulses.Knockback.IGNORED, verdict.knockback);
        assertEquals("the moves themselves are plain", 0.0, verdict.result.offset, 1.0E-9);
    }

    @Test
    public void aMoveLeftOutIsNotCheckedAndItsKnockbackIsNotHeldAgainstTheNext() {
        /* Right after a correction. */
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        PlayerSim.Input idle = new PlayerSim.Input();
        game.tick(WORLD, idle);
        audit.endOfTick(1, game.copy(), WORLD, 0.0F, false, false, false);
        audit.impulses().velocity(0.3, 0.36, -0.2);
        game.motionX = 0.3;
        game.motionY = 0.36;
        game.motionZ = -0.2;
        game.tick(WORLD, idle);
        MoveAudit.Verdict left = audit.endOfTick(2, game.copy(), WORLD, 0.0F, false, false, true);
        assertNull(left.result);
        assertEquals(Impulses.Knockback.NONE, left.knockback);
        game.tick(WORLD, idle);
        MoveAudit.Verdict next = audit.endOfTick(3, game.copy(), WORLD, 0.0F, false, false, false);
        assertEquals(0.0, next.result.offset, 1.0E-9);
        assertEquals(Impulses.Knockback.NONE, next.knockback);
    }

    @Test
    public void anAttacksSlowdownBeforeTheMoveFits() {
        /* A hit while sprinting: the click takes the motion across to 0.6 before the tick's move, and the sprint
           key puts the sprint back in the same tick. */
        MoveAudit audit = new MoveAudit();
        PlayerSim game = standing();
        PlayerSim.Input run = new PlayerSim.Input();
        run.yaw = -90.0F;
        run.forward = 1.0F;
        run.sprint = true;
        for (int t = 0; t < 20; t++) {
            game.tick(WORLD, run);
            audit.endOfTick(t, game.copy(), WORLD, run.yaw, game.sprinting, false, false);
        }
        assertTrue(game.sprinting);
        audit.impulses().attack();
        game.motionX *= 0.6;
        game.motionZ *= 0.6;
        game.sprinting = false;
        game.tick(WORLD, run);
        MoveAudit.Verdict verdict = audit.endOfTick(20, game.copy(), WORLD, run.yaw, game.sprinting, false, false);
        assertEquals(0.0, verdict.result.offset, 1.0E-9);
        assertEquals(1, verdict.result.attackSlows);
    }
}
