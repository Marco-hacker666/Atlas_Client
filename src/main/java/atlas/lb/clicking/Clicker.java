package atlas.lb.clicking;

import java.util.function.BooleanSupplier;

// Click scheduling for a module (LiquidBounce NextGen port, GPL-3.0)

public final class Clicker {
    public static final int TECHNIQUE_HUMAN = 0;
    public static final int TECHNIQUE_CONSTANT = 1;

    public static final int TECHNIQUE_ATLAS_HUMAN = 2;
    private static final int TICKS_AHEAD = 20;

    private int technique = TECHNIQUE_HUMAN;
    private final HumanClickTiming human = new HumanClickTiming();
    private final atlas.util.HumanClicks atlasHuman = new atlas.util.HumanClicks();

    private long comboBase;
    private final ClickTiming atlasTiming = (recent, comboMs, minCps, maxCps, random) -> {
        if (comboMs == 0L) {
            this.comboBase = System.currentTimeMillis();
        }
        return this.atlasHuman.next(minCps, maxCps, this.comboBase + comboMs);
    };
    private final ClickPlan plan;
    private Integer clickAmount;
    private int ticksSinceLastClick;

    public Clicker() {
        this.plan = new ClickPlan((recent, comboMs, minCps, maxCps, random) -> timing()
                .nextInterval(recent, comboMs, minCps, maxCps, random));
    }

    private ClickTiming timing() {
        return this.technique == TECHNIQUE_CONSTANT ? ConstantClickTiming.INSTANCE
                : this.technique == TECHNIQUE_ATLAS_HUMAN ? this.atlasTiming : this.human;
    }

    Clicker(java.util.Random random) {
        this.plan = new ClickPlan((recent, comboMs, minCps, maxCps, r) -> timing()
                .nextInterval(recent, comboMs, minCps, maxCps, r), random);
    }

    public void setEnforced(java.util.function.IntPredicate enforced) {
        this.plan.setEnforced(enforced);
    }

    public void tick(int technique, int minCps, int maxCps, int maxPerTick, long nowMs) {
        this.ticksSinceLastClick++;
        this.clickAmount = null;
        this.technique = technique;
        this.plan.setCps(Math.min(minCps, maxCps), Math.max(minCps, maxCps));
        this.plan.setMaxPerTick(maxPerTick);
        this.plan.tick(nowMs);
    }

    public boolean isClickTick() {
        return willClickAt(0);
    }

    public boolean willClickAt(int tick) {
        return this.plan.clicksAt(tick) > 0;
    }

    public int clicksAt(int tick) {
        return this.plan.clicksAt(tick);
    }

    public int ticksUntilClick() {
        for (int tick = 0; tick < TICKS_AHEAD; tick++) {
            if (willClickAt(tick)) {
                return tick;
            }
        }
        return TICKS_AHEAD;
    }

    public int ticksSinceLastClick() {
        return this.ticksSinceLastClick;
    }

    public Integer clickAmount() {
        return this.clickAmount;
    }

    public long comboMs() {
        return this.plan.comboMs();
    }

    public int click(BooleanSupplier missOk, ClickPlan.Attempt attempt) {
        int clicks = this.plan.consume(missOk, () -> {
            boolean success = attempt.run();
            if (success) {
                this.ticksSinceLastClick = 0;
            }
            return success;
        });
        this.clickAmount = (this.clickAmount == null ? 0 : this.clickAmount) + clicks;
        return clicks;
    }

    public boolean canExecuteClickNow(boolean missOk) {
        return this.plan.clicksAt(0) > 0 && missOk;
    }
}
