package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;

/**
 * Raven script API: a packet from the server (2026-11-02). The typed ones carry their fields; any other kind comes
 * as a plain SPacket with its name. convert() gives the game's packet made from the fields, for
 * client.processPacket. Wrapping goes through ClientPackets (see CPacket for why not a constructor).
 */
public class SPacket {
    public String name;
    public Packet packet;

    public SPacket(Packet packet) {
        this.packet = packet;
        this.name = packet == null ? this.getClass().getSimpleName() : packet.getClass().getSimpleName();
    }

    SPacket(String name) {
        this.name = name;
    }

    public Packet convert() {
        return this.packet;
    }

    @Override
    public String toString() {
        return this.name;
    }
}
