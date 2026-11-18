package atlas.management;

import atlas.event.EventTarget;
import atlas.event.types.Priority;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import net.minecraft.client.Minecraft;
import atlas.util.Ping;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S19PacketEntityStatus;

public class HitTimer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int SAMPLES = 20;
    private static final long MAX_DELAY = 500L;
    private static final int MIN_MEASURED = 3;

    private static final byte HURT = 2;

    private static HitTimer instance;

    private final long[] delays = new long[SAMPLES];
    private int count;
    private int next;
    private volatile long lastAttackAt;
    private volatile int targetId = -1;

    public HitTimer() {
        instance = this;
    }

    public static HitTimer get() {
        return instance;
    }

    @EventTarget(Priority.LOWEST)
    public void onPacket(PacketEvent event) {
        if (mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.SEND) {
            if (!event.isCancelled() && event.getPacket() instanceof C02PacketUseEntity) {
                C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();
                if (use.getAction() == C02PacketUseEntity.Action.ATTACK) {
                    noteAttack(use.getEntityFromWorld(mc.theWorld));
                }
            }
            return;
        }
        if (event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
            if (status.getOpCode() != HURT || this.targetId < 0) {
                return;
            }
            Entity entity = status.getEntity(mc.theWorld);
            if (entity == null || entity.getEntityId() != this.targetId) {
                return;
            }
            long delay = System.currentTimeMillis() - this.lastAttackAt;
            if (delay >= 0L && delay < MAX_DELAY) {
                record(delay);
            }

            this.targetId = -1;
        }
    }

    @EventTarget
    public void onSession(atlas.events.SessionEvent event) {
        if (event.getType() == atlas.events.SessionEvent.Type.START && event.serverChanged()) {
            clear();
        }
        if (event.getType() != atlas.events.SessionEvent.Type.WORLD) {
            this.targetId = -1;
        }
    }

    synchronized void clear() {
        this.count = 0;
        this.next = 0;
    }

    private void noteAttack(Entity entity) {
        if (entity != null) {
            this.targetId = entity.getEntityId();
            this.lastAttackAt = System.currentTimeMillis();
        }
    }

    public static void noteAttackSent(Entity entity) {
        if (instance != null) {
            instance.noteAttack(entity);
        }
    }

    synchronized void record(long delay) {
        this.delays[this.next] = delay;
        this.next = (this.next + 1) % SAMPLES;
        if (this.count < SAMPLES) {
            this.count++;
        }
    }

    public synchronized long averageDelay() {
        if (this.count == 0) {
            int tab = Ping.tab();
            return tab >= 2 ? tab : 0;
        }
        long sum = 0L;
        for (int i = 0; i < this.count; i++) {
            sum += this.delays[i];
        }
        return sum / this.count;
    }

    public int expectedTicks() {
        return (int) (averageDelay() / 50L);
    }

    public synchronized long measured() {
        return this.count < MIN_MEASURED ? -1L : averageDelay();
    }

    public synchronized int samples() {
        return this.count;
    }

    public boolean vulnerable(EntityLivingBase target) {
        return target.hurtTime <= expectedTicks();
    }

    public static int ticks() {
        return instance == null ? 0 : instance.expectedTicks();
    }

    public static boolean canHurt(EntityLivingBase target) {
        return instance == null || instance.vulnerable(target);
    }
}
