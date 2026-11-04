package atlas.script.raven.packet.serverbound;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C13PacketPlayerAbilities;

/**
 * The player's abilities. The game's packet has no getters for the two speeds, so a packet that came from the
 * game reports the player's own.
 */
public class C13 extends CPacket {
    public boolean invulnerable;
    public boolean flying;
    public boolean allowFlying;
    public boolean creativeMode;
    public float flySpeed;
    public float walkSpeed;

    public C13(boolean invulnerable, boolean flying, boolean allowFlying, boolean creativeMode, float flySpeed,
               float walkSpeed) {
        super("C13PacketPlayerAbilities");
        this.invulnerable = invulnerable;
        this.flying = flying;
        this.allowFlying = allowFlying;
        this.creativeMode = creativeMode;
        this.flySpeed = flySpeed;
        this.walkSpeed = walkSpeed;
    }

    static C13 of(C13PacketPlayerAbilities packet) {
        Minecraft mc = Minecraft.getMinecraft();
        PlayerCapabilities own = mc.thePlayer == null ? new PlayerCapabilities() : mc.thePlayer.capabilities;
        C13 out = new C13(packet.isInvulnerable(), packet.isFlying(), packet.isAllowFlying(), packet.isCreativeMode(),
                own.getFlySpeed(), own.getWalkSpeed());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        C13PacketPlayerAbilities packet = new C13PacketPlayerAbilities();
        packet.setInvulnerable(this.invulnerable);
        packet.setFlying(this.flying);
        packet.setAllowFlying(this.allowFlying);
        packet.setCreativeMode(this.creativeMode);
        packet.setFlySpeed(this.flySpeed);
        packet.setWalkSpeed(this.walkSpeed);
        return packet;
    }
}
