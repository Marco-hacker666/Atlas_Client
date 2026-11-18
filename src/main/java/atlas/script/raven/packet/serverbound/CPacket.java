package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;

public class CPacket {
    public String name;
    public Packet packet;

    public CPacket(Packet packet) {
        this.packet = packet;
        this.name = packet == null ? this.getClass().getSimpleName() : packet.getClass().getSimpleName();
    }

    CPacket(String name) {
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
