package atlas.module.modules;

import atlas.management.LagManager;

public final class TestLagHolderModule {
    private TestLagHolderModule() {
    }

    public static void hold(LagManager lag, int ticks) {
        lag.setDelay(ticks);
    }
}
