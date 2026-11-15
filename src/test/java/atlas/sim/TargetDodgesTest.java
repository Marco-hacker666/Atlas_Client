package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where a player gets to if it changes what it is doing: the ways out of an arrow's path (2026-11-16). */
public class TargetDodgesTest {

    private static final double RESTING_FALL = -0.0784000015258789;

    /** Flat ground at y = 0. */
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

    private static TargetFit.Seen seen(PlayerSim sim, float yaw, boolean sprinting) {
        TargetFit.Seen seen = new TargetFit.Seen();
        seen.x = MathHelper.floor_double(sim.x() * 32.0);
        seen.y = MathHelper.floor_double(sim.y() * 32.0);
        seen.z = MathHelper.floor_double(sim.z() * 32.0);
        seen.onGround = sim.onGround;
        seen.yaw = yaw;
        seen.sprinting = sprinting;
        return seen;
    }

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    private static TargetForecast way(List<TargetForecast> paths, String name) {
        for (int i = 0; i < TargetDodges.WAYS.length; i++) {
            if (TargetDodges.WAYS[i].name.equals(name)) {
                return paths.get(i);
            }
        }
        throw new AssertionError("no way called " + name);
    }

    /** A track that has seen one position: a player standing at the origin's cell. */
    private static TargetTrack resting() {
        TargetTrack track = new TargetTrack();
        track.observe(seen(standing(), 0.0F, false), WORLD, 0.0);
        return track;
    }

    @Test
    public void fromRestEveryWayStartsAfterTheReaction() {
        List<TargetForecast> paths = TargetDodges.pathsAtRest(resting(), WORLD, 12, 4, 0.0F);
        assertEquals(TargetDodges.WAYS.length, paths.size());
        for (TargetForecast path : paths) {
            assertEquals(12, path.moves());
            assertTrue(path.simulated);
            /* Until it has reacted it stands where it stood. */
            for (int i = 0; i <= 4; i++) {
                assertEquals(path.x[0], path.x[i], 0.0);
                assertEquals(path.z[0], path.z[i], 0.0);
                assertEquals(0.0, path.y[i], 0.0);
            }
        }
        TargetForecast stop = way(paths, "stop");
        assertEquals(stop.x[0], stop.x[12], 0.0);
        assertEquals(stop.z[0], stop.z[12], 0.0);
    }

    @Test
    public void theWaysGoWhereTheirKeysTakeThem() {
        List<TargetForecast> paths = TargetDodges.pathsAtRest(resting(), WORLD, 12, 4, 0.0F);
        /* Facing south (+z): its left is east (+x). */
        TargetForecast left = way(paths, "left");
        TargetForecast right = way(paths, "right");
        assertTrue(left.x[8] - left.x[0] > 0.3);
        assertEquals(left.z[0], left.z[8], 1.0E-9);
        assertEquals(left.x[8] - left.x[0], right.x[0] - right.x[8], 1.0E-9);
        assertTrue(way(paths, "back").z[8] < way(paths, "back").z[0] - 0.3);
        assertTrue(way(paths, "forward").z[8] > way(paths, "forward").z[0] + 0.3);
        /* A turn to the left and a sprint goes east too, and further than the step does. */
        TargetForecast turned = way(paths, "turn left");
        assertTrue(turned.x[8] - turned.x[0] > left.x[8] - left.x[0]);
        assertEquals(turned.z[0], turned.z[8], 1.0E-6);
        /* The sprint-jump is the farthest of all, and leaves the ground. */
        TargetForecast jumped = way(paths, "turn left jump");
        assertTrue(jumped.x[8] - jumped.x[0] > turned.x[8] - turned.x[0]);
        assertTrue(jumped.y[6] > 0.4);
        assertFalse(jumped.onGround[6]);
        assertEquals(jumped.x[8] - jumped.x[0], way(paths, "turn right jump").x[0] - way(paths, "turn right jump").x[8],
                1.0E-6);
        /* Three moves after the reaction: a step is still inside an arrow's reach of 0.6, a sprint-jump is not. */
        assertTrue("step " + (left.x[7] - left.x[0]), left.x[7] - left.x[0] < 0.6);
        assertTrue("sprint-jump " + (jumped.x[7] - jumped.x[0]), jumped.x[7] - jumped.x[0] > 0.6);
        /* A jump on the spot: up and nowhere else. */
        TargetForecast hop = way(paths, "jump");
        assertTrue(hop.y[7] > 0.9);
        assertEquals(hop.x[0], hop.x[12], 0.0);
        /* The yaw it faces turns the lot. */
        TargetForecast west = way(TargetDodges.pathsAtRest(resting(), WORLD, 12, 4, 90.0F), "forward");
        assertTrue(west.x[8] < west.x[0] - 0.3);
        assertEquals(west.z[0], west.z[8], 1.0E-6);
    }

    @Test
    public void aRunningPlayerGoesOnAsForecastUntilItReacts() {
        float yaw = 25.0F;
        PlayerSim game = standing();
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        in.forward = 1.0F;
        in.sprint = true;
        TargetTrack track = new TargetTrack();
        track.observe(seen(game, yaw, false), WORLD, 0.0);
        assertTrue(TargetDodges.paths(track, WORLD, 10, 3).isEmpty());
        for (int i = 0; i < 12; i++) {
            game.tick(WORLD, in);
            game.tick(WORLD, in);
            track.observe(seen(game, yaw, true), WORLD, 2.0);
        }
        assertTrue(track.locked());
        TargetForecast forecast = track.forecast(WORLD, 10);
        List<TargetForecast> paths = TargetDodges.paths(track, WORLD, 10, 3);
        assertEquals(TargetDodges.WAYS.length, paths.size());
        for (TargetForecast path : paths) {
            for (int i = 0; i <= 3; i++) {
                assertEquals(forecast.x[i], path.x[i], 1.0E-9);
                assertEquals(forecast.z[i], path.z[i], 1.0E-9);
            }
        }
        /* Sprinting on is the forecast itself. */
        TargetForecast on = way(paths, "forward");
        assertEquals(forecast.x[10], on.x[10], 1.0E-9);
        assertEquals(forecast.z[10], on.z[10], 1.0E-9);
        /* Letting go falls behind it; a turn leaves its line. */
        TargetForecast stop = way(paths, "stop");
        double ahead = Math.hypot(forecast.x[10] - forecast.x[3], forecast.z[10] - forecast.z[3]);
        assertTrue(Math.hypot(stop.x[10] - stop.x[3], stop.z[10] - stop.z[3]) < 0.6 * ahead);
        TargetForecast turned = way(paths, "turn left");
        assertTrue(Math.hypot(turned.x[10] - forecast.x[10], turned.z[10] - forecast.z[10]) > 0.6);
        /* It keeps its keys for a jump: as far along, and in the air. */
        TargetForecast hop = way(paths, "jump");
        assertTrue(hop.y[6] > 0.4);
        assertTrue(Math.hypot(hop.x[10] - forecast.x[3], hop.z[10] - forecast.z[3]) >= ahead);
        /* No time to react at all: the ways part from the first move. */
        assertTrue(Math.hypot(way(TargetDodges.paths(track, WORLD, 10, 0), "stop").x[2] - forecast.x[2],
                way(TargetDodges.paths(track, WORLD, 10, 0), "stop").z[2] - forecast.z[2]) > 0.01);
    }

    @Test
    public void nothingKnownGivesNoPaths() {
        assertTrue(TargetDodges.paths(null, WORLD, 10, 3).isEmpty());
        assertTrue(TargetDodges.pathsAtRest(new TargetTrack(), WORLD, 10, 3, 0.0F).isEmpty());
        assertTrue(TargetDodges.pathsAtRest(resting(), WORLD, 0, 3, 0.0F).isEmpty());
        /* Last seen in the air: where it comes down is not known from rest. */
        PlayerSim falling = standing();
        falling.box = falling.box.offset(0.0, 3.0, 0.0);
        falling.onGround = false;
        TargetTrack track = new TargetTrack();
        track.observe(seen(falling, 0.0F, false), WORLD, 0.0);
        assertTrue(TargetDodges.pathsAtRest(track, WORLD, 10, 3, 0.0F).isEmpty());
    }
}
