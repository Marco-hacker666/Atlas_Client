package atlas.sim;

import atlas.util.DodgeAim;
import atlas.util.ProjectileSolver;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * The two halves together (2026-11-16): a full bow shot at a player standing some way off who, a few ticks after
 * the arrow leaves, gets out of the way by each of the ways the simulation runs. How many of those the arrow still
 * hits, aimed at the middle as before and aimed by {@link DodgeAim}. Hits here are the game's own (the box grown by
 * 0.3), not the cautious ones the aim is picked by.
 */
public class DodgeShotTest {
    private static final double EYE = 1.62;

    private static final PlayerSim.World WORLD = new PlayerSim.World() {
        private final AxisAlignedBB ground = new AxisAlignedBB(-500, -1, -500, 500, 0, 500);

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            if (this.ground.intersectsWith(area)) {
                out.add(this.ground);
            }
            return out;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6F;
        }
    };

    /** A path as a target: the player's box {@code ticks} after the shot, the path's index 0 being the shot. */
    private static ProjectileSolver.Target along(final TargetForecast path) {
        return ticks -> {
            double at = Math.max(0.0, Math.min(path.moves(), ticks));
            int i = (int) Math.floor(at);
            int j = Math.min(path.moves(), i + 1);
            double k = at - i;
            double x = path.x[i] + (path.x[j] - path.x[i]) * k;
            double y = path.y[i] + (path.y[j] - path.y[i]) * k;
            double z = path.z[i] + (path.z[j] - path.z[i]) * k;
            return new double[]{x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3};
        };
    }

    /** {hits aimed at the middle, hits aimed by DodgeAim, ways} for a target {@code distance} off reacting after {@code reaction} ticks. */
    private static int[] shoot(double distance, int reaction, double speed) {
        PlayerSim player = new PlayerSim();
        player.box = new AxisAlignedBB(-0.3, 0.0, distance - 0.3, 0.3, 1.8, distance + 0.3);
        player.onGround = true;
        player.motionY = -0.0784000015258789;
        TargetFit.Seen seen = new TargetFit.Seen();
        seen.x = MathHelper.floor_double(player.x() * 32.0);
        seen.y = 0;
        seen.z = MathHelper.floor_double(player.z() * 32.0);
        seen.onGround = true;
        TargetTrack track = new TargetTrack();
        track.observe(seen, WORLD, 0.0);
        /* It faces the shooter. */
        List<TargetForecast> paths = TargetDodges.pathsAtRest(track, WORLD, 60, reaction, 180.0F);
        List<ProjectileSolver.Target> dodges = new ArrayList<ProjectileSolver.Target>();
        for (TargetForecast path : paths) {
            dodges.add(along(path));
        }
        final double x = seen.midX();
        final double z = seen.midZ();
        ProjectileSolver.Target still = ticks -> new double[]{x - 0.3, 0.0, z - 0.3, x + 0.3, 1.8, z + 0.3};
        ProjectileSolver.Aim base = ProjectileSolver.solve(0.0, EYE, 0.0, speed, ProjectileSolver.ARROW, still, false);
        DodgeAim.Result result = DodgeAim.best(0.0, EYE, 0.0, base, speed, ProjectileSolver.ARROW, still, dodges, 0.0,
                0.0);
        int ticks = DodgeAim.flightTicks(base.ticks);
        assertTrue("still hit standing", ProjectileSolver.fly(0.0, EYE, 0.0, result.aim.yaw, result.aim.pitch, speed,
                ProjectileSolver.ARROW, still, ProjectileSolver.OPEN, ticks).hits());
        return new int[]{
                DodgeAim.covered(0.0, EYE, 0.0, base.yaw, base.pitch, speed, ProjectileSolver.ARROW, dodges, ticks),
                DodgeAim.covered(0.0, EYE, 0.0, result.aim.yaw, result.aim.pitch, speed, ProjectileSolver.ARROW,
                        dodges, ticks),
                dodges.size()};
    }

    @Test
    public void aimedForTheDodgesMoreOfThemAreHit() {
        int middle = 0;
        int proofed = 0;
        int all = 0;
        StringBuilder table = new StringBuilder();
        for (double distance = 10.0; distance <= 40.0; distance += 5.0) {
            table.append(String.format("%n%3.0f blocks:", distance));
            for (int reaction = 2; reaction <= 8; reaction += 2) {
                int[] hit = shoot(distance, reaction, 3.0);
                table.append(String.format("  react %d: %2d -> %2d of %d", reaction, hit[0], hit[1], hit[2]));
                /* Never fewer than aiming at the middle. */
                assertTrue(table.toString(), hit[1] >= hit[0]);
                middle += hit[0];
                proofed += hit[1];
                all += hit[2];
            }
        }
        /* Measured when written: 224 of 308 aimed at the middle, 233 aimed for the dodges. */
        assertTrue(table.toString(), proofed >= middle + 5);
    }

    /** The same for a player running across the shooter's view: {middle, proofed, ways}. */
    private static int[] shootRunner(double distance, int reaction, boolean sprint) {
        float yaw = 90.0F;
        PlayerSim game = new PlayerSim();
        game.box = new AxisAlignedBB(5.7, 0.0, distance - 0.3, 6.3, 1.8, distance + 0.3);
        game.onGround = true;
        game.motionY = -0.0784000015258789;
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        in.forward = 1.0F;
        in.sprint = sprint;
        TargetTrack track = new TargetTrack();
        for (int i = 0; i <= 12; i++) {
            TargetFit.Seen seen = new TargetFit.Seen();
            seen.x = MathHelper.floor_double(game.x() * 32.0);
            seen.y = 0;
            seen.z = MathHelper.floor_double(game.z() * 32.0);
            seen.onGround = true;
            seen.yaw = yaw;
            seen.sprinting = sprint && i > 0;
            track.observe(seen, WORLD, i == 0 ? 0.0 : 2.0);
            if (i < 12) {
                game.tick(WORLD, in);
                game.tick(WORLD, in);
            }
        }
        assertTrue(track.locked());
        ProjectileSolver.Target carryingOn = along(track.forecast(WORLD, 60));
        List<ProjectileSolver.Target> dodges = new ArrayList<ProjectileSolver.Target>();
        for (TargetForecast path : TargetDodges.paths(track, WORLD, 60, reaction)) {
            dodges.add(along(path));
        }
        ProjectileSolver.Aim base = ProjectileSolver.solve(0.0, EYE, 0.0, 3.0, ProjectileSolver.ARROW, carryingOn,
                false);
        DodgeAim.Result result = DodgeAim.best(0.0, EYE, 0.0, base, 3.0, ProjectileSolver.ARROW, carryingOn, dodges,
                0.0, 0.0);
        int ticks = DodgeAim.flightTicks(base.ticks);
        assertTrue("still hit carrying on", ProjectileSolver.fly(0.0, EYE, 0.0, result.aim.yaw, result.aim.pitch,
                3.0, ProjectileSolver.ARROW, carryingOn, ProjectileSolver.OPEN, ticks).hits());
        return new int[]{
                DodgeAim.covered(0.0, EYE, 0.0, base.yaw, base.pitch, 3.0, ProjectileSolver.ARROW, dodges, ticks),
                DodgeAim.covered(0.0, EYE, 0.0, result.aim.yaw, result.aim.pitch, 3.0, ProjectileSolver.ARROW,
                        dodges, ticks),
                dodges.size()};
    }

    @Test
    public void aRunnerThatStopsOrTurnsIsHitMoreOftenToo() {
        int middle = 0;
        int proofed = 0;
        int all = 0;
        StringBuilder table = new StringBuilder();
        for (int run = 0; run < 2; run++) {
            for (double distance = 10.0; distance <= 40.0; distance += 5.0) {
                table.append(String.format("%n%s %3.0f blocks:", run == 0 ? "walk  " : "sprint", distance));
                for (int reaction = 2; reaction <= 8; reaction += 2) {
                    int[] hit = shootRunner(distance, reaction, run == 1);
                    table.append(String.format("  react %d: %2d -> %2d of %d", reaction, hit[0], hit[1], hit[2]));
                    assertTrue(table.toString(), hit[1] >= hit[0]);
                    middle += hit[0];
                    proofed += hit[1];
                    all += hit[2];
                }
            }
        }
        /* Measured when written: 390 of 616 aimed at the middle, 446 aimed for the dodges. */
        assertTrue(table.toString(), proofed >= middle + 40);
    }
}
