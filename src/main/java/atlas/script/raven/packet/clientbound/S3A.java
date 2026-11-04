package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S3APacketTabComplete;

/** The server's answer to tab completion. */
public class S3A extends SPacket {
    public String[] matches;

    public S3A(String[] matches) {
        super("S3APacketTabComplete");
        this.matches = matches == null ? new String[0] : matches;
    }

    static S3A of(S3APacketTabComplete packet) {
        S3A out = new S3A(packet.func_149630_c());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S3APacketTabComplete(this.matches);
    }
}
