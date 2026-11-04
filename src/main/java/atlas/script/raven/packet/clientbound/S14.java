package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S14PacketEntity;

import java.lang.reflect.Field;

/**
 * An entity's relative move and/or look: position in 1/32 blocks, angles in 1/256 turns, as the game sends them.
 */
public class S14 extends SPacket {
    private static Field entityIdField;

    public int entityId;
    public byte posX;
    public byte posY;
    public byte posZ;
    public byte yaw;
    public byte pitch;
    public boolean onGround;
    public boolean rotating;
    private boolean moving;

    public S14(int entityId, byte posX, byte posY, byte posZ, byte yaw, byte pitch, boolean onGround) {
        super("S17PacketEntityLookMove");
        this.entityId = entityId;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.rotating = true;
        this.moving = true;
    }

    public S14(int entityId, byte posX, byte posY, byte posZ, boolean onGround) {
        super("S15PacketEntityRelMove");
        this.entityId = entityId;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.onGround = onGround;
        this.moving = true;
    }

    public S14(int entityId, byte yaw, byte pitch, boolean onGround) {
        super("S16PacketEntityLook");
        this.entityId = entityId;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.rotating = true;
    }

    static S14 of(S14PacketEntity packet) {
        S14 out = new S14(entityId(packet), packet.func_149062_c(), packet.func_149061_d(), packet.func_149064_e(),
                packet.func_149066_f(), packet.func_149063_g(), packet.getOnGround());
        out.rotating = packet.func_149060_h();
        out.moving = !(packet instanceof S14PacketEntity.S16PacketEntityLook);
        out.name = packet.getClass().getSimpleName();
        out.packet = packet;
        return out;
    }

    /** The id is a protected field with no getter: "entityId" here, its SRG name in the game. */
    private static int entityId(S14PacketEntity packet) {
        try {
            if (entityIdField == null) {
                Field field;
                try {
                    field = S14PacketEntity.class.getDeclaredField("field_149074_a");
                } catch (NoSuchFieldException e) {
                    field = S14PacketEntity.class.getDeclaredField("entityId");
                }
                field.setAccessible(true);
                entityIdField = field;
            }
            return entityIdField.getInt(packet);
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public Packet convert() {
        if (this.moving && this.rotating) {
            return new S14PacketEntity.S17PacketEntityLookMove(this.entityId, this.posX, this.posY, this.posZ,
                    this.yaw, this.pitch, this.onGround);
        }
        if (this.moving) {
            return new S14PacketEntity.S15PacketEntityRelMove(this.entityId, this.posX, this.posY, this.posZ,
                    this.onGround);
        }
        return new S14PacketEntity.S16PacketEntityLook(this.entityId, this.yaw, this.pitch, this.onGround);
    }
}
