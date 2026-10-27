package atlas.lb.aiming;

import java.util.Random;

/**
 * The aura's server-side rotation, tick by tick (2026-10-25, LiquidBounce
 * NextGen port, GPL-3.0; RotationManager.update, RotationTarget.towards,
 * RotationsValueGroup and the ShortStop / Fail processors).
 *
 * The module asks for a rotation each tick it aims ({@link #request}); a
 * request lives ticks-until-reset ticks. While one is alive the rotation
 * turns toward it through the angle smoother, then Fail and ShortStop; once
 * it expires it turns back toward the camera the same way and is let go
 * within reset-threshold degrees of it (at once with no processors, or for
 * ChangeLook). Every result is snapped to whole mouse steps from the
 * rotation it came from. No game state.
 */
public final class AuraRotations {
    public static final int CORRECTION_OFF = 0;
    public static final int CORRECTION_STRICT = 1;
    public static final int CORRECTION_SILENT = 2;
    public static final int CORRECTION_CHANGE_LOOK = 3;

    public AngleSmooth smooth = new AngleSmooth.Linear();
    public int movementCorrection = CORRECTION_SILENT;
    public float resetThreshold = 2.0F;
    public int ticksUntilReset = 5;
    /** This tick's turn is scaled by this (1: as the smoother says); humanization's speed variance sets it. */
    public float speedScale = 1.0F;

    public boolean shortStopOn;
    public int shortStopRate = 3;
    public int shortStopMin = 1;
    public int shortStopMax = 2;

    public boolean failOn;
    public int failRate = 3;
    public float failFactor = 0.04F;
    public float failHorizontalMin = 5.0F;
    public float failHorizontalMax = 10.0F;
    public float failVerticalMin = 0.0F;
    public float failVerticalMax = 2.0F;
    public int failTransitionMin = 1;
    public int failTransitionMax = 4;

    private final Random random;

    private Rotation requested;
    private double requestedDistance;
    private boolean requestedCrosshair;
    private int requestTicks;
    /** The target followed last tick (null once let go). */
    private Rotation previousTarget;
    private Rotation current;
    private Rotation previous;

    private int shortStopElapsed;
    private int shortStopDuration = -1;
    private int failElapsed = Integer.MAX_VALUE / 2;
    private int failDuration;
    private float failShiftYaw;
    private float failShiftPitch;

    public AuraRotations() {
        this(new Random());
    }

    AuraRotations(Random random) {
        this.random = random;
    }

    /** The rotation the server gets now, or null when the camera's is used. */
    public Rotation current() {
        return this.current;
    }

    public Rotation previous() {
        return this.previous;
    }

    /** Whether a target (alive or being reset from) is held. */
    public boolean active() {
        return this.requestTicks > 0 || this.previousTarget != null;
    }

    public boolean requesting() {
        return this.requestTicks > 0;
    }

    public void reset() {
        this.requested = null;
        this.requestTicks = 0;
        this.previousTarget = null;
        this.current = null;
        this.previous = null;
    }

    /** Drops the live request, so the next updates turn back to the camera (switched off, target gone for good). */
    public void expire() {
        this.requestTicks = 0;
    }

    /** Aim at {@code rotation} (distance to the entity and whether the current look already lands on it, for Acceleration). */
    public void request(Rotation rotation, double distance, boolean onCrosshair) {
        this.requested = rotation;
        this.requestedDistance = distance;
        this.requestedCrosshair = onCrosshair;
        this.requestTicks = this.movementCorrection == CORRECTION_CHANGE_LOOK ? 1 : Math.max(1, this.ticksUntilReset);
    }

    /** Start of a client tick: Fail's roll (LiquidBounce rolls it on every game tick). */
    public void gameTick() {
        if (!this.failOn) {
            return;
        }
        float chance = this.random.nextFloat() * 100.0F;
        if (this.failRate > chance) {
            this.failDuration = between(this.failTransitionMin, this.failTransitionMax);
            this.failShiftYaw = (this.random.nextBoolean() ? 1 : -1) * between(this.failHorizontalMin, this.failHorizontalMax);
            this.failShiftPitch = (this.random.nextBoolean() ? 1 : -1) * between(this.failVerticalMin, this.failVerticalMax);
            this.failElapsed = 0;
        } else {
            this.failElapsed++;
        }
    }

    /**
     * One step. {@code player} is the camera's rotation, {@code gcd} the mouse
     * step in degrees (0: no snapping). Returns the rotation the server gets
     * this tick, or null when the camera's is used (nothing held, or just let go).
     */
    public Rotation update(Rotation player, double gcd) {
        boolean alive = this.requestTicks > 0;
        Rotation target = alive ? this.requested : this.previousTarget;
        if (target == null) {
            return null;
        }
        Rotation from = this.current != null ? this.current : player;
        boolean resetting = !alive;
        Rotation goal = resetting ? player : target;
        SmoothContext context = new SmoothContext(true, this.previous, this.previousTarget,
                resetting ? 0.0 : this.requestedDistance, !resetting && this.requestedCrosshair);
        Rotation next = process(context, from, goal).normalize(from, gcd);
        float diff = next.deltaLengthTo(player);
        if (resetting && (this.movementCorrection == CORRECTION_CHANGE_LOOK || diff <= this.resetThreshold)) {
            setCurrent(null, player);
            this.previousTarget = null;
        } else {
            setCurrent(next, player);
            this.previousTarget = target;
        }
        if (this.requestTicks > 0) {
            this.requestTicks--;
        }
        return this.current;
    }

    /**
     * This tick's look is {@code look} after all (2026-10-26: Velocity Polar's
     * counter-hit went out along it, so the movement packet keeps it). The
     * next step turns on from there. Only while this aura holds a rotation.
     */
    public Rotation hold(Rotation look) {
        if (this.current != null) {
            this.current = look;
        }
        return look;
    }

    /** Ticks the smoother needs from {@code from} to {@code to} (LiquidBounce's calculateTicks). */
    public int calculateTicks(Rotation from, Rotation to) {
        return this.smooth.calculateTicks(SmoothContext.estimate(this.previous), from, to);
    }

    private void setCurrent(Rotation value, Rotation player) {
        this.previous = value == null ? null : (this.current != null ? this.current : player);
        this.current = value;
    }

    private Rotation process(SmoothContext context, Rotation from, Rotation goal) {
        Rotation out = this.smooth.process(context, from, goal);
        if (this.speedScale != 1.0F) {
            out = from.interpolateTo(out, Math.max(0.0F, this.speedScale));
        }
        if (this.failOn && this.failElapsed < this.failDuration && this.previous != null) {
            float dYaw = (this.previous.yaw - from.yaw) * this.failFactor;
            float dPitch = (this.previous.pitch - from.pitch) * this.failFactor;
            out = new Rotation(out.yaw + dYaw + this.failShiftYaw, out.pitch + dPitch + this.failShiftPitch);
        }
        if (this.shortStopOn) {
            if (this.shortStopDuration < 0) {
                this.shortStopDuration = between(this.shortStopMin, this.shortStopMax);
                this.shortStopElapsed = this.shortStopDuration;
            }
            if (this.random.nextInt(100) < this.shortStopRate) {
                this.shortStopDuration = between(this.shortStopMin, this.shortStopMax);
                this.shortStopElapsed = 0;
            }
            if (this.shortStopElapsed < this.shortStopDuration) {
                this.shortStopElapsed++;
                out = from.towardsLinear(out, between(0.0F, 0.1F), between(0.0F, 0.1F));
            }
        }
        return out;
    }

    private int between(int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return low + this.random.nextInt(high - low + 1);
    }

    private float between(float min, float max) {
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        return low + this.random.nextFloat() * (high - low);
    }
}
