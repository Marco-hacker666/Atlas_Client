package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C16PacketClientStatus;

import java.util.Locale;

/** "PERFORM_RESPAWN", "REQUEST_STATS" or "OPEN_INVENTORY_ACHIEVEMENT". */
public class C16 extends CPacket {
    public String status;

    public C16(String status) {
        super("C16PacketClientStatus");
        this.status = status;
    }

    static C16 of(C16PacketClientStatus packet) {
        C16 out = new C16(packet.getStatus().name());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C16PacketClientStatus(C16PacketClientStatus.EnumState.valueOf(this.status.toUpperCase(Locale.ROOT)));
    }
}
