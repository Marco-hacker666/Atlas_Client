package atlas.script.raven.packet.serverbound;

import atlas.script.raven.packet.clientbound.SPacket;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;
import net.minecraft.network.play.client.C13PacketPlayerAbilities;
import net.minecraft.network.play.client.C16PacketClientStatus;

/**
 * Raven script API: the game's packets as script packets and back (2026-11-02). A kind with no class of its own
 * becomes a plain CPacket / SPacket carrying its name; null when the conversion itself failed.
 */
public final class PacketHandler {
    private PacketHandler() {
    }

    public static CPacket convertServerBound(Packet packet) {
        if (packet == null) {
            return null;
        }
        try {
            if (packet instanceof C03PacketPlayer) {
                return C03.of((C03PacketPlayer) packet);
            }
            if (packet instanceof C0FPacketConfirmTransaction) {
                return C0F.of((C0FPacketConfirmTransaction) packet);
            }
            if (packet instanceof C0APacketAnimation) {
                return C0A.of((C0APacketAnimation) packet);
            }
            if (packet instanceof C02PacketUseEntity) {
                return C02.of((C02PacketUseEntity) packet);
            }
            if (packet instanceof C08PacketPlayerBlockPlacement) {
                return C08.of((C08PacketPlayerBlockPlacement) packet);
            }
            if (packet instanceof C07PacketPlayerDigging) {
                return C07.of((C07PacketPlayerDigging) packet);
            }
            if (packet instanceof C09PacketHeldItemChange) {
                return C09.of((C09PacketHeldItemChange) packet);
            }
            if (packet instanceof C0BPacketEntityAction) {
                return C0B.of((C0BPacketEntityAction) packet);
            }
            if (packet instanceof C0EPacketClickWindow) {
                return C0E.of((C0EPacketClickWindow) packet);
            }
            if (packet instanceof C0DPacketCloseWindow) {
                return C0D.of((C0DPacketCloseWindow) packet);
            }
            if (packet instanceof C01PacketChatMessage) {
                return C01.of((C01PacketChatMessage) packet);
            }
            if (packet instanceof C10PacketCreativeInventoryAction) {
                return C10.of((C10PacketCreativeInventoryAction) packet);
            }
            if (packet instanceof C13PacketPlayerAbilities) {
                return C13.of((C13PacketPlayerAbilities) packet);
            }
            if (packet instanceof C16PacketClientStatus) {
                return C16.of((C16PacketClientStatus) packet);
            }
            return new CPacket(packet);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static SPacket convertClientBound(Packet packet) {
        return atlas.script.raven.packet.clientbound.ClientPackets.convert(packet);
    }

    /** The game's packet a script packet stands for. */
    public static Packet convertCPacket(CPacket cPacket) {
        return cPacket == null ? null : cPacket.convert();
    }
}
