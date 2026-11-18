package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TargetDodgesTest {
    private static final double RESTING_FALL = -0.0784000015258789;

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

        TargetForecast left = way(paths, "left");
        TargetForecast right = way(paths, "right");
        assertTrue(left.x[8] - left.x[0] > 0.3);
        assertEquals(left.z[0], left.z[8], 1.0E-9);
        assertEquals(left.x[8] - left.x[0], right.x[0] - right.x[8], 1.0E-9);
        assertTrue(way(paths, "back").z[8] < way(paths, "back").z[0] - 0.3);
        assertTrue(way(paths, "forward").z[8] > way(paths, "forward").z[0] + 0.3);

        TargetForecast turned = way(paths, "turn left");
        assertTrue(turned.x[8] - turned.x[0] > left.x[8] - left.x[0]);
        assertEquals(turned.z[0], turned.z[8], 1.0E-6);

        TargetForecast jumped = way(paths, "turn left jump");
        assertTrue(jumped.x[8] - jumped.x[0] > turned.x[8] - turned.x[0]);
        assertTrue(jumped.y[6] > 0.4);
        assertFalse(jumped.onGround[6]);
        assertEquals(jumped.x[8] - jumped.x[0], way(paths, "turn right jump").x[0] - way(paths, "turn right jump").x[8],
                1.0E-6);

        assertTrue("step " + (left.x[7] - left.x[0]), left.x[7] - left.x[0] < 0.6);
        assertTrue("sprint-jump " + (jumped.x[7] - jumped.x[0]), jumped.x[7] - jumped.x[0] > 0.6);

        TargetForecast hop = way(paths, "jump");
        assertTrue(hop.y[7] > 0.9);
        assertEquals(hop.x[0], hop.x[12], 0.0);

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

        TargetForecast on = way(paths, "forward");
        assertEquals(forecast.x[10], on.x[10], 1.0E-9);
        assertEquals(forecast.z[10], on.z[10], 1.0E-9);

        TargetForecast stop = way(paths, "stop");
        double ahead = Math.hypot(forecast.x[10] - forecast.x[3], forecast.z[10] - forecast.z[3]);
        assertTrue(Math.hypot(stop.x[10] - stop.x[3], stop.z[10] - stop.z[3]) < 0.6 * ahead);
        TargetForecast turned = way(paths, "turn left");
        assertTrue(Math.hypot(turned.x[10] - forecast.x[10], turned.z[10] - forecast.z[10]) > 0.6);

        TargetForecast hop = way(paths, "jump");
        assertTrue(hop.y[6] > 0.4);
        assertTrue(Math.hypot(hop.x[10] - forecast.x[3], hop.z[10] - forecast.z[3]) >= ahead);

        assertTrue(Math.hypot(way(TargetDodges.paths(track, WORLD, 10, 0), "stop").x[2] - forecast.x[2],
                way(TargetDodges.paths(track, WORLD, 10, 0), "stop").z[2] - forecast.z[2]) > 0.01);
    }

    @Test
    public void keysJustLetGoKeepThePathsOnTheForecast() {
        float yaw = -60.0F;
        PlayerSim game = standing();
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        in.forward = 1.0F;
        in.sprint = true;
        TargetTrack track = new TargetTrack();
        track.observe(seen(game, yaw, false), WORLD, 0.0);
        for (int i = 0; i < 12; i++) {
            game.tick(WORLD, in);
            game.tick(WORLD, in);
            track.observe(seen(game, yaw, true), WORLD, 2.0);
        }
        in.forward = 0.0F;
        in.sprint = false;
        game.tick(WORLD, in);
        track.observe(seen(game, yaw, false), WORLD, 1.0);
        assertTrue(track.locked());
        assertTrue("the forecast is blending", track.runTapInput() != null);
        TargetForecast forecast = track.forecast(WORLD, 8);
        List<TargetForecast> paths = TargetDodges.paths(track, WORLD, 8, 3);
        for (TargetForecast path : paths) {
            for (int i = 0; i <= 3; i++) {
                assertEquals(forecast.x[i], path.x[i], 1.0E-9);
                assertEquals(forecast.z[i], path.z[i], 1.0E-9);
            }
        }

        assertTrue(Math.hypot(way(paths, "turn left").x[8] - forecast.x[8],
                way(paths, "turn left").z[8] - forecast.z[8]) > 0.3);
    }

    @Test
    public void runningTheSharedMovesOnceGivesEachWaysOwnPath() {
        float yaw = 33.0F;
        PlayerSim game = standing();
        PlayerSim.Input in = new PlayerSim.Input();
        in.yaw = yaw;
        in.forward = 1.0F;
        in.sprint = true;
        for (int i = 0; i < 9; i++) {
            game.tick(WORLD, in);
        }
        for (int after : new int[]{0, 1, 4, 12, 20}) {
            for (float rate : new float[]{0.0F, 7.5F}) {
                List<TargetForecast> all = TargetDodges.runAll(game, WORLD, 12, after, in, yaw, rate);
                for (int w = 0; w < TargetDodges.WAYS.length; w++) {
                    TargetForecast one = TargetDodges.run(game, WORLD, 12, after, in, yaw, rate, TargetDodges.WAYS[w]);
                    for (int i = 0; i <= 12; i++) {
                        String where = TargetDodges.WAYS[w].name + " after " + after + " rate " + rate + " move " + i;
                        assertEquals(where, one.x[i], all.get(w).x[i], 0.0);
                        assertEquals(where, one.y[i], all.get(w).y[i], 0.0);
                        assertEquals(where, one.z[i], all.get(w).z[i], 0.0);
                        assertEquals(where, one.onGround[i], all.get(w).onGround[i]);
                    }
                }
            }
        }
    }

    @Test
    public void nothingKnownGivesNoPaths() {
        assertTrue(TargetDodges.paths(null, WORLD, 10, 3).isEmpty());
        assertTrue(TargetDodges.pathsAtRest(new TargetTrack(), WORLD, 10, 3, 0.0F).isEmpty());
        assertTrue(TargetDodges.pathsAtRest(resting(), WORLD, 0, 3, 0.0F).isEmpty());

        PlayerSim falling = standing();
        falling.box = falling.box.offset(0.0, 3.0, 0.0);
        falling.onGround = false;
        TargetTrack track = new TargetTrack();
        track.observe(seen(falling, 0.0F, false), WORLD, 0.0);
        assertTrue(TargetDodges.pathsAtRest(track, WORLD, 10, 3, 0.0F).isEmpty());
    }
}
