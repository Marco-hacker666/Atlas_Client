package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.IntProperty;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C16PacketClientStatus;

/** Sends the vanilla respawn request after death, with an optional short delay. */
public class AutoRespawn extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty delayTicks = new IntProperty("delay-ticks", 0, 0, 60);
    private boolean requested;

    public AutoRespawn() {
        super("AutoRespawn", false, false, "Automatically respawns after death.");
    }

    @Override
    public void onEnabled() {
        this.requested = false;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) return;

        if (!mc.thePlayer.isDead && mc.thePlayer.getHealth() > 0.0F) {
            this.requested = false;
            return;
        }
        if (this.requested || mc.thePlayer.deathTime < this.delayTicks.getValue()
                || mc.getNetHandler() == null) return;

        PacketUtil.sendPacket(new C16PacketClientStatus(
                C16PacketClientStatus.EnumState.PERFORM_RESPAWN));
        this.requested = true;
    }
}
