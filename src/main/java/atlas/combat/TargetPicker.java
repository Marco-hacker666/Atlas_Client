package atlas.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chooses the target among valid candidates, without game types.
 *
 * <ul>
 *   <li>Candidates the hit can reach come before ones only in scan range
 *       (LiquidBounce: aim before reach, but never chase a far one while a
 *       near one can be hit).</li>
 *   <li>SINGLE keeps the current target while it is a candidate, unless it
 *       left reach and another is in it.</li>
 *   <li>SWITCH moves to the next candidate in reach once the switch delay
 *       has passed since the last switch, so damage is spread.</li>
 * </ul>
 */
public final class TargetPicker {
    public enum Mode { SINGLE, SWITCH }

    public enum Sort { DISTANCE, HEALTH, ANGLE, HURT_TIME }

    /** One valid entity, measured this tick. */
    public static final class Candidate {
        public final Object key;
        public final double distance;
        public final double health;
        public final double angle;
        public final int hurtTime;
        public final boolean inReach;

        public Candidate(Object key, double distance, double health, double angle, int hurtTime, boolean inReach) {
            this.key = key;
            this.distance = distance;
            this.health = health;
            this.angle = angle;
            this.hurtTime = hurtTime;
            this.inReach = inReach;
        }
    }

    private Object current;
    private long lastSwitch = Long.MIN_VALUE / 2;

    public Object current() {
        return this.current;
    }

    public void reset() {
        this.current = null;
    }

    /** The hit went to another valid entity along the look (raycast): it is the target now. */
    public void force(Object key, long now) {
        if (key != this.current) {
            this.lastSwitch = now;
        }
        this.current = key;
    }

    /** The target for this tick, or null; {@code now} in milliseconds. */
    public Object pick(List<Candidate> candidates, Mode mode, Sort sort, long switchDelayMs, long now) {
        if (candidates.isEmpty()) {
            this.current = null;
            return null;
        }
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(order(sort));
        Candidate best = ordered.get(0);
        Candidate held = null;
        for (Candidate c : ordered) {
            if (c.key == this.current) {
                held = c;
                break;
            }
        }

        Object chosen;
        if (held == null) {
            chosen = best.key;
        } else if (!held.inReach && best.inReach) {
            chosen = best.key;
        } else if (mode == Mode.SWITCH && now - this.lastSwitch >= switchDelayMs) {
            chosen = nextInReach(ordered, held);
        } else {
            chosen = held.key;
        }
        if (chosen != this.current) {
            this.lastSwitch = now;
        }
        this.current = chosen;
        return chosen;
    }

    /** The first candidate in reach after {@code held} in the order, wrapping; {@code held} if it is alone. */
    private static Object nextInReach(List<Candidate> ordered, Candidate held) {
        int start = ordered.indexOf(held);
        for (int i = 1; i < ordered.size(); i++) {
            Candidate c = ordered.get((start + i) % ordered.size());
            if (c.inReach) {
                return c.key;
            }
        }
        return held.key;
    }

    private static Comparator<Candidate> order(Sort sort) {
        Comparator<Candidate> reach = Comparator.comparing(c -> !c.inReach);
        Comparator<Candidate> by;
        switch (sort) {
            case HEALTH:
                by = Comparator.comparingDouble(c -> c.health);
                break;
            case ANGLE:
                by = Comparator.comparingDouble(c -> c.angle);
                break;
            case HURT_TIME:
                by = Comparator.comparingInt(c -> c.hurtTime);
                break;
            default:
                by = Comparator.comparingDouble(c -> c.distance);
                break;
        }
        return reach.thenComparing(by).thenComparingDouble(c -> c.distance);
    }
}
