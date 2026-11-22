package atlas.module.modules;

import java.util.ArrayDeque;
import java.util.Iterator;

final class FightPhase {
    enum Phase {
        IDLE,
        APPROACH,
        COMBO,
        TRADE,
        COMBOED
    }

    static final int LAND_WINDOW = 12;

    private final ArrayDeque<int[]> swings = new ArrayDeque<int[]>();
    private int ourStreak;
    private int theirStreak;
    private int lastExchange = Integer.MIN_VALUE / 2;
    private boolean lastWasOurs;
    private Phase phase = Phase.IDLE;

    void attackSent(int entityId, int tick) {
        this.swings.addLast(new int[]{entityId, tick});
        while (this.swings.size() > 32) {
            this.swings.removeFirst();
        }
    }

    boolean opponentHurt(int entityId, int tick) {
        Iterator<int[]> it = this.swings.iterator();
        boolean ours = false;
        while (it.hasNext()) {
            int[] swing = it.next();
            if (tick - swing[1] > LAND_WINDOW) {
                it.remove();
            } else if (swing[0] == entityId) {
                ours = true;
                it.remove();
            }
        }
        if (ours) {
            this.ourStreak++;
            this.theirStreak = 0;
            this.lastExchange = tick;
            this.lastWasOurs = true;
        }
        return ours;
    }

    void weWereHit(int tick) {
        this.theirStreak++;
        this.ourStreak = 0;
        this.lastExchange = tick;
        this.lastWasOurs = false;
    }

    Phase tick(int tick, boolean enemyNear, int memory, int comboedHits) {
        if (tick - this.lastExchange > memory) {
            this.ourStreak = 0;
            this.theirStreak = 0;
            this.phase = enemyNear ? Phase.APPROACH : Phase.IDLE;
            return this.phase;
        }
        if (this.theirStreak >= comboedHits) {
            this.phase = Phase.COMBOED;
        } else if (this.lastWasOurs) {
            this.phase = Phase.COMBO;
        } else {
            this.phase = Phase.TRADE;
        }
        return this.phase;
    }

    Phase phase() {
        return this.phase;
    }

    int ourStreak() {
        return this.ourStreak;
    }

    int theirStreak() {
        return this.theirStreak;
    }

    void reset() {
        this.swings.clear();
        this.ourStreak = 0;
        this.theirStreak = 0;
        this.lastExchange = Integer.MIN_VALUE / 2;
        this.lastWasOurs = false;
        this.phase = Phase.IDLE;
    }
}
