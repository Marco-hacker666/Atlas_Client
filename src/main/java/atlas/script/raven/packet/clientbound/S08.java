package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The server setting the player's position and look; enumFlags names the relative parts (X, Y, Z, Y_ROT, X_ROT). */
public class S08 extends SPacket {
    public Vec3 position;
    public float yaw;
    public float pitch;
    public Set<String> enumFlags;

    public S08(Vec3 position, float yaw, float pitch, Set<String> enumFlags) {
        super("S08PacketPlayerPosLook");
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.enumFlags = enumFlags == null ? new HashSet<String>() : enumFlags;
    }

    static S08 of(S08PacketPlayerPosLook packet) {
        Set<String> flags = new HashSet<String>();
        for (S08PacketPlayerPosLook.EnumFlags flag : packet.func_179834_f()) {
            flags.add(flag.name());
        }
        S08 out = new S08(new Vec3(packet.getX(), packet.getY(), packet.getZ()), packet.getYaw(), packet.getPitch(), flags);
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        Set<S08PacketPlayerPosLook.EnumFlags> flags = EnumSet.noneOf(S08PacketPlayerPosLook.EnumFlags.class);
        for (String flag : this.enumFlags) {
            flags.add(S08PacketPlayerPosLook.EnumFlags.valueOf(flag.toUpperCase(Locale.ROOT)));
        }
        return new S08PacketPlayerPosLook(this.position.x, this.position.y, this.position.z, this.yaw, this.pitch, flags);
    }
}
