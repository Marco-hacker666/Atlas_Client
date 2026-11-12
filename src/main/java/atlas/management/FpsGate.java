package atlas.management;

/**
 * Whether the game runs too slowly for a background check to be worth its cost (2026-11-12, taken out of MoveCheck).
 * Given the frame rate once a second: slow after {@code lowSeconds} in a row under {@code low}, fine again after
 * {@code highSeconds} in a row at {@code high} or more. The gap between the two keeps the check's own small cost from
 * flipping it back and forth.
 */
final class FpsGate {
    private final int low;
    private final int lowSeconds;
    private final int high;
    private final int highSeconds;
    private boolean slow;
    private int run;

    FpsGate(int low, int lowSeconds, int high, int highSeconds) {
        this.low = low;
        this.lowSeconds = lowSeconds;
        this.high = high;
        this.highSeconds = highSeconds;
    }

    /** One second's frame rate. */
    void sample(int fps) {
        if (!this.slow) {
            this.run = fps < this.low ? this.run + 1 : 0;
            if (this.run >= this.lowSeconds) {
                this.slow = true;
                this.run = 0;
            }
        } else {
            this.run = fps >= this.high ? this.run + 1 : 0;
            if (this.run >= this.highSeconds) {
                this.slow = false;
                this.run = 0;
            }
        }
    }

    boolean slow() {
        return this.slow;
    }
}
