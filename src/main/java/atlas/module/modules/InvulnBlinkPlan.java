package atlas.module.modules;

final class InvulnBlinkPlan {
    static final int IMMUNE_TICKS = 10;
    static final int TICK_MS = 50;

    enum Action {
        NONE,
        START,
        HOLD,
        RELEASE
    }

    static final class Settings {
        int maxTicks = 8;
        int minTicks = 2;
        int margin = 1;
        int cooldown = 10;
        boolean releaseOnHit = true;
    }

    static final class Input {
        boolean freshHit;
        boolean enemyNear;
        boolean safe = true;
        boolean mayHold = true;
        boolean stillHeld = true;
        boolean attacked;
        int pingMs;
    }

    private boolean holding;
    private int held;
    private int budget;
    private int cooldown;
    private String why = "";

    static int budget(int pingMs, Settings s) {
        int ping = Math.max(0, pingMs);
        int travel = (ping + TICK_MS - 1) / TICK_MS;
        int ticks = IMMUNE_TICKS - travel - 1 - Math.max(0, s.margin);
        return Math.max(0, Math.min(Math.min(s.maxTicks, IMMUNE_TICKS - 1), ticks));
    }

    Action tick(Input in, Settings s) {
        if (this.holding) {
            if (!in.stillHeld) {
                end(s, "taken away");
                return Action.NONE;
            }
            this.held++;
            if (!in.safe) {
                end(s, "unsafe");
                return Action.RELEASE;
            }
            if (!in.enemyNear) {
                end(s, "no enemy");
                return Action.RELEASE;
            }
            if (s.releaseOnHit && in.attacked) {
                end(s, "hit");
                return Action.RELEASE;
            }
            if (this.held >= this.budget) {
                end(s, "time");
                return Action.RELEASE;
            }
            return Action.HOLD;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (!in.freshHit || !in.enemyNear || !in.safe || this.cooldown > 0) {
            return Action.NONE;
        }
        int b = budget(in.pingMs, s);
        if (b < Math.max(1, s.minTicks)) {
            this.why = "ping too high";
            return Action.NONE;
        }
        if (!in.mayHold) {
            this.why = "not allowed now";
            return Action.NONE;
        }
        this.holding = true;
        this.held = 0;
        this.budget = b;
        return Action.START;
    }

    void stop(Settings s, String reason) {
        if (this.holding) {
            end(s, reason);
        }
    }

    void reset() {
        this.holding = false;
        this.held = 0;
        this.budget = 0;
        this.cooldown = 0;
        this.why = "";
    }

    private void end(Settings s, String reason) {
        this.holding = false;
        this.cooldown = Math.max(0, s.cooldown);
        this.why = reason;
    }

    boolean holding() {
        return this.holding;
    }

    int held() {
        return this.held;
    }

    int budget() {
        return this.budget;
    }

    String why() {
        return this.why;
    }
}
