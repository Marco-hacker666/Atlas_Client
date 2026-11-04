package atlas.script.raven.packet.clientbound;

import atlas.script.raven.model.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S04PacketEntityEquipment;

/** What an entity holds or wears: slot 0 held, 1 boots .. 4 helmet. */
public class S04 extends SPacket {
    public int entityId;
    public int slot;
    public ItemStack item;

    public S04(int entityId, int slot, ItemStack item) {
        super("S04PacketEntityEquipment");
        this.entityId = entityId;
        this.slot = slot;
        this.item = item;
    }

    static S04 of(S04PacketEntityEquipment packet) {
        S04 out = new S04(packet.getEntityID(), packet.getEquipmentSlot(), ItemStack.convert(packet.getItemStack()));
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new S04PacketEntityEquipment(this.entityId, this.slot, this.item == null ? null : this.item.itemStack);
    }
}
