package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentText;

/** A chat line: type 0 chat, 1 system, 2 above the hotbar; the message without formatting. */
public class S02 extends SPacket {
    public byte type;
    public String message;

    public S02(byte type, String message) {
        super("S02PacketChat");
        this.type = type;
        this.message = message;
    }

    static S02 of(S02PacketChat packet) {
        S02 out = new S02(packet.getType(), packet.getChatComponent() == null ? ""
                : packet.getChatComponent().getUnformattedText());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S02PacketChat(new ChatComponentText(this.message), this.type);
    }
}
