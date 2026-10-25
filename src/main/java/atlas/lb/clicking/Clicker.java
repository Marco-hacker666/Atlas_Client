package atlas.lb.clicking;

import java.util.function.BooleanSupplier;

/**
 * Click scheduling for a module (2026-10-24, LiquidBounce NextGen port,
 * GPL-3.0; Clicker.kt).
 *
 * The module calls {@link #tick} once at the start of every client tick with
 * its settings, asks {@link #isClickTick} / {@link #willClickAt} whether it
 * clicks now or n ticks ahead, and spends this tick's presses with
 * {@link #click}. 1.8 has no attack cooldown, so the item-cooldown part of
 * LiquidBounce's clicker is left out; the miss delay (vanilla's
 * leftClickCounter after a swing at the air) is the gate a press can fail.
 */
public final class Clicker {
    public static final int TECHNIQUE_HUMAN = 0;
    public static final int TECHNIQUE_CONSTANT = 1;
    /** Atlas's HumanClicks (AutoClicker's Human), for humanization's human-clicks. */
    public static final int TECHNIQUE_ATLAS_HUMAN = 2;
    private static final int TICKS_AHEAD = 20;

    private int technique = TECHNIQUE_HUMAN;
    private final HumanClickTiming human = new HumanClickTiming();
    private final atlas.util.HumanClicks atlasHuman = new atlas.util.HumanClicks();
    /* HumanClicks wants a clock: the wall time at the combo's start plus the planned press's offset. */
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

    /** Package-private: a plan on a fixed seed, for tests. */
    Clicker(java.util.Random random) {
        this.plan = new ClickPlan((recent, comboMs, minCps, maxCps, r) -> timing()
                .nextInterval(recent, comboMs, minCps, maxCps, r), random);
    }

    /** Start of a client tick: the settings for this tick and the time. */
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

    /** Ticks until the next press, up to 20. */
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

    /** Presses used in this tick so far, or null before any. */
    public Integer clickAmount() {
        return this.clickAmount;
    }

    public long comboMs() {
        return this.plan.comboMs();
    }

    /**
     * Spends this tick's presses. A press during the miss delay ({@code missOk}
     * false) is dropped; {@code attempt} returns whether the click counted.
     */
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

    /** Whether a click can go out right now, with the same gate as {@link #click}. */
    public boolean canExecuteClickNow(boolean missOk) {
        return this.plan.clicksAt(0) > 0 && missOk;
    }
}
