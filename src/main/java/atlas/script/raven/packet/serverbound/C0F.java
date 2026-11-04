package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;

/** The answer to a window transaction (anticheats also use it as a ping). */
public class C0F extends CPacket {
    public int windowId;
    public short uid;

    public C0F(int windowId, short uid) {
        super("C0FPacketConfirmTransaction");
        this.windowId = windowId;
        this.uid = uid;
    }

    static C0F of(C0FPacketConfirmTransaction packet) {
        C0F out = new C0F(packet.getWindowId(), packet.getUid());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C0FPacketConfirmTransaction(this.windowId, this.uid, true);
    }
}
