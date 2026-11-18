package atlas.module.modules;

import atlas.events.PacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

final class DamageHits {
    static final long LAPSE_MS = 500L;

    static final long GAP_MS = 250L;

    private volatile int left;
    private volatile long until;
    private volatile long lastKnockback;

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
