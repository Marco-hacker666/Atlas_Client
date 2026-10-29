package atlas.lb.combat;

/**
 * Silent Aura's Rhythm block (2026-10-27, owner: "a good auto block that
 * actually works"). Decides, once a tick, whether the use key is held and
 * whether this tick's click is the one that deals damage.
 *
 * 1.8's rules it is built on:
 *  - a hit deals damage only once the target's 10 ticks of invulnerability
 *    are over; every other hit in those ticks is wasted. So one click per
 *    window does the damage, timed for when it arrives: the target's hurt
 *    timer as the client sees it runs a round trip behind the server, and our
 *    hit takes half a trip more, so a hit sent now lands vulnerable when
 *    hurtTime - roundTripTicks <= 0. Our own damaging hit is remembered too:
 *    until its hurt shows on the client (a round trip later) the target is
 *    already invulnerable.
 *  - vanilla takes no attack while an item is in use, nor in the tick the
 *    use is released (runTick drains the presses). So the sword comes down a
 *    tick before the damaging click, the click goes out, and in the same tick
 *    after it the sword goes back up -- vanilla's own order in runTick
 *    (clicks, then the use key).
 *  - blocking only matters when this player can be damaged: after being hit
 *    it is invulnerable too, so the block goes up only when that is over by
 *    the time the block reaches the server (selfHurt - roundTripTicks <= 1),
 *    and only with a threat: an enemy close enough, facing us.
 *  - no release sooner than minHold ticks after raising, so the server never
 *    sees a block raised and lowered within a tick.
 *  - after a hit taken the moment of the enemy's next one is known too: the
 *    first tick we can take one. The sword is up from just before it until
 *    {@code guard} ticks after it, then down: a fast enemy has landed it by
 *    then, and we only learn that a round trip later -- waiting for it kept
 *    the sword up a whole round trip more every exchange. When no hit shows
 *    up after that round trip, the enemy did not land it on time and the
 *    sword goes back up for as long as it threatens.
 *    In that window any enemy within reach counts as a threat (who hit us is
 *    about to again; its look as we see it is late), outside it only one
 *    looking at us and swinging.
 *  - our release must not fall on the enemy's next hit: a fast enemy lands
 *    it on the first tick we can take one, which after a hit taken is known
 *    (selfHurt - roundTripTicks reaching 0). When the release would leave the
 *    server without the block right then, the sword stays up and our hit goes
 *    a tick or two later -- once: from then on the two rhythms are apart.
 *  - the round trip is learned from our own damaging hits (ticks from the
 *    click to the target's hurt coming back) once there is one; the ping the
 *    server reports is only the start (it can be missing, or an average).
 *  - a hit due but not yet in reach (the target at the edge of reach, or
 *    LagAware holding it) keeps the sword down for at most {@code patience}
 *    ticks; then it goes up and comes down only once the look is on the target
 *    (not on a guess that it will be), so hovering at the edge does not flicker
 *    the block.
 *
 * Pure state and arithmetic; ticks come in. Tested on its own.
 */
public final class RhythmBlock {

    /** Ticks the sword stays up at least once raised. */
    public int minHold = 2;
    /** Ticks after the enemy's next hit can first land that the sword stays up for it. */
    public int guard = 2;
    /** Ticks the sword stays down, in one approach, for a due hit that is not in reach yet. */
    public int patience = 3;

    private boolean keyDown;
    private int changedAt = -1000;
    private int targetId = -1;
    private int lastTargetHurt;
    private int ourHitTick = -1;
    private boolean ourHitConfirmed;
    /** The tick the sword first stayed or came down for a due hit in this approach; -1: none. */
    private int holdOffSince = -1;
    private boolean gaveUp;
    private int outOfReach;
    /** Ticks out of reach after which the target is approached afresh. */
    private static final int FRESH_APPROACH = 5;
    private int decidedTick = Integer.MIN_VALUE;
    private int decidedWait = Integer.MAX_VALUE;
    private double learnedRtt;
    private int learned;
    private static final int NONE = Integer.MIN_VALUE;
    /** The tick the last hit we took showed (its hurt at 10); NONE: none known. */
    private int hurtSeenAt = NONE;
    private int lastSelfHurt;

    /** What one tick looks like. */
    public static final class Input {
        public int tick;
        public boolean sword;
        /** The client has the sword up (isUsingItem). */
        public boolean using;
        /** The aura's target's entity id, or -1. */
        public int targetId = -1;
        public int targetHurt;
        /** This tick's look lands on the target in reach (and LagAware agrees). */
        public boolean reachNow;
        /** In reach now or next tick by distance (the look may not be on it yet). */
        public boolean reachSoon;
        /** An enemy close enough and facing us. */
        public boolean threat;
        /** An enemy within its hit's reach of us, by distance alone (for the guard window). */
        public boolean enemyNear;
        public int selfHurt;
        public int rtt;
    }

    /** The tick's answer. */
    public static final class Decision {
        /** Hold the use key down. */
        public final boolean key;
        /** The key goes down this tick (press it, so vanilla raises at once). */
        public final boolean raise;
        /** This tick's click deals damage: send it. */
        public final boolean hitNow;
        /** Ticks until a hit would deal damage (<= 0: now). */
        public final int wait;
        /** A block is wanted (threat, and this player can be damaged). */
        public final boolean needBlock;
        /** The round trip in ticks this decision used. */
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

    /** The round trip to use: learned from our own hits once there is one, else the reported one. */
    int rtt(int reported) {
        return this.learned > 0 ? (int) Math.round(this.learnedRtt) : reported;
    }

    /**
     * An attack on the target went out in {@code tick} (whoever pressed it).
     * When the target was due then, it is the damaging hit of the window.
     */
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

    /** The key was let go of from outside (the aura stopped, a screen opened). */
    public void released(int tick) {
        if (this.keyDown) {
            this.keyDown = false;
            this.changedAt = tick;
        }
    }

    /** Ticks until a hit sent now would deal damage (<= 0: now). */
    int waitTicks(Input in, int rtt) {
        int byClient = in.targetHurt - rtt;
        int byOurs = Integer.MIN_VALUE;
        if (this.ourHitTick >= 0) {
            int age = in.tick - this.ourHitTick;
            if (!this.ourHitConfirmed && age > rtt + 3) {
                /* No hurt showed: that hit did not land. Try again. */
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
            /* A tick without a target (the aura paused a moment) keeps what is known about the last one. */
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
                /* Ticks from our click to its hurt showing; a hurt seen below 10 showed that many ticks ago. */
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
            /* Nothing due, or well out of reach (not a flicker at the edge): a new approach starts eager again. */
            this.holdOffSince = -1;
            this.gaveUp = false;
        }
        if (this.holdOffSince >= 0 && in.tick - this.holdOffSince >= this.patience) {
            /* Waited long enough at the edge: up, and down again only with the look on the target. */
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
            /* since 0: what we send this tick reaches the server as our invulnerability ends. */
            int since = in.tick - (this.hurtSeenAt + 10 - rtt);
            guardSpent = since > this.guard && since <= rtt + 1;
            inGuard = since >= -1 && since <= this.guard;
        }
        /* 2026-10-28 (owner's log: 6 of 8 hits taken with no threat seen): in the guard window someone has just
           been hitting us, so any enemy within reach counts -- the look and swing we see of them run a round trip
           late. Outside it the strict danger check still decides. */
        boolean danger = in.threat || inGuard && in.enemyNear;
        boolean needBlock = in.sword && danger && in.selfHurt - rtt <= 1 && !guardSpent;
        /* The enemy's next hit lands on what we send now or next tick. */
        boolean enemyHitDue = needBlock && in.selfHurt > 0 && in.selfHurt - rtt >= 0;
        int held = in.tick - this.changedAt;

        boolean key;
        if (!in.sword) {
            key = false;
        } else if (in.using) {
            if (held >= this.minHold && hitNext && !enemyHitDue) {
                /* Down now (vanilla releases in this runTick); the damaging click goes next tick. */
                key = false;
                holdOff(in.tick);
            } else {
                key = held < this.minHold || needBlock;
            }
        } else if (hitNow) {
            /* Click first (runTick), then straight back up in the same tick if still needed. */
            key = needBlock;
        } else if (hitNext && !enemyHitDue) {
            /* Not up into a hit due next tick. */
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
