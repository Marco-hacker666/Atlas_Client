package atlas.lb.aiming;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** AngleSmooth.Tracking (2026-10-26): keeps up with a strafing target, settles on a still one. */
public class TrackingSmoothTest {

    private static AngleSmooth.Tracking fixed() {
        AngleSmooth.Tracking t = new AngleSmooth.Tracking();
        t.gainVariance = 0.0F;
        t.maxYawMin = t.maxYawMax = 40.0F;
        t.maxPitchMin = t.maxPitchMax = 20.0F;
        return t;
    }

    /** The yaw error after 40 ticks of a target moving {@code speed} degrees a tick. */
    private static float lagAfter(AngleSmooth smooth, float speed) {
        Rotation current = new Rotation(0.0F, 0.0F);
        Rotation previous = null;
        Rotation lastTarget = null;
        Rotation target = new Rotation(5.0F, 0.0F);
        for (int tick = 0; tick < 40; tick++) {
            SmoothContext context = new SmoothContext(true, previous, lastTarget, 3.0, false);
            Rotation next = smooth.process(context, current, target);
            previous = current;
            current = next;
            lastTarget = target;
            target = new Rotation(target.yaw + speed, 0.0F);
        }
        return Math.abs(current.deltaTo(target)[0]);
    }

    @Test
    public void keepsUpWithAStrafingTargetBetterThanAShareOfWhatIsLeft() {
        float tracking = lagAfter(fixed(), 4.0F);
        /* A turn of 35 % of what is left (Tracking's own kp alone) trails by speed * (1 - k) / k. */
        AngleSmooth proportional = new AngleSmooth() {
            @Override
            public Rotation process(SmoothContext context, Rotation current, Rotation target) {
                float[] d = current.deltaTo(target);
                return new Rotation(current.yaw + d[0] * 0.35F, current.pitch + d[1] * 0.35F);
            }

            @Override
            public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
                return 0;
            }
        };
        float trailing = lagAfter(proportional, 4.0F);
        assertTrue("tracking " + tracking + " vs proportional " + trailing, tracking < trailing * 0.5F);
        assertTrue("tracking lag " + tracking, tracking < 3.0F);
    }

    @Test
    public void settlesOnAStillTargetWithoutFlyingPast() {
        AngleSmooth.Tracking t = fixed();
        Rotation current = new Rotation(0.0F, 0.0F);
        Rotation previous = null;
        Rotation target = new Rotation(30.0F, 10.0F);
        float furthest = 0.0F;
        for (int tick = 0; tick < 40; tick++) {
            Rotation next = t.process(new SmoothContext(true, previous, target, 3.0, false), current, target);
            previous = current;
            current = next;
            furthest = Math.max(furthest, current.yaw);
        }
        assertEquals(30.0F, current.yaw, 0.5F);
        assertEquals(10.0F, current.pitch, 0.5F);
        assertTrue("overshoot to " + furthest, furthest <= 30.0F + 1.0F);
    }

    @Test
    public void neverTurnsMoreThanTheMaxInATick() {
        AngleSmooth.Tracking t = fixed();
        t.yawP = 1.0F;
        Rotation next = t.process(new SmoothContext(true, null, null, 3.0, false),
                new Rotation(0.0F, 0.0F), new Rotation(170.0F, 80.0F));
        assertTrue(Math.abs(next.yaw) <= 40.0F + 1e-3F);
        assertTrue(Math.abs(next.pitch) <= 20.0F + 1e-3F);
    }

    @Test
    public void aJumpOfTheGoalStartsTheMemoryOver() {
        AngleSmooth.Tracking t = fixed();
        Rotation current = new Rotation(0.0F, 0.0F);
        /* The goal jumps 90 degrees in a tick (a new target): no feed-forward from that jump. */
        Rotation next = t.process(new SmoothContext(true, current, new Rotation(0.0F, 0.0F), 3.0, false),
                current, new Rotation(90.0F, 0.0F));
        assertTrue("first step " + next.yaw, next.yaw <= 40.0F + 1e-3F && next.yaw > 0.0F);
        assertTrue(t.calculateTicks(null, current, new Rotation(90.0F, 0.0F)) > 1);
    }
}
