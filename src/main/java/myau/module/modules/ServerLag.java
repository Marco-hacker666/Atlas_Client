package myau.module.modules;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.IntProperty;
import myau.util.PacketUtil;
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
    /* 2026-10-19: down to 25 ms, so it also works as a light incoming delay
       (Slinky's Fake Lag "inbound"), next to FakeLag's outgoing one. */
    private final IntProperty maxBlinkTime = new IntProperty("Lag ms", 1000, 25, 30000);
    /* Hold only within a second of your own last hit (Slinky's "Attacked"). */
    private final myau.property.properties.BooleanProperty onlyInCombat =
            new myau.property.properties.BooleanProperty("only-in-combat", false);
    private final myau.property.properties.BooleanProperty weaponsOnly =
            new myau.property.properties.BooleanProperty("weapons-only", false);
    private volatile long lastAttackAt;
    /** Whether a hold may start now; set on the client thread each tick. */
    private volatile boolean allowed = true;

    public ServerLag() {
        super("ServerLag", false, false, "I feel the lag ");

        /* Incoming holder: one at a time, never a packet another module has taken (PacketHub, 2026-10-18). */
        myau.management.PacketHub.registerIncoming("ServerLag", () -> this.packetQueue.size());
        /* What is held, for PacketHolds (plan step 13). */
        myau.management.PacketHolds.register(() -> {
            int count = this.packetQueue.size();
            if (count == 0) {
                return null;
            }
            TimedPacket head = this.packetQueue.peek();
            return new myau.management.PacketHolds.Hold("ServerLag", myau.management.PacketHolds.Direction.IN, count, head == null ? 0L : head.time,
                    "all incoming", "after " + this.maxBlinkTime.getValue()
                            + "ms; at once on a correction, a disconnect or a catch");
        }, new myau.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            /* Its own limit, and a second more. */
            @Override
            public long ceilingMs() {
                return maxBlinkTime.getValue() + myau.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                releaseAllPackets();
            }
        });
    }
    /* ---- 2026-09-24 ------------------------------------------------------

       Two faults. The delay was copied into currentLatency on enable and
       zeroed by releaseAllPackets(), which runs on every S08 and every world
       load, and nothing ever set it again -- so the first teleport or server
       change switched the module off while it still showed as on. And queued
       packets were processed from inside the receive handler, on the network
       thread, only when another packet happened to arrive.

       The delay is now read from the setting every time, and everything
       queued is handed to the client thread: due packets at each tick, the
       whole queue ahead of an S08 or disconnect. */

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        Packet<?> packet = event.getPacket();
        if (PacketUtil.isWorldRenderPacket(packet)) return;
        if (packet instanceof S19PacketEntityStatus || packet instanceof S02PacketChat || packet instanceof S0BPacketAnimation || packet instanceof S06PacketUpdateHealth
                || packet instanceof net.minecraft.network.play.server.S29PacketSoundEffect) return;
        if (!this.allowed) return;
        if (myau.management.Arbiter.catching()) {
            /* F-27: every other holder let go for a catch; this one kept
               holding. Held packets go (scheduled ahead of this one's own
               handling, so still in order) and nothing is held until the
               catch is over. */
            if (!this.packetQueue.isEmpty()) {
                this.scheduleReleaseAll();
                myau.management.Arbiter.yielded(this.getName());
            }
            return;
        }
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect) {
            /* Scheduled before vanilla schedules this packet's own handler,
               so the held packets still run first, in order. */
            this.scheduleReleaseAll();
            return;
        }

        @SuppressWarnings("unchecked")
        Packet<INetHandlerPlayClient> playPacket = (Packet<INetHandlerPlayClient>) packet;
        if (myau.management.PacketHub.take(event, "ServerLag")) {
            packetQueue.add(new TimedPacket(playPacket, System.currentTimeMillis()));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) return;
        boolean combat = !this.onlyInCombat.getValue() || System.currentTimeMillis() - this.lastAttackAt <= 1000L;
        boolean weapon = !this.weaponsOnly.getValue() || myau.util.ItemUtil.isHoldingSword();
        this.allowed = combat && weapon;
        if (!this.allowed) {
            releaseAllPackets();
            return;
        }
        releaseDue();
    }

    /* Checked every frame as well, not only every tick: a short delay is
       then kept to within a frame instead of up to 50 ms late. The due
       packets are handled at the start of the next frame (the task queue
       vanilla handles its own packets from), never in the middle of
       drawing the world. */
    private volatile boolean releaseScheduled;

    @EventTarget
    public void onRender(myau.events.Render3DEvent event) {
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
    public void onAttack(myau.events.AttackEvent event) {
        this.lastAttackAt = System.currentTimeMillis();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        /* A new world: the held packets describe the old one. */
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

    /** Client thread only. */
    private static void process(TimedPacket timed) {
        if (timed == null || mc.getNetHandler() == null) {
            return;
        }
        try {
            timed.packet.processPacket(mc.getNetHandler());
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
            // Vanilla's normal "rescheduled onto the client thread" signal.
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
