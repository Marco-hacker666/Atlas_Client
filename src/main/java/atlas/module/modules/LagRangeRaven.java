package atlas.module.modules;

/**
 * LagRange's RAVEN mode: when to start and stop holding, as Raven bS's Lag
 * Range decides it (2026-10-23, owner: players called LagRange useless).
 *
 * ATLAS lets the queue out before reach and pauses 750 ms after every attack,
 * so in a trade it hardly holds at all. Raven holds while closing on a target
 * from beyond 3 blocks, and after landing a hit while not hit back it starts
 * (or keeps) holding inside 3 blocks too; an attack sends what is held and the
 * next tick may hold again. It lets go when the target is out of range or the
 * gap stops closing (unless that hit-hold applies), when this player is hit,
 * on a change of target, without a weapon, when sprint or a sword block
 * starts, and while something else rules holding out.
 *
 * One step a tick from what the client sees; no game state, so it is tested
 * on its own. Distances are squared, eye to the target's box.
 */
final class LagRangeRaven {
    static final double MINIMUM_DISTANCE_SQ = 3.0 * 3.0;

    /** What a step decided. */
    enum Step { NOTHING, START, HOLD, FLUSH }

    boolean lagging;
    /** Why the last FLUSH happened. */
    String reason = "";
    private int targetId = -1;
    private double lastDistSq = -1;
    private int lastSelfHurt;
    private int lastTargetHurt;
    private int hitMarked = -1;
    private boolean lastSprint;
    private boolean lastBlock;

    void reset() {
        this.lagging = false;
        this.reason = "";
        this.targetId = -1;
        this.lastDistSq = -1;
        this.lastSelfHurt = 0;
        this.lastTargetHurt = 0;
        this.hitMarked = -1;
        this.lastSprint = false;
        this.lastBlock = false;
    }

    private Step flush(String why) {
        this.reason = why;
        if (!this.lagging) {
            return Step.NOTHING;
        }
        this.lagging = false;
        return Step.FLUSH;
    }

    /**
     * @param blocked  why holding is ruled out now (screen, digging, a catch, another holder...), or null
     * @param target   the target's entity id, or -1 for none in range
     * @param distSq   squared distance to it
     * @param rangeSq  squared range setting
     */
    Step step(String blocked, int target, double distSq, double rangeSq, int selfHurt, int targetHurt,
              boolean moving, boolean weaponOk, boolean sprinting, boolean blocking,
              boolean flushOnSprint, boolean flushOnBlock) {
        if (blocked != null) {
            return flush(blocked);
        }
        if (target != this.targetId) {
            Step out = flush("target");
            this.lastDistSq = -1;
            this.hitMarked = -1;
            this.lastTargetHurt = target >= 0 ? targetHurt : 0;
            this.targetId = target;
            if (out == Step.FLUSH) {
                return out;
            }
        }
        if (target < 0) {
            return flush("no-target");
        }
        if (this.lagging) {
            Step out = holdingStep(distSq, rangeSq, selfHurt, weaponOk, sprinting, blocking, flushOnSprint, flushOnBlock);
            this.lastDistSq = distSq;
            this.lastTargetHurt = targetHurt;
            return out;
        }
        if (selfHurt > this.lastSelfHurt) {
            this.hitMarked = -1;
        }
        this.lastSelfHurt = selfHurt;
        this.lastSprint = sprinting;
        this.lastBlock = blocking;
        /* Our hit landed: the target's hurt animation started while we were not hurt. */
        if (selfHurt == 0 && this.lastTargetHurt == 0 && targetHurt > 0) {
            this.hitMarked = target;
        }
        this.lastTargetHurt = targetHurt;
        boolean closing = this.lastDistSq >= 0 && distSq < this.lastDistSq;
        boolean outsideMinimum = distSq > MINIMUM_DISTANCE_SQ;
        boolean hitStart = this.hitMarked == target && distSq <= MINIMUM_DISTANCE_SQ && selfHurt == 0 && moving && weaponOk;
        this.lastDistSq = distSq;
        if (selfHurt == 0 && weaponOk && moving && (closing && outsideMinimum || hitStart)) {
            this.lagging = true;
            this.reason = "";
            return Step.START;
        }
        return Step.NOTHING;
    }

    private Step holdingStep(double distSq, double rangeSq, int selfHurt, boolean weaponOk, boolean sprinting,
                             boolean blocking, boolean flushOnSprint, boolean flushOnBlock) {
        if (distSq > rangeSq) {
            this.hitMarked = -1;
            return flush("out-of-range");
        }
        if (this.lastDistSq >= 0 && distSq >= this.lastDistSq) {
            boolean hitHold = this.hitMarked == this.targetId && distSq <= MINIMUM_DISTANCE_SQ && selfHurt == 0;
            if (!hitHold) {
                return flush("not-closing");
            }
        }
        if (selfHurt > this.lastSelfHurt) {
            this.hitMarked = -1;
            this.lastSelfHurt = selfHurt;
            return flush("hurt");
        }
        this.lastSelfHurt = selfHurt;
        if (!weaponOk) {
            return flush("no-weapon");
        }
        boolean sprintStarted = sprinting && !this.lastSprint;
        this.lastSprint = sprinting;
        if (flushOnSprint && sprintStarted) {
            return flush("sprint");
        }
        boolean blockStarted = blocking && !this.lastBlock;
        this.lastBlock = blocking;
        if (flushOnBlock && blockStarted) {
            return flush("block");
        }
        return Step.HOLD;
    }

    /** Sent what was held for an attack, knockback or correction: the next tick may start again. */
    void released() {
        this.lagging = false;
    }
}
