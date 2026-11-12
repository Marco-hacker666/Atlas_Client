package atlas.sim;

import java.util.function.BooleanSupplier;

/**
 * What acted on the player's motion between two movement packets other than its keys (2026-11-12; this was fields
 * and a dozen lines spread through MoveCheck, with nothing testing them): the attacks sent (each may take the motion
 * across to 0.6), a knockback (it replaces the motion) and explosions (they add to it). A server that re-runs a move
 * allows for each of these, and so does {@link SelfCheck}, given what {@link #close} hands it.
 *
 * A window is the stretch from one movement packet to the next. The packets are seen as they pass, on whatever thread
 * that is; {@link #close} ends the window on the client thread, once the move that follows it is made, and then
 * comes {@link #settle} (the move was checked) or {@link #skip} (it was not).
 *
 * A knockback is the hard one. The packet is seen on the network thread the moment it arrives; the game applies it on
 * the client thread at the start of the next frame. Usually that is before the next move, but one that arrives while
 * a tick is running is applied a move later. So it is offered to the move of the window it arrived in and, not taken
 * there, once more to the next. Taken by neither is what a server checking knockback flags ({@link Knockback#IGNORED}).
 * "Taken" means some input with the knockback explains the move, whatever explains it without: against a wall both
 * can end in the same place.
 *
 * The limit: when the game runs several ticks in one frame (after a hitch) a knockback that arrives during the first
 * is applied only after them all, later than the two moves it is offered to.
 */
public final class Impulses {

    /** What became of the knockback offered to the move just checked. */
    public enum Knockback {
        /** None was on offer. */
        NONE,
        /** An input with it explains the move. */
        TAKEN,
        /** Not in this move; the next one is offered it once more. */
        WAITING,
        /** Offered to two moves and in neither. */
        IGNORED
    }

    private final Object lock = new Object();
    /* The open window: written as packets pass (any thread), taken whole by close(). Guarded by lock, so a knockback
       and the explosions it resets change together (review, PR #38). */
    private int attacks;
    private double[] velocity;
    private double[] explosion;
    /** The explosions since the last knockback of the window: a knockback replaces the motion, so with it taken only
        these still push. All of the window's when it has no knockback. */
    private double[] explosionAfterVelocity;

    /* From here on: the client thread only. What the last window left for one more offer... */
    private double[] velocityCarry;
    private double[] explosionCarry;
    private double[] explosionAfterCarry;
    /* ...and what the move being checked was offered. */
    private double[] velocityOffer;
    private boolean velocityOfferLast;

    /** An attack was sent. */
    public void attack() {
        synchronized (this.lock) {
            this.attacks++;
        }
    }

    /** A knockback for this player arrived: the motion the server set, blocks a tick. */
    public void velocity(double x, double y, double z) {
        synchronized (this.lock) {
            this.velocity = new double[]{x, y, z};
            this.explosionAfterVelocity = null;
        }
    }

    /** An explosion's push arrived. Two in one window both push, as the game adds each. */
    public void explosion(double x, double y, double z) {
        synchronized (this.lock) {
            this.explosion = plus(this.explosion, x, y, z);
            this.explosionAfterVelocity = plus(this.explosionAfterVelocity, x, y, z);
        }
    }

    private static double[] plus(double[] sum, double x, double y, double z) {
        return sum == null ? new double[]{x, y, z} : new double[]{sum[0] + x, sum[1] + y, sum[2] + z};
    }

    /** Forgets everything: a new world, or the check starting again. Client thread. */
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

    /**
     * Ends the window (client thread, once a tick, after the move): what came in since the last call, and what the
     * last window left untaken, as the things SelfCheck may use to explain the move just made. Follow it with
     * {@link #settle} when that move is checked, {@link #skip} when it is not.
     */
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
        /* With a knockback this window, only what came after it goes with it; without, the explosions waiting do. */
        extra.explosionAfterVelocity = velocity != null ? explosionAfter
                : explosion != null ? explosion : this.explosionAfterCarry;
        extra.ordered = true;
        this.velocityCarry = velocity;
        this.explosionCarry = explosion;
        this.explosionAfterCarry = velocity != null ? explosionAfter : explosion;
        return extra;
    }

    /**
     * The move {@link #close} was for is not checked (right after a correction, or a move the simulation does not
     * cover): nothing of its window waits for the next move. Whatever came in was most likely applied in the move
     * nobody looked at, and offered on it would end as "ignored" by a move it never belonged to. Client thread.
     */
    public void skip() {
        this.velocityCarry = null;
        this.explosionCarry = null;
        this.explosionAfterCarry = null;
        this.velocityOffer = null;
        this.velocityOfferLast = false;
    }

    /**
     * After the check of the move {@link #close} was for (client thread): what that move took is not offered again.
     *
     * @param result           the check's answer for the move, made with what close() gave
     * @param fitsWithVelocity whether some input with the knockback explains the move too
     *                         ({@link SelfCheck#fitsWithVelocity}); asked only when the move fits without it
     */
    public Knockback settle(SelfCheck.Result result, BooleanSupplier fitsWithVelocity) {
        boolean fits = result.offset <= SelfCheck.OFF;
        if (fits && result.explosion) {
            this.explosionCarry = null;
            this.explosionAfterCarry = null;
        }
        if (this.velocityOffer == null) {
            return Knockback.NONE;
        }
        /* Nothing fitting at all leaves nothing to ask: no input with the knockback fits either. */
        if (fits && (result.velocity || fitsWithVelocity.getAsBoolean())) {
            this.velocityCarry = null;
            /* The knockback replaced the motion: an explosion from before it is gone with it, and must not be
               offered to the next move. What came after it still may be (review, PR #39). */
            this.explosionCarry = this.explosionAfterCarry;
            return Knockback.TAKEN;
        }
        return this.velocityOfferLast ? Knockback.IGNORED : Knockback.WAITING;
    }
}
