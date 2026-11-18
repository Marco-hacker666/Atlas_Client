package atlas.combat;

public final class PressBurst {
    private boolean wasDown;
    private int until = Integer.MIN_VALUE;
    private int target = -1;

    public boolean update(int tick, boolean down, int lengthTicks) {
        if (down && !this.wasDown) {
            this.target = -1;
        }
        this.wasDown = down;
        if (down) {
            this.until = tick + Math.max(0, lengthTicks);
        }
        return tick <= this.until;
    }

    public void end() {
        this.until = Integer.MIN_VALUE;
        this.target = -1;
    }

    public void reset() {
        this.wasDown = false;
        this.end();
    }

    public int target() {
        return this.target;
    }

    public void lock(int entityId) {
        this.target = entityId;
    }
}
