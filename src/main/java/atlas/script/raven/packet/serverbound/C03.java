package atlas.script.raven.packet.serverbound;

import atlas.script.raven.model.Vec3;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * The movement packet: position null when it carries none (C03/C05), and the look only when it carries one
 * (C05/C06). Which of the four it is follows from the constructor used, or from the packet it came from.
 */
public class C03 extends CPacket {
    public Vec3 position;
    public float yaw;
    public float pitch;
    public boolean ground;
    /** bS 17: whether it carries a position and a look; a script may switch either off. */
    public boolean moving;
    public boolean rotating;

    public C03(boolean ground) {
        super("C03PacketPlayer");
        this.ground = ground;
    }

    public C03(Vec3 position, boolean ground) {
        super("C04PacketPlayerPosition");
        this.position = position;
        this.ground = ground;
        this.moving = position != null;
    }

    public C03(float yaw, float pitch, boolean ground) {
        super("C05PacketPlayerLook");
        this.yaw = yaw;
        this.pitch = pitch;
        this.ground = ground;
        this.rotating = true;
    }

    public C03(Vec3 position, float yaw, float pitch, boolean ground) {
        super("C06PacketPlayerPosLook");
        this.position = position;
        this.moving = position != null;
        this.yaw = yaw;
        this.pitch = pitch;
        this.ground = ground;
        this.rotating = true;
    }

    static C03 of(C03PacketPlayer packet) {
        Vec3 position = packet.isMoving()
                ? new Vec3(packet.getPositionX(), packet.getPositionY(), packet.getPositionZ()) : null;
        C03 out = packet.getRotating()
                ? position == null ? new C03(packet.getYaw(), packet.getPitch(), packet.isOnGround())
                : new C03(position, packet.getYaw(), packet.getPitch(), packet.isOnGround())
                : position == null ? new C03(packet.isOnGround()) : new C03(position, packet.isOnGround());
        out.packet = packet;
        out.name = packet.getClass().getSimpleName();
        return out;
    }

    /** Whether it carries a look. */
    public boolean isRotating() {
        return this.rotating;
    }

    @Override
    public Packet convert() {
        if (this.position != null && this.moving && this.rotating) {
            return new C03PacketPlayer.C06PacketPlayerPosLook(this.position.x, this.position.y, this.position.z,
                    this.yaw, this.pitch, this.ground);
        }
        if (this.position != null && this.moving) {
            return new C03PacketPlayer.C04PacketPlayerPosition(this.position.x, this.position.y, this.position.z,
                    this.ground);
        }
        if (this.rotating) {
            return new C03PacketPlayer.C05PacketPlayerLook(this.yaw, this.pitch, this.ground);
        }
        return new C03PacketPlayer(this.ground);
    }
}
