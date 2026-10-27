package atlas.module.modules;

import atlas.module.Module;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.server.S00PacketKeepAlive;

/**
 * Keep-alives skip every lag queue, both ways (2026-10-19, Slinky's Ping Fix
 * rebuilt).
 *
 * The ping in the tab list is the keep-alive round trip. While a module
 * holds packets, a keep-alive caught in the queue makes that ping jump by
 * the whole hold, which is visible to everyone. With this on, PacketHub
 * refuses every holder a keep-alive, so it goes straight through and the
 * ping shown stays your real one. (Blink, Lag and the delay manager already
 * let them through; this covers the modules that hold through PacketHub:
 * FakeLag, LagRange, Backtrack, KnockbackDelay, ServerLag, TimerRange,
 * Freeze, NoSlow's blink modes, Criticals, PingSpoof, Disabler.)
 *
 * 2026-10-27 check: every holder of keep-alives goes through PacketHub or
 * never holds them (BlinkManager, LagManager, DelayManager, KnockbackDelay,
 * Velocity Lag, SmartBlinker); Silent Aura's Rhythm-Lag holds transaction
 * replies but never keep-alives. Disabler's Cancel C00 and its Polar preset
 * dropped keep-alives outright, past PacketHub: they now ask exempt() too.
 */
public class PingFix extends Module {
    private static volatile boolean on;

    public PingFix() {
        super("PingFix", false);
    }

    /** True when this packet must not be held by anyone. */
    public static boolean exempt(Packet<?> packet) {
        return on && (packet instanceof C00PacketKeepAlive || packet instanceof S00PacketKeepAlive);
    }

    @Override
    public void onEnabled() {
        on = true;
    }

    @Override
    public void onDisabled() {
        on = false;
    }
}
