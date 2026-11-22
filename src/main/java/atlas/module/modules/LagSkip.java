package atlas.module.modules;

import java.util.Random;

final class LagSkip {
    private final Random random;

    private int left = -1;
    private boolean skipping;

    LagSkip() {
        this(new Random());
    }

    LagSkip(Random random) {
        this.random = random;
    }

    boolean startSkips(int every) {
        if (every <= 0) {
            this.left = -1;
            return false;
        }
        if (this.skipping) {
            return true;
        }
        if (this.left < 0) {
            this.left = every + this.random.nextInt(2);
        }
        if (this.left == 0) {
            this.left = -1;
            this.skipping = true;
            return true;
        }
        this.left--;
        return false;
    }

    boolean skipping() {
        return this.skipping;
    }

    void approachOver() {
        this.skipping = false;
    }
}
