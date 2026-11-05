package atlas.module.modules;

import java.util.HashMap;
import java.util.Map;

/**
 * What HitSelect remembers about the fight (2026-11-05), apart from the game so it can be tested: the hits landed in a
 * row, when each target was last attacked, and how hard. Times are passed in. Packets arrive on another thread, so
 * everything here is synchronized.
 */
final class HitSelectGate {

    /** A target's hurt status this soon after our attack is our hit. */
    static final long LAND_MS = 750L;
    /** No attack for this long: the combo is over. */
    static final long COMBO_MS = 1250L;
    /** No attack on a target for this long: the next one is a first hit again. */
    static final long FIRST_HIT_MS = 10000L;
    /** A wait not asked about for this long is over: the player left that fight. */
    private static final long WAIT_FORGET_MS = 1000L;
    private static final int MAX_TARGETS = 64;
    private static final double SAME_DAMAGE = 0.01;

    private final Map<Integer, Long> attackedAt = new HashMap<Integer, Long>();
    private final Map<Integer, Double> attackDamage = new HashMap<Integer, Double>();
    private int combo;
    private int lastTarget = -1;
    private long lastAttack;
    private int waitTarget = -1;
    private long waitStart;
    private long waitAsked;

    /** The damage of a hit: a critical adds half of the base, enchantments stay out of it, as in vanilla. */
    static double damage(double base, double enchantment, boolean critical) {
        return (critical ? base * 1.5 : base) + enchantment;
    }

    synchronized void attacked(int target, double damage, long now) {
        if (this.attackedAt.size() >= MAX_TARGETS && !this.attackedAt.containsKey(target)) {
            this.attackedAt.clear();
            this.attackDamage.clear();
        }
        this.attackedAt.put(target, now);
        this.attackDamage.put(target, damage);
        this.lastTarget = target;
        this.lastAttack = now;
    }

    /** The server says this entity was hurt: a hit in the combo if it is the one just attacked. */
    synchronized void hurt(int target, long now) {
        if (target == this.lastTarget && now - this.lastAttack < LAND_MS) {
            this.combo++;
        }
    }

    /** This player took knockback: the combo is broken, and so is any wait for the other side's first hit. */
    synchronized void knockedBack() {
        this.combo = 0;
        this.waitTarget = -1;
        this.waitStart = 0L;
        this.waitAsked = 0L;
    }

    synchronized int combo(long now) {
        if (now - this.lastAttack > COMBO_MS) {
            this.combo = 0;
        }
        return this.combo;
    }

    synchronized boolean firstHit(int target, long now) {
        Long at = this.attackedAt.get(target);
        return at == null || now - at > FIRST_HIT_MS;
    }

    /** This hit would do more than the last one on the target: inside its hurt time the server takes the difference. */
    synchronized boolean beats(int target, double damage) {
        Double last = this.attackDamage.get(target);
        return last != null && damage > last + SAME_DAMAGE;
    }

    /** Before a first hit: held for this long from the first time it was asked, unless knockback ends it. */
    synchronized boolean waiting(int target, long now, long millis) {
        if (millis <= 0L || !firstHit(target, now)) {
            return false;
        }
        if (target != this.waitTarget || now - this.waitAsked > WAIT_FORGET_MS) {
            this.waitTarget = target;
            this.waitStart = now;
        }
        this.waitAsked = now;
        return now - this.waitStart < millis;
    }

    synchronized void clear() {
        this.attackedAt.clear();
        this.attackDamage.clear();
        this.combo = 0;
        this.lastTarget = -1;
        this.lastAttack = 0L;
        this.waitTarget = -1;
        this.waitStart = 0L;
        this.waitAsked = 0L;
    }
}
