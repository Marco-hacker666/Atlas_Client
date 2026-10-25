package atlas.lb.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the enemy to attack (2026-10-24, LiquidBounce NextGen port, GPL-3.0;
 * utils/combat/TargetTracker.kt and TargetPriority in CombatExtensions.kt).
 *
 * The module turns each loaded living entity it would attack (its own
 * "should be attacked" filters: teams, friends, bots, entity types) into a
 * {@link Candidate}; this keeps those within FOV, hurt time and range, and
 * sorts them by the priority chain (Type first, then the chosen ones).
 *
 * @param <E> the entity type (a plain object in tests)
 */
public final class TargetTracker<E> {
    public static final int PRIORITY_TYPE = 0;
    public static final int PRIORITY_HEALTH = 1;
    public static final int PRIORITY_DISTANCE = 2;
    public static final int PRIORITY_DIRECTION = 3;
    public static final int PRIORITY_HURT_TIME = 4;
    public static final int PRIORITY_AGE = 5;

    /** What the selector needs to know about one entity. */
    public static final class Candidate<E> {
        public final E entity;
        public final boolean player;
        public final boolean monster;
        public final float health;
        public final double distanceSq;
        /** Cosine of the angle between the look and the direction to the entity. */
        public final double crosshairCos;
        public final int hurtTime;
        public final int age;

        public Candidate(E entity, boolean player, boolean monster, float health, double distanceSq,
                         double crosshairCos, int hurtTime, int age) {
            this.entity = entity;
            this.player = player;
            this.monster = monster;
            this.health = health;
            this.distanceSq = distanceSq;
            this.crosshairCos = crosshairCos;
            this.hurtTime = hurtTime;
            this.age = age;
        }

        int typeWeight() {
            return this.player ? 0 : (this.monster ? 1 : Integer.MAX_VALUE);
        }
    }

    public float fov = 180.0F;
    public int maxHurtTime = 10;
    /** Range limit (blocks), or a negative value for none. */
    public float range = -1.0F;
    /** Priority chain after Type: PRIORITY_* values, in order. */
    public int[] priorities = {PRIORITY_HEALTH};

    private E target;
    private double closestSquaredEnemyDistance;

    public E target() {
        return this.target;
    }

    public void setTarget(E target) {
        this.target = target;
    }

    public void reset() {
        this.target = null;
    }

    public double closestSquaredEnemyDistance() {
        return this.closestSquaredEnemyDistance;
    }

    public boolean validate(Candidate<E> candidate) {
        if (candidate.hurtTime > this.maxHurtTime) {
            return false;
        }
        if (this.range >= 0.0F && candidate.distanceSq > (double) this.range * this.range) {
            return false;
        }
        return this.fov >= 180.0F || Math.cos(Math.toRadians(this.fov)) <= candidate.crosshairCos;
    }

    /** The valid candidates, best first. */
    public List<Candidate<E>> targets(List<Candidate<E>> all) {
        List<Candidate<E>> out = new ArrayList<Candidate<E>>();
        for (Candidate<E> candidate : all) {
            if (validate(candidate)) {
                out.add(candidate);
            }
        }
        if (out.isEmpty()) {
            return out;
        }
        out.sort(comparator());
        double closest = Double.MAX_VALUE;
        for (Candidate<E> candidate : out) {
            closest = Math.min(closest, candidate.distanceSq);
        }
        this.closestSquaredEnemyDistance = closest;
        return out;
    }

    private Comparator<Candidate<E>> comparator() {
        Comparator<Candidate<E>> chain = Comparator.<Candidate<E>>comparingInt(c -> c.typeWeight());
        for (int priority : this.priorities) {
            chain = chain.thenComparing(by(priority));
        }
        return chain;
    }

    private Comparator<Candidate<E>> by(int priority) {
        switch (priority) {
            case PRIORITY_HEALTH:
                return (a, b) -> Float.compare(a.health, b.health);
            case PRIORITY_DISTANCE:
                return (a, b) -> Double.compare(a.distanceSq, b.distanceSq);
            case PRIORITY_DIRECTION:
                return (a, b) -> Double.compare(b.crosshairCos, a.crosshairCos);
            case PRIORITY_HURT_TIME:
                return (a, b) -> Integer.compare(a.hurtTime, b.hurtTime);
            case PRIORITY_AGE:
                return (a, b) -> Integer.compare(b.age, a.age);
            default:
                return (a, b) -> 0;
        }
    }
}
