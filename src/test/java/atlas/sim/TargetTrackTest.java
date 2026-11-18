package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TargetTrackTest {
    private static final double RESTING_FALL = -0.0784000015258789;
    private static final float YAW = -37.0F;

    private static final int AHEAD = 5;
    private static final int[] JITTER = {1, 1, 2, 0, 1, 1, 0, 2, 1, 1, 1, 2, 0, 1};
    private static final double[] HIT = {-0.35, 0.36, -0.2};

    private static final class Course implements PlayerSim.World {
        private final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();

        Course() {
            this.solid.add(new AxisAlignedBB(-500, -1, -500, 500, 0, 500));
        }

        Course box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.solid.add(new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));
            return this;
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

        private double waterTop = -1.0;

        Course water(double top) {
            this.waterTop = top;
            return this;
        }

        @Override
        public boolean water(AxisAlignedBB area, double[] flow) {
            return area.minY < this.waterTop;
        }
    }

    private interface Script {
        void keys(int tick, PlayerSim.Input in, PlayerSim game);
    }

    private static PlayerSim standing() {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(0.0, 0.0, -0.1, 0.6, 1.8, 0.5);
        sim.onGround = true;
        sim.motionY = RESTING_FALL;
        return sim;
    }

    private static final class Tracker {
        private int counter = 1;
        private int encodedX;
        private int encodedY;
        private int encodedZ;
        private int encodedYaw;
        private int sinceTeleport;
        private boolean encodedGround;
        private boolean lastSprint;
        private boolean lastSneak;

        int x;
        int y;
        int z;
        float yaw;
        boolean onGround;

        Tracker(PlayerSim player, float yaw) {
            this.encodedX = this.x = MathHelper.floor_double(player.x() * 32.0);
            this.encodedY = this.y = MathHelper.floor_double(player.y() * 32.0);
            this.encodedZ = this.z = MathHelper.floor_double(player.z() * 32.0);
            this.encodedYaw = (int) Math.floor(yaw * 256.0F / 360.0F);
            this.yaw = (float) ((byte) this.encodedYaw * 360) / 256.0F;
            this.encodedGround = this.onGround = player.onGround;
        }

        boolean tick(PlayerSim player, float playerYaw, boolean airborne, boolean sneak) {
            boolean changed = player.sprinting != this.lastSprint || sneak != this.lastSneak;
            this.lastSprint = player.sprinting;
            this.lastSneak = sneak;
            boolean sent = false;
            if (this.counter % 2 == 0 || airborne || changed) {
                this.sinceTeleport++;
                int nowX = MathHelper.floor_double(player.x() * 32.0);
                int nowY = MathHelper.floor_double(player.y() * 32.0);
                int nowZ = MathHelper.floor_double(player.z() * 32.0);
                int nowYaw = (int) Math.floor(playerYaw * 256.0F / 360.0F);
                int dx = nowX - this.encodedX;
                int dy = nowY - this.encodedY;
                int dz = nowZ - this.encodedZ;
                boolean moved = Math.abs(dx) >= 4 || Math.abs(dy) >= 4 || Math.abs(dz) >= 4
                        || this.counter % 60 == 0;
                boolean turned = Math.abs(nowYaw - this.encodedYaw) >= 4;
                if (dx >= -128 && dx < 128 && dy >= -128 && dy < 128 && dz >= -128 && dz < 128
                        && this.sinceTeleport <= 400 && this.encodedGround == player.onGround) {
                    if (moved) {
                        this.x += dx;
                        this.y += dy;
                        this.z += dz;
                        sent = true;
                    }
                    if (turned) {
                        this.yaw = (float) ((byte) nowYaw * 360) / 256.0F;
                    }
                    if (moved || turned) {
                        this.onGround = player.onGround;
                    }
                } else {
                    this.encodedGround = player.onGround;
                    this.sinceTeleport = 0;
                    this.x = nowX;
                    this.y = nowY;
                    this.z = nowZ;
                    this.yaw = (float) ((byte) nowYaw * 360) / 256.0F;
                    this.onGround = player.onGround;
                    sent = true;
                }
                if (moved) {
                    this.encodedX = nowX;
                    this.encodedY = nowY;
                    this.encodedZ = nowZ;
                }
                if (turned) {
                    this.encodedYaw = nowYaw;
                }
            }
            this.counter++;
            return sent;
        }
    }

    private static final class Stats {
        final TargetTrack track = new TargetTrack();
        int positions;
        int fits;
        int measured;
        int simulated;
        private final double[] engine = new double[AHEAD + 1];
        private final double[] line = new double[AHEAD + 1];
        private final double[] engineHeight = new double[AHEAD + 1];
        private final double[] lineHeight = new double[AHEAD + 1];
        private final double[] engineWorst = new double[AHEAD + 1];

        double engine(int ahead) {
            return this.engine[ahead] / (double) this.measured;
        }

        double line(int ahead) {
            return this.line[ahead] / (double) this.measured;
        }

        double engineHeight(int ahead) {
            return this.engineHeight[ahead] / (double) this.measured;
        }

        double lineHeight(int ahead) {
            return this.lineHeight[ahead] / (double) this.measured;
        }

        double engineWorst(int ahead) {
            return this.engineWorst[ahead];
        }

        String at(int ahead) {
            return "engine " + engine(ahead) + ", line " + line(ahead) + " at " + ahead + " moves";
        }
    }

    private static boolean isIn(int[] ticks, int tick) {
        if (ticks != null) {
            for (int t : ticks) {
                if (t == tick) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Stats follow(PlayerSim.World world, int ticks, Script script) {
        return follow(world, ticks, script, null, null, false);
    }

    private static Stats follow(PlayerSim.World world, int ticks, Script script, int[] movesPerTick, int[] hitTicks,
                                boolean told) {
        List<PlayerSim> truth = new ArrayList<PlayerSim>();
        List<PlayerSim.Input> inputs = new ArrayList<PlayerSim.Input>();
        PlayerSim game = standing();
        truth.add(game.copy());
        for (int t = 0; t < ticks + 8; t++) {
            PlayerSim.Input in = new PlayerSim.Input();
            script.keys(t, in, game);
            if (isIn(hitTicks, t)) {
                game.motionX = HIT[0];
                game.motionY = HIT[1];
                game.motionZ = HIT[2];
            }
            inputs.add(in);
            game.tick(world, in);
            truth.add(game.copy());
        }
        Tracker tracker = new Tracker(truth.get(0), inputs.get(0).yaw);
        Stats stats = new Stats();
        TargetFit.Seen last = null;
        int lastSentTick = -1;
        int move = 0;
        int serverTick = 0;
        while (move < ticks) {
            int made = movesPerTick == null ? 1 : movesPerTick[serverTick % movesPerTick.length];
            boolean airborne = false;
            for (int j = 0; j < made && move < ticks; j++) {
                move++;
                PlayerSim before = truth.get(move - 1);
                PlayerSim after = truth.get(move);
                airborne |= before.onGround && inputs.get(move - 1).jump && after.motionY > 0.0 && !after.onGround;
                if (told && isIn(hitTicks, move - 1)) {
                    stats.track.expect(HIT[0], HIT[1], HIT[2]);
                }
            }
            PlayerSim now = truth.get(move);
            PlayerSim.Input in = inputs.get(Math.max(0, move - 1));
            boolean sent = tracker.tick(now, in.yaw, airborne, in.sneak);
            serverTick++;
            if (!sent) {
                continue;
            }
            TargetFit.Seen seen = new TargetFit.Seen();
            seen.x = tracker.x;
            seen.y = tracker.y;
            seen.z = tracker.z;
            seen.onGround = tracker.onGround;
            seen.yaw = tracker.yaw;
            seen.sprinting = now.sprinting;
            seen.sneaking = in.sneak;
            stats.track.observe(seen, world, lastSentTick < 0 ? 0.0 : (double) (serverTick - lastSentTick));
            stats.positions++;
            if (stats.track.lastFit() != null && stats.track.lastFit().fits()) {
                stats.fits++;
            }
            TargetForecast forecast = stats.track.forecast(world, AHEAD);

            if (last != null && forecast != null && stats.positions > 4) {
                stats.measured++;
                if (forecast.simulated) {
                    stats.simulated++;
                }
                double dt = (double) Math.max(1, serverTick - lastSentTick);
                double perTickX = (seen.midX() - last.midX()) / dt;
                double perTickZ = (seen.midZ() - last.midZ()) / dt;
                for (int ahead = 1; ahead <= AHEAD; ahead++) {
                    PlayerSim real = truth.get(Math.min(truth.size() - 1, move + ahead));
                    double engine = Math.hypot(forecast.x[ahead] - real.x(), forecast.z[ahead] - real.z());
                    double line = Math.hypot(seen.midX() + perTickX * (double) ahead - real.x(),
                            seen.midZ() + perTickZ * (double) ahead - real.z());
                    stats.engine[ahead] += engine;
                    stats.line[ahead] += line;
                    stats.engineHeight[ahead] += Math.abs(forecast.y[ahead] - real.y());
                    stats.lineHeight[ahead] += Math.abs(seen.midY() - real.y());
                    stats.engineWorst[ahead] = Math.max(stats.engineWorst[ahead], engine);
                }
            }
            last = seen;
            lastSentTick = serverTick;
        }
        return stats;
    }

    private static void assertBeatsLine(Stats stats, int ahead, double share) {
        assertTrue(stats.at(ahead), stats.measured > 10 && stats.engine(ahead) < share * stats.line(ahead));
    }

    private static final Script SPRINT = new Script() {
        @Override
        public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
            in.yaw = YAW;
            in.forward = 1.0F;
            in.sprint = true;
        }
    };

    @Test
    public void aStraightSprintIsForecastToAFractionOfACell() {
        Stats stats = follow(new Course(), 120, SPRINT);
        assertTrue(stats.at(AHEAD), stats.engine(AHEAD) < 0.03);
        assertBeatsLine(stats, AHEAD, 0.5);
        assertEquals(stats.measured, stats.simulated);
        assertTrue(stats.track.locked());
        assertTrue(stats.track.confidence() > 0.8);
        assertEquals(1, stats.track.keys().forward);
        assertEquals(0, stats.track.keys().strafe);

        assertEquals(YAW, stats.track.keys().yaw, 1.0F);
        TargetForecast forecast = stats.track.forecast(new Course(), 3);
        assertEquals(3, forecast.moves());
        assertTrue(forecast.simulated);
        assertTrue(forecast.onGround[3]);
        AxisAlignedBB box = forecast.box(3);
        assertEquals(0.6, box.maxX - box.minX, 1.0E-6);
        assertEquals(1.8, box.maxY - box.minY, 1.0E-6);
        assertEquals(forecast.x[3], (box.minX + box.maxX) / 2.0, 1.0E-9);
        stats.track.reset();
        assertFalse(stats.track.locked());
    }

    @Test
    public void aWalkAndASneakToo() {
        Stats walk = follow(new Course(), 120, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW;
                in.forward = 1.0F;
            }
        });
        assertTrue(walk.at(AHEAD), walk.engine(AHEAD) < 0.03);
        assertBeatsLine(walk, AHEAD, 0.6);
        Stats sneak = follow(new Course(), 200, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW;
                in.forward = 0.3F;
                in.sneak = true;
            }
        });
        assertTrue(sneak.at(AHEAD), sneak.engine(AHEAD) < 0.03);
        assertBeatsLine(sneak, AHEAD, 0.6);
    }

    @Test
    public void aSprintJumpIsForecastWithItsHeight() {
        Script jumping = new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW;
                in.forward = 1.0F;
                in.sprint = true;
                in.jump = true;
            }
        };
        Stats stats = follow(new Course(), 160, jumping);
        assertBeatsLine(stats, AHEAD, 0.5);
        assertTrue(stats.engineHeight(AHEAD) + " of " + stats.lineHeight(AHEAD),
                stats.engineHeight(AHEAD) < 0.5 * stats.lineHeight(AHEAD));

        assertBeatsLine(follow(new Course(), 160, jumping, JITTER, null, false), AHEAD, 0.5);
    }

    @Test
    public void aTurningPlayerIsForecastAlongItsCurve() {
        Stats slow = follow(new Course(), 160, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW + 6.0F * (float) tick;
                in.forward = 1.0F;
                in.sprint = true;
            }
        });
        assertBeatsLine(slow, AHEAD, 0.5);
        assertTrue("rate " + slow.track.yawRate(), slow.track.yawRate() > 3.0F && slow.track.yawRate() < 9.0F);
        assertBeatsLine(follow(new Course(), 160, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW + 12.0F * (float) tick;
                in.forward = 1.0F;
                in.sprint = true;
            }
        }), AHEAD, 0.5);

        assertBeatsLine(follow(new Course(), 160, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW + 9.0F * (float) tick;
                in.strafe = 1.0F;
            }
        }), AHEAD, 0.5);
    }

    @Test
    public void aWallStopsTheForecastWhereItStopsThePlayer() {
        Stats wall = follow(new Course().box(8, 0, -100, 9, 4, 100), 80, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = -70.0F;
                in.forward = 1.0F;
                in.sprint = true;
            }
        });
        assertBeatsLine(wall, AHEAD, 1.0);

        assertTrue("worst " + wall.engineWorst(AHEAD), wall.engineWorst(AHEAD) < 0.25);
        Stats slab = follow(new Course().box(4, 0, -100, 30, 0.5, 100), 60, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = -80.0F;
                in.forward = 1.0F;
                in.sprint = true;
            }
        });
        assertBeatsLine(slab, 3, 1.0);
    }

    @Test
    public void aSwimmerIsFollowedThroughWater() {
        Stats stats = follow(new Course().water(30.0), 200, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                if (tick == 0) {
                    game.box = game.box.offset(0.0, 12.0, 0.0);
                    game.onGround = false;
                }
                in.yaw = YAW + 3.0F * (float) tick;
                in.forward = 1.0F;
                in.jump = tick % 40 < 20;
            }
        });

        assertBeatsLine(stats, AHEAD, 0.8);
        assertTrue(stats.engineHeight(AHEAD) + " of " + stats.lineHeight(AHEAD),
                stats.engineHeight(AHEAD) < 0.5 * stats.lineHeight(AHEAD));
        assertTrue(stats.simulated * 10 >= stats.measured * 8);
    }

    @Test
    public void aKnockbackNobodyToldOfIsWorkedOutFromThePositions() {
        int[] hits = {40, 80};
        Stats untold = follow(new Course(), 120, SPRINT, null, hits, false);
        assertBeatsLine(untold, AHEAD, 0.85);

        Stats told = follow(new Course(), 120, SPRINT, null, hits, true);
        assertTrue(told.fits + " fits told, " + untold.fits + " untold", told.fits >= untold.fits);
        assertTrue(told.at(AHEAD) + "; untold " + untold.engine(AHEAD),
                told.engine(AHEAD) <= untold.engine(AHEAD) + 0.01);
    }

    @Test
    public void keysThatKeepChangingStillBeatTheLine() {
        Stats tap = follow(new Course(), 160, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW;
                in.forward = tick % 10 < 2 ? 0.0F : 1.0F;
                in.sprint = true;
            }
        });
        assertBeatsLine(tap, 3, 1.0);
        Stats strafe = follow(new Course(), 160, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW;
                in.forward = 1.0F;
                in.sprint = true;
                in.strafe = (tick / 8) % 2 == 0 ? 1.0F : -1.0F;
            }
        });
        assertBeatsLine(strafe, 3, 1.0);
        Stats stopAndGo = follow(new Course(), 200, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW + (float) (tick / 40) * 70.0F;
                in.forward = (tick / 20) % 2 == 0 ? 1.0F : 0.0F;
                in.sprint = true;
            }
        });
        assertBeatsLine(stopAndGo, 3, 1.0);
    }

    @Test
    public void unevenArrivalDoesNotThrowASprintOff() {
        Stats stats = follow(new Course(), 120, SPRINT, JITTER, null, false);
        assertTrue(stats.at(3), stats.engine(3) < 0.03);
        assertBeatsLine(stats, 3, 0.3);
    }

    @Test
    public void aPlayerNoKeysExplainIsNotSimulated() {
        Stats stats = follow(new Course(), 120, new Script() {
            @Override
            public void keys(int tick, PlayerSim.Input in, PlayerSim game) {
                in.yaw = YAW;
                in.forward = 1.0F;
                in.sprint = true;
                game.motionX *= 1.6;
                game.motionZ *= 1.6;
            }
        });
        assertEquals(0, stats.simulated);
        assertFalse(stats.track.locked());
        assertEquals(0.0, stats.track.forecast(new Course(), AHEAD).confidence, 0.0);
        assertEquals(stats.line(AHEAD), stats.engine(AHEAD), 0.02);
    }

    @Test
    public void theMovesToTryStartAtTheClocksCount() {
        assertArrayEquals(new int[]{2, 1, 3}, TargetTrack.candidates(0.0));
        assertArrayEquals(new int[]{1, 2, 3}, TargetTrack.candidates(1.0));
        assertArrayEquals(new int[]{2, 3, 1, 4}, TargetTrack.candidates(2.0));
        assertArrayEquals(new int[]{3, 2, 4, 1}, TargetTrack.candidates(2.6));
        assertArrayEquals(new int[]{4, 3, 2}, TargetTrack.candidates(9.0));
    }

    @Test
    public void theFallAfterARiseIsTheGames() {
        PlayerSim.World nothing = new PlayerSim.World() {
            @Override
            public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
                return new ArrayList<AxisAlignedBB>();
            }

            @Override
            public float slipperiness(int x, int y, int z) {
                return 0.6F;
            }
        };
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(0.0, 50.0, 0.0, 0.6, 51.8, 0.6);
        sim.motionY = 0.3;
        for (int i = 0; i < 3; i++) {
            sim.move(nothing, new PlayerSim.Input());
        }
        assertEquals(sim.motionY, TargetTrack.fallAfter(sim.y() - 50.0, 3), 1.0E-9);
    }

    @Test
    public void aVanillaHitAsTheGameDealsIt() {
        double[] plain = TargetTrack.vanillaHit(1.0, 0.0, 0.0F, 0);
        assertEquals(-(double) 0.4F, plain[0], 1.0E-9);
        assertEquals(0.36080000519752503, plain[1], 1.0E-12);
        assertEquals(0.0, plain[2], 1.0E-9);

        double[] sprinted = TargetTrack.vanillaHit(0.0, -1.0, 0.0F, 1);
        assertEquals(0.0, sprinted[0], 1.0E-6);
        assertEquals(0.46080000519752503, sprinted[1], 1.0E-12);
        assertEquals((double) 0.4F + 0.5, sprinted[2], 1.0E-6);
    }
}
