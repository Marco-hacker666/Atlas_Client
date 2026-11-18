package atlas.module.modules;

import java.util.HashMap;
import java.util.Map;

final class HitSelectGate {
    static final long LAND_MS = 750L;

    static final long COMBO_MS = 1250L;

    static final long FIRST_HIT_MS = 10000L;

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

    private long noWaitUntil;

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

    synchronized void hurt(int target, long now) {
        if (target == this.lastTarget && now - this.lastAttack < LAND_MS) {
            this.combo++;
        }
    }

    synchronized void knockedBack(long now) {
        this.combo = 0;
        this.waitTarget = -1;
        this.waitStart = 0L;
        this.waitAsked = 0L;
        this.noWaitUntil = now + WAIT_FORGET_MS;
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

    synchronized boolean beats(int target, double damage) {
        Double last = this.attackDamage.get(target);
        return last != null && damage > last + SAME_DAMAGE;
    }

    synchronized boolean waiting(int target, long now, long millis) {
        if (millis <= 0L || now < this.noWaitUntil || !firstHit(target, now)) {
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
        this.noWaitUntil = 0L;
    }
}
