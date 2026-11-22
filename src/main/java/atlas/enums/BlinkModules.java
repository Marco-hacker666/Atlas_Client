package atlas.enums;

public enum BlinkModules {
    NONE("BlinkManager", 0L),

    ANTI_VOID("AntiVoid", 10000L),

    AUTO_BLOCK("KillAura", 2000L),

    AUTO_BLOCK_LAG("AutoBlock", 1000L),

    BLINK("Blink", -1L),
    DISPLACE("Displace", 2000L),
    HITFLICK("Hitflick", 2000L),
    INVULN_BLINK("InvulnBlink", 1000L),

    NO_FALL("NoFall", 10000L),

    SCAFFOLD("Scaffold", 5000L),
    NO_SLOW("NoSlow", 2000L),

    SMART_BLINKER("SmartBlinker", 5000L),

    RHYTHM("Silent Aura", 1000L);

    private final String moduleName;
    private final long leaseMs;

    BlinkModules(String moduleName, long leaseMs) {
        this.moduleName = moduleName;
        this.leaseMs = leaseMs;
    }

    public String moduleName() {
        return this.moduleName;
    }

    public long leaseMs() {
        return this.leaseMs;
    }
}
