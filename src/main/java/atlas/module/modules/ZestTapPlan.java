package atlas.module.modules;

import java.util.Random;

/**
 * Zest tap (2026-11-10, owner's guide and r/CompetitiveMinecraft "what is zest tapping"): a sequence is three landed
 * hits. The first two go with W held, no sprint reset, so the knockback stays small and the opponent stays close. W is
 * then let go "for 1" hit (owner's guide: "hold w for 2 hits and let go for 1"): from the top of the target's vertical
 * knockback from the second hit ("W tap once the opponent is on the peak of his vertical kb") until the third lands,
 * capped by a time, then pressed again, so the next sequence starts sprinting. The player strafes through each sequence, the side swapping every sequence. A combo starts from a
 * first hit, a hit select (answering their hit) or a trade, and only works on an opponent walking in facing this
 * player: it stops when they crouch, block, back off or look away. Clicks are left to the player (the guides burst
 * click). No game code: SprintReset reads the world and applies what this returns.
 */
final class ZestTapPlan {

    static final int START_ANY = 0;
    static final int START_FIRST_HIT = 1;
    static final int START_HIT_SELECT = 2;
    static final int START_TRADE = 3;

    /** A hit taken this close to ours, either way, is a trade. */
    static final long TRADE_MS = 150L;
    /** Their hit this long before ours, at most, and ours answered it: a hit select. */
    static final long SELECT_MS = 600L;
    /** No hit taken for this long before ours: a first hit. */
    static final long FIRST_HIT_QUIET_MS = 1000L;
    /** No landed hit for this long ends the combo. */
    static final long COMBO_MS = 1000L;
    /** No top of the knockback seen this long after the second hit (already in the air): the tap goes anyway. */
    static final long PEAK_WAIT_MS = 400L;
    /* Release at the hit (2026-11-10, owner's video: "for the third hit go off W for a split second while hitting him
       again"): W goes the moment the target can be hurt again after the second hit, the third hit's moment, and stays
       off its full time, so the tap and the hit overlap. No opening seen this long after the second hit: it goes. */
    static final long OPEN_WAIT_MS = 700L;
    /** Ticks after the second hit before an opening counts (its hurt is still being shown). */
    static final long OPEN_AFTER_MS = 100L;
    /* Random strafe (owner, 2026-11-10: "not that much, a little at a time"): short taps of A or D, not a held key.
       Each sequence: the side swaps 7 times in 10; 0-3 ticks after the first hit, 2-4 taps of 1-2 ticks, 3-6 ticks
       apart. */
    /* The values below are the defaults; the owner asked (2026-11-10) that players can set each one (strafeShape). */
    static final int SWAP_PERCENT = 70;
    static final int STRAFE_DELAY_MAX = 3;
    /* 2026-11-10 (owner: "too little"): taps per sequence set by zest-strafe-taps (n-2 to n), 1-3 ticks, 2-5 apart. */
    static final int DEFAULT_TAPS = 5;
    static final int MAX_TAPS = 8;
    static final int TAP_MIN_TICKS = 1;
    static final int TAP_MAX_TICKS = 3;
    static final int GAP_MIN_TICKS = 2;
    static final int GAP_MAX_TICKS = 5;

    /** What to do with the keys this tick. */
    static final class Keys {
        /** Let go of W. */
        boolean release;
        /** -1 left, 1 right, 0 none. */
        int strafe;
    }

    private final Random random;
    private boolean combo;
    private int hits;
    private long lastHitMs = Long.MIN_VALUE / 2;
    private long lastHurtMs = Long.MIN_VALUE / 2;
    private boolean waitingPeak;
    private long peakWaitFrom;
    private boolean releasing;
    private long releaseUntilMs;
    private int strafeSide = 1;
    private int strafeDelay;
    private int strafeTaps = DEFAULT_TAPS;
    private boolean releaseAtHit;
    private boolean targetOpen;
    private int tapMin = TAP_MIN_TICKS;
    private int tapMax = TAP_MAX_TICKS;
    private int gapMin = GAP_MIN_TICKS;
    private int gapMax = GAP_MAX_TICKS;
    private int delayMax = STRAFE_DELAY_MAX;
    private int swapPercent = SWAP_PERCENT;
    private int strafeLeft = -1;
    private boolean tapping;
    private int tapsLeft;
    private int tapLeft;
    private int gapLeft;

    ZestTapPlan(Random random) {
        this.random = random;
    }

    /** Most taps a sequence (zest-strafe-taps); the sequence takes n-2 to n. */
    void strafeTaps(int taps) {
        this.strafeTaps = Math.max(1, Math.min(MAX_TAPS, taps));
    }

    /** The whole strafe shape from the settings; a min above its max is swapped, not refused. */
    void strafeShape(int taps, int tapMinTicks, int tapMaxTicks, int gapMinTicks, int gapMaxTicks, int delayMaxTicks,
                     int swapPercent) {
        strafeTaps(taps);
        this.tapMin = Math.max(1, Math.min(tapMinTicks, tapMaxTicks));
        this.tapMax = Math.max(this.tapMin, Math.max(tapMinTicks, tapMaxTicks));
        this.gapMin = Math.max(0, Math.min(gapMinTicks, gapMaxTicks));
        this.gapMax = Math.max(this.gapMin, Math.max(gapMinTicks, gapMaxTicks));
        this.delayMax = Math.max(0, delayMaxTicks);
        this.swapPercent = Math.max(0, Math.min(100, swapPercent));
    }

    /** true: W goes at the third hit's moment and stays off its full time; false: at the knockback peak, back at the hit. */
    void releaseAtHit(boolean atHit) {
        this.releaseAtHit = atHit;
    }

    /** This tick: an attack sent now would hurt the target (its hurt has run out, given the round trip). */
    void targetOpen(boolean open) {
        this.targetOpen = open;
    }

    boolean inCombo() {
        return this.combo;
    }

    int hits() {
        return this.hits;
    }

    void reset() {
        this.combo = false;
        this.hits = 0;
        this.waitingPeak = false;
        this.releasing = false;
    }

    /** Whether a hit landed now starts a combo, from when this player was last hit. */
    static boolean starts(int startMode, long hitMs, long lastHurtMs) {
        long since = hitMs - lastHurtMs;
        boolean trade = Math.abs(since) <= TRADE_MS;
        boolean select = since > TRADE_MS && since <= SELECT_MS;
        boolean first = since > FIRST_HIT_QUIET_MS;
        switch (startMode) {
            case START_FIRST_HIT:
                return first;
            case START_HIT_SELECT:
                return select;
            case START_TRADE:
                return trade;
            default:
                return trade || select || first;
        }
    }

    /**
     * One tick. landed: a hit of ours did damage this tick. hurt: this player took one this tick. opponentOk: the
     * target faces this player and is not crouching, blocking or backing off. targetAtPeak: its rise just ended.
     * releaseMs: the most W stays let go; the third hit landing ends it sooner.
     */
    Keys tick(long now, boolean landed, boolean hurt, boolean opponentOk, boolean targetAtPeak, int startMode,
              boolean strafe, boolean strafeRandom, long releaseMs) {
        if (hurt) {
            this.lastHurtMs = now;
        }
        if (this.combo && (!opponentOk || now - this.lastHitMs > COMBO_MS)) {
            reset();
        }
        if (landed) {
            if (!this.combo) {
                if (opponentOk && starts(startMode, now, this.lastHurtMs)) {
                    this.combo = true;
                    beginSequence(strafeRandom);
                }
            } else if (this.hits >= 3) {
                beginSequence(strafeRandom);
            } else {
                this.hits++;
                if (this.hits == 2) {
                    this.waitingPeak = true;
                    this.peakWaitFrom = now;
                } else {
                    /* The third. At the peak: whatever the tap had left, W is back. At the hit: the tap was timed
                       to overlap this hit and runs its time. */
                    this.waitingPeak = false;
                    if (!this.releaseAtHit) {
                        this.releasing = false;
                    }
                }
            }
            this.lastHitMs = now;
        } else if (this.combo && this.waitingPeak && (this.releaseAtHit
                ? this.targetOpen && now - this.peakWaitFrom >= OPEN_AFTER_MS || now - this.peakWaitFrom >= OPEN_WAIT_MS
                : targetAtPeak || now - this.peakWaitFrom >= PEAK_WAIT_MS)) {
            this.waitingPeak = false;
            /* Within 15 % either way: a hand does not let go for the same time every time. */
            this.releasing = true;
            this.releaseUntilMs = now + Math.max(50L, Math.round(releaseMs * (0.85 + 0.3 * this.random.nextDouble())));
        }
        if (this.releasing && now >= this.releaseUntilMs) {
            this.releasing = false;
        }
        Keys keys = new Keys();
        if (!this.combo) {
            return keys;
        }
        if (this.releasing) {
            keys.release = true;
        } else if (strafe) {
            if (this.strafeDelay > 0) {
                this.strafeDelay--;
            } else if (!this.tapping) {
                if (this.strafeLeft != 0) {
                    keys.strafe = this.strafeSide;
                    if (this.strafeLeft > 0) {
                        this.strafeLeft--;
                    }
                }
            } else if (this.gapLeft > 0) {
                this.gapLeft--;
            } else if (this.tapsLeft > 0) {
                keys.strafe = this.strafeSide;
                if (--this.tapLeft <= 0) {
                    this.tapsLeft--;
                    this.tapLeft = between(this.tapMin, this.tapMax);
                    this.gapLeft = between(this.gapMin, this.gapMax);
                }
            }
        }
        return keys;
    }

    private void beginSequence(boolean strafeRandom) {
        this.hits = 1;
        this.waitingPeak = false;
        this.releasing = false;
        this.tapping = strafeRandom;
        if (!strafeRandom) {
            this.strafeSide = -this.strafeSide;
            this.strafeDelay = 0;
            this.strafeLeft = -1;
            return;
        }
        if (this.random.nextInt(100) < this.swapPercent) {
            this.strafeSide = -this.strafeSide;
        }
        this.strafeDelay = this.random.nextInt(this.delayMax + 1);
        this.tapsLeft = between(Math.max(1, this.strafeTaps - 2), this.strafeTaps);
        this.tapLeft = between(this.tapMin, this.tapMax);
        this.gapLeft = 0;
    }

    private int between(int min, int max) {
        return min + this.random.nextInt(max - min + 1);
    }
}
