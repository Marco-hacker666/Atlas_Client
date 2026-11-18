package atlas.lb.aiming;

public final class ReactionAim extends AngleSmooth {
    public int delayMin = 2;
    public int delayMax = 4;
    public float stiffness = 0.22F;
    public float damping = 0.72F;

    public float prediction = 0.6F;

    public float tremor = 0.12F;
    public float maxTurnYaw = 40.0F;
    public float maxTurnPitch = 25.0F;

    private static final int HISTORY = 12;
    private static final float JUMP = 30.0F;
    private final Rotation[] seen = new Rotation[HISTORY];
    private int seenCount;

    private int realCount;
    private int delay = -1;
    private final float[] speed = new float[2];
    private final float[] lastShake = new float[2];
    private final SmoothNoise tremorYaw = new SmoothNoise(System.nanoTime() ^ 0x9E3779B97F4A7C15L);
    private final SmoothNoise tremorPitch = new SmoothNoise(System.nanoTime() ^ 0xC2B2AE3D27D4EB4FL);

    private void remember(Rotation target) {
        System.arraycopy(this.seen, 0, this.seen, 1, HISTORY - 1);
        this.seen[0] = target;
        this.seenCount = Math.min(HISTORY, this.seenCount + 1);
        this.realCount = Math.min(HISTORY, this.realCount + 1);
    }

    private Rotation perceived(int ticks) {
        int index = Math.min(ticks, this.seenCount - 1);
        Rotation then = this.seen[index];

        if (index + 1 >= this.realCount || this.prediction <= 0.0F) {
            return then;
        }
        float[] moving = this.seen[index + 1].deltaTo(then);
        float carry = this.prediction * index;
        return new Rotation(then.yaw + moving[0] * carry,
                Rotation.clamp(then.pitch + moving[1] * carry, -90.0F, 90.0F));
    }

    @Override
    public Rotation process(SmoothContext context, Rotation current, Rotation target) {
        if (this.delay < 0 || this.seenCount > 0 && this.seen[0].deltaLengthTo(target) > JUMP) {
            this.delay = between(Math.max(0, this.delayMin), Math.min(HISTORY - 2, Math.max(this.delayMin, this.delayMax)));

            this.seenCount = 0;
            for (int i = 0; i <= this.delay; i++) {
                remember(current);
            }
            this.realCount = 0;
        }
        remember(target);
        Rotation goal = perceived(this.delay);

        float tremor = Math.max(0.0F, this.tremor);
        float shakeYaw = tremor * this.tremorYaw.advance(0.18F);
        float shakePitch = tremor * this.tremorPitch.advance(0.18F);
        float moveYaw = shakeYaw - this.lastShake[0];
        float movePitch = shakePitch - this.lastShake[1];
        this.lastShake[0] = shakeYaw;
        this.lastShake[1] = shakePitch;
        return spring(current, goal, moveYaw, movePitch, true);
    }

    private Rotation spring(Rotation current, Rotation goal, float shakeYaw, float shakePitch, boolean keep) {
        float[] left = current.deltaTo(goal);
        float yawSpeed = this.speed[0] + this.stiffness * left[0] - this.damping * this.speed[0];
        float pitchSpeed = this.speed[1] + this.stiffness * left[1] - this.damping * this.speed[1];
        yawSpeed = Rotation.clamp(yawSpeed, -this.maxTurnYaw, this.maxTurnYaw);
        pitchSpeed = Rotation.clamp(pitchSpeed, -this.maxTurnPitch, this.maxTurnPitch);
        if (keep) {
            this.speed[0] = yawSpeed;
            this.speed[1] = pitchSpeed;
        }
        return new Rotation(current.yaw + yawSpeed + shakeYaw,
                Rotation.clamp(current.pitch + pitchSpeed + shakePitch, -90.0F, 90.0F));
    }

    @Override
    public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
        ReactionAim estimate = new ReactionAim();
        estimate.stiffness = this.stiffness;
        estimate.damping = this.damping;
        estimate.maxTurnYaw = this.maxTurnYaw;
        estimate.maxTurnPitch = this.maxTurnPitch;
        estimate.speed[0] = this.speed[0];
        estimate.speed[1] = this.speed[1];
        Rotation rotation = current;
        int ticks = Math.max(0, Math.min(this.delayMin, this.delayMax));
        while (!rotation.isDeltaCloseTo(target) && ticks < 80) {
            rotation = estimate.spring(rotation, target, 0.0F, 0.0F, true);
            ticks++;
        }
        return ticks;
    }
}
