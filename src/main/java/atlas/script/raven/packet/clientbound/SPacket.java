package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;

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
