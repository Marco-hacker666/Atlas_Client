package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.ChatComponentText;

import java.util.Locale;

/** A title: type "TITLE", "SUBTITLE", "TIMES", "CLEAR" or "RESET"; times in ticks. */
public class S45 extends SPacket {
    public String type;
    public String message;
    public int fadeInTime;
    public int displayTime;
    public int fadeOutTime;

    public S45(String type, String message, int fadeInTime, int displayTime, int fadeOutTime) {
        super("S45PacketTitle");
        this.type = type;
        this.message = message;
        this.fadeInTime = fadeInTime;
        this.displayTime = displayTime;
        this.fadeOutTime = fadeOutTime;
    }

    static S45 of(S45PacketTitle packet) {
        S45 out = new S45(packet.getType().name(), packet.getMessage() == null ? ""
                : packet.getMessage().getUnformattedText(), packet.getFadeInTime(), packet.getDisplayTime(),
                packet.getFadeOutTime());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        S45PacketTitle.Type kind = S45PacketTitle.Type.valueOf(this.type.toUpperCase(Locale.ROOT));
        return new S45PacketTitle(kind, new ChatComponentText(this.message == null ? "" : this.message),
                this.fadeInTime, this.displayTime, this.fadeOutTime);
    }
}
