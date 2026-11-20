package atlas.mommy;

public final class PanicTrigger {
    public static final int DOUBLE_TAP = 0;
    public static final int HOLD = 1;
    public static final int SINGLE = 2;
    public static final String[] MODES = {"Double tap", "Hold", "Single"};
    static final long DOUBLE_MS = 400L;
    static final long HOLD_MS = 300L;

    private boolean wasDown;
    private long firstPress = -1L;
    private long downSince = -1L;
    private boolean held;

    public boolean feed(int mode, boolean down, long now) {
        boolean pressed = down && !this.wasDown;
        this.wasDown = down;
        switch (mode) {
            case SINGLE:
                return pressed;
            case HOLD:
                if (pressed) {
                    this.downSince = now;
                    this.held = false;
                }
                if (!down) {
                    this.downSince = -1L;
                    this.held = false;
                    return false;
                }
                if (!this.held && this.downSince >= 0L && now - this.downSince >= HOLD_MS) {
                    this.held = true;
                    return true;
                }
                return false;
            default:
                if (!pressed) {
                    return false;
                }
                if (this.firstPress >= 0L && now - this.firstPress <= DOUBLE_MS) {
                    this.firstPress = -1L;
                    return true;
                }
                this.firstPress = now;
                return false;
        }
    }

    public void reset() {
        this.firstPress = -1L;
        this.downSince = -1L;
        this.held = false;
    }
}
