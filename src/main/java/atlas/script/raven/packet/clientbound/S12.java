package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

/** An entity's velocity -- knockback when it is this player -- in blocks a tick. */
public class S12 extends SPacket {
    public int entityId;
    public Vec3 motion;

    public S12(int entityId, Vec3 motion) {
        super("S12PacketEntityVelocity");
        this.entityId = entityId;
        this.motion = motion;
    }

    static S12 of(S12PacketEntityVelocity packet) {
        /* As Raven gives it: the packet's own numbers (8000 to a block a tick). One a script makes takes blocks. */
        S12 out = new S12(packet.getEntityID(), new Vec3(packet.getMotionX(), packet.getMotionY(), packet.getMotionZ()));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S12PacketEntityVelocity(this.entityId, this.motion.x, this.motion.y, this.motion.z);
    }
}
