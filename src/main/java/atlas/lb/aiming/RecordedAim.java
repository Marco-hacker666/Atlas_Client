package atlas.lb.aiming;

import java.util.Random;

/**
 * AngleSmooth "Recorded" (2026-11-10, owner: "AI rotations, as Augustus
 * has"): turns the way you turned. Augustus's AI rotations are recordings of
 * the player's own aim played back by the aura; this is Atlas's version,
 * built on the Recorder's aim files (AimLibrary). No code taken.
 *
 * Each tick, the error now (how far the look is off the goal) is matched to
 * a recorded tick with a similar error, and that tick's turn is used --
 * scaled by how much bigger or smaller the error is now (0.6 to 1.6 times),
 * and mirrored when the target is on the other side. Once a recorded run is
 * picked it is followed tick by tick while its errors stay within
 * {@code follow} degrees of the live ones, so the turn keeps the shape your
 * hand gave it (the speed-up, the slow-down, the small corrections) instead
 * of being stitched from unrelated ticks. Small errors copy the recorded
 * turn as it was: that is the tremor and drift of a hand on target.
 *
 * With no recordings (or nothing near), {@code fallback} turns instead.
 */
public final class RecordedAim extends AngleSmooth {
    /** How far, degrees, a run's error may drift from the live one before a new run is picked. */
    public float follow = 4.0F;
    /** 0..1: how much of each recorded turn is used (1 = all of it). */
    public float strength = 1.0F;
    /** Use ticks recorded while Silent Aura was on (record-with-aura). */
    public boolean useAuraData = true;
    public AngleSmooth fallback = new Tracking();

    private volatile AimLibrary library = new AimLibrary();
    private int run = -1;
    private final Random random = new Random();

    public void setLibrary(AimLibrary library) {
        this.library = library == null ? new AimLibrary() : library;
        this.run = -1;
    }

    /**
     * A line the Recorder just wrote (AimFeed). Client thread. Rows that came
     * in while the files were still being read are in the old library and are
     * read again from the file next time.
     */
    public void addLive(String line) {
        this.library.addLive(line);
    }

    public AimLibrary library() {
        return this.library;
    }

    @Override
    public Rotation process(SmoothContext context, Rotation current, Rotation target) {
        AimLibrary lib = this.library;
        lib.includeAura = this.useAuraData;
        float[] error = current.deltaTo(target);
        int pick = continued(lib, error);
        if (pick < 0) {
            pick = lib.nearest(error[0], error[1], this.random);
        }
        this.run = pick;
        if (pick < 0) {
            return this.fallback.process(context, current, target);
        }
        float[] turn = turnFor(lib.get(pick), error, this.strength);
        return new Rotation(current.yaw + turn[0], Rotation.clamp(current.pitch + turn[1], -90.0F, 90.0F));
    }

    /** The next tick of the run being followed, while it still fits; else -1. */
    private int continued(AimLibrary lib, float[] error) {
        if (this.run < 0 || this.run >= lib.size()) {
            return -1;
        }
        int next = lib.get(this.run).next;
        if (next < 0) {
            return -1;
        }
        AimLibrary.Sample s = lib.get(next);
        if (s.aura && !lib.includeAura) {
            return -1;
        }
        float dy = Math.abs(s.errYaw) - Math.abs(error[0]);
        float dp = s.errPitch - error[1];
        return dy * dy + dp * dp <= this.follow * this.follow ? next : -1;
    }

    /** A recorded tick's turn applied to the error now: mirrored to its side, scaled to its size. */
    static float[] turnFor(AimLibrary.Sample s, float[] error, float strength) {
        boolean mirror = Math.abs(s.errYaw) > 0.3F && Math.abs(error[0]) > 0.3F
                && Math.signum(s.errYaw) != Math.signum(error[0]);
        float yaw = (mirror ? -s.turnYaw : s.turnYaw) * scale(s.errYaw, error[0]);
        float pitch = s.turnPitch * scale(s.errPitch, error[1]);
        float k = Rotation.clamp(strength, 0.0F, 1.0F);
        return new float[]{yaw * k, pitch * k};
    }

    private static float scale(float recorded, float now) {
        if (Math.abs(recorded) < 1.0F) {
            return 1.0F;
        }
        return Rotation.clamp(Math.abs(now) / Math.abs(recorded), 0.6F, 1.6F);
    }

    @Override
    public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
        return this.fallback.calculateTicks(context, current, target);
    }
}
