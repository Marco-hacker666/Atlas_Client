package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C01PacketChatMessage;

/** A chat message or command the player sends. */
public class C01 extends CPacket {
    public String message;

    public C01(String message) {
        super("C01PacketChatMessage");
        this.message = message;
    }

    static C01 of(C01PacketChatMessage packet) {
        C01 out = new C01(packet.getMessage());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C01PacketChatMessage(this.message);
    }
}
