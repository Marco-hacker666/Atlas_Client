package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S2APacketParticles;
import net.minecraft.util.EnumParticleTypes;

import java.util.Locale;

/** Particles: type is the particle's name (CRIT, FLAME, REDSTONE ...). */
public class S2A extends SPacket {
    public String type;
    public Vec3 position;
    public Vec3 offset;
    public float speed;
    public int count;
    public boolean longDistance;
    public int[] args;

    public S2A(String type, boolean longDistance, Vec3 position, Vec3 offset, float speed, int count, int[] args) {
        super("S2APacketParticles");
        this.type = type;
        this.longDistance = longDistance;
        this.position = position;
        this.offset = offset;
        this.speed = speed;
        this.count = count;
        this.args = args == null ? new int[0] : args;
    }

    static S2A of(S2APacketParticles packet) {
        S2A out = new S2A(packet.getParticleType().name(), packet.isLongDistance(),
                new Vec3(packet.getXCoordinate(), packet.getYCoordinate(), packet.getZCoordinate()),
                new Vec3(packet.getXOffset(), packet.getYOffset(), packet.getZOffset()), packet.getParticleSpeed(),
                packet.getParticleCount(), packet.getParticleArgs());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S2APacketParticles(EnumParticleTypes.valueOf(this.type.toUpperCase(Locale.ROOT)), this.longDistance,
                (float) this.position.x, (float) this.position.y, (float) this.position.z, (float) this.offset.x,
                (float) this.offset.y, (float) this.offset.z, this.speed, this.count, this.args);
    }
}
