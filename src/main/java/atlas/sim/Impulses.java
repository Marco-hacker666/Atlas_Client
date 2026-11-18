package atlas.sim;

import java.util.function.BooleanSupplier;

public final class Impulses {
    public enum Knockback {
        NONE,

        TAKEN,

        WAITING,

        IGNORED
    }

    private final Object lock = new Object();

    private int attacks;
    private double[] velocity;
    private double[] explosion;

    private double[] explosionAfterVelocity;

    private double[] velocityCarry;
    private double[] explosionCarry;
    private double[] explosionAfterCarry;

    private double[] velocityOffer;
    private boolean velocityOfferLast;

    public void attack() {
        synchronized (this.lock) {
            this.attacks++;
        }
    }

    public void velocity(double x, double y, double z) {
        synchronized (this.lock) {
            this.velocity = new double[]{x, y, z};
            this.explosionAfterVelocity = null;
        }
    }

    public void explosion(double x, double y, double z) {
        synchronized (this.lock) {
            this.explosion = plus(this.explosion, x, y, z);
            this.explosionAfterVelocity = plus(this.explosionAfterVelocity, x, y, z);
        }
    }

    private static double[] plus(double[] sum, double x, double y, double z) {
        return sum == null ? new double[]{x, y, z} : new double[]{sum[0] + x, sum[1] + y, sum[2] + z};
    }

    public void clear() {
        synchronized (this.lock) {
            this.attacks = 0;
            this.velocity = null;
            this.explosion = null;
            this.explosionAfterVelocity = null;
        }
        this.velocityCarry = null;
        this.explosionCarry = null;
        this.explosionAfterCarry = null;
        this.velocityOffer = null;
        this.velocityOfferLast = false;
    }

    public SelfCheck.Extra close() {
        int attacks;
        double[] velocity;
        double[] explosion;
        double[] explosionAfter;
        synchronized (this.lock) {
            attacks = this.attacks;
            velocity = this.velocity;
            explosion = this.explosion;
            explosionAfter = this.explosionAfterVelocity;
            this.attacks = 0;
            this.velocity = null;
            this.explosion = null;
            this.explosionAfterVelocity = null;
        }
        SelfCheck.Extra extra = new SelfCheck.Extra();
        extra.attacks = attacks;
        this.velocityOfferLast = velocity == null && this.velocityCarry != null;
        this.velocityOffer = velocity != null ? velocity : this.velocityCarry;
        extra.velocity = this.velocityOffer;
        extra.explosion = explosion != null ? explosion : this.explosionCarry;

        extra.explosionAfterVelocity = velocity != null ? explosionAfter
                : explosion != null ? explosion : this.explosionAfterCarry;
        extra.ordered = true;
        this.velocityCarry = velocity;
        this.explosionCarry = explosion;
        this.explosionAfterCarry = velocity != null ? explosionAfter : explosion;
        return extra;
    }

    public void skip() {
        this.velocityCarry = null;
        this.explosionCarry = null;
        this.explosionAfterCarry = null;
        this.velocityOffer = null;
        this.velocityOfferLast = false;
    }

    public Knockback settle(SelfCheck.Result result, BooleanSupplier fitsWithVelocity) {
        boolean fits = result.offset <= SelfCheck.OFF;
        if (fits && result.explosion) {
            this.explosionCarry = null;
            this.explosionAfterCarry = null;
        }
        if (this.velocityOffer == null) {
            return Knockback.NONE;
        }

        if (fits && (result.velocity || fitsWithVelocity.getAsBoolean())) {
            this.velocityCarry = null;

            this.explosionCarry = this.explosionAfterCarry;
            return Knockback.TAKEN;
        }
        return this.velocityOfferLast ? Knockback.IGNORED : Knockback.WAITING;
    }
}
