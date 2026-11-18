package atlas.management;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;

public final class TickActions {
    public static final int SLOT = 1;
    public static final int ATTACK = 1 << 1;
    public static final int INTERACT = 1 << 2;
    public static final int SWING = 1 << 3;

    public static final int USE = 1 << 4;

    public static final int DIG = 1 << 5;

    public static final int INVENTORY = 1 << 6;

    private static volatile int sent;
    private static volatile long refused;

    public static boolean any(int kinds) {
        return (sent & kinds) != 0;
    }

    public static int sent() {
        return sent;
    }

    public static void refuse() {
        refused++;
    }

    public static long refusals() {
        return refused;
    }

    static int kindOf(Packet<?> packet) {
        if (packet instanceof C09PacketHeldItemChange) {
            return SLOT;
        }
        if (packet instanceof C02PacketUseEntity) {
            return ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK ? ATTACK : INTERACT;
        }
        if (packet instanceof C0APacketAnimation) {
            return SWING;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            return USE;
        }
        if (packet instanceof C07PacketPlayerDigging) {
            return DIG;
        }
        if (packet instanceof C0EPacketClickWindow || packet instanceof C0DPacketCloseWindow) {
            return INVENTORY;
        }
        if (packet instanceof C16PacketClientStatus
                && ((C16PacketClientStatus) packet).getStatus() == C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT) {
            return INVENTORY;
        }
        return 0;
    }

    public static void noteDirect(Packet<?> packet) {
        noteSent(packet);
    }

    static void noteSent(Packet<?> packet) {
        int kind = kindOf(packet);
        if (kind != 0) {
            sent |= kind;
        }
    }

    static void newTick() {
        sent = 0;
    }

    public static final class Listener {
        @EventTarget(Priority.HIGHEST)
        public void onTick(TickEvent event) {
            if (event.getType() == EventType.PRE) {
                newTick();
            }
        }

        @EventTarget(Priority.LOWEST)
        public void onPacket(PacketEvent event) {
            if (event.getType() == EventType.SEND && !event.isCancelled()) {
                noteSent(event.getPacket());
            }
        }
    }

    static void resetForTests() {
        sent = 0;
        refused = 0L;
    }

    private TickActions() {
    }
}
