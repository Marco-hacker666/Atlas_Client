package atlas.lb.aiming;

public final class NoiseAim extends AngleSmooth {
    public float speedMin = 18.0F;
    public float speedMax = 26.0F;

    public float acceleration = 6.0F;

    public float response = 0.45F;

    public float spreadYaw = 2.0F;
    public float spreadPitch = 1.2F;

    public float scale = 0.08F;

    public float sway = 0.25F;

    private final SmoothNoise wanderYaw = new SmoothNoise(System.nanoTime());
    private final SmoothNoise wanderPitch = new SmoothNoise(System.nanoTime() ^ 0x5DEECE66DL);
    private final SmoothNoise speedSway = new SmoothNoise(System.nanoTime() ^ 0x2545F4914F6CDD1DL);
    private final float[] velocity = new float[2];
    private float topSpeed = Float.NaN;

    private static final float JUMP = 30.0F;

    @Override
    public Rotation process(SmoothContext context, Rotation current, Rotation target) {
        if (context != null && context.previousTarget != null && context.previousTarget.deltaLengthTo(target) > JUMP) {
            this.topSpeed = Float.NaN;
        }
        if (Float.isNaN(this.topSpeed)) {
            this.topSpeed = between(this.speedMin, this.speedMax);
        }
        float step = Math.max(0.0F, this.scale);
        float goalYaw = target.yaw + this.wanderYaw.advance(step) * this.spreadYaw;
        float goalPitch = Rotation.clamp(target.pitch + this.wanderPitch.advance(step) * this.spreadPitch, -90.0F, 90.0F);
        float cap = Math.max(0.5F, this.topSpeed * (1.0F + Rotation.clamp(this.sway, 0.0F, 1.0F) * this.speedSway.advance(step * 0.5F)));
        return turn(current, new Rotation(goalYaw, goalPitch), cap, true);
    }

    private Rotation turn(Rotation current, Rotation goal, float cap, boolean keep) {
        float[] left = current.deltaTo(goal);
        float[] out = new float[2];
        for (int axis = 0; axis < 2; axis++) {
            float wanted = Rotation.clamp(left[axis] * this.response, -cap, cap);
            float v = this.velocity[axis];
            float change = Rotation.clamp(wanted - v, -this.acceleration, this.acceleration);
            v += change;

            if (Math.signum(v) == Math.signum(left[axis]) && Math.abs(v) > Math.abs(left[axis]) * 1.15F + 0.2F) {
                v = left[axis] * 1.15F;
            }
            out[axis] = v;
            if (keep) {
                this.velocity[axis] = v;
            }
        }
        return new Rotation(current.yaw + out[0], Rotation.clamp(current.pitch + out[1], -90.0F, 90.0F));
    }

    @Override
    public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
        NoiseAim estimate = new NoiseAim();
        estimate.response = this.response;
        estimate.acceleration = this.acceleration;
        estimate.velocity[0] = this.velocity[0];
        estimate.velocity[1] = this.velocity[1];
        float cap = Math.min(this.speedMin, this.speedMax);
        Rotation rotation = current;
        int ticks = 0;
        while (!rotation.isDeltaCloseTo(target) && ticks < 80) {
            rotation = estimate.turn(rotation, target, cap, true);
            ticks++;
        }
        return ticks;
    }
}
