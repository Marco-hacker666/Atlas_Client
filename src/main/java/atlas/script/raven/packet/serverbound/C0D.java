package atlas.script.raven.packet.serverbound;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;

/** Closing a container window (0 for the inventory). */
public class C0D extends CPacket {
    public int windowId;

    public C0D(int windowId) {
        super("C0DPacketCloseWindow");
        this.windowId = windowId;
    }

    static C0D of(C0DPacketCloseWindow packet) {
        C0D out = new C0D(((atlas.mixin.IAccessorC0DPacketCloseWindow) packet).getWindowId());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C0DPacketCloseWindow(this.windowId);
    }
}
