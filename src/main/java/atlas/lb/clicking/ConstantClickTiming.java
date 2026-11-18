package atlas.lb.clicking;

import java.util.List;
import java.util.Random;

public final class ConstantClickTiming implements ClickTiming {
    public static final ConstantClickTiming INSTANCE = new ConstantClickTiming();

    private ConstantClickTiming() {
    }

    @Override
    public long nextInterval(List<Long> recent, long comboMs, int minCps, int maxCps, Random random) {
        return Math.round(1000.0 / maxCps);
    }
}
