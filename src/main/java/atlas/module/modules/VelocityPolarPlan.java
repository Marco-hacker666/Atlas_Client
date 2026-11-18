package atlas.module.modules;

import java.util.ArrayDeque;
import java.util.Random;

final class VelocityPolarPlan {
    static final class Decision {
        static final Decision NONE = new Decision(false, false, -1);
        final boolean counter;
        final boolean jump;

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

    Decision decide(long now, boolean canCounter, boolean canJump, Random random) {
        if (!canCounter && !canJump || now - this.lastAnsweredAt < this.minIntervalMs
                || rateFull() || streakBroken(random)) {
            return skip();
        }
        boolean counter = canCounter && roll(random, this.counterChance);
        boolean jump = canJump && roll(random, this.jumpChance);
        if (counter && jump && !roll(random, this.bothChance)) {
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

    void didNotHappen() {
        if (!this.recent.isEmpty() && this.recent.peekLast()) {
            this.recent.removeLast();
            this.recent.addLast(false);
        }
        this.streak = Math.max(0, this.streak - 1);
    }

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

    private boolean rateFull() {
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
