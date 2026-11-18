package atlas.module.modules;

import java.util.Random;

final class GodBridgeRhythm {
    static final int MAX_WAIT = 20;

    static final int MANUAL_GROUND_TICKS = 2;

    static final int CROUCH_MIN = 5;
    static final int CROUCH_MAX = 7;

    static final int LANDING_WAIT = 10;

    static final class Settings {
        int jumpAfterMin = 8;
        int jumpAfterMax = 8;
        int jumpDelayMin;
        int jumpDelayMax;
        int resetAfterMin = 7;
        int resetAfterMax = 8;
        int startDelayMin = 10;
        int startDelayMax = 13;
        boolean startCrouch = true;
    }

    static final class Input {
        boolean forward;

        boolean jumpKey;
        boolean onGround;

        boolean diagonal;

        boolean settled;

        boolean sliding;

        boolean canJump;
    }

    static final class Keys {
        boolean hold;
        boolean sneak;
        boolean jump;

        boolean noStrafe;

        boolean starting;
    }

    private final Random random;
    private boolean starting = true;
    private boolean manual;
    private int manualGround;
    private int lastDiagonal = -1;
    private int startTicks;
    private int startDelay;
    private int crouchTicks;
    private int blocks;
    private int placedSince;
    private int sincePlace;
    private int jumpAfter;
    private int jumpDelay;
    private int jumps;
    private int resetAfter;
    private boolean drawn;

    private boolean restartDue;
    private boolean leftGround;
    private int dueTicks;

    GodBridgeRhythm() {
        this(new Random());
    }

    GodBridgeRhythm(Random random) {
        this.random = random;
    }

    void placed() {
        this.placedSince++;
    }

    void reset() {
        this.starting = true;
        this.manual = false;
        this.manualGround = 0;
        this.lastDiagonal = -1;
        this.startTicks = 0;
        this.drawn = false;
        this.blocks = 0;
        this.placedSince = 0;
        this.jumps = 0;
        this.restartDue = false;
    }

    boolean starting() {
        return this.starting;
    }

    Keys tick(Input in, Settings s) {
        Keys keys = new Keys();
        int placed = this.placedSince;
        this.placedSince = 0;
        if (!this.drawn) {
            draw(s);
        }
        if (in.jumpKey) {
            this.manual = true;
            this.manualGround = 0;
            restart(s);
            return keys;
        }
        if (this.manual) {
            this.manualGround = in.onGround ? this.manualGround + 1 : 0;
            if (this.manualGround <= MANUAL_GROUND_TICKS) {
                return keys;
            }
            this.manual = false;
        }
        if (!in.forward) {
            restart(s);
            return keys;
        }
        int diagonal = in.diagonal ? 1 : 0;
        if (this.lastDiagonal >= 0 && this.lastDiagonal != diagonal) {
            restart(s);
        }
        this.lastDiagonal = diagonal;
        if (this.starting) {
            return start(in, s, keys);
        }
        keys.noStrafe = !in.diagonal;
        if (this.restartDue) {
            this.leftGround |= !in.onGround;
            this.dueTicks++;
            if (in.onGround && (this.leftGround || this.dueTicks > LANDING_WAIT)) {
                restart(s);
                return start(in, s, keys);
            }
            return keys;
        }
        if (placed > 0) {
            this.blocks += placed;
            this.sincePlace = 0;
        } else {
            this.sincePlace++;
        }
        if (this.blocks >= this.jumpAfter && this.sincePlace >= this.jumpDelay && in.canJump) {
            this.blocks = 0;
            this.jumps++;
            this.jumpAfter = between(s.jumpAfterMin, s.jumpAfterMax, 1);
            this.jumpDelay = between(s.jumpDelayMin, s.jumpDelayMax, 0);
            keys.jump = true;
            if (this.jumps >= this.resetAfter) {
                this.restartDue = true;
                this.leftGround = false;
                this.dueTicks = 0;
            }
        }
        return keys;
    }

    private Keys start(Input in, GodBridgeRhythm.Settings s, Keys keys) {
        this.startTicks++;
        keys.starting = true;
        keys.hold = true;
        keys.noStrafe = true;
        keys.sneak = in.onGround && (s.startCrouch && this.startTicks <= this.crouchTicks || in.sliding);
        boolean waited = this.startTicks >= this.startDelay;
        if (waited && (in.settled || this.startTicks >= this.startDelay + MAX_WAIT) && !in.sliding) {
            this.starting = false;
            this.blocks = 0;
            this.sincePlace = 0;
            this.jumps = 0;
        }
        return keys;
    }

    private void restart(Settings s) {
        this.restartDue = false;
        if (!this.starting) {
            this.starting = true;
            draw(s);
        }
        this.startTicks = 0;
    }

    private void draw(Settings s) {
        this.drawn = true;
        this.startDelay = between(s.startDelayMin, s.startDelayMax, 0);
        this.crouchTicks = CROUCH_MIN + this.random.nextInt(CROUCH_MAX - CROUCH_MIN + 1);
        this.resetAfter = between(s.resetAfterMin, s.resetAfterMax, 1);
        this.jumpAfter = between(s.jumpAfterMin, s.jumpAfterMax, 1);
        this.jumpDelay = between(s.jumpDelayMin, s.jumpDelayMax, 0);
    }

    private int between(int a, int b, int floor) {
        int lo = Math.max(floor, Math.min(a, b));
        int hi = Math.max(lo, Math.max(a, b));
        return lo + this.random.nextInt(hi - lo + 1);
    }
}
