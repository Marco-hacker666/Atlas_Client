package atlas.script.raven.packet.serverbound;

import atlas.script.raven.model.Entity;
import atlas.script.raven.model.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;

/** Using an entity: action "ATTACK", "INTERACT" or "INTERACT_AT" (with hitVec, relative to the entity). */
public class C02 extends CPacket {
    public Entity entity;
    public String action;
    public Vec3 hitVec;

    public C02(Entity entity, String action, Vec3 hitVec) {
        super("C02PacketUseEntity");
        this.entity = entity;
        this.action = action;
        this.hitVec = hitVec;
    }

    static C02 of(C02PacketUseEntity packet) {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.util.Vec3 hit = packet.getHitVec();
        C02 out = new C02(mc.theWorld == null ? null : Entity.convert(packet.getEntityFromWorld(mc.theWorld)),
                packet.getAction().name(), hit == null ? null : new Vec3(hit));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        C02PacketUseEntity.Action kind = C02PacketUseEntity.Action.valueOf(this.action.toUpperCase(java.util.Locale.ROOT));
        if (kind == C02PacketUseEntity.Action.INTERACT_AT && this.hitVec != null) {
            return new C02PacketUseEntity(this.entity.entity, Vec3.getVec3(this.hitVec));
        }
        return new C02PacketUseEntity(this.entity.entity, kind);
    }
}
