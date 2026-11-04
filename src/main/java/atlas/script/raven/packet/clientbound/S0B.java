package atlas.script.raven.packet.clientbound;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S0BPacketAnimation;

/** An entity's animation: 0 swing, 1 hurt, 2 wake up, 4 critical, 5 magic critical. */
public class S0B extends SPacket {
    public int entityId;
    public int type;

    public S0B(int entityId, int type) {
        super("S0BPacketAnimation");
        this.entityId = entityId;
        this.type = type;
    }

    static S0B of(S0BPacketAnimation packet) {
        S0B out = new S0B(packet.getEntityID(), packet.getAnimationType());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        Minecraft mc = Minecraft.getMinecraft();
        Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(this.entityId);
        return entity == null ? this.packet : new S0BPacketAnimation(entity, this.type);
    }
}
