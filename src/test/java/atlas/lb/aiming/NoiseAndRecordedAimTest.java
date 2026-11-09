package atlas.lb.aiming;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The Noise, Noise-React and Recorded turns (2026-11-10). */
public class NoiseAndRecordedAimTest {

    private static SmoothContext live(Rotation previousTarget) {
        return new SmoothContext(true, null, previousTarget, 3.0, false);
    }

    @Test
    public void smoothNoiseStaysInRangeAndMovesInSmallSteps() {
        SmoothNoise noise = new SmoothNoise(42L);
        float last = noise.value();
        for (int i = 0; i < 2000; i++) {
            float v = noise.advance(0.05F);
            assertTrue(v >= -1.0F && v <= 1.0F);
            /* A smoothstep between two values in [-1, 1], 20 steps apart: never a jump. */
            assertTrue("jumped " + (v - last), Math.abs(v - last) < 0.2F);
            last = v;
        }
    }

    @Test
    public void noiseReachesAStillTargetWithoutFlyingPast() {
        NoiseAim aim = new NoiseAim();
        aim.spreadYaw = 0.0F;
        aim.spreadPitch = 0.0F;
        aim.sway = 0.0F;
        Rotation target = new Rotation(60.0F, 10.0F);
        Rotation look = new Rotation(0.0F, 0.0F);
        float firstStep = 0.0F;
        for (int tick = 0; tick < 60; tick++) {
            Rotation next = aim.process(live(target), look, target);
            if (tick == 0) {
                firstStep = Math.abs(next.yaw - look.yaw);
            }
            assertTrue("overshot to " + next.yaw, next.yaw <= 60.0F * 1.15F + 0.5F);
            look = next;
        }
        /* It speeds up instead of starting at full speed. */
        assertTrue(firstStep <= aim.acceleration + 1e-3F);
        assertTrue(look.deltaLengthTo(target) < 1.0F);
    }

    @Test
    public void reactionWaitsBeforeFollowingAndThenSettles() {
        ReactionAim aim = new ReactionAim();
        aim.delayMin = 3;
        aim.delayMax = 3;
        aim.tremor = 0.0F;
        aim.prediction = 0.0F;
        Rotation look = new Rotation(0.0F, 0.0F);
        /* The target is at 0 for a while, then steps to 20 degrees. */
        Rotation still = new Rotation(0.0F, 0.0F);
        for (int i = 0; i < 5; i++) {
            look = aim.process(live(still), look, still);
        }
        Rotation moved = new Rotation(20.0F, 0.0F);
        float[] yaws = new float[40];
        for (int i = 0; i < yaws.length; i++) {
            look = aim.process(live(moved), look, moved);
            yaws[i] = look.yaw;
        }
        /* The first ticks after the step still see the old place. */
        assertEquals(0.0F, yaws[0], 1e-3F);
        assertEquals(0.0F, yaws[2], 1e-3F);
        assertTrue(yaws[3] > 0.0F);
        assertEquals(20.0F, yaws[yaws.length - 1], 0.5F);
    }

    private static final String HEADER = "tick,target,yaw,pitch,err_yaw,err_pitch,dist,tx,ty,tz,px,py,pz,"
            + "t_ground,p_ground,t_hurt,p_hurt,swings,attacks,attack_key,ping,assist";

    private static String row(int tick, String target, float yaw, float pitch, float errYaw, float errPitch, int assist) {
        return tick + "," + target + "," + yaw + "," + pitch + "," + errYaw + "," + errPitch
                + ",3,0,0,0,0,0,0,1,1,0,0,0,0,0,50," + assist;
    }

    @Test
    public void libraryKeepsOnlyOwnConsecutiveTicksAndLinksRuns() {
        AimLibrary library = new AimLibrary();
        library.add(Arrays.asList(
                "# aim-recorder test",
                HEADER,
                row(1, "e5w1", 0.0F, 0.0F, 10.0F, 2.0F, 0),
                row(2, "e5w1", 4.0F, 0.5F, 6.0F, 1.5F, 0),
                row(3, "e5w1", 7.0F, 1.0F, 3.0F, 1.0F, 0),
                /* A gap: not the next tick. */
                row(5, "e5w1", 8.0F, 1.0F, 2.0F, 1.0F, 0),
                /* Another module was on: neither side of this pair is used. */
                row(6, "e5w1", 9.0F, 1.0F, 1.0F, 1.0F, 8),
                row(7, "e5w1", 9.5F, 1.0F, 0.5F, 1.0F, 0),
                /* A different target on the next tick. */
                row(8, "e6w1", 100.0F, 1.0F, 30.0F, 1.0F, 0)));
        assertEquals(2, library.size());
        AimLibrary.Sample first = library.get(0);
        assertEquals(10.0F, first.errYaw, 1e-4F);
        assertEquals(4.0F, first.turnYaw, 1e-4F);
        assertEquals(1, first.next);
        assertEquals(3.0F, library.get(1).turnYaw, 1e-4F);
        assertEquals(-1, library.get(1).next);
    }

    @Test
    public void nearestFindsTheClosestErrorOnEitherSide() {
        AimLibrary library = new AimLibrary();
        library.add(Arrays.asList(HEADER,
                row(1, "a", 0.0F, 0.0F, 10.0F, 0.0F, 0),
                row(2, "a", 5.0F, 0.0F, 5.0F, 0.0F, 0),
                row(10, "b", 0.0F, 0.0F, 40.0F, 0.0F, 0),
                row(11, "b", 20.0F, 0.0F, 20.0F, 0.0F, 0)));
        int found = library.nearest(-11.0F, 0.0F, new Random(1));
        assertEquals(10.0F, library.get(found).errYaw, 1e-4F);
        assertEquals(-1, new AimLibrary().nearest(5.0F, 0.0F, new Random(1)));
    }

    @Test
    public void recordedTurnIsMirroredAndScaled() {
        AimLibrary.Sample sample = new AimLibrary.Sample(10.0F, 2.0F, 4.0F, 1.0F, -1);
        /* Same side, same size: as recorded. */
        float[] same = RecordedAim.turnFor(sample, new float[]{10.0F, 2.0F}, 1.0F);
        assertEquals(4.0F, same[0], 1e-4F);
        assertEquals(1.0F, same[1], 1e-4F);
        /* Target on the left, error twice as big (capped at 1.6x). */
        float[] mirrored = RecordedAim.turnFor(sample, new float[]{-20.0F, 2.0F}, 1.0F);
        assertEquals(-4.0F * 1.6F, mirrored[0], 1e-4F);
        /* Half strength. */
        assertEquals(2.0F, RecordedAim.turnFor(sample, new float[]{10.0F, 2.0F}, 0.5F)[0], 1e-4F);
    }

    @Test
    public void recordedFallsBackWithoutData() {
        RecordedAim aim = new RecordedAim();
        AngleSmooth.Linear linear = new AngleSmooth.Linear();
        linear.horizontalMin = linear.horizontalMax = 5.0F;
        linear.verticalMin = linear.verticalMax = 5.0F;
        aim.fallback = linear;
        Rotation next = aim.process(live(null), new Rotation(0.0F, 0.0F), new Rotation(30.0F, 0.0F));
        assertEquals(5.0F, next.yaw, 1e-3F);
    }

    @Test
    public void auraTicksAreMarkedAndCanBeLeftOut() {
        AimLibrary library = new AimLibrary();
        library.add(Arrays.asList(HEADER,
                row(1, "a", 0.0F, 0.0F, 10.0F, 0.0F, AimLibrary.ASSIST_AURA),
                row(2, "a", 5.0F, 0.0F, 5.0F, 0.0F, AimLibrary.ASSIST_AURA)));
        assertEquals(1, library.size());
        assertTrue(library.get(0).aura);
        assertTrue(library.nearest(10.0F, 0.0F, new Random(1)) >= 0);
        library.includeAura = false;
        assertEquals(-1, library.nearest(10.0F, 0.0F, new Random(1)));
    }

    @Test
    public void liveRowsLinkIntoRunsAcrossCalls() {
        AimLibrary library = new AimLibrary();
        library.addLive(HEADER);
        library.addLive(row(1, "a", 0.0F, 0.0F, 10.0F, 0.0F, 0));
        library.addLive(row(2, "a", 4.0F, 0.0F, 6.0F, 0.0F, 0));
        assertEquals(1, library.size());
        library.addLive(row(3, "a", 7.0F, 0.0F, 3.0F, 0.0F, 0));
        assertEquals(2, library.size());
        assertEquals(1, library.get(0).next);
        /* A new recording's header starts over: no link across it. */
        library.addLive(HEADER);
        library.addLive(row(4, "a", 8.0F, 0.0F, 2.0F, 0.0F, 0));
        assertEquals(2, library.size());
    }

    @Test
    public void feedReachesListeners() {
        final java.util.List<String> got = new java.util.ArrayList<String>();
        java.util.function.Consumer<String> listener = got::add;
        AimFeed.listen(listener);
        AimFeed.publish("x");
        assertEquals(java.util.Collections.singletonList("x"), got);
    }
}
