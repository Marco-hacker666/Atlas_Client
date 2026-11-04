package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S2FPacketSetSlot;

/** The server setting one slot of a window (-1 / -1 for the item on the cursor). */
public class S2F extends SPacket {
    public int windowId;
    public int slot;
    public ItemStack itemStack;

    public S2F(int windowId, int slot, ItemStack itemStack) {
        super("S2FPacketSetSlot");
        this.windowId = windowId;
        this.slot = slot;
        this.itemStack = itemStack;
    }

    static S2F of(S2FPacketSetSlot packet) {
        S2F out = new S2F(packet.func_149175_c(), packet.func_149173_d(), ItemStack.convert(packet.func_149174_e()));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S2FPacketSetSlot(this.windowId, this.slot, this.itemStack == null ? null : this.itemStack.itemStack);
    }
}
