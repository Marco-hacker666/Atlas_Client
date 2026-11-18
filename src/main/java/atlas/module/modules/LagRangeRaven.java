package atlas.module.modules;

final class LagRangeRaven {
    static final double MINIMUM_DISTANCE_SQ = 3.0 * 3.0;

    enum Step { NOTHING, START, HOLD, FLUSH }

    boolean lagging;

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

    void released() {
        this.lagging = false;
    }
}
