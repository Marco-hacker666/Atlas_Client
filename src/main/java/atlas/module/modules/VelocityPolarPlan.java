package atlas.module.modules;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Velocity's Polar mode: what to do about one knockback (2026-10-26, owner:
 * "a Polar velocity"). Nothing here changes motion or holds a packet; the
 * two answers are things a hand does:
 *
 *   counter  -- hit back while sprinting, before this tick's movement: 1.8's
 *               own attack code takes 40 % off the horizontal speed
 *               (Velocity runs it through Silent Aura's vanilla click)
 *   jump     -- jump on the knockback tick, on the ground (a jump reset)
 *
 * and how often: a chance for each, a smaller chance for both on one hit,
 * at most max-rate of the last 10 knockbacks answered (from the 4th on), a
 * run of answers broken somewhere between half of max-streak and all of it,
 * and min-interval between two. The counter-hit waits 0..react-max ticks, a
 * reaction; the jump has only the knockback tick.
 *
 * Clock and dice come in, so it is tested on its own.
 */
final class VelocityPolarPlan {

    static final class Decision {
        static final Decision NONE = new Decision(false, false, -1);
        final boolean counter;
        final boolean jump;
        /** Ticks after the knockback the counter-hit goes out (0 = this tick), -1 without one. */
        final int counterTick;

        Decision(boolean counter, boolean jump, int counterTick) {
            this.counter = counter;
            this.jump = jump;
            this.counterTick = counterTick;
        }

        boolean any() {
            return this.counter || this.jump;
        }
    }

    int counterChance = 70;
    int jumpChance = 60;
    int bothChance = 20;
    int reactMin = 0;
    int reactMax = 1;
    int maxRate = 50;
    int maxStreak = 3;
    long minIntervalMs = 300L;

    private static final int WINDOW = 10;
    private final ArrayDeque<Boolean> recent = new ArrayDeque<Boolean>();
    private int streak;
    private int streakLimit;
    private long lastAnsweredAt = Long.MIN_VALUE / 2;

    void reset() {
        this.recent.clear();
        this.streak = 0;
        this.lastAnsweredAt = Long.MIN_VALUE / 2;
    }

    /**
     * One knockback. {@code canCounter}: sprinting with a target to hit;
     * {@code canJump}: on the ground, pushed up, nothing in the way of a jump.
     */
    Decision decide(long now, boolean canCounter, boolean canJump, Random random) {
        if (!canCounter && !canJump || now - this.lastAnsweredAt < this.minIntervalMs
                || rateFull() || streakBroken(random)) {
            return skip();
        }
        boolean counter = canCounter && roll(random, this.counterChance);
        boolean jump = canJump && roll(random, this.jumpChance);
        if (counter && jump && !roll(random, this.bothChance)) {
            /* Usually one or the other. */
            if (random.nextBoolean()) {
                counter = false;
            } else {
                jump = false;
            }
        }
        if (!counter && !jump) {
            return skip();
        }
        remember(true);
        this.streak++;
        this.lastAnsweredAt = now;
        int low = Math.max(0, Math.min(this.reactMin, this.reactMax));
        int high = Math.max(this.reactMin, this.reactMax);
        int tick = counter ? low + random.nextInt(high - low + 1) : -1;
        return new Decision(counter, jump, tick);
    }

    /** The answer decided on did not happen after all (no hit landed, no jump made). */
    void didNotHappen() {
        if (!this.recent.isEmpty() && this.recent.peekLast()) {
            this.recent.removeLast();
            this.recent.addLast(false);
        }
        this.streak = Math.max(0, this.streak - 1);
    }

    /** Answers in the window, for tests and the debug line. */
    int answeredInWindow() {
        int made = 0;
        for (Boolean b : this.recent) {
            if (b) {
                made++;
            }
        }
        return made;
    }

    private Decision skip() {
        remember(false);
        this.streak = 0;
        return Decision.NONE;
    }

    private static boolean roll(Random random, int percent) {
        return random.nextInt(100) < percent;
    }

    private void remember(boolean answered) {
        this.recent.addLast(answered);
        while (this.recent.size() > WINDOW) {
            this.recent.removeFirst();
        }
    }

    /** True when one more answer would put the last 10 over max-rate (from the 4th knockback on). */
    private boolean rateFull() {
        /* This knockback and the 9 before it (not the 10 before it: that would be 11). */
        int before = Math.min(WINDOW - 1, this.recent.size());
        int made = 0;
        int skip = this.recent.size() - before;
        for (Boolean b : this.recent) {
            if (skip-- > 0) {
                continue;
            }
            if (b) {
                made++;
            }
        }
        int window = before + 1;
        return window >= 4 && (made + 1) * 100 > this.maxRate * window;
    }

    /** True when this answer would make the run too long. */
    private boolean streakBroken(Random random) {
        int max = this.maxStreak;
        if (max <= 0) {
            return false;
        }
        if (this.streak == 0) {
            int low = Math.max(1, (max + 1) / 2);
            this.streakLimit = low + random.nextInt(max - low + 1);
        }
        return this.streak >= this.streakLimit;
    }
}
