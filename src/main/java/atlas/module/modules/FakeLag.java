package atlas.module.modules;

import atlas.util.AsyncLog;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.management.Arbiter;
import atlas.util.ActionLedger;
import atlas.util.ChatUtil;
import atlas.util.Ping;
import atlas.util.PacketUtil;
import atlas.util.RotationUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FakeLag extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int LATENCY = 0;

    private static final int DYNAMIC = 1;
    private static final int REPEL = 2;

    public final ModeProperty mode = new ModeProperty("mode", 0,
            new String[]{"LATENCY", "DYNAMIC", "REPEL"}).alias("LIQUID", "DYNAMIC")

            .retire("DYNAMIC", "REPEL");

    public final IntProperty delay = new IntProperty("delay", 150, 0, 1000);

    public final IntProperty pingBudget = new IntProperty("ping-budget", 250, 0, 600);
    static final int MIN_WORTH_HOLDING = 40;

    public final IntProperty transmissionOffset = new IntProperty("transmission-offset",
            0, 0, 400, () -> this.mode.getValue() == REPEL);

    public final FloatProperty range = new FloatProperty("range", 8.0F, 1.0F, 24.0F,
            () -> this.mode.getValue() != LATENCY);

    public final BooleanProperty releaseOnAttack = new BooleanProperty("release-on-attack",
            true, () -> this.mode.getValue() != LATENCY);

    public final IntProperty maxHeld = new IntProperty("max-held", 20, 4, 100);

    public final FloatProperty meleeDistance = new FloatProperty("melee-distance", 3.5F, 0.0F, 6.0F,
            () -> this.mode.getValue() != LATENCY);

    public final IntProperty recoilTime = new IntProperty("recoil-time", 750, 0, 2000);

    public final FloatProperty taper = new FloatProperty("taper", 1.5F, 0.0F, 4.0F,
            () -> this.mode.getValue() == DYNAMIC);

    public final IntProperty catchUp = new IntProperty("catch-up", 2, 2, 5,
            () -> this.mode.getValue() == DYNAMIC);

    public final ModeProperty releaseStyle = new ModeProperty("release-style", 0,
            new String[]{"IN_ORDER", "COLLAPSE"});

    public final IntProperty delayRandom = new IntProperty("delay-random", 100, 0, 500);

    public final BooleanProperty holdTransactions = new BooleanProperty("hold-transactions", true,
            () -> this.mode.getValue() == DYNAMIC);

    public final IntProperty flagPause = new IntProperty("flag-pause", 2000, 0, 5000);

    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true,
            () -> this.mode.getValue() != LATENCY);

    public final ModeProperty log = new ModeProperty("log", 2,
            new String[]{"OFF", "FILE", "CHAT+FILE"}, () -> this.mode.getValue() != LATENCY);

    private final ConcurrentLinkedQueue<Held> queue = new ConcurrentLinkedQueue<Held>();

    private static final String HUB = "FakeLag";

    private volatile int rolledDelay = 150;
    private long rolledAt;

    private C02PacketUseEntity heldHit;
    private net.minecraft.entity.EntityLivingBase heldHitTarget;
    private long heldHitAt;
    private long heldHitDeadline;
    private int heldHitHurt;
    private int hitsHeld;
    private int hitsMerged;

    private void rollDelay() {
        int spread = this.delayRandom.getValue();
        this.rolledDelay = this.delay.getValue() + (spread > 0 ? (int) (Math.random() * (spread + 1)) : 0);
        this.rolledAt = System.currentTimeMillis();
    }

    private int holdDelay() {
        return budgetedDelay(this.rolledDelay, Ping.own(), this.pingBudget.getValue());
    }

    static int budgetedDelay(int rolled, int ping, int budget) {
        if (budget <= 0 || ping < 0) {
            return rolled;
        }
        return Math.max(0, Math.min(rolled, budget - ping));
    }

    static boolean pingRulesOut(int rolled, int ping, int budget) {
        int left = budgetedDelay(rolled, ping, budget);
        return left < rolled && left < MIN_WORTH_HOLDING;
    }

    public int heldCount() {
        return this.queue.size();
    }

    private volatile long recoilUntil;

    private boolean holdAllowed;

    private volatile boolean easing;

    private volatile boolean drainingOut;
    static final int DRAIN_POSITIONS = 3;

    private volatile boolean flushRequested;
    private volatile boolean attackQueued;

    private volatile boolean releasing;

    private Vec3 lastSent;

    private boolean sentPositionThisTick;

    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    private static final int ENGAGEMENT_GAP_TICKS = 60;

    private static final int ENGAGEMENT_MIN_TICKS = 20;

    private File logTarget;

    private String blockedBy;

    private EntityPlayer currentTarget;

    private long episodeStart;
    private int episodeHeld;
    private int episodePeak;
    private String episodeTarget;
    private double episodeDistance;

    private int tickCount;
    private String engagedWith;
    private int engagedLastSeen;
    private int engagedTicks;
    private int engagedOpen;
    private int engagedHolds;
    private int engagedPackets;
    private final Map<String, Integer> engagedBlocked = new LinkedHashMap<String, Integer>();

    public FakeLag() {
        super("FakeLag", false, false,
                "Delays outgoing movement so the server's copy of this player runs behind the real one");

        atlas.management.PacketHub.register(HUB, atlas.management.PacketHub.MOVEMENT,
                () -> this.queue.size() + (this.heldHit != null ? 1 : 0), () -> {
                    releaseAll("packet hub");
                    if (this.heldHit != null) {
                        releaseHit("packet hub");
                    }
                });

        atlas.management.PacketHolds.register(() -> {
            int count = this.queue.size() + (this.heldHit != null ? 1 : 0);
            if (count == 0) {
                return null;
            }
            Held head = this.queue.peek();
            return new atlas.management.PacketHolds.Hold("FakeLag", atlas.management.PacketHolds.Direction.OUT, count, head == null ? 0L : head.stamp,
                    this.mode.getModeString() + (this.heldHit != null ? ", an attack held" : ""),
                    "after " + this.rolledDelay + "ms; at once for an attack, a placement or a catch");
        }, new atlas.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            @Override
            public long ceilingMs() {
                return delay.getValue() + delayRandom.getValue() + atlas.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                releaseHit("lease");
                releaseAll("lease");
            }
        });
    }

    @Override
    public void onEnabled() {
        this.queue.clear();
        this.flushRequested = false;
        this.attackQueued = false;
        this.releasing = false;
        this.lastSent = null;
        this.drainingOut = false;
        this.holdAllowed = false;

        this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
        this.blockedBy = null;
        this.currentTarget = null;
        this.episodeStart = 0L;
        this.engagedWith = null;
        this.logTarget = null;
        this.heldHit = null;
        this.heldHitTarget = null;
        this.hitsHeld = 0;
        this.hitsMerged = 0;
        rollDelay();
    }

    @Override
    public void onDisabled() {
        releaseHit("disabled");
        releaseAll("disabled");
        endEngagement();
        this.lastSent = null;
        this.currentTarget = null;
    }

    private static boolean delayable(Packet<?> packet) {
        return packet instanceof C03PacketPlayer
                || packet instanceof C02PacketUseEntity
                || packet instanceof C0APacketAnimation;
    }

    private static boolean mustBeCurrent(Packet<?> packet) {
        return packet instanceof C07PacketPlayerDigging
                || packet instanceof C08PacketPlayerBlockPlacement;
    }

    private static boolean queuesBehind(Packet<?> packet) {
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            return ((C08PacketPlayerBlockPlacement) packet).getPlacedBlockDirection() == 255;
        }
        return packet instanceof C07PacketPlayerDigging
                && ((C07PacketPlayerDigging) packet).getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM;
    }

    private boolean holdsReply(Packet<?> packet) {
        int mode = this.mode.getValue();
        return packet instanceof net.minecraft.network.play.client.C0FPacketConfirmTransaction
                && this.holdTransactions.getValue() && mode == DYNAMIC;
    }

    private static boolean keepsOrder(Packet<?> packet) {
        return packet instanceof C09PacketHeldItemChange
                || packet instanceof C0BPacketEntityAction;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        handlePacket(event);
        Packet<?> packet = event.getPacket();
        if (this.isEnabled() && event.getType() == EventType.SEND && !event.isCancelled()
                && packet instanceof C03PacketPlayer && ((C03PacketPlayer) packet).isMoving()) {
            C03PacketPlayer position = (C03PacketPlayer) packet;
            this.lastSent = new Vec3(position.getPositionX(), position.getPositionY(), position.getPositionZ());
        }
    }

    private void handlePacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Packet<?> packet = event.getPacket();

        if (event.getType() == EventType.RECEIVE) {
            if (packet instanceof S08PacketPlayerPosLook || packet instanceof S07PacketRespawn) {
                this.flushRequested = true;
                this.recoilUntil = System.currentTimeMillis()
                        + Math.max(this.recoilTime.getValue(), this.flagPause.getValue());
            } else if (hitsThisPlayer(packet)) {
                this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
                final String cause = packet instanceof S12PacketEntityVelocity ? "knockback"
                        : packet instanceof S27PacketExplosion ? "explosion" : "damage";

                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (FakeLag.this.isEnabled()) {
                            releaseAll(cause);
                        }
                    }
                });
            }
            return;
        }

        if (this.releasing || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.flushRequested && packet instanceof C03PacketPlayer.C06PacketPlayerPosLook) {
            discardStalePositions();

            this.flushRequested = false;
            return;
        }
        if (keepsOrder(packet) || this.holdsReply(packet)) {
            if (!this.queue.isEmpty() && !Arbiter.catching()) {
                if (!atlas.management.PacketHub.take(event, HUB)) {
                            return;
                        }
                        this.queue.offer(new Held(packet, System.currentTimeMillis()));
                noteHeld();
                enforceLimits();
            } else if (!this.queue.isEmpty()) {
                releaseAll(actionName(packet));
            }
            return;
        }
        if (packet instanceof C08PacketPlayerBlockPlacement && !this.queue.isEmpty()
                && atlas.util.ItemUtil.isHoldingBlock() && !Arbiter.catching()) {
            if (!atlas.management.PacketHub.take(event, HUB)) {
                    return;
                }
                this.queue.offer(new Held(packet, System.currentTimeMillis()));
            noteHeld();
            enforceLimits();
            return;
        }
        if (queuesBehind(packet) && !this.queue.isEmpty() && !Arbiter.catching()) {
            if (!atlas.management.PacketHub.take(event, HUB)) {
                return;
            }
            this.queue.offer(new Held(packet, System.currentTimeMillis()));
            noteHeld();
            enforceLimits();
            return;
        }
        if (mustBeCurrent(packet)) {
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            if (this.mode.getValue() == DYNAMIC && !this.queue.isEmpty() && !Arbiter.catching()) {
                if (!atlas.management.PacketHub.take(event, HUB)) {
                    return;
                }
                this.queue.offer(new Held(packet, System.currentTimeMillis()));
                this.drainingOut = true;
                noteHeld();
                enforceLimits();
                return;
            }
            releaseAll(actionName(packet));
            return;
        }
        if (this.mode.getValue() == REPEL) {
            if (!(packet instanceof C02PacketUseEntity)
                    || ((C02PacketUseEntity) packet).getAction() != C02PacketUseEntity.Action.ATTACK
                    || Arbiter.catching()) {
                return;
            }
            net.minecraft.entity.Entity entity = ((C02PacketUseEntity) packet).getEntityFromWorld(mc.theWorld);
            if (this.heldHit != null) {
                if (entity != null && entity == this.heldHitTarget) {
                    event.setCancelled(true);
                    this.hitsMerged++;
                    return;
                }

                releaseHit("switched target");
            }
            boolean armed = !this.weaponsOnly.getValue() || atlas.util.ItemUtil.hasRawUnbreakingEnchant();
            if (armed && entity instanceof EntityPlayer) {
                EntityPlayer target = (EntityPlayer) entity;
                int early = target.hurtTime - atlas.management.HitTimer.ticks();
                int spread = this.delayRandom.getValue();
                long window = this.delay.getValue() + this.transmissionOffset.getValue()
                        + (spread > 0 ? (long) (Math.random() * (spread + 1)) : 0L);
                if (early > 0 && early * 50L <= window) {
                    if (!atlas.management.PacketHub.take(event, HUB)) {
                                    return;
                                }
                                this.heldHit = (C02PacketUseEntity) packet;
                    this.heldHitTarget = target;
                    this.heldHitAt = System.currentTimeMillis();
                    this.heldHitDeadline = this.heldHitAt + window;
                    this.heldHitHurt = target.hurtTime;
                    this.hitsHeld++;
                }
            }
            return;
        }
        if (!delayable(packet)) {
            return;
        }
        if (Arbiter.catching()) {
            if (!this.queue.isEmpty()) {
                releaseAll("catch");
                Arbiter.yielded(this.getName());
            }
            return;
        }

        boolean attack = packet instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK;
        if (attack && this.mode.getValue() != LATENCY && this.releaseOnAttack.getValue()) {
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            releaseAll("attack");
            return;
        }
        if (!this.holdAllowed || System.currentTimeMillis() < this.recoilUntil) {
            if (!this.queue.isEmpty()) {
                if (this.easing && System.currentTimeMillis() >= this.recoilUntil
                        || this.drainingOut && this.mode.getValue() == DYNAMIC) {
                    if (!atlas.management.PacketHub.take(event, HUB)) {
                                    return;
                                }
                                this.queue.offer(new Held(packet, System.currentTimeMillis()));
                    noteHeld();
                    enforceLimits();
                    return;
                }
                releaseAll(this.holdAllowed ? "recoil" : String.valueOf(this.blockedBy));
            }
            return;
        }
        if (!mc.theWorld.isBlockLoaded(new BlockPos(mc.thePlayer.posX, 0.0, mc.thePlayer.posZ))) {
            return;
        }

        if (!atlas.management.PacketHub.take(event, HUB)) {
            return;
        }
        this.queue.offer(new Held(packet, System.currentTimeMillis()));
        noteHeld();
        enforceLimits();
    }

    private static String actionName(Packet<?> packet) {
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            return "right-click";
        }
        if (packet instanceof C07PacketPlayerDigging) {
            return "dig";
        }
        if (packet instanceof C09PacketHeldItemChange) {
            return "slot";
        }
        if (packet instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction.Action action = ((C0BPacketEntityAction) packet).getAction();
            return action == null ? "entity-action" : action.name().toLowerCase();
        }
        return packet.getClass().getSimpleName();
    }

    private void enforceLimits() {
        while (this.queue.size() > this.maxHeld.getValue()) {
            sendOne();
        }

        long deadline = holdDelay() + 500L;
        long now = System.currentTimeMillis();
        while (!this.queue.isEmpty() && now - this.queue.peek().stamp > deadline) {
            sendOne();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.POST) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
            this.queue.clear();
            this.heldHit = null;
            this.heldHitTarget = null;

            this.episodeStart = 0L;
            return;
        }

        closeEpisode("drained");
        if (this.heldHit != null) {
            net.minecraft.entity.EntityLivingBase target = this.heldHitTarget;
            if (Arbiter.catching()) {
                releaseHit("catch");
            } else if (target == null || target.isDead || mc.theWorld.getEntityByID(target.getEntityId()) != target) {
                this.heldHit = null;
                this.heldHitTarget = null;
            } else if (target.hurtTime <= atlas.management.HitTimer.ticks()) {
                releaseHit("hurtable");
            } else if (System.currentTimeMillis() >= this.heldHitDeadline) {
                releaseHit("window over");
            }
        }
        if (this.mode.getValue() == LATENCY && System.currentTimeMillis() - this.rolledAt > 2000L) {
            rollDelay();
        }
        if (this.flushRequested) {
            this.flushRequested = false;
            discardStalePositions();
            return;
        }
        if (mc.thePlayer.isDead) {
            this.heldHit = null;
            releaseAll("dead");
            return;
        }

        this.sentPositionThisTick = false;
        enforceLimits();

        int current = this.mode.getValue();
        if (current == DYNAMIC) {
            dynamicTick();
            return;
        }
        if (current == LATENCY) {
            this.blockedBy = null;
            this.holdAllowed = true;
            releaseOlderThan(holdDelay());
            return;
        }

        if (current == REPEL) {
            this.holdAllowed = false;
            if (!this.queue.isEmpty()) {
                releaseAll("repel");
            }
            return;
        }
    }

    private void dynamicTick() {
        long now = System.currentTimeMillis();
        this.drainingAhead = false;
        boolean hold = false;
        double taperFactor = 1.0;

        String blocked = ineligibility();
        EntityPlayer target = nearestTarget();
        if (target == null) {
            if (blocked == null) {
                blocked = "no-target";
            }
        } else if (blocked == null) {
            double eye = mc.thePlayer.getEyeHeight();
            Vec3 server = heldServerPosition().addVector(0.0, eye, 0.0);
            Vec3 client = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + eye, mc.thePlayer.posZ);
            double serverDistance = RotationUtil.distanceToBox(target, server);
            double clientDistance = RotationUtil.distanceToBox(target, client);
            if (serverDistance <= this.meleeDistance.getValue()) {
                this.recoilUntil = now + this.recoilTime.getValue();
                blocked = "melee";
            } else {
                hold = serverDistance >= clientDistance;
                if (!hold) {
                    blocked = "retreating";
                }
                float span = this.taper.getValue();
                if (span > 0.0F) {
                    taperFactor = Math.max(0.0, Math.min(1.0,
                            (serverDistance - this.meleeDistance.getValue()) / span));
                }
                double closingPerTick = this.closingSpeed(target, serverDistance);
                if (hold && mustDrain(serverDistance, this.meleeDistance.getValue(),
                        closingPerTick, heldPositions(), this.catchUp.getValue())) {
                    taperFactor = 0.0;
                    this.drainingAhead = true;
                }
            }
        }
        if (target == null || blocked != null) {
            this.lastDistanceTarget = null;
        }
        if (hold && now < this.recoilUntil) {
            blocked = "recoil";
        }
        observe(target, blocked);
        this.holdAllowed = hold;
        boolean recoiling = now < this.recoilUntil;
        this.easing = !hold && !recoiling && easesOut(blocked);
        if (!hold || recoiling) {
            if (!this.queue.isEmpty()) {
                if (this.easing) {
                    releaseDue(Long.MAX_VALUE, this.catchUp.getValue());
                    closeEpisode(blocked + ", let out");
                } else {
                    this.drainingOut = true;
                    releaseDue(Long.MAX_VALUE, Math.max(DRAIN_POSITIONS, this.catchUp.getValue()));
                    closeEpisode(blocked + ", drained");
                }
            }
            if (this.queue.isEmpty()) {
                this.drainingOut = false;
            }
            return;
        }
        this.drainingOut = false;

        long cutoff = now - (long) (holdDelay() * taperFactor);
        releaseDue(cutoff, taperFactor < 1.0 ? this.catchUp.getValue() : Integer.MAX_VALUE);
        if (taperFactor < 1.0) {
            closeEpisode(this.drainingAhead ? "ahead of melee" : "tapered");
        }
        this.drainingAhead = false;
    }

    private double lastServerDistance = -1.0;
    private EntityPlayer lastDistanceTarget;
    private double closing;
    private boolean drainingAhead;

    private double closingSpeed(EntityPlayer target, double serverDistance) {
        if (target != this.lastDistanceTarget || this.lastServerDistance < 0.0) {
            this.closing = 0.0;
        } else {
            double now = this.lastServerDistance - serverDistance;
            this.closing = this.closing * 0.5 + now * 0.5;
        }
        this.lastDistanceTarget = target;
        this.lastServerDistance = serverDistance;
        return this.closing;
    }

    private int heldPositions() {
        int count = 0;
        for (Held held : this.queue) {
            if (held.packet instanceof C03PacketPlayer) {
                count++;
            }
        }
        return count;
    }

    static boolean mustDrain(double serverDistance, double meleeDistance, double closingPerTick,
                             int heldPositions, int catchUp) {
        if (heldPositions <= 0 || closingPerTick <= 0.02) {
            return false;
        }
        double ticksLeft = (serverDistance - meleeDistance) / closingPerTick;
        double ticksToEmpty = heldPositions / (double) Math.max(1, catchUp - 1);
        return ticksToEmpty + 1.0 >= ticksLeft;
    }

    private static boolean easesOut(String reason) {
        return "retreating".equals(reason) || "not-moving".equals(reason) || "no-target".equals(reason)
                || "screen".equals(reason) || "using-item".equals(reason) || "water".equals(reason)
                || "blocks".equals(reason) || "no-weapon".equals(reason) || "ping".equals(reason);
    }

    private void releaseDue(long cutoff, int positions) {
        int sent = 0;
        while (!this.queue.isEmpty() && this.queue.peek().stamp <= cutoff) {
            if (this.queue.peek().packet instanceof C03PacketPlayer) {
                if (sent >= positions) {
                    return;
                }
                sent++;
            }
            sendOne();
        }
    }

    private Vec3 heldServerPosition() {
        for (Held held : this.queue) {
            if (held.packet instanceof C03PacketPlayer && ((C03PacketPlayer) held.packet).isMoving()) {
                if (this.lastSent != null) {
                    return this.lastSent;
                }
                C03PacketPlayer position = (C03PacketPlayer) held.packet;
                return new Vec3(position.getPositionX(), position.getPositionY(), position.getPositionZ());
            }
        }
        return new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    private String ineligibility() {
        if (mc.isSingleplayer()) {
            return "singleplayer";
        }
        if (mc.currentScreen != null) {
            return "screen";
        }
        if (mc.thePlayer.isUsingItem() || mc.gameSettings.keyBindUseItem.isKeyDown() || auraBlocking()) {
            return "using-item";
        }
        if (pingRulesOut(this.rolledDelay, Ping.own(), this.pingBudget.getValue())) {
            return "ping";
        }
        if (mc.thePlayer.isInWater()) {
            return "water";
        }

        if (atlas.util.ItemUtil.isHoldingBlock()) {
            return "blocks";
        }
        if (this.weaponsOnly.getValue() && !atlas.util.ItemUtil.hasRawUnbreakingEnchant()) {
            return "no-weapon";
        }
        if (mc.thePlayer.hurtTime != 0) {
            return "hurt";
        }
        if (mc.thePlayer.movementInput.moveForward == 0.0F
                && mc.thePlayer.movementInput.moveStrafe == 0.0F) {
            return "not-moving";
        }
        return null;
    }

    private static boolean auraBlocking() {
        KillAura aura = (KillAura) atlas.Atlas.moduleManager.getModule(KillAura.class);
        if (aura != null && aura.isEnabled() && aura.isAuraBlocking()) {
            return true;
        }
        return false;
    }

    private void releaseAll(String reason) {
        this.easing = false;
        this.drainingOut = false;
        if (this.releaseStyle.getValue() == 1) {
            flush();
        } else {
            flushInOrder();
        }
        closeEpisode(reason);
    }

    private static boolean hitsThisPlayer(Packet<?> packet) {
        if (mc.thePlayer == null) {
            return false;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            return velocity.getEntityID() == mc.thePlayer.getEntityId()
                    && (velocity.getMotionX() != 0 || velocity.getMotionY() != 0 || velocity.getMotionZ() != 0);
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F;
        }
        if (packet instanceof S06PacketUpdateHealth) {
            return ((S06PacketUpdateHealth) packet).getHealth() < mc.thePlayer.getHealth();
        }
        return false;
    }

    private void flushInOrder() {
        int remaining = this.queue.size();
        for (int i = 0; i < remaining && !this.queue.isEmpty(); i++) {
            sendOne();
        }
        this.attackQueued = false;
    }

    private EntityPlayer nearestTarget() {
        EntityPlayer best = null;
        double bestDistance = this.range.getValue();
        Vec3 eyes = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player == mc.thePlayer.ridingEntity
                    || player.isDead || player.deathTime > 0) {
                continue;
            }
            if (!TargetFilter.accepts(player)) {
                continue;
            }
            if (TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player)
                    || TeamUtil.isBot(player)) {
                continue;
            }
            double distance = RotationUtil.distanceToBox(player, eyes);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void releaseOlderThan(int millis) {
        long now = System.currentTimeMillis();
        while (!this.queue.isEmpty()) {
            Held head = this.queue.peek();
            if (now - head.stamp < millis) {
                return;
            }
            if (head.packet instanceof C03PacketPlayer) {
                if (this.sentPositionThisTick) {
                    return;
                }
                this.sentPositionThisTick = true;
            }
            sendOne();
        }
    }

    private void flush() {
        Held lastPosition = null;
        for (Held held : this.queue) {
            if (held.packet instanceof C03PacketPlayer) {
                lastPosition = held;
            }
        }
        int remaining = this.queue.size();
        for (int i = 0; i < remaining; i++) {
            Held held = this.queue.poll();
            if (held == null) {
                break;
            }
            if (held.packet instanceof C03PacketPlayer && held != lastPosition) {
                continue;
            }
            send(held.packet);
            if (held == lastPosition) {
                C03PacketPlayer position = (C03PacketPlayer) held.packet;
                if (position.isMoving()) {
                    this.lastSent = new Vec3(position.getPositionX(),
                            position.getPositionY(), position.getPositionZ());
                }
                this.sentPositionThisTick = true;
            }
        }
        this.attackQueued = false;
    }

    private void discardStalePositions() {
        releaseHit("correction");
        int remaining = this.queue.size();
        for (int i = 0; i < remaining; i++) {
            Held held = this.queue.poll();
            if (held == null) {
                break;
            }
            if (held.packet instanceof C03PacketPlayer) {
                continue;
            }
            send(held.packet);
        }
        this.attackQueued = false;

        this.lastSent = null;
        closeEpisode("correction");
    }

    private void sendOne() {
        Held held = this.queue.poll();
        if (held == null) {
            return;
        }
        send(held.packet);
        if (held.packet instanceof C03PacketPlayer) {
            C03PacketPlayer position = (C03PacketPlayer) held.packet;
            if (position.isMoving()) {
                this.lastSent = new Vec3(position.getPositionX(),
                        position.getPositionY(), position.getPositionZ());
            }
        }
    }

    private void releaseHit(String reason) {
        C02PacketUseEntity hit = this.heldHit;
        if (hit == null) {
            return;
        }
        this.heldHit = null;
        net.minecraft.entity.EntityLivingBase target = this.heldHitTarget;
        this.heldHitTarget = null;
        if (mc.thePlayer == null || mc.getNetHandler() == null) {
            return;
        }
        if (!this.queue.isEmpty() && this.mode.getValue() != LATENCY && this.releaseOnAttack.getValue()) {
            this.recoilUntil = System.currentTimeMillis() + this.recoilTime.getValue();
            releaseAll("attack");
        }
        send(new C0APacketAnimation());
        send(hit);
        if (this.log.getValue() != 0) {
            writeLog(String.format("HIT held %dms | %s hurt %d->%d | %s",
                    System.currentTimeMillis() - this.heldHitAt,
                    target == null ? "?" : target.getName(), this.heldHitHurt,
                    target == null ? -1 : target.hurtTime, reason));
        }
    }

    private void send(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) packet).getAction() == C02PacketUseEntity.Action.ATTACK
                && mc.theWorld != null) {
            net.minecraft.entity.Entity attacked = ((C02PacketUseEntity) packet).getEntityFromWorld(mc.theWorld);
            atlas.management.HitTimer.noteAttackSent(attacked);
            FightLog.noteAttackSent(attacked);
        }
        this.releasing = true;
        try {
            ActionLedger.note(this.getName(), "release");
            PacketUtil.sendPacketNoEvent(packet);
        } catch (Exception ignored) {
            this.queue.clear();
        } finally {
            this.releasing = false;
        }
    }

    private void noteHeld() {
        if (this.episodeStart == 0L) {
            if (this.mode.getValue() != LATENCY) {
                rollDelay();
            }
            this.episodeStart = System.currentTimeMillis();
            this.episodeHeld = 0;
            this.episodePeak = 0;
            EntityPlayer target = this.currentTarget;
            this.episodeTarget = target == null ? null : target.getName();
            this.episodeDistance = target == null ? -1.0 : eyeDistance(target);
        }
        this.episodeHeld++;
        this.episodePeak = Math.max(this.episodePeak, this.queue.size());
    }

    private void closeEpisode(String reason) {
        if (this.episodeStart == 0L || !this.queue.isEmpty()) {
            return;
        }
        long lasted = System.currentTimeMillis() - this.episodeStart;
        this.episodeStart = 0L;
        if (this.engagedWith != null) {
            this.engagedHolds++;
            this.engagedPackets += this.episodeHeld;
        }
        int where = this.log.getValue();
        if (where == 0 || this.mode.getValue() == LATENCY) {
            return;
        }
        String against = "no target";
        if (this.episodeTarget != null) {
            EntityPlayer target = this.currentTarget;
            String now = target != null && this.episodeTarget.equals(target.getName())
                    ? String.format("%.1f", eyeDistance(target)) : "?";
            against = String.format("%s %.1f->%s", this.episodeTarget, this.episodeDistance, now);
        }
        writeLog(String.format("HOLD %dp %dms peak %d delay %d | %s | %s",
                this.episodeHeld, lasted, this.episodePeak, holdDelay(), against, reason));
        if (where == 2) {
            chat(String.format("&7[&bFakeLag&7] held &f%dp&7 for &f%dms&7 (peak %d) &8| &7%s &8| &7released: &f%s",
                    this.episodeHeld, lasted, this.episodePeak, against, reason));
        }
    }

    private void observe(EntityPlayer target, String blocked) {
        this.blockedBy = blocked;
        this.currentTarget = target;
        this.tickCount++;
        if (target == null) {
            if (this.engagedWith != null
                    && this.tickCount - this.engagedLastSeen > ENGAGEMENT_GAP_TICKS) {
                endEngagement();
            }
            return;
        }
        String name = target.getName();
        if (this.engagedWith != null && !this.engagedWith.equals(name)) {
            endEngagement();
        }
        if (this.engagedWith == null) {
            this.engagedWith = name;
            this.engagedTicks = 0;
            this.engagedOpen = 0;
            this.engagedHolds = 0;
            this.engagedPackets = 0;
            this.engagedBlocked.clear();
        }
        this.engagedLastSeen = this.tickCount;
        this.engagedTicks++;
        if (blocked == null) {
            this.engagedOpen++;
        } else {
            Integer seen = this.engagedBlocked.get(blocked);
            this.engagedBlocked.put(blocked, seen == null ? 1 : seen + 1);
        }
    }

    private void endEngagement() {
        if (this.engagedWith == null) {
            return;
        }
        String name = this.engagedWith;
        this.engagedWith = null;
        if (this.engagedTicks < ENGAGEMENT_MIN_TICKS || this.log.getValue() == 0
                || this.mode.getValue() == LATENCY) {
            return;
        }
        StringBuilder sb = new StringBuilder(String.format(
                "ENGAGE %s %.1fs in range | holding allowed %dt | holds %d (%dp)",
                name, this.engagedTicks / 20.0, this.engagedOpen, this.engagedHolds, this.engagedPackets));
        if (!this.engagedBlocked.isEmpty()) {
            List<Map.Entry<String, Integer>> reasons =
                    new ArrayList<Map.Entry<String, Integer>>(this.engagedBlocked.entrySet());
            Collections.sort(reasons, new Comparator<Map.Entry<String, Integer>>() {
                @Override
                public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                    return b.getValue() - a.getValue();
                }
            });
            sb.append(" | ruled out:");
            for (Map.Entry<String, Integer> reason : reasons) {
                sb.append(' ').append(reason.getKey()).append(' ').append(reason.getValue()).append('t');
            }
        }
        writeLog(sb.toString());
    }

    private double eyeDistance(EntityPlayer target) {
        return RotationUtil.distanceToBox(target, new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ));
    }

    private static void chat(final String line) {
        if (mc.isCallingFromMinecraftThread()) {
            ChatUtil.sendFormatted(line);
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                ChatUtil.sendFormatted(line);
            }
        });
    }

    private void writeLog(String line) {
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "fakelag-" + FILE_STAMP.format(new Date()) + ".txt");
        }

        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    @Override
    public String[] getSuffix() {
        String label = this.mode.getModeString().toLowerCase();
        int held = this.queue.size();
        String hits = this.mode.getValue() == REPEL ? this.hitsHeld + " held, " + this.hitsMerged + " merged" : null;
        if (held == 0) {
            return hits == null ? new String[]{label} : new String[]{label, hits};
        }
        return hits == null ? new String[]{label, held + "p"} : new String[]{label, held + "p", hits};
    }

    private static final class Held {
        final Packet<?> packet;
        final long stamp;

        Held(Packet<?> packet, long stamp) {
            this.packet = packet;
            this.stamp = stamp;
        }
    }
}
