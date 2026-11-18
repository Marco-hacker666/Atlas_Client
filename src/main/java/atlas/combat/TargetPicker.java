package atlas.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TargetPicker {
    public enum Mode { SINGLE, SWITCH }

    public enum Sort { DISTANCE, HEALTH, ANGLE, HURT_TIME }

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

    public void force(Object key, long now) {
        if (key != this.current) {
            this.lastSwitch = now;
        }
        this.current = key;
    }

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
