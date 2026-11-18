package atlas.module.modules;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.IntProperty;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S40PacketDisconnect;

public class ServerLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final ConcurrentLinkedQueue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();

    private final IntProperty maxBlinkTime = new IntProperty("Lag ms", 1000, 25, 30000);

    private final atlas.property.properties.BooleanProperty onlyInCombat =
            new atlas.property.properties.BooleanProperty("only-in-combat", false);
    private final atlas.property.properties.BooleanProperty weaponsOnly =
            new atlas.property.properties.BooleanProperty("weapons-only", false);
    private volatile long lastAttackAt;

    private volatile boolean allowed = true;

    public ServerLag() {
        super("ServerLag", false, false, "I feel the lag ");

        atlas.management.PacketHub.registerIncoming("ServerLag", () -> this.packetQueue.size(), this::releaseAllPackets);

        atlas.management.PacketHolds.register(() -> {
            int count = this.packetQueue.size();
            if (count == 0) {
                return null;
            }
            TimedPacket head = this.packetQueue.peek();
            return new atlas.management.PacketHolds.Hold("ServerLag", atlas.management.PacketHolds.Direction.IN, count, head == null ? 0L : head.time,
                    "all incoming", "after " + this.maxBlinkTime.getValue()
                            + "ms; at once on a correction, a disconnect or a catch");
        }, new atlas.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            @Override
            public long ceilingMs() {
                return maxBlinkTime.getValue() + atlas.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                releaseAllPackets();
            }
        });
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        Packet<?> packet = event.getPacket();
        if (PacketUtil.isWorldRenderPacket(packet)) return;
        if (packet instanceof S19PacketEntityStatus || packet instanceof S02PacketChat || packet instanceof S0BPacketAnimation || packet instanceof S06PacketUpdateHealth
                || packet instanceof net.minecraft.network.play.server.S29PacketSoundEffect) return;
        if (!this.allowed) return;
        if (atlas.management.Arbiter.catching()) {
            if (!this.packetQueue.isEmpty()) {
                this.scheduleReleaseAll();
                atlas.management.Arbiter.yielded(this.getName());
            }
            return;
        }
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect) {
            this.scheduleReleaseAll();
            return;
        }

        @SuppressWarnings("unchecked")
        Packet<INetHandlerPlayClient> playPacket = (Packet<INetHandlerPlayClient>) packet;
        if (atlas.management.PacketHub.take(event, "ServerLag")) {
            packetQueue.add(new TimedPacket(playPacket, System.currentTimeMillis()));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        boolean combat = !this.onlyInCombat.getValue() || System.currentTimeMillis() - this.lastAttackAt <= 1000L;
        boolean weapon = !this.weaponsOnly.getValue() || atlas.util.ItemUtil.isHoldingSword();
        this.allowed = combat && weapon;
        if (!this.allowed) {
            releaseAllPackets();
            return;
        }
        releaseDue();
    }

    private volatile boolean releaseScheduled;

    @EventTarget
    public void onRender(atlas.events.Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || this.releaseScheduled) {
            return;
        }
        TimedPacket head = packetQueue.peek();
        if (head != null && head.time <= System.currentTimeMillis() - this.maxBlinkTime.getValue()) {
            this.releaseScheduled = true;
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ServerLag.this.releaseScheduled = false;
                    releaseDue();
                }
            });
        }
    }

    private void releaseDue() {
        long due = System.currentTimeMillis() - this.maxBlinkTime.getValue();
        while (!packetQueue.isEmpty() && packetQueue.peek().time <= due) {
            process(packetQueue.poll());
        }
    }

    @EventTarget
    public void onAttack(atlas.events.AttackEvent event) {
        this.lastAttackAt = System.currentTimeMillis();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        packetQueue.clear();
        this.releaseScheduled = false;
    }

    private void scheduleReleaseAll() {
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                releaseAllPackets();
            }
        });
    }

    private void releaseAllPackets() {
        TimedPacket timed;
        while ((timed = packetQueue.poll()) != null) {
            process(timed);
        }
    }

    private static void process(TimedPacket timed) {
        if (timed == null || mc.getNetHandler() == null) {
            return;
        }
        try {
            timed.packet.processPacket(mc.getNetHandler());
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisabled() {
        if (mc.isCallingFromMinecraftThread()) {
            this.releaseAllPackets();
        } else {
            this.scheduleReleaseAll();
        }
    }

    private static class TimedPacket {
        private final Packet<INetHandlerPlayClient> packet;
        private final long time;

        public TimedPacket(Packet<INetHandlerPlayClient> packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }
}
