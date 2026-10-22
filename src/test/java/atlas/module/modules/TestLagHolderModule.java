package atlas.module.modules;

import atlas.management.LagManager;

/**
 * Test helper. Lives in atlas.module.modules so ActionLedger.callerModule()
 * finds it on the stack exactly as it would a real module.
 */
public final class TestLagHolderModule {
    private TestLagHolderModule() {
    }

    public static void hold(LagManager lag, int ticks) {
        lag.setDelay(ticks);
    }
}
