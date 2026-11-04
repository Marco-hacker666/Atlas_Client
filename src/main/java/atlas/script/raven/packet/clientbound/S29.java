package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S29PacketSoundEffect;

/** A sound played at a position ("random.explode", "mob.zombie.hurt" ...). */
public class S29 extends SPacket {
    public String sound;
    public Vec3 position;
    public float volume;
    public float pitch;

    public S29(String sound, Vec3 position, float volume, float pitch) {
        super("S29PacketSoundEffect");
        this.sound = sound;
        this.position = position;
        this.volume = volume;
        this.pitch = pitch;
    }

    static S29 of(S29PacketSoundEffect packet) {
        S29 out = new S29(packet.getSoundName(), new Vec3(packet.getX(), packet.getY(), packet.getZ()),
                packet.getVolume(), packet.getPitch());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S29PacketSoundEffect(this.sound, this.position.x, this.position.y, this.position.z, this.volume,
                this.pitch);
    }
}
