package atlas.humanize;

import java.util.Random;

/**
 * One module's humanization (2026-10-25): its own accuracy, reaction, aim
 * error and stats, all read from the shared profile the Humanization module
 * publishes. A consumer (Silent Aura, KillAura2, AimAssist, AutoClicker) keeps
 * one of these and, each tick and click, asks it:
 *
 *   reacted(target)      may it aim / click at this target yet?
 *   aim(turn, speed)     the tremor, overshoot and lapse to add this tick
 *   click(situation)     land this click, or miss it (and how)?
 *   landed / missed      what really happened, for the measured rate
 *
 * With the module off (or this consumer not chosen) {@link #profile} is null
 * and the consumer behaves as it did without it.
 */
public final class Humanizer {
    public static final int CONSUMER_LIQUIDBOUNCE = 0;
    public static final int CONSUMER_ATLAS_AURA = 1;
    public static final int CONSUMER_KILLAURA2 = 2;
    public static final int CONSUMER_AIMASSIST = 3;
    public static final int CONSUMER_AUTOCLICKER = 4;
    public static final int CONSUMERS = 5;

    /** Published by the Humanization module each tick: the profile, and who it applies to. */
    private static volatile HumanProfile published;
    private static volatile boolean[] applies = new boolean[CONSUMERS];

    public static void publish(HumanProfile profile, boolean[] appliesTo) {
        published = profile;
        applies = appliesTo == null ? new boolean[CONSUMERS] : appliesTo.clone();
    }

    /** The profile for a consumer, or null when humanization is off for it. */
    public static HumanProfile profileFor(int consumer) {
        HumanProfile p = published;
        boolean[] a = applies;
        return p != null && consumer >= 0 && consumer < a.length && a[consumer] ? p : null;
    }

    /** A click's verdict. */
    public static final class Verdict {
        public final boolean land;
        /** How to miss (null when landing). */
        public final Misses.Style miss;

        public Verdict(boolean land, Misses.Style miss) {
            this.land = land;
            this.miss = miss;
        }
    }

    private final int consumer;
    private final Random random;
    private final Accuracy accuracy;
    private final Reaction reaction;
    private final AimError aimError;
    private final HumanStats stats = new HumanStats();
    private boolean wasLapsing;
    private long lastReactionLogged = -1L;

    public Humanizer(int consumer) {
        this(consumer, new Random());
    }

    public Humanizer(int consumer, Random random) {
        this.consumer = consumer;
        this.random = random;
        this.accuracy = new Accuracy(random);
        this.reaction = new Reaction(random);
        this.aimError = new AimError(random);
    }

    public HumanProfile profile() {
        return profileFor(this.consumer);
    }

    public HumanStats stats() {
        return this.stats;
    }

    public Accuracy accuracy() {
        return this.accuracy;
    }

    public void reset() {
        this.accuracy.reset();
        this.reaction.reset();
        this.aimError.reset();
    }

    /** Whether the reaction to this target is over (true when humanization is off). */
    public boolean reacted(int targetId, long now) {
        HumanProfile p = profile();
        if (p == null) {
            return true;
        }
        boolean ready = this.reaction.ready(targetId, now, p);
        if (targetId != Integer.MIN_VALUE && this.reaction.lastDrawn() != this.lastReactionLogged) {
            this.lastReactionLogged = this.reaction.lastDrawn();
            this.stats.reaction(this.reaction.lastDrawn(), now);
        }
        return ready;
    }

    /** No target this tick (for the reaction's reacquire memory). */
    public void noTarget(long now) {
        HumanProfile p = profile();
        if (p != null) {
            this.reaction.ready(Integer.MIN_VALUE, now, p);
        }
    }

    /** This tick's aim adjustment, or null when humanization is off. */
    public AimError.Adjust aim(float turnYaw, float turnPitch, float angularSpeed) {
        HumanProfile p = profile();
        if (p == null) {
            return null;
        }
        AimError.Adjust adjust = this.aimError.tick(p, turnYaw, turnPitch, angularSpeed);
        this.stats.aim(adjust);
        if (adjust.lapsing && !this.wasLapsing) {
            this.stats.lapse();
        }
        this.wasLapsing = adjust.lapsing;
        return adjust;
    }

    /** Land this click or miss it; null when humanization is off (land as usual). */
    public Verdict click(Accuracy.Situation situation, long now) {
        HumanProfile p = profile();
        if (p == null) {
            return null;
        }
        if (this.accuracy.decide(p, situation, now)) {
            return new Verdict(true, null);
        }
        return new Verdict(false, Misses.pick(p, this.random));
    }

    /** The aimed-miss turn from the box's middle (see Misses.aimOffOffset); {0, 0} when off. */
    public float[] aimOff(double distance, double halfWidth, double halfHeight) {
        HumanProfile p = profile();
        return p == null ? new float[]{0.0F, 0.0F} : Misses.aimOffOffset(p, distance, halfWidth, halfHeight, this.random);
    }

    /** A click that landed. */
    public void landed(long now) {
        this.accuracy.record(true, now);
        this.stats.hit(now);
    }

    /** A click meant to land whose ray missed. */
    public void unlucky(long now) {
        this.accuracy.record(false, now);
        this.stats.unlucky(now);
    }

    /** A planned miss, as it went out. A skip is not a click and is not counted in the rate. */
    public void missed(Misses.Style style, long now) {
        if (style != Misses.Style.SKIP) {
            this.accuracy.record(false, now);
        }
        this.stats.miss(style, now);
    }

    /** Whether this click is dropped (clickDrop %), or doubled (clickDouble %). */
    public boolean dropClick() {
        HumanProfile p = profile();
        return p != null && this.random.nextDouble() * 100.0 < p.clickDrop;
    }

    public boolean doubleClick() {
        HumanProfile p = profile();
        return p != null && this.random.nextDouble() * 100.0 < p.clickDouble;
    }
}
