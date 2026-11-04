package atlas.script.raven.packet.serverbound;

import atlas.script.raven.model.ItemStack;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0EPacketClickWindow;

/** A click in a container window; the action number is the window's next one when it is sent. */
public class C0E extends CPacket {
    public int windowId;
    public int slot;
    public int button;
    public int mode;
    public short action;
    public ItemStack itemStack;

    public C0E(int windowId, int slot, int button, int mode, ItemStack itemStack) {
        super("C0EPacketClickWindow");
        this.windowId = windowId;
        this.slot = slot;
        this.button = button;
        this.mode = mode;
        this.itemStack = itemStack;
        this.action = -1;
    }

    static C0E of(C0EPacketClickWindow packet) {
        C0E out = new C0E(packet.getWindowId(), packet.getSlotId(), packet.getUsedButton(), packet.getMode(),
                new ItemStack(packet.getClickedItem(), (byte) 0));
        out.action = packet.getActionNumber();
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        short number = this.action;
        Minecraft mc = Minecraft.getMinecraft();
        if (number < 0 && mc.thePlayer != null && mc.thePlayer.openContainer != null) {
            number = mc.thePlayer.openContainer.getNextTransactionID(mc.thePlayer.inventory);
        }
        return new C0EPacketClickWindow(this.windowId, this.slot, this.button, this.mode,
                this.itemStack == null ? null : this.itemStack.itemStack, number);
    }
}
