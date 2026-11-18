package atlas.humanize;

import java.util.Random;

public final class Humanizer {
    public static final int CONSUMER_LIQUIDBOUNCE = 0;
    public static final int CONSUMER_ATLAS_AURA = 1;
    public static final int CONSUMER_KILLAURA2 = 2;
    public static final int CONSUMER_AIMASSIST = 3;
    public static final int CONSUMER_AUTOCLICKER = 4;
    public static final int CONSUMER_HITFLICK = 5;
    public static final int CONSUMER_SCAFFOLD = 6;
    public static final int CONSUMERS = 7;

    private static volatile HumanProfile published;
    private static volatile boolean[] applies = new boolean[CONSUMERS];

    public static void publish(HumanProfile profile, boolean[] appliesTo) {
        published = profile;
        applies = appliesTo == null ? new boolean[CONSUMERS] : appliesTo.clone();
    }

    public static HumanProfile profileFor(int consumer) {
        HumanProfile p = published;
        boolean[] a = applies;
        return p != null && consumer >= 0 && consumer < a.length && a[consumer] ? p : null;
    }

    public static final class Verdict {
        public final boolean land;

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

    public void noTarget(long now) {
        HumanProfile p = profile();
        if (p != null) {
            this.reaction.ready(Integer.MIN_VALUE, now, p);
        }
    }

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

    public float[] aimOff(double distance, double halfWidth, double halfHeight) {
        HumanProfile p = profile();
        return p == null ? new float[]{0.0F, 0.0F} : Misses.aimOffOffset(p, distance, halfWidth, halfHeight, this.random);
    }

    public void landed(long now) {
        this.accuracy.record(true, now);
        this.stats.hit(now);
    }

    public void unlucky(long now) {
        this.accuracy.record(false, now);
        this.stats.unlucky(now);
    }

    public void missed(Misses.Style style, long now) {
        if (style != Misses.Style.SKIP) {
            this.accuracy.record(false, now);
        }
        this.stats.miss(style, now);
    }

    public boolean dropClick() {
        HumanProfile p = profile();
        return p != null && this.random.nextDouble() * 100.0 < p.clickDrop;
    }

    public boolean doubleClick() {
        HumanProfile p = profile();
        return p != null && this.random.nextDouble() * 100.0 < p.clickDouble;
    }
}
