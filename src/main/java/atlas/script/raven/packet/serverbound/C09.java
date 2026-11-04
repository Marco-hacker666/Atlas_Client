package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** The hotbar slot held (0..8). */
public class C09 extends CPacket {
    public int slot;

    public C09(int slot) {
        super("C09PacketHeldItemChange");
        this.slot = slot;
    }

    static C09 of(C09PacketHeldItemChange packet) {
        C09 out = new C09(packet.getSlotId());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C09PacketHeldItemChange(this.slot);
    }
}
