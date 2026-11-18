package atlas.module.modules;

import java.util.Random;

final class ZestTapPlan {
    static final int START_ANY = 0;
    static final int START_FIRST_HIT = 1;
    static final int START_HIT_SELECT = 2;
    static final int START_TRADE = 3;

    static final long TRADE_MS = 150L;

    static final long SELECT_MS = 600L;

    static final long FIRST_HIT_QUIET_MS = 1000L;

    static final long COMBO_MS = 1000L;

    static final long PEAK_WAIT_MS = 400L;

    static final long OPEN_WAIT_MS = 700L;

    static final long OPEN_AFTER_MS = 100L;

    static final int SWAP_PERCENT = 70;
    static final int STRAFE_DELAY_MAX = 3;

    static final int DEFAULT_TAPS = 5;
    static final int MAX_TAPS = 8;
    static final int TAP_MIN_TICKS = 1;
    static final int TAP_MAX_TICKS = 3;
    static final int GAP_MIN_TICKS = 2;
    static final int GAP_MAX_TICKS = 5;

    static final class Keys {
        boolean release;

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

    void strafeTaps(int taps) {
        this.strafeTaps = Math.max(1, Math.min(MAX_TAPS, taps));
    }

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

    void releaseAtHit(boolean atHit) {
        this.releaseAtHit = atHit;
    }

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
