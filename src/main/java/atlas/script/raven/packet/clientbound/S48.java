package atlas.script.raven.packet.clientbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S48PacketResourcePackSend;

/** The server asking the client to load a resource pack. */
public class S48 extends SPacket {
    public String url;
    public String hash;

    public S48(String url, String hash) {
        super("S48PacketResourcePackSend");
        this.url = url;
        this.hash = hash;
    }

    static S48 of(S48PacketResourcePackSend packet) {
        S48 out = new S48(packet.getURL(), packet.getHash());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S48PacketResourcePackSend(this.url, this.hash);
    }
}
