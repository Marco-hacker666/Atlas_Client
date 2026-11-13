package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which keys moved another player, from two of the positions the server sends (2026-11-13). The "other player" is
 * the simulation run with known keys; what the fit is given is only what a client gets: the cell of 1/32 the player
 * ends in, the flags, and a yaw no finer than the packets carry it.
 */
public class TargetFitTest {

    private static final double RESTING_FALL = -0.0784000015258789;

    /** Ground at y = 0 and a wall where x reaches 6. */
    private static final PlayerSim.World WORLD = new PlayerSim.World() {
        private final AxisAlignedBB ground = new AxisAlignedBB(-200, -1, -200, 200, 0, 200);
        private final AxisAlignedBB wall = new AxisAlignedBB(6, 0, -200, 7, 4, 200);

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            if (this.ground.intersectsWith(area)) {
                out.add(this.ground);
            }
            if (this.wall.intersectsWith(area)) {
                out.add(this.wall);
            }
            return out;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6F;
        }
    };

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(0.01, 0.0, 0.02, 0.61, 1.8, 0.62);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    private static PlayerSim.Input keys(float yaw, float forward, float strafe, boolean jump) {
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        in.forward = forward;
        in.strafe = strafe;
        in.jump = jump;
        return in;
    }

    private static TargetFit.Keys held(int forward, int strafe, float yaw) {
        TargetFit.Keys keys = new TargetFit.Keys();
        keys.forward = forward;
        keys.strafe = strafe;
        keys.yaw = yaw;
        return keys;
    }

    /** {@code moves} moves by these keys, the sprint as given. */
    private static PlayerSim run(PlayerSim from, PlayerSim.Input in, boolean sprinting, int moves) {
        PlayerSim sim = from.copy();
        for (int i = 0; i < moves; i++) {
            sim.sprinting = sprinting;
            sim.jumpTicks = 0;
            sim.move(WORLD, in);
        }
        return sim;
    }

    /** What the server sends of a player in this state: the cell, the ground flag, the yaw to 1/256 of a turn. */
    private static TargetFit.Seen seen(PlayerSim sim, float yaw, boolean sprinting) {
        TargetFit.Seen seen = new TargetFit.Seen();
        seen.x = MathHelper.floor_double(sim.x() * 32.0);
        seen.y = MathHelper.floor_double(sim.y() * 32.0);
        seen.z = MathHelper.floor_double(sim.z() * 32.0);
        seen.onGround = sim.onGround;
        seen.yaw = (float) ((int) Math.floor(yaw * 256.0F / 360.0F) * 360) / 256.0F;
        seen.sprinting = sprinting;
        return seen;
    }

    private static TargetFit.Result fit(PlayerSim start, TargetFit.Seen now, TargetFit.Seen before, double hint,
                                        TargetFit.Keys last, double[] impulse) {
        return TargetFit.fit(start, WORLD, now, before, now.yaw + TargetFit.YAW_MID, TargetFit.YAW_HALF,
                TargetTrack.candidates(hint), hint, last, impulse, TargetFit.SLACK);
    }

    /** A player that has been sprinting by this yaw for a while. */
    private static PlayerSim sprinting(float yaw) {
        return run(standing(), keys(yaw, 1.0F, 0.0F, false), true, 12);
    }

    @Test
    public void theCellIsThe32ndTheServerFlooredTo() {
        TargetFit.Seen seen = new TargetFit.Seen();
        seen.x = 33;
        seen.y = -1;
        seen.z = 0;
        assertEquals(33.5 / 32.0, seen.midX(), 0.0);
        assertEquals(0.0, seen.outside(33.0 / 32.0, 1.0 / 32.0), 0.0);
        assertEquals(0.0, seen.outside(34.0 / 32.0, 0.0), 0.0);
        assertEquals(0.25, seen.outside(34.0 / 32.0 + 0.25, 0.01), 1.0E-12);
        assertEquals(0.5, seen.outside(33.0 / 32.0 - 0.3, -0.4), 1.0E-12);
        /* Under the cell is positive (it has to come up), over it negative. */
        assertEquals(0.0, seen.under(-1.0 / 32.0), 0.0);
        assertEquals(0.1, seen.under(-1.0 / 32.0 - 0.1), 1.0E-12);
        assertEquals(-0.1, seen.under(0.1), 1.0E-12);
    }

    @Test
    public void theKeysAsTheGameScalesThem() {
        TargetFit.Keys keys = held(1, -1, 10.0F);
        PlayerSim.Input plain = keys.input(false);
        assertEquals(1.0F, plain.forward, 0.0F);
        assertEquals(-1.0F, plain.strafe, 0.0F);
        assertEquals(0.3F, keys.input(true).forward, 0.0F);
        keys.itemSlow = true;
        assertEquals(0.2F, keys.input(false).forward, 0.0F);
        assertEquals(0.3F * 0.2F, keys.input(true).forward, 0.0F);
        assertEquals(10.0F, keys.input(true).yaw, 0.0F);
        assertTrue(keys.input(true).sneak);
    }

    @Test
    public void aSprintedRunIsFoundWithItsYaw() {
        /* The packet's yaw is up to 5.6 degrees off the player's; the yaw found has to be far nearer. */
        float yaw = 23.7F;
        PlayerSim start = sprinting(yaw);
        PlayerSim end = run(start, keys(yaw, 1.0F, 0.0F, false), true, 2);
        TargetFit.Seen now = seen(end, yaw - 4.0F, true);
        TargetFit.Result result = fit(start, now, null, 2.0, null, null);
        assertTrue(result.fits());
        assertEquals(2, result.moves);
        assertEquals(1, result.keys.forward);
        assertEquals(0, result.keys.strafe);
        assertFalse(result.keys.jump);
        assertEquals(yaw, result.keys.yaw, 3.5F);
        assertEquals(0, result.led);
        assertEquals(-1, result.impulseAt);
    }

    @Test
    public void theYawsRangeTellsForwardFromForwardAndLeft() {
        /* W+A by yaw 0 runs where W by yaw -45 runs; the packet's yaw says which. */
        PlayerSim start = run(standing(), keys(0.0F, 1.0F, 1.0F, false), false, 12);
        PlayerSim end = run(start, keys(0.0F, 1.0F, 1.0F, false), false, 2);
        TargetFit.Result result = fit(start, seen(end, 0.0F, false), null, 2.0, null, null);
        assertTrue(result.fits());
        assertEquals(1, result.keys.forward);
        assertEquals(1, result.keys.strafe);
        assertEquals(0.0F, result.keys.yaw, 5.0F);
    }

    @Test
    public void aSprintIsOnlyLookedForOnTheForwardKey() {
        /* Backwards with the sprint flag set is something no client does: it is not offered as an answer. */
        PlayerSim start = run(standing(), keys(0.0F, -1.0F, 0.0F, false), false, 12);
        PlayerSim end = run(start, keys(0.0F, -1.0F, 0.0F, false), false, 2);
        assertTrue(fit(start, seen(end, 0.0F, false), null, 2.0, null, null).fits());
        TargetFit.Result flagged = fit(start, seen(end, 0.0F, true), null, 2.0, null, null);
        assertFalse(flagged.fits());
    }

    @Test
    public void aJumpIsToldByTheHeight() {
        float yaw = -80.0F;
        PlayerSim start = sprinting(yaw);
        PlayerSim end = run(start, keys(yaw, 1.0F, 0.0F, true), true, 1);
        assertTrue(end.y() > 0.4);
        TargetFit.Result result = fit(start, seen(end, yaw, true), null, 1.0, null, null);
        assertTrue(result.fits());
        assertTrue(result.keys.jump);
        assertEquals(1, result.moves);
        assertFalse(result.end.onGround);
    }

    @Test
    public void theNumberOfMovesIsFoundWhenTheClockIsOneOff() {
        float yaw = 100.0F;
        PlayerSim start = sprinting(yaw);
        PlayerSim end = run(start, keys(yaw, 1.0F, 0.0F, false), true, 3);
        TargetFit.Result result = fit(start, seen(end, yaw, true), null, 2.0, null, null);
        assertTrue(result.fits());
        assertEquals(3, result.moves);
    }

    @Test
    public void lettingGoOfTheKeysIsFoundAsNoKeys() {
        float yaw = 40.0F;
        PlayerSim start = run(standing(), keys(yaw, 1.0F, 0.0F, false), false, 12);
        PlayerSim end = run(start, keys(yaw, 0.0F, 0.0F, false), false, 2);
        TargetFit.Result result = fit(start, seen(end, yaw, false), null, 2.0, held(1, 0, yaw), null);
        assertTrue(result.fits());
        assertEquals(0, result.keys.forward);
        assertEquals(0, result.keys.strafe);
    }

    @Test
    public void theSameKeysByTheSameYawAreFoundInOneRun() {
        /* The yaw given back is exactly the one handed in: it is left alone while it keeps landing in the cell. */
        float yaw = 61.3F;
        PlayerSim start = sprinting(yaw);
        PlayerSim end = run(start, keys(yaw, 1.0F, 0.0F, false), true, 2);
        TargetFit.Result result = fit(start, seen(end, yaw, true), null, 2.0, held(1, 0, yaw), null);
        assertTrue(result.fits());
        assertEquals(0.0, result.offset, 0.0);
        assertEquals(yaw, result.keys.yaw, 0.0F);
    }

    @Test
    public void aRunIntoAWallIsFoundAlthoughTheWallBendsIt() {
        /* Toward the wall at x 6, a little across: the wall takes the x, the z goes on. */
        float yaw = -70.0F;
        PlayerSim start = run(standing(), keys(yaw, 1.0F, 0.0F, false), true, 22);
        assertTrue(start.collidedHorizontally);
        PlayerSim end = run(start, keys(yaw, 1.0F, 0.0F, false), false, 2);
        TargetFit.Result result = fit(start, seen(end, yaw, false), null, 2.0, null, null);
        assertTrue(result.fits());
        assertEquals(1, result.keys.forward);
        assertTrue(result.end.collidedHorizontally);
    }

    @Test
    public void aMoveNoKeysMakeFitsNothing() {
        PlayerSim start = sprinting(0.0F);
        PlayerSim end = run(start, keys(0.0F, 1.0F, 0.0F, false), true, 2);
        end.box = end.box.offset(0.6, 0.0, 0.0);
        TargetFit.Result result = fit(start, seen(end, 0.0F, true), null, 2.0, held(1, 0, 0.0F), null);
        assertFalse(result.fits());
    }

    @Test
    public void anExpectedKnockbackIsFoundAtTheMoveItCameBefore() {
        float yaw = 0.0F;
        PlayerSim start = sprinting(yaw);
        double[] hit = {-0.3, 0.36, 0.25};
        /* One move as before, then the knockback, then a move with it. */
        PlayerSim mid = run(start, keys(yaw, 1.0F, 0.0F, false), true, 1);
        mid.motionX = hit[0];
        mid.motionY = hit[1];
        mid.motionZ = hit[2];
        PlayerSim end = run(mid, keys(yaw, 1.0F, 0.0F, false), true, 1);
        TargetFit.Seen now = seen(end, yaw, true);
        assertFalse("without it nothing fits", fit(start, now, null, 2.0, held(1, 0, yaw), null).fits());
        TargetFit.Result result = fit(start, now, null, 2.0, held(1, 0, yaw), hit);
        assertTrue(result.fits());
        assertEquals(1, result.impulseAt);
        assertEquals(2, result.moves);
        assertEquals(end.motionY, result.end.motionY, 1.0E-9);
    }

    @Test
    public void keysChangedBetweenTwoPositionsAreFoundWithTheMoveTheyChangedAt() {
        /* W for one move, then W+D for two: no one set of keys ends there. */
        float yaw = 15.0F;
        PlayerSim start = run(standing(), keys(yaw, 1.0F, 0.0F, false), false, 12);
        PlayerSim mid = run(start, keys(yaw, 1.0F, 0.0F, false), false, 1);
        PlayerSim end = run(mid, keys(yaw, 1.0F, -1.0F, false), false, 2);
        TargetFit.Seen before = seen(start, yaw, false);
        TargetFit.Seen now = seen(end, yaw, false);
        TargetFit.Result result = fit(start, now, before, 3.0, held(1, 0, yaw), null);
        assertTrue(result.fits());
        assertEquals(3, result.moves);
        assertEquals(1, result.led);
        assertFalse(result.tapped);
        assertEquals(1, result.keys.forward);
        assertEquals(-1, result.keys.strafe);
    }

    @Test
    public void aTapBetweenTwoPositionsLeavesTheKeysAsTheyWere() {
        /* In a sprint: back for two moves, forward again for two (an s-tap). The keys held after it are the ones
           before it. (Walked, a tap moves too little to be told from other keys, and is not looked for.) */
        float yaw = -120.0F;
        PlayerSim start = run(standing(), keys(yaw, 1.0F, 0.0F, false), true, 12);
        PlayerSim mid = run(start, keys(yaw, -1.0F, 0.0F, false), false, 2);
        PlayerSim end = run(mid, keys(yaw, 1.0F, 0.0F, false), true, 2);
        TargetFit.Seen before = seen(start, yaw, true);
        TargetFit.Seen now = seen(end, yaw, true);
        TargetFit.Result result = fit(start, now, before, 4.0, held(1, 0, yaw), null);
        assertTrue(result.fits());
        assertTrue(result.tapped);
        assertEquals(2, result.led);
        assertEquals(4, result.moves);
        assertEquals(1, result.keys.forward);
        assertEquals(0, result.keys.strafe);
    }

    @Test
    public void inTheAirTheKeysOfBeforeStayWhileTheyFit() {
        /* Rising after a jump with W held; the keys add 0.05 over two moves, and every direction would fit. */
        float yaw = 30.0F;
        PlayerSim start = run(sprinting(yaw), keys(yaw, 1.0F, 0.0F, true), true, 2);
        assertFalse(start.onGround);
        PlayerSim end = run(start, keys(yaw, 1.0F, 0.0F, true), true, 2);
        TargetFit.Keys last = held(1, 0, yaw + 3.0F);
        last.jump = true;
        TargetFit.Result result = fit(start, seen(end, yaw, true), null, 2.0, last, null);
        assertTrue(result.fits());
        assertEquals(1, result.keys.forward);
        assertEquals(0, result.keys.strafe);
        assertTrue("the jump key shows nothing in the air: as it was", result.keys.jump);
    }

    @Test
    public void theMotionThatReplacedThePlayersIsWorkedOut() {
        /* A knockback this client was not told of, before the first of two moves. */
        float yaw = 0.0F;
        PlayerSim start = sprinting(yaw);
        PlayerSim hit = start.copy();
        hit.motionX = 0.31;
        hit.motionY = 0.36;
        hit.motionZ = -0.27;
        PlayerSim end = run(hit, keys(yaw, 1.0F, 0.0F, false), true, 2);
        TargetFit.Seen now = seen(end, yaw, true);
        PlayerSim solved = TargetFit.solve(start, WORLD, now, held(1, 0, yaw), 2, 0, true);
        assertNotNull(solved);
        assertEquals(0.0, now.outside(solved.x(), solved.z()), 0.0);
        assertEquals(0.0, now.under(solved.y()), 0.0);
        /* To what two cells allow: a 64th either way over two moves. */
        assertEquals(end.motionX, solved.motionX, 0.012);
        assertEquals(end.motionY, solved.motionY, 0.012);
        assertEquals(end.motionZ, solved.motionZ, 0.012);
        /* Level only: the fall is left as it is. */
        PlayerSim level = TargetFit.solve(start, WORLD, now, held(1, 0, yaw), 2, 0, false);
        assertNotNull(level);
        assertEquals(run(start, keys(yaw, 1.0F, 0.0F, false), true, 2).motionY, level.motionY, 0.0);
    }

    @Test
    public void noMotionGetsThroughAWall() {
        /* The cell is behind the wall at x 6. */
        PlayerSim start = run(standing(), keys(-90.0F, 1.0F, 0.0F, false), true, 22);
        assertTrue(start.collidedHorizontally);
        TargetFit.Seen now = seen(start, -90.0F, true);
        now.x += 40;
        assertNull(TargetFit.solve(start, WORLD, now, held(1, 0, -90.0F), 2, 0, false));
    }

    @Test
    public void aHeldJumpKeyWaitsOutItsTenTicks() {
        /* Back on ground early (a low ceiling) with the wait not over: the held key does not jump yet. */
        PlayerSim waiting = standing();
        waiting.jumpTicks = 5;
        PlayerSim.Input hop = keys(0.0F, 0.0F, 0.0F, true);
        assertEquals(0.0, TargetFit.positions(waiting, WORLD, hop, false, 4)[3][1], 0.0);
        assertTrue(TargetFit.positions(waiting, WORLD, hop, false, 5)[4][1] > 0.4);
        assertTrue(TargetFit.positions(standing(), WORLD, hop, false, 1)[0][1] > 0.4);
    }

    @Test
    public void wrapAndCopies() {
        assertEquals(-170.0, TargetFit.wrap(190.0), 1.0E-9);
        assertEquals(170.0, TargetFit.wrap(-190.0), 1.0E-9);
        assertEquals(0.0, TargetFit.wrap(720.0), 1.0E-9);
        TargetFit.Seen seen = new TargetFit.Seen();
        seen.x = 5;
        seen.yaw = 12.0F;
        seen.sprinting = true;
        TargetFit.Seen copy = seen.copy();
        copy.x = 6;
        assertEquals(5, seen.x);
        assertEquals(12.0F, copy.yaw, 0.0F);
        assertTrue(copy.sprinting);
        assertArrayEquals(new int[]{2, 1, 3}, TargetTrack.candidates(0.0));
    }
}
