package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0APacketAnimation;

/** A swing of the arm. */
public class C0A extends CPacket {
    public C0A() {
        super("C0APacketAnimation");
    }

    static C0A of(C0APacketAnimation packet) {
        C0A out = new C0A();
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C0APacketAnimation();
    }
}
