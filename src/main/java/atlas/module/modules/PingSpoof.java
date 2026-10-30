package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0): holds keep-alive and transaction replies
// to spoof ping, driven by a tick queue with no lag-core dependency.
import java.util.concurrent.ConcurrentLinkedQueue;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ChatUtil;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;

public class PingSpoof extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int MAX_QUEUE = 200;

    public final IntProperty delayMs = new IntProperty("delay-ms", 250, 0, 2000);
    public final BooleanProperty keepAlive = new BooleanProperty("keep-alive", true);
    public final BooleanProperty transactions = new BooleanProperty("transactions", true);
    public final ModeProperty release = new ModeProperty("release", 0, new String[]{"Delay", "Pulse"});
    public final IntProperty pulseSize = new IntProperty("pulse-size", 20, 1, 100, () -> release.getValue() == 1);

    private final ConcurrentLinkedQueue<PacketData> queue = new ConcurrentLinkedQueue<PacketData>();
    private boolean stackWarned = false;

    public PingSpoof() {
        super("PingSpoof", false, false, "Delays keep alive packets to spoof your ping.");
        /* Replies held on purpose: never one another module has taken (PacketHub, 2026-10-18). */
        atlas.management.PacketHub.registerSide("PingSpoof", () -> this.queue.size());
    }

    @Override
    public void onEnabled() {
        queue.clear();
        stackWarned = false;
        warnIfStacked();
    }

    @Override
    public void onDisabled() {
        flushAll();
        queue.clear();
        stackWarned = false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.SEND) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        boolean hold = (event.getPacket() instanceof C00PacketKeepAlive && keepAlive.getValue())
                || (event.getPacket() instanceof C0FPacketConfirmTransaction && transactions.getValue());
        if (!hold) return;

        warnIfStacked();
        if (!atlas.management.PacketHub.take(event, "PingSpoof")) return;
        queue.add(new PacketData(event.getPacket(), System.currentTimeMillis(), connection()));
        while (queue.size() > MAX_QUEUE) {
            PacketData dropped = queue.poll();
            if (dropped != null) send(dropped);
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled() || event.getType() != EventType.PRE) return;
        if (queue.isEmpty()) return;
        if (mc.getNetHandler() == null) {
            queue.clear();
            return;
        }

        if (release.getValue() == 1) {
            PacketData oldest = queue.peek();
            long age = oldest == null ? 0 : System.currentTimeMillis() - oldest.timestamp;
            if (queue.size() >= pulseSize.getValue() || age >= delayMs.getValue()) {
                flushAll();
            }
            return;
        }

        long now = System.currentTimeMillis();
        while (true) {
            PacketData data = queue.peek();
            if (data == null || now - data.timestamp < delayMs.getValue()) break;
            queue.poll();
            send(data);
        }
    }

    private void flushAll() {
        if (mc.getNetHandler() == null) {
            queue.clear();
            return;
        }
        PacketData data;
        while ((data = queue.poll()) != null) {
            send(data);
        }
    }

    /* 2026-10-30 (audit): a keep-alive or transaction reply held across a server change went to the next server,
       with the old server's id. It is sent only on the connection it answers. */
    private static Object connection() {
        return mc.getNetHandler() == null ? null : mc.getNetHandler().getNetworkManager();
    }

    private void send(PacketData data) {
        if (data.connection != null && data.connection == connection()) {
            PacketUtil.sendPacketNoEvent(data.packet);
        }
    }

    /* the client's Disabler names its Matrix switches matrix-disabler / matrix-ta. */
    private boolean isMatrixPingSpoofActive() {
        try {
            if (Atlas.moduleManager == null) return false;
            Module m = Atlas.moduleManager.getModule(Disabler.class);
            if (!(m instanceof Disabler)) return false;
            Disabler disabler = (Disabler) m;
            return disabler.isEnabled() && disabler.matrixDisabler.getValue() && disabler.matrixTA.getValue();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void warnIfStacked() {
        if (stackWarned) return;
        if (!(keepAlive.getValue() || transactions.getValue())) return;
        if (!isMatrixPingSpoofActive()) return;
        stackWarned = true;
        ChatUtil.sendFormatted(Atlas.clientName + "PingSpoof: &eDisabler matrix PingSpoof also delays C00/C0F. Stacking both may flag. Disable one.");
    }

    private static class PacketData {
        private final Packet<?> packet;
        private final long timestamp;
        /** The NetworkManager it was held on. */
        private final Object connection;

        private PacketData(Packet<?> packet, long timestamp, Object connection) {
            this.packet = packet;
            this.timestamp = timestamp;
            this.connection = connection;
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{delayMs.getValue() + "ms"};
    }
}
