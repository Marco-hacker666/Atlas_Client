package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S06PacketUpdateHealth;

/** The player's health and food. */
public class S06 extends SPacket {
    public float health;
    public float saturation;
    public int food;

    public S06(float health, float saturation, int food) {
        super("S06PacketUpdateHealth");
        this.health = health;
        this.saturation = saturation;
        this.food = food;
    }

    static S06 of(S06PacketUpdateHealth packet) {
        S06 out = new S06(packet.getHealth(), packet.getSaturationLevel(), packet.getFoodLevel());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S06PacketUpdateHealth(this.health, this.food, this.saturation);
    }
}
