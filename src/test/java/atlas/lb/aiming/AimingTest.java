package atlas.lb.aiming;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The LiquidBounce aiming port: rotation math, smoothers, the point tracker (2026-10-24). */
public class AimingTest {
    private static final SmoothContext LIVE = new SmoothContext(true, null, null, 3.0, false);

    @Test
    public void deltasWrapTheShortWayRound() {
        Rotation a = new Rotation(170.0F, 0.0F);
        Rotation b = new Rotation(-170.0F, 10.0F);
        float[] d = a.deltaTo(b);
        assertEquals(20.0F, d[0], 1e-4);
        assertEquals(10.0F, d[1], 1e-4);
    }

    @Test
    public void towardsLinearMovesAlongTheStraightLineAndStopsAtTheTarget() {
        Rotation from = new Rotation(0.0F, 0.0F);
        Rotation to = new Rotation(30.0F, 40.0F);
        Rotation step = from.towardsLinear(to, 10.0F, 10.0F);
        assertEquals(6.0F, step.yaw, 1e-4);
        assertEquals(8.0F, step.pitch, 1e-4);
        Rotation whole = from.towardsLinear(to, 180.0F, 180.0F);
        assertEquals(30.0F, whole.yaw, 1e-4);
        assertEquals(40.0F, whole.pitch, 1e-4);
        assertEquals(from.yaw, from.towardsLinear(from, 10.0F, 10.0F).yaw, 0.0); // no NaN on a zero delta
    }

    @Test
    public void normalizeRoundsToWholeMouseSteps() {
        Rotation from = new Rotation(0.0F, 0.0F);
        Rotation fixed = new Rotation(10.07F, -95.0F).normalize(from, 0.15);
        assertEquals(10.05F, fixed.yaw, 1e-3);
        assertEquals(-90.0F, fixed.pitch, 1e-4);
    }

    @Test
    public void fromVectorLooksAlongIt() {
        Rotation south = Rotation.fromVector(0, 0, 1);
        assertEquals(0.0F, south.yaw, 1e-4);
        Rotation east = Rotation.fromVector(1, 0, 0);
        assertEquals(-90.0F, east.yaw, 1e-4);
        assertEquals(90.0F, Rotation.fromVector(0, -1, 0).pitch, 1e-4);
    }

    @Test
    public void linearTurnsAtItsSpeedAndCountsTicks() {
        AngleSmooth.Linear linear = new AngleSmooth.Linear();
        linear.horizontalMin = linear.horizontalMax = 20.0F;
        linear.verticalMin = linear.verticalMax = 20.0F;
        Rotation from = new Rotation(0.0F, 0.0F);
        Rotation to = new Rotation(90.0F, 0.0F);
        assertEquals(20.0F, linear.process(LIVE, from, to).yaw, 1e-4);
        assertEquals(4, linear.calculateTicks(LIVE, from, to)); // 20, 40, 60, 80 -> within 2 of 90 after 5 steps
    }

    @Test
    public void sigmoidSlowsNearTheTarget() {
        AngleSmooth.Sigmoid sigmoid = new AngleSmooth.Sigmoid();
        Rotation from = new Rotation(0.0F, 0.0F);
        float far = sigmoid.process(LIVE, from, new Rotation(120.0F, 0.0F)).yaw;
        float near = sigmoid.process(LIVE, from, new Rotation(5.0F, 0.0F)).yaw;
        assertTrue(far > 100.0F);
        assertTrue(near <= 5.0F);
    }

    @Test
    public void interpolationNeverOvershoots() {
        AngleSmooth.Interpolation interpolation = new AngleSmooth.Interpolation();
        Rotation current = new Rotation(0.0F, 0.0F);
        Rotation target = new Rotation(100.0F, -30.0F);
        for (int i = 0; i < 200; i++) {
            current = interpolation.process(LIVE, current, target);
            assertTrue(current.yaw <= 100.0F + 1e-3F);
            assertTrue(current.pitch >= -30.0F - 1e-3F);
        }
        assertTrue(current.isDeltaCloseTo(target));
    }

    @Test
    public void accelerationTurnRateGrowsFromTheLastOne() {
        AngleSmooth.Acceleration acceleration = new AngleSmooth.Acceleration();
        acceleration.accelerationError = false;
        acceleration.constantError = false;
        acceleration.yawAccelMin = acceleration.yawAccelMax = 10.0F;
        acceleration.pitchAccelMin = acceleration.pitchAccelMax = 10.0F;
        Rotation previous = new Rotation(0.0F, 0.0F);
        Rotation current = new Rotation(5.0F, 0.0F); // turned 5 last tick
        Rotation next = acceleration.process(new SmoothContext(true, previous, null, 0.0, false), current, new Rotation(90.0F, 0.0F));
        assertEquals(20.0F, next.yaw, 1e-4); // 5 + 5 last tick + at most 10 more
    }

    @Test
    public void aimAssistTurnsAsAimAssistDoes() {
        AngleSmooth.AimAssist assist = new AngleSmooth.AimAssist();
        assist.horizontalSpeed = 10.0F;
        assist.verticalSpeed = 5.0F;
        assist.smoothing = 0.6F;
        Rotation from = new Rotation(0.0F, 0.0F);
        for (int i = 0; i < 200; i++) {
            Rotation next = assist.process(LIVE, from, new Rotation(40.0F, 20.0F));
            // yaw: 40 x (0.5 + 0.5 x (1 - 0.6 +- 0.1)) x 1.0 = 26..30; pitch: 20 x 0.65..0.75 x 0.5 = 6.5..7.5
            assertTrue("yaw " + next.yaw, next.yaw >= 25.9F && next.yaw <= 30.1F);
            assertTrue("pitch " + next.pitch, next.pitch >= 6.4F && next.pitch <= 7.6F);
        }
        assertEquals(0.0F, assist.process(LIVE, from, new Rotation(0.8F, 0.5F)).yaw, 1e-4); // dead zone
        assist.verticalSpeed = 0.0F;
        assertEquals(0.0F, assist.process(LIVE, from, new Rotation(40.0F, 20.0F)).pitch, 1e-4);
    }

    @Test
    public void aimAssistStrafeIncreaseSpeedsUpTheYaw() {
        AngleSmooth.AimAssist assist = new AngleSmooth.AimAssist();
        assist.horizontalSpeed = 3.0F;
        assist.smoothing = 0.0F;
        Rotation from = new Rotation(0.0F, 0.0F);
        Rotation to = new Rotation(30.0F, 0.0F); // to the right
        float plain = assist.process(LIVE, from, to).yaw;
        assist.strafeSide = 1; // strafing left: AimAssist's "side > 0 && strafe > 0"
        float boosted = assist.process(LIVE, from, to).yaw;
        assertTrue(plain + " -> " + boosted, boosted > plain * 1.5F);
        assertTrue(assist.calculateTicks(LIVE, from, to) > 0);
        assertTrue(assist.calculateTicks(LIVE, from, to) < 80);
    }

    @Test
    public void pointsAreCastOverTheVisibleFaces() {
        double[] box = {-0.3, 0.0, 2.7, 0.3, 1.8, 3.3};
        List<double[]> points = PointTracker.projectPointsOnBox(new double[]{0, 1.62, 0}, box, 128);
        assertNotNull(points);
        assertTrue(points.size() > 40);
        for (double[] p : points) {
            assertTrue(p[2] >= 2.7 - 1e-9 && p[2] <= 3.3 + 1e-9);
            assertTrue(p[1] >= -1e-9 && p[1] <= 1.8 + 1e-9);
        }
        assertNull(PointTracker.projectPointsOnBox(new double[]{0, 1.0, 3.0}, box, 128)); // eye inside
    }

    @Test
    public void theNearestPointIsTakenAndExemptsMoveIt() {
        double[] box = {-0.3, 0.0, 2.7, 0.3, 1.8, 3.3};
        double[] eyes = {0, 1.62, 0};
        PointTracker tracker = new PointTracker(new Random(1L));
        PointTracker.Point point = tracker.findPoint(eyes, box);
        assertEquals(2.7, point.pos[2], 1e-6); // the front face
        assertEquals(1.62, point.pos[1], 0.1); // level with the eyes

        PointTracker noHead = new PointTracker(new Random(1L));
        noHead.exemptParts = PointTracker.PART_HEAD;
        assertTrue(noHead.findPoint(eyes, box).pos[1] <= 1.2 + 1e-6);
    }

    @Test
    public void lazyHoldsThePointUntilItMovesFarEnough() {
        PointTracker tracker = new PointTracker(new Random(1L));
        tracker.lazyOn = true;
        tracker.lazyMin = tracker.lazyMax = 0.3F;
        double[] eyes = {0, 1.62, 0};
        double[] first = tracker.findPoint(eyes, new double[]{-0.3, 0.0, 2.7, 0.3, 1.8, 3.3}).pos;
        double[] held = tracker.findPoint(eyes, new double[]{-0.2, 0.0, 2.7, 0.4, 1.8, 3.3}).pos;
        assertEquals(first[0], held[0], 1e-9);
        double[] moved = tracker.findPoint(eyes, new double[]{1.7, 0.0, 2.7, 2.3, 1.8, 3.3}).pos;
        assertTrue(Math.abs(moved[0] - first[0]) > 0.3);
    }

    @Test
    public void linearExtrapolationRepeatsTheLastStep() {
        double[] p = PositionExtrapolation.linear(1, 2, 3, 0.5, 2, 3.25, 2.0);
        assertEquals(2.0, p[0], 1e-9);
        assertEquals(2.0, p[1], 1e-9);
        assertEquals(2.5, p[2], 1e-9);
    }
}
