package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Attacks, knockback and explosions between two movement packets, and which move each is offered to (2026-11-12).
 * The moves are made by the simulation itself, so a knockback is "applied" by setting the motion as the game does.
 */
public class ImpulsesTest {

    private static final double RESTING_FALL = -0.0784000015258789;

    /** Ground at y = 0, and a wall where x reaches {@code wallX} (none when NaN). */
    private static final class Ground implements PlayerSim.World {
        private final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();

        Ground(double wallX) {
            this.solid.add(new AxisAlignedBB(-100, -1, -100, 100, 0, 100));
            if (!Double.isNaN(wallX)) {
                this.solid.add(new AxisAlignedBB(wallX, 0, -100, wallX + 1, 5, 100));
            }
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

    private static final PlayerSim.World OPEN = new Ground(Double.NaN);
    private static final PlayerSim.Input IDLE = new PlayerSim.Input();
    private static final double[] KB = {0.3, 0.36, 0.0};

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(0.2, 0.0, 0.2, 0.8, 1.8, 0.8);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    /** One move of {@code sim}, with the knockback applied first when {@code applied}; the state after it. */
    private static PlayerSim move(PlayerSim sim, PlayerSim.World world, boolean applied) {
        PlayerSim next = sim.copy();
        if (applied) {
            next.motionX = KB[0];
            next.motionY = KB[1];
            next.motionZ = KB[2];
        }
        next.tick(world, IDLE);
        return next;
    }

    /** Closes the window, checks the move from {@code before} to {@code after} and settles, as MoveCheck does. */
    private static Impulses.Knockback check(Impulses impulses, final PlayerSim before, final PlayerSim after,
                                            final PlayerSim.World world) {
        final SelfCheck.Extra extra = impulses.close();
        SelfCheck.Result result = SelfCheck.check(before, world, 0.0F, false, false, after.x(), after.y(), after.z(),
                extra);
        return impulses.settle(result, () -> SelfCheck.fitsWithVelocity(before, world, 0.0F, false, false, after.x(),
                after.y(), after.z(), extra));
    }

    @Test
    public void withNothingOnOfferThereIsNothingToSettle() {
        Impulses impulses = new Impulses();
        PlayerSim before = standing();
        assertEquals(Impulses.Knockback.NONE, check(impulses, before, move(before, OPEN, false), OPEN));
    }

    @Test
    public void aKnockbackTheGameAppliedBeforeTheMoveIsTakenAndNotOfferedAgain() {
        Impulses impulses = new Impulses();
        PlayerSim before = standing();
        impulses.velocity(KB[0], KB[1], KB[2]);
        PlayerSim after = move(before, OPEN, true);
        assertEquals(Impulses.Knockback.TAKEN, check(impulses, before, after, OPEN));
        assertNull(impulses.close().velocity);
    }

    @Test
    public void aKnockbackAppliedAMoveLateIsTakenByTheNextMove() {
        /* It arrived while a tick was running: that tick's move is without it, the next one has it. */
        Impulses impulses = new Impulses();
        PlayerSim before = standing();
        impulses.velocity(KB[0], KB[1], KB[2]);
        PlayerSim first = move(before, OPEN, false);
        assertEquals(Impulses.Knockback.WAITING, check(impulses, before, first, OPEN));
        PlayerSim second = move(first, OPEN, true);
        assertEquals(Impulses.Knockback.TAKEN, check(impulses, first, second, OPEN));
        assertNull(impulses.close().velocity);
    }

    @Test
    public void aKnockbackInNeitherOfItsTwoMovesIsIgnoredAndThenForgotten() {
        /* What a module that cancels the packet leaves: two plain moves. */
        Impulses impulses = new Impulses();
        PlayerSim before = standing();
        impulses.velocity(KB[0], KB[1], KB[2]);
        PlayerSim first = move(before, OPEN, false);
        assertEquals(Impulses.Knockback.WAITING, check(impulses, before, first, OPEN));
        PlayerSim second = move(first, OPEN, false);
        assertEquals(Impulses.Knockback.IGNORED, check(impulses, first, second, OPEN));
        PlayerSim third = move(second, OPEN, false);
        assertEquals(Impulses.Knockback.NONE, check(impulses, second, third, OPEN));
    }

    @Test
    public void aKnockbackIntoAWallIsTakenAlthoughThePlainMoveFitsToo() {
        /* Straight into a wall the box already touches, with no rise: it ends where standing still ends. The plain
           move is found first; asked, the move with the knockback fits too, so it is taken on the first offer.
           Before, it waited for the next move, which no longer fits it, and was reported as ignored. */
        PlayerSim.World walled = new Ground(0.8);
        Impulses impulses = new Impulses();
        PlayerSim before = standing();
        impulses.velocity(0.4, RESTING_FALL, 0.0);
        PlayerSim pushed = before.copy();
        pushed.motionX = 0.4;
        pushed.motionY = RESTING_FALL;
        pushed.motionZ = 0.0;
        pushed.tick(walled, IDLE);
        PlayerSim plain = before.copy();
        plain.tick(walled, IDLE);
        assertEquals(plain.x(), pushed.x(), 0.0);
        assertEquals(plain.y(), pushed.y(), 0.0);

        final SelfCheck.Extra extra = impulses.close();
        SelfCheck.Result result = SelfCheck.check(before, walled, 0.0F, false, false, pushed.x(), pushed.y(),
                pushed.z(), extra);
        assertFalse("the plain move is found first", result.velocity);
        assertEquals(Impulses.Knockback.TAKEN, impulses.settle(result, () -> SelfCheck.fitsWithVelocity(before,
                walled, 0.0F, false, false, pushed.x(), pushed.y(), pushed.z(), extra)));
        assertNull(impulses.close().velocity);
    }

    @Test
    public void theSecondSearchIsMadeOnlyWhenThePlainMoveFits() {
        /* It costs a whole check. Found with the knockback: no need. Nothing fitting at all: no input with the
           knockback fits either. */
        Impulses impulses = new Impulses();
        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.close();
        SelfCheck.Result withIt = new SelfCheck.Result();
        withIt.offset = 0.0;
        withIt.velocity = true;
        assertEquals(Impulses.Knockback.TAKEN, impulses.settle(withIt, () -> {
            fail("asked although the knockback was in the answer");
            return false;
        }));

        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.close();
        SelfCheck.Result off = new SelfCheck.Result();
        off.offset = 0.5;
        assertEquals(Impulses.Knockback.WAITING, impulses.settle(off, () -> {
            fail("asked although nothing fits");
            return false;
        }));
    }

    @Test
    public void aNewKnockbackReplacesTheOneWaiting() {
        Impulses impulses = new Impulses();
        impulses.velocity(1.0, 0.0, 0.0);
        assertArrayEquals(new double[]{1.0, 0.0, 0.0}, impulses.close().velocity, 0.0);
        impulses.velocity(0.0, 0.0, 2.0);
        SelfCheck.Extra extra = impulses.close();
        assertArrayEquals(new double[]{0.0, 0.0, 2.0}, extra.velocity, 0.0);
        /* The new one is on its first offer: not in this move means waiting, not ignored. */
        SelfCheck.Result off = new SelfCheck.Result();
        off.offset = 0.5;
        assertEquals(Impulses.Knockback.WAITING, impulses.settle(off, () -> false));
    }

    @Test
    public void attacksGoWithTheMoveOfTheirWindowOnly() {
        Impulses impulses = new Impulses();
        impulses.attack();
        impulses.attack();
        assertEquals(2, impulses.close().attacks);
        assertEquals(0, impulses.close().attacks);
    }

    @Test
    public void explosionsAddUpAndOnlyThoseAfterAKnockbackGoWithIt() {
        /* Review, PR #38: explosion A, knockback V, explosion B in one window end as V + B, not V + A + B. */
        Impulses impulses = new Impulses();
        impulses.explosion(0.3, 0.0, 0.0);
        impulses.velocity(0.0, 0.2, 0.25);
        impulses.explosion(0.0, 0.0, 0.1);
        SelfCheck.Extra extra = impulses.close();
        assertTrue(extra.ordered);
        assertArrayEquals(new double[]{0.0, 0.2, 0.25}, extra.velocity, 0.0);
        assertArrayEquals(new double[]{0.3, 0.0, 0.1}, extra.explosion, 0.0);
        assertArrayEquals(new double[]{0.0, 0.0, 0.1}, extra.explosionAfterVelocity, 0.0);
    }

    @Test
    public void withNoKnockbackAllOfTheWindowsExplosionsGoTogether() {
        Impulses impulses = new Impulses();
        impulses.explosion(0.3, 0.0, 0.0);
        impulses.explosion(0.0, 0.1, 0.1);
        SelfCheck.Extra extra = impulses.close();
        assertNull(extra.velocity);
        assertArrayEquals(new double[]{0.3, 0.1, 0.1}, extra.explosion, 0.0);
        assertArrayEquals(new double[]{0.3, 0.1, 0.1}, extra.explosionAfterVelocity, 0.0);
    }

    @Test
    public void anExplosionIsOfferedOnceMoreUnlessAMoveTookIt() {
        Impulses impulses = new Impulses();
        impulses.explosion(0.3, 0.0, 0.0);
        impulses.close();
        SelfCheck.Result plain = new SelfCheck.Result();
        plain.offset = 0.0;
        impulses.settle(plain, () -> false);
        assertArrayEquals("not taken: offered to the next move", new double[]{0.3, 0.0, 0.0},
                impulses.close().explosion, 0.0);
        assertNull("and then no more", impulses.close().explosion);

        impulses.explosion(0.3, 0.0, 0.0);
        impulses.close();
        SelfCheck.Result took = new SelfCheck.Result();
        took.offset = 0.0;
        took.explosion = true;
        impulses.settle(took, () -> false);
        assertNull("taken: not offered again", impulses.close().explosion);
    }

    @Test
    public void aKnockbackOfAMoveNobodyCheckedIsNotCarriedToTheNext() {
        /* The tick right after a correction is left out. Its knockback was applied in that move; offered on, the
           next move (plain) would report it as ignored. */
        Impulses impulses = new Impulses();
        PlayerSim before = standing();
        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.explosion(0.1, 0.0, 0.0);
        PlayerSim unchecked = move(before, OPEN, true);
        impulses.close();
        impulses.skip();
        PlayerSim next = move(unchecked, OPEN, false);
        final SelfCheck.Extra extra = impulses.close();
        assertNull(extra.velocity);
        assertNull(extra.explosion);
        assertNull(extra.explosionAfterVelocity);
        SelfCheck.Result result = SelfCheck.check(unchecked, OPEN, 0.0F, false, false, next.x(), next.y(), next.z(),
                extra);
        assertEquals(Impulses.Knockback.NONE, impulses.settle(result, () -> false));
        /* What comes in after the skip is a new window's, and is offered as ever. */
        impulses.velocity(KB[0], KB[1], KB[2]);
        assertArrayEquals(KB, impulses.close().velocity, 0.0);
    }

    @Test
    public void anExplosionFromBeforeATakenKnockbackIsNotOfferedAgain() {
        /* Review, PR #39: A, then V. V taken replaced A's push; A must not explain the next move. */
        Impulses impulses = new Impulses();
        impulses.explosion(0.3, 0.0, 0.0);
        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.close();
        SelfCheck.Result tookVelocity = new SelfCheck.Result();
        tookVelocity.offset = 0.0;
        tookVelocity.velocity = true;
        assertEquals(Impulses.Knockback.TAKEN, impulses.settle(tookVelocity, () -> false));
        assertNull(impulses.close().explosion);

        /* A, V, then B: B came after the knockback and is still offered once more. */
        impulses.explosion(0.3, 0.0, 0.0);
        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.explosion(0.0, 0.0, 0.1);
        impulses.close();
        assertEquals(Impulses.Knockback.TAKEN, impulses.settle(tookVelocity, () -> false));
        assertArrayEquals(new double[]{0.0, 0.0, 0.1}, impulses.close().explosion, 0.0);
    }

    @Test
    public void clearForgetsTheWindowAndWhatWasWaiting() {
        Impulses impulses = new Impulses();
        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.explosion(0.1, 0.0, 0.0);
        impulses.close();
        impulses.attack();
        impulses.velocity(KB[0], KB[1], KB[2]);
        impulses.clear();
        SelfCheck.Extra extra = impulses.close();
        assertEquals(0, extra.attacks);
        assertNull(extra.velocity);
        assertNull(extra.explosion);
        assertNull(extra.explosionAfterVelocity);
        SelfCheck.Result any = new SelfCheck.Result();
        any.offset = 0.0;
        assertEquals(Impulses.Knockback.NONE, impulses.settle(any, () -> false));
    }
}
