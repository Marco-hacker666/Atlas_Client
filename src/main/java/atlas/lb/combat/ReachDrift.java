package atlas.lb.combat;

import java.util.Random;

public final class ReachDrift {
    private final Random random;
    private Object target;
    private boolean inside;
    private float cut;

    public ReachDrift(Random random) {
        this.random = random;
    }

    public void update(float base, float drift, Object target, double distance) {
        if (!(drift > 0.0F)) {
            this.target = target;
            this.cut = 0.0F;
            this.inside = target != null && distance <= base;
            return;
        }
        if (target != this.target) {
            this.target = target;
            this.cut = this.random.nextFloat() * drift;
            this.inside = target != null && distance <= base - this.cut;
            return;
        }
        if (target == null) {
            return;
        }
        if (this.cut > drift) {
            this.cut = this.random.nextFloat() * drift;
            this.inside = distance <= base - this.cut;
            return;
        }
        boolean now = distance <= base - this.cut;
        if (now != this.inside) {
            this.cut = this.random.nextFloat() * drift;

            this.inside = distance <= base - this.cut;
        }
    }

    public float cut() {
        return this.cut;
    }

    public void reset() {
        this.target = null;
        this.inside = false;
        this.cut = 0.0F;
    }
}
