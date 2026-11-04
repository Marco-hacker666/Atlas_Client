package atlas.script.raven.packet.serverbound;

import atlas.script.raven.model.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;

/** Creative mode: putting an item into a slot. */
public class C10 extends CPacket {
    public int slot;
    public ItemStack itemStack;

    public C10(int slot, ItemStack itemStack) {
        super("C10PacketCreativeInventoryAction");
        this.slot = slot;
        this.itemStack = itemStack;
    }

    static C10 of(C10PacketCreativeInventoryAction packet) {
        C10 out = new C10(packet.getSlotId(), ItemStack.convert(packet.getStack()));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C10PacketCreativeInventoryAction(this.slot, this.itemStack == null ? null : this.itemStack.itemStack);
    }
}
