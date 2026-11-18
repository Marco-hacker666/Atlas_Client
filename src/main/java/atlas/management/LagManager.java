package atlas.management;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.util.ActionLedger;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.util.Vec3;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public class LagManager {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final Deque<LagPacket> packetQueue;

    private volatile int tickDelay;

    private volatile Thread flushingThread;

    private volatile String holder = "LagManager";
    private volatile Vec3 lastPosition;

    public LagManager() {
        this.packetQueue = new ConcurrentLinkedDeque<>();
        this.tickDelay = 0;
        this.lastPosition = new Vec3(0.0, 0.0, 0.0);

        PacketHolds.register(() -> {
            int count = this.packetQueue.size();
            if (count == 0) {
                return null;
            }
            LagPacket head = this.packetQueue.peek();
            return new PacketHolds.Hold(this.holder, PacketHolds.Direction.OUT, count,
                    head == null ? 0L : head.at, "delay " + this.tickDelay + " ticks",
                    "each after its delay; all on a catch or a join");
        }, new PacketHolds.Lease() {
            @Override
            public String owner() {
                String holder = LagManager.this.holder;
                return "LagManager".equals(holder) ? null : holder;
            }

            @Override
            public long ceilingMs() {
                return LagManager.this.tickDelay * 50L + PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                LagManager.this.tickDelay = 0;
                flushQueue(true);
            }
        });
    }

    private void flushQueue() {
        flushQueue(false);
    }

    private synchronized void flushQueue(boolean all) {
        if (!connected()) {
            this.packetQueue.clear();
            return;
        }
        this.flushingThread = Thread.currentThread();
        int sent = 0;
        try {
            for (; !this.packetQueue.isEmpty(); this.packetQueue.poll()) {
                LagPacket lagPacket = this.packetQueue.peek();
                if (!all && this.tickDelay > 0 && lagPacket.delay <= this.tickDelay) {
                    break;
                }
                if (!all && lagPacket.packet instanceof C03PacketPlayer && this.movesThisTick >= MOVES_PER_TICK) {
                    break;
                }
                if (lagPacket.packet instanceof C03PacketPlayer) {
                    this.movesThisTick++;
                }
                send(lagPacket.packet);
                sent++;
                if (lagPacket.packet instanceof C03PacketPlayer) {
                    C03PacketPlayer c03 = (C03PacketPlayer) lagPacket.packet;
                    if (c03.isMoving()) {
                        this.lastPosition = new Vec3(c03.getPositionX(), c03.getPositionY(), c03.getPositionZ());
                    }
                }
            }
        } finally {
            this.flushingThread = null;

            if (sent > 0) {
                ActionLedger.note(this.holder, "release", sent);
            }
        }
    }

    protected boolean connected() {
        return mc != null && mc.getNetHandler() != null;
    }

    protected void send(Packet<?> packet) {
        PacketUtil.sendPacketNoEvent(packet);
    }

    private synchronized void incrementDelays() {
        this.packetQueue.forEach(z -> z.delay++);
    }

    public boolean handlePacket(Packet<?> packet) {
        if (Arbiter.catching()) {
            if (!this.packetQueue.isEmpty()) {
                this.flushQueue(true);
                Arbiter.yielded("LagManager");
            }
            return false;
        }
        this.flushQueue();
        if (packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        } else if ((long) this.tickDelay > 0L || !this.packetQueue.isEmpty()) {
            ActionLedger.note(this.holder, "held-send");
            this.packetQueue.offer(new LagPacket(packet));
            return true;
        } else {
            if (packet instanceof C03PacketPlayer) {
                C03PacketPlayer c03 = (C03PacketPlayer) packet;
                if (c03.isMoving()) {
                    this.lastPosition = new Vec3(c03.getPositionX(), c03.getPositionY(), c03.getPositionZ());
                }
            }
            return false;
        }
    }

    public void setDelay(int delay) {
        if (delay > 0 && delay != this.tickDelay) {
            String caller = ActionLedger.callerModule();
            if (caller != null) {
                this.holder = caller;
            }
        }
        if (delay > 0 && !LagGuard.mayHold(this.holder, false, null)) {
            delay = 0;
        }
        this.tickDelay = delay;
    }

    public void releaseAll() {
        this.flushQueue(true);
    }

    public String holder() {
        return this.holder;
    }

    public int getDelay() {
        return this.tickDelay;
    }

    public Vec3 getLastPosition() {
        return this.lastPosition;
    }

    public boolean isFlushing() {
        return this.flushingThread == Thread.currentThread();
    }

    @EventTarget(Priority.HIGHEST)
    public void onWorldLoad(LoadWorldEvent event) {
        dropForWorldChange(event.getWorld() != null);
    }

    public synchronized void dropForWorldChange(boolean sendReplies) {
        java.util.List<Packet<?>> replies = new java.util.ArrayList<Packet<?>>();
        LagPacket lagPacket;
        while ((lagPacket = this.packetQueue.poll()) != null) {
            if (sendReplies && lagPacket.packet instanceof net.minecraft.network.play.client.C0FPacketConfirmTransaction) {
                replies.add(lagPacket.packet);
            }
        }
        if (replies.isEmpty() || !connected()) {
            return;
        }
        Thread before = this.flushingThread;
        this.flushingThread = Thread.currentThread();
        try {
            for (Packet<?> reply : replies) {
                send(reply);
            }
        } finally {
            this.flushingThread = before;
        }
    }

    private static final int MOVES_PER_TICK = 2;
    private volatile int movesThisTick;

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.POST) {
            this.movesThisTick = 0;
            if (mc.thePlayer.isDead) {
                this.setDelay(0);
            }
            this.incrementDelays();
            this.flushQueue();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C00Handshake
                || event.getPacket() instanceof C00PacketLoginStart
                || event.getPacket() instanceof C00PacketServerQuery
                || event.getPacket() instanceof C01PacketPing
                || event.getPacket() instanceof C01PacketEncryptionResponse) {
            this.setDelay(0);
        }
    }

    public static class LagPacket {
        public final Packet<?> packet;
        public int delay;

        public final long at = System.currentTimeMillis();

        public LagPacket(Packet<?> packet) {
            this.packet = packet;
            this.delay = 0;
        }
    }
}
