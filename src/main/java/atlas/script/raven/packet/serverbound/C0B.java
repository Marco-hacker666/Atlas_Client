package atlas.script.raven.packet.serverbound;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0BPacketEntityAction;

import java.util.Locale;

/**
 * An action of the player: "START_SNEAKING", "STOP_SNEAKING", "STOP_SLEEPING", "START_SPRINTING",
 * "STOP_SPRINTING", "RIDING_JUMP" or "OPEN_INVENTORY"; horsePower for a horse's jump.
 */
public class C0B extends CPacket {
    public String action;
    public int horsePower;

    public C0B(String action, int horsePower) {
        super("C0BPacketEntityAction");
        this.action = action;
        this.horsePower = horsePower;
    }

    static C0B of(C0BPacketEntityAction packet) {
        C0B out = new C0B(packet.getAction().name(), packet.getAuxData());
        out.packet = packet;
        return out;
    }

    @Override
    public Packet convert() {
        return new C0BPacketEntityAction(Minecraft.getMinecraft().thePlayer,
                C0BPacketEntityAction.Action.valueOf(this.action.toUpperCase(Locale.ROOT)), this.horsePower);
    }
}
