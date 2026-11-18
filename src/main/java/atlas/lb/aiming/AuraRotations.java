package atlas.lb.aiming;

import java.util.Random;

// The aura's server-side rotation, tick by tick (LiquidBounce NextGen port, GPL-3.0)

public final class AuraRotations {
    public static final int CORRECTION_OFF = 0;
    public static final int CORRECTION_STRICT = 1;
    public static final int CORRECTION_SILENT = 2;
    public static final int CORRECTION_CHANGE_LOOK = 3;

    public AngleSmooth smooth = new AngleSmooth.Linear();
    public int movementCorrection = CORRECTION_SILENT;
    public float resetThreshold = 2.0F;
    public int ticksUntilReset = 5;

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

    public Rotation current() {
        return this.current;
    }

    public Rotation previous() {
        return this.previous;
    }

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

    public void expire() {
        this.requestTicks = 0;
    }

    public void request(Rotation rotation, double distance, boolean onCrosshair) {
        this.requested = rotation;
        this.requestedDistance = distance;
        this.requestedCrosshair = onCrosshair;
        this.requestTicks = this.movementCorrection == CORRECTION_CHANGE_LOOK ? 1 : Math.max(1, this.ticksUntilReset);
    }

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

    public Rotation hold(Rotation look) {
        if (this.current != null) {
            this.current = look;
        }
        return look;
    }

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
