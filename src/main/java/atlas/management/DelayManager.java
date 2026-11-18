package atlas.management;

import atlas.enums.DelayModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public class DelayManager {
    public static Minecraft mc = Minecraft.getMinecraft();
    public DelayModules delayModule = DelayModules.NONE;
    public long delay = 0L;
    public Deque<Packet<INetHandlerPlayClient>> delayedPacket = new ConcurrentLinkedDeque<>();

    private volatile long since;

    public DelayManager() {
        PacketHolds.register(() -> {
            int count = this.delayedPacket.size();
            if (count == 0) {
                return null;
            }
            return new PacketHolds.Hold("DelayManager/" + this.delayModule, PacketHolds.Direction.IN, count,
                    this.since, "held ahead of the event bus",
                    "when " + this.delayModule + " lets go; all on a catch or a world change");
        }, new PacketHolds.Lease() {
            @Override
            public String owner() {
                return moduleName(DelayManager.this.delayModule);
            }

            @Override
            public long ceilingMs() {
                return DelayManager.this.delayModule == DelayModules.NONE ? 0L : -1L;
            }

            @Override
            public void expire() {
                DelayManager.this.delayModule = DelayModules.NONE;
                releaseDelayed();
            }
        });
    }

    static String moduleName(DelayModules module) {
        switch (module) {
            case VELOCITY:
                return "Velocity";
            case BED_NUKER:
                return "BedNuker";
            default:
                return null;
        }
    }

    public boolean shouldDelay(Packet<INetHandlerPlayClient> packet) {
        if (this.delayModule == DelayModules.NONE) {
            return false;
        }
        if (Arbiter.catching()) {
            if (!this.delayedPacket.isEmpty()) {
                releaseDelayed();
                Arbiter.yielded(this.delayModule.name());
            }
            return false;
        }
        if (packet instanceof S00PacketKeepAlive) {
            return false;
        } else if (PacketUtil.isWorldRenderPacket(packet)) {
            return false;
        } else if (!(packet instanceof S01PacketJoinGame) && !(packet instanceof S07PacketRespawn)) {
            if (packet instanceof S19PacketEntityStatus) {
                S19PacketEntityStatus s19 = (S19PacketEntityStatus) packet;
                Entity entity = s19.getEntity(mc.theWorld);
                if (entity != null && (!entity.equals(mc.thePlayer) || s19.getOpCode() != 2)) {
                    return false;
                }
            }
            if (this.delayedPacket.isEmpty()) {
                this.since = System.currentTimeMillis();
            }
            this.delayedPacket.offer(packet);
            return true;
        } else {
            this.clearDelayState();
            return false;
        }
    }

    private void releaseDelayed() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.getNetHandler() == null) {
            this.delayedPacket.clear();
            return;
        }
        Packet<INetHandlerPlayClient> packet;
        while ((packet = this.delayedPacket.poll()) != null) {
            try {
                packet.processPacket(Minecraft.getMinecraft().getNetHandler());
            } catch (net.minecraft.network.ThreadQuickExitException ignored) {
            }
        }
    }

    public boolean setDelayState(boolean state, DelayModules delayModule) {
        if (state) {
            this.delay = 0;
            this.delayModule = delayModule;
        } else {
            this.delayModule = DelayModules.NONE;
            if (Minecraft.getMinecraft().getNetHandler() != null && this.delayedPacket.isEmpty()) {
                return true;
            }
            if (Minecraft.getMinecraft().getNetHandler() == null) {
                this.delayedPacket.clear();
                return false;
            }
            while (true) {
                Packet<INetHandlerPlayClient> packet = this.delayedPacket.poll();
                if (packet == null) {
                    this.delayedPacket.clear();
                    break;
                }
                try {
                    packet.processPacket(Minecraft.getMinecraft().getNetHandler());
                } catch (net.minecraft.network.ThreadQuickExitException ignored) {
                }
            }
        }
        return this.delayModule != DelayModules.NONE;
    }

    public void clearDelayState() {
        this.delayModule = DelayModules.NONE;
        this.delay = 0L;
        this.delayedPacket.clear();
    }

    public DelayModules getDelayModule() {
        return this.delayModule;
    }

    public void delay(DelayModules modules) {
        this.delayModule = modules;
    }

    public long getDelay() {
        return this.delay;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof C00Handshake
                || event.getPacket() instanceof C00PacketLoginStart
            || event.getPacket() instanceof C00PacketServerQuery
            || event.getPacket() instanceof C01PacketPing
            || event.getPacket() instanceof C01PacketEncryptionResponse) {
            this.clearDelayState();
        }
    }

    @EventTarget
    public void onLoadWorld(LoadWorldEvent event) {
        this.clearDelayState();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.POST) {
            if (mc.thePlayer == null || mc.thePlayer.isDead) {
                this.setDelayState(false, this.delayModule);
            }
            if (this.delayModule != DelayModules.NONE) {
                this.delay++;
            }
        }
    }
}
