package atlas.management;

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
