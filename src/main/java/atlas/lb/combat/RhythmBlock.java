package atlas.lb.combat;

public final class RhythmBlock {
    public int minHold = 2;

    public int guard = 2;

    public int patience = 3;

    private boolean keyDown;
    private int changedAt = -1000;
    private int targetId = -1;
    private int lastTargetHurt;
    private int ourHitTick = -1;
    private boolean ourHitConfirmed;

    private int holdOffSince = -1;
    private boolean gaveUp;
    private int outOfReach;

    private static final int FRESH_APPROACH = 5;
    private int decidedTick = Integer.MIN_VALUE;
    private int decidedWait = Integer.MAX_VALUE;
    private double learnedRtt;
    private int learned;
    private static final int NONE = Integer.MIN_VALUE;

    private int hurtSeenAt = NONE;
    private int lastSelfHurt;

    public static final class Input {
        public int tick;
        public boolean sword;

        public boolean using;

        public int targetId = -1;
        public int targetHurt;

        public boolean reachNow;

        public boolean reachSoon;

        public boolean threat;

        public boolean enemyNear;
        public int selfHurt;
        public int rtt;
    }

    public static final class Decision {
        public final boolean key;

        public final boolean raise;

        public final boolean hitNow;

        public final int wait;

        public final boolean needBlock;

        public final int rtt;

        Decision(boolean key, boolean raise, boolean hitNow, int wait, boolean needBlock, int rtt) {
            this.key = key;
            this.raise = raise;
            this.hitNow = hitNow;
            this.wait = wait;
            this.needBlock = needBlock;
            this.rtt = rtt;
        }
    }

    public void reset() {
        this.keyDown = false;
        this.changedAt = -1000;
        this.targetId = -1;
        this.lastTargetHurt = 0;
        this.ourHitTick = -1;
        this.ourHitConfirmed = false;
        this.holdOffSince = -1;
        this.gaveUp = false;
        this.outOfReach = 0;
        this.decidedTick = Integer.MIN_VALUE;
        this.decidedWait = Integer.MAX_VALUE;
        this.learnedRtt = 0.0;
        this.learned = 0;
        this.hurtSeenAt = NONE;
        this.lastSelfHurt = 0;
    }

    int rtt(int reported) {
        return this.learned > 0 ? (int) Math.round(this.learnedRtt) : reported;
    }

    public void attackSent(int tick) {
        if (tick == this.decidedTick && this.decidedWait <= 0) {
            this.ourHitTick = tick;
            this.ourHitConfirmed = false;
            this.holdOffSince = -1;
            this.gaveUp = false;
        }
    }

    private void holdOff(int tick) {
        if (this.holdOffSince < 0) {
            this.holdOffSince = tick;
        }
    }

    public boolean keyDown() {
        return this.keyDown;
    }

    public void released(int tick) {
        if (this.keyDown) {
            this.keyDown = false;
            this.changedAt = tick;
        }
    }

    int waitTicks(Input in, int rtt) {
        int byClient = in.targetHurt - rtt;
        int byOurs = Integer.MIN_VALUE;
        if (this.ourHitTick >= 0) {
            int age = in.tick - this.ourHitTick;
            if (!this.ourHitConfirmed && age > rtt + 3) {
                this.ourHitTick = -1;
            } else if (age < 10) {
                byOurs = this.ourHitTick + 10 - in.tick;
            }
        }
        return Math.max(byClient, byOurs);
    }

    public Decision tick(Input in) {
        boolean target = in.targetId >= 0;
        if (target) {
            if (in.targetId != this.targetId) {
                this.targetId = in.targetId;
                this.ourHitTick = -1;
                this.ourHitConfirmed = false;
                this.lastTargetHurt = in.targetHurt;
                this.holdOffSince = -1;
                this.gaveUp = false;
            }
            if (this.ourHitTick >= 0 && !this.ourHitConfirmed && in.targetHurt > this.lastTargetHurt
                    && in.targetHurt >= 8) {
                this.ourHitConfirmed = true;

                int measured = Math.max(0, Math.min(12, in.tick - this.ourHitTick - (10 - in.targetHurt)));
                this.learnedRtt = this.learned == 0 ? measured : this.learnedRtt * 0.65 + measured * 0.35;
                this.learned++;
            }
            this.lastTargetHurt = in.targetHurt;
        }

        int rtt = rtt(in.rtt);
        int wait = target ? waitTicks(in, rtt) : Integer.MAX_VALUE / 2;
        this.decidedTick = in.tick;
        this.decidedWait = wait;
        boolean dueNext = target && wait - 1 <= 0;
        this.outOfReach = in.reachNow || in.reachSoon ? 0 : this.outOfReach + 1;
        if (!dueNext || this.outOfReach >= FRESH_APPROACH) {
            this.holdOffSince = -1;
            this.gaveUp = false;
        }
        if (this.holdOffSince >= 0 && in.tick - this.holdOffSince >= this.patience) {
            this.gaveUp = true;
        }
        boolean hitNow = target && in.reachNow && !in.using && wait <= 0;
        boolean hitNext = dueNext && (in.reachNow || in.reachSoon && !this.gaveUp);
        if (in.selfHurt > this.lastSelfHurt && in.selfHurt >= 8) {
            this.hurtSeenAt = in.tick - (10 - in.selfHurt);
        }
        this.lastSelfHurt = in.selfHurt;
        boolean guardSpent = false;
        boolean inGuard = false;
        if (this.hurtSeenAt != NONE) {
            int since = in.tick - (this.hurtSeenAt + 10 - rtt);
            guardSpent = since > this.guard && since <= rtt + 1;
            inGuard = since >= -1 && since <= this.guard;
        }

        boolean danger = in.threat || inGuard && in.enemyNear;
        boolean needBlock = in.sword && danger && in.selfHurt - rtt <= 1 && !guardSpent;

        boolean enemyHitDue = needBlock && in.selfHurt > 0 && in.selfHurt - rtt >= 0;
        int held = in.tick - this.changedAt;

        boolean key;
        if (!in.sword) {
            key = false;
        } else if (in.using) {
            if (held >= this.minHold && hitNext && !enemyHitDue) {
                key = false;
                holdOff(in.tick);
            } else {
                key = held < this.minHold || needBlock;
            }
        } else if (hitNow) {
            key = needBlock;
        } else if (hitNext && !enemyHitDue) {
            key = false;
            holdOff(in.tick);
        } else {
            key = needBlock;
        }
        boolean raise = key && !this.keyDown;
        if (key != this.keyDown) {
            this.keyDown = key;
            this.changedAt = in.tick;
        }
        return new Decision(key, raise, hitNow, wait, needBlock, rtt);
    }
}
