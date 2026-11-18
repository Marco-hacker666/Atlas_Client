package atlas.ui.impl.clickgui.atlas;

final class Spring {
    private static final float STEP = 0.004F;

    static float dampingScale = 1.0F;

    float value;
    float velocity;
    float target;
    private final float stiffness;
    private final float damping;

    Spring(float value, float stiffness, float damping) {
        this.value = value;
        this.target = value;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    float step(float delta) {
        int steps = Math.max(1, (int) Math.ceil(delta / STEP));
        float h = delta / steps;
        for (int i = 0; i < steps; i++) {
            float accel = -this.stiffness * (this.value - this.target)
                    - this.damping * dampingScale * this.velocity;
            this.velocity += accel * h;
            this.value += this.velocity * h;
        }
        if (Math.abs(this.value - this.target) < 0.001F && Math.abs(this.velocity) < 0.01F) {
            this.value = this.target;
            this.velocity = 0.0F;
        }
        return this.value;
    }

    void snap(float to) {
        this.value = to;
        this.target = to;
        this.velocity = 0.0F;
    }
}
