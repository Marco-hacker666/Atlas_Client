package atlas.management;

import atlas.enums.BlinkModules;
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
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BooleanSupplier;

public class BlinkManager {
    public static Minecraft mc = Minecraft.getMinecraft();

    public volatile BlinkModules blinkModule = BlinkModules.NONE;
    public volatile boolean blinking = false;
    public Deque<Packet<?>> blinkedPackets = new ConcurrentLinkedDeque<>();
    private volatile boolean releasing = false;

    private volatile long blinkSince;

    public BlinkManager() {
        PacketHolds.register(() -> {
            int count = this.blinkedPackets.size();
            if (count == 0) {
                return null;
            }
            String owner = this.blinkModule.moduleName();
            return new PacketHolds.Hold("Blink/" + owner, PacketHolds.Direction.OUT, count, this.blinkSince,
                    "blink", "until " + owner + " ends it; all on a catch");
        }, new PacketHolds.Lease() {
            @Override
            public String owner() {
                BlinkModules owner = blinkModule;
                return owner == BlinkModules.NONE ? null : owner.moduleName();
            }

            @Override
            public long ceilingMs() {
                return blinkModule.leaseMs();
            }

            @Override
            public void expire() {
                releaseAll();
            }
        });
    }

    private volatile int refusals;
    private volatile String lastRefusal = "";

    public void release(Packet<?> packet) {
        this.releasing = true;
        try {
            ActionLedger.note(this.blinkModule.moduleName(), "release");
            send(packet);
        } finally {
            this.releasing = false;
        }
    }

    public static volatile BooleanSupplier holdTransactions = () -> false;

    public boolean offerPacket(Packet<?> packet) {
        if (this.releasing || this.blinkModule == BlinkModules.NONE || packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        } else if (packet instanceof C0FPacketConfirmTransaction
                && (holdTransactions.getAsBoolean() || this.blinkModule == BlinkModules.RHYTHM)) {
            ActionLedger.note(this.blinkModule.moduleName(), "held-send");
            this.blinkedPackets.offer(packet);
            return true;
        } else if (!"Client thread".equals(Thread.currentThread().getName())) {
            return false;
        } else if (packet instanceof C0FPacketConfirmTransaction) {
            return false;
        } else {
            ActionLedger.note(this.blinkModule.moduleName(), "held-send");
            this.blinkedPackets.offer(packet);
            return true;
        }
    }

    public boolean setBlinkState(boolean state, BlinkModules module) {
        if (module == BlinkModules.NONE) {
            return false;
        }
        if (state) {
            if (this.blinkModule != module && !LagGuard.mayHold(module.moduleName(), false, null)) {
                this.refusals++;
                this.lastRefusal = module.name() + " refused by LagGuard";
                return false;
            }
            if (Arbiter.catching()) {
                Arbiter.yielded(module.name());
                return false;
            }
            if (this.blinkModule != BlinkModules.NONE && this.blinkModule != module) {
                this.refusals++;
                this.lastRefusal = module.name() + " refused, " + this.blinkModule.name() + " holds";
                return false;
            }
            if (this.blinkModule != module) {
                this.blinkSince = System.currentTimeMillis();
            }
            this.blinkModule = module;
            this.blinking = true;
        } else {
            if(blinkModule != module){
                return false;
            }
            releaseAll();
        }
        return true;
    }

    private void releaseAll() {
        BlinkModules module = this.blinkModule;
        this.blinking = false;

        this.blinkModule = BlinkModules.NONE;
        if (!connected()) {
            this.blinkedPackets.clear();
            return;
        }

        if (!this.blinkedPackets.isEmpty()) {
            ActionLedger.note(module.moduleName(), "release", this.blinkedPackets.size());
        }
        for (Packet<?> blinkedPacket : blinkedPackets) {
            send(blinkedPacket);
        }
        this.blinkedPackets.clear();
    }

    public void discardHeld() {
        Packet<?> packet;
        while ((packet = this.blinkedPackets.poll()) != null) {
            if (packet instanceof C0FPacketConfirmTransaction && connected()) {
                release(packet);
            }
        }
    }

    public void abortForSetback() {
        if (this.blinkModule == BlinkModules.NONE) {
            return;
        }
        ActionLedger.note(this.blinkModule.moduleName(), "dropped on a correction", this.blinkedPackets.size());
        discardHeld();
        this.blinking = false;
        this.blinkModule = BlinkModules.NONE;
    }

    public BlinkModules getBlinkingModule() {
        return this.blinkModule;
    }

    public int refusals() {
        return this.refusals;
    }

    public String lastRefusal() {
        return this.lastRefusal;
    }

    protected boolean connected() {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft != null && minecraft.getNetHandler() != null;
    }

    protected void send(Packet<?> packet) {
        PacketUtil.sendPacketNoEvent(packet);
    }

    public long countMovement() {
        return this.blinkedPackets.stream().filter(packet -> packet instanceof C03PacketPlayer).count();
    }

    public boolean isBlinking() {
        return blinking;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C00Handshake
                || event.getPacket() instanceof C00PacketLoginStart
                || event.getPacket() instanceof C00PacketServerQuery
                || event.getPacket() instanceof C01PacketPing
                || event.getPacket() instanceof C01PacketEncryptionResponse) {
            this.setBlinkState(false, this.blinkModule);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onWorldLoad(LoadWorldEvent event) {
        dropForWorldChange(event.getWorld() != null);
    }

    public void dropForWorldChange(boolean sendReplies) {
        this.blinking = false;
        this.blinkModule = BlinkModules.NONE;
        if (sendReplies) {
            discardHeld();
        } else {
            this.blinkedPackets.clear();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.POST) {
            if (mc.thePlayer.isDead) {
                this.setBlinkState(false, this.blinkModule);
            }
        }
    }
}
