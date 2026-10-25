package atlas.lb.clicking;

import java.util.List;
import java.util.Random;

/**
 * When the next press comes (2026-10-24, LiquidBounce NextGen port, GPL-3.0;
 * net.ccbluex.liquidbounce.utils.clicking.ClickTiming).
 */
public interface ClickTiming {

    /**
     * @param recent  intervals of the current combo in ms, oldest first
     * @param comboMs time from the combo start to the press the interval starts at
     * @param minCps  bottom of the CPS range
     * @param maxCps  top of the CPS range
     * @return ms until the next press
     */
    long nextInterval(List<Long> recent, long comboMs, int minCps, int maxCps, Random random);
}
