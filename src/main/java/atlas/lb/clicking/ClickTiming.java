package atlas.lb.clicking;

import java.util.List;
import java.util.Random;

// When the next press comes (LiquidBounce NextGen port, GPL-3.0)

public interface ClickTiming {
    long nextInterval(List<Long> recent, long comboMs, int minCps, int maxCps, Random random);
}
