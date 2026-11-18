package atlas.module.modules;

// Rebuilt from what Yuri Client's AuraModule does (Yuri, AGPL-3.0, github.com/unleg1t/Yuri), not copied

final class KillAuraYuri {
    static final int HYPIXEL = 0;
    static final int LEGIT = 1;
    static final int NCP = 2;
    static final int VANILLA = 3;

    static final long[] CLICK_MS = {125L, 200L, 0L, 0L};

    static final class Plan {
        boolean release;

        boolean block;

        boolean blockAfterMove;

        Boolean useKey;

        boolean mayClick = true;
    }

    boolean blocking;
    private int hypixelTick;
    private int legitTicks = -1;

    void reset() {
        this.blocking = false;
        this.hypixelTick = 0;
        this.legitTicks = -1;
    }

    Plan step(int mode, boolean inRange, double distance, int hurtTime, boolean onlyWhenHurt, int hurtTicks,
              long msToClick, boolean usingItem) {
        Plan plan = new Plan();
        if (!inRange || (onlyWhenHurt && hurtTime < hurtTicks)) {
            letGo(mode, plan);
            return plan;
        }
        switch (mode) {
            case HYPIXEL:
                if (distance <= 3.0) {
                    if (this.blocking) {
                        plan.release = true;
                        this.blocking = false;
                    }
                    if (msToClick <= 50L) {
                        this.hypixelTick = 1;
                    } else if (this.hypixelTick == 1) {
                        plan.block = true;
                        this.blocking = true;
                        this.hypixelTick = 0;
                    }
                } else {
                    if (this.blocking) {
                        plan.release = true;
                        this.blocking = false;
                    }
                    this.hypixelTick = 0;
                }
                break;
            case LEGIT: {
                boolean press = hurtTime <= 10 && hurtTime >= 6 && distance <= 3.0;
                plan.useKey = press;
                this.blocking = press;
                this.legitTicks++;
                if (press || usingItem) {
                    this.legitTicks = 0;
                }
                plan.mayClick = this.legitTicks >= 1;
                break;
            }
            case NCP:
                if (this.blocking) {
                    plan.release = true;
                    this.blocking = false;
                }
                plan.blockAfterMove = true;
                break;
            case VANILLA:
                plan.block = true;
                this.blocking = true;
                break;
            default:
                break;
        }
        return plan;
    }

    void blockedAfterMove() {
        this.blocking = true;
    }

    private void letGo(int mode, Plan plan) {
        if (mode == LEGIT) {
            if (this.blocking) {
                plan.useKey = false;
            }
        } else if (this.blocking) {
            plan.release = true;
        }
        this.blocking = false;
        this.hypixelTick = 0;
        this.legitTicks = -1;
    }
}
