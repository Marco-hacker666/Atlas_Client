package atlas.management;

import atlas.event.EventTarget;
import atlas.events.TickEvent;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;

public class PlayerStateManager {
    private long lastCombatActionTime = 0;
    private static final long COMBAT_TIMEOUT = 5000;
    private boolean inCombat = false;
    public boolean attacking = false;
    public boolean digging = false;
    public boolean placing = false;
    public boolean swapping = false;
    public boolean swinging = false;

    public void handlePacket(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity) {
            this.attacking = true;
            this.updateCombatState();
        }
        if (packet instanceof C07PacketPlayerDigging) {
            this.digging = true;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            this.placing = true;
        }
        if (packet instanceof C09PacketHeldItemChange) {
            this.swapping = true;
        }
        if (packet instanceof C0APacketAnimation) {
            this.swinging = true;
            this.updateCombatState();
        }
        if (packet instanceof C03PacketPlayer) {
            this.attacking = false;
            this.digging = false;
            this.placing = false;
            this.swapping = false;
            this.swinging = false;
        }
    }

    private void updateCombatState() {
        this.lastCombatActionTime = System.currentTimeMillis();
        this.inCombat = true;
    }

    public boolean isInCombat() {
        if (this.inCombat && System.currentTimeMillis() - this.lastCombatActionTime > COMBAT_TIMEOUT) {
            this.inCombat = false;
        }
        return this.inCombat;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == atlas.event.types.EventType.POST) {
            if (this.inCombat && (System.currentTimeMillis() - this.lastCombatActionTime > COMBAT_TIMEOUT)) {
                this.inCombat = false;
            }
        }
    }
}
