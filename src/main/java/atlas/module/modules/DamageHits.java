package atlas.module.modules;

import atlas.events.PacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

/**
 * AttackOnDamage, for either aura engine (2026-11-05): how many forced clicks are left from the last knockback, and
 * when they lapse. Packets arrive on another thread, hence volatile.
 */
final class DamageHits {

    /** Forced clicks not made by then are dropped. */
    static final long LAPSE_MS = 500L;
    /** Knockback this soon after the last is the same hit: it starts nothing. */
    static final long GAP_MS = 250L;

    private volatile int left;
    private volatile long until;
    private volatile long lastKnockback;

    /** Call from a handler ahead of Velocity, which cancels the packet: the hit was taken all the same. */
    void onPacket(PacketEvent event, int clicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (clicks > 0 && mc.thePlayer != null && event.getPacket() instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()) {
            knockedBack(clicks, System.currentTimeMillis());
        }
    }

    void knockedBack(int clicks, long now) {
        if (now - this.lastKnockback > GAP_MS) {
            this.left = clicks;
            this.until = now + LAPSE_MS;
        }
        this.lastKnockback = now;
    }

    boolean due(long now) {
        return this.left > 0 && now <= this.until;
    }

    void spend() {
        if (this.left > 0) {
            this.left--;
        }
    }

    void clear() {
        this.left = 0;
    }
}
