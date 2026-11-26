package atlas.lb.combat;

import java.util.Random;

/**
 * Where the aura starts hitting, drawn again each time the target crosses it: the reach is the set range
 * minus a cut between 0 and the drift, so a player does not start swinging at exactly the same distance every
 * time. A new cut is drawn when the target changes and whenever the target goes in or out of the current
 * reach, so the edge moves after each crossing instead of flickering on it. Only ever shorter than the set
 * range. Call {@link #update} once a tick, then read {@link #cut}.
 */
public final class ReachDrift {
    private final Random random;
    private Object target;
    private boolean inside;
    private float cut;

    public ReachDrift(Random random) {
        this.random = random;
    }

    /**
     * @param base     the set range
     * @param drift    the most it may be shortened by; 0 turns this off
     * @param target   the current target, or null
     * @param distance the distance to it (any value without a target)
     */
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
            // the drift was turned down: a cut within it
            this.cut = this.random.nextFloat() * drift;
            this.inside = distance <= base - this.cut;
            return;
        }
        boolean now = distance <= base - this.cut;
        if (now != this.inside) {
            this.cut = this.random.nextFloat() * drift;
            // judged against the new edge, so standing still never counts as another crossing
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
