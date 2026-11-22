package atlas.module.modules;

import atlas.util.Ping;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.management.Arbiter;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.DelayLine;
import atlas.util.ItemUtil;
import atlas.util.LatencyRamp;
import atlas.util.PacketUtil;
import atlas.util.RandomUtil;
import atlas.util.RotationUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.lang.reflect.Field;
import java.util.List;

public class KnockbackDelay extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int STEADY = 0;
    private static final int SPIKE = 1;

    private static final int AIR = 2;
    public final ModeProperty mode = new ModeProperty("Mode", SPIKE, new String[]{"Steady", "Spike", "Air"});
    private final java.util.function.BooleanSupplier steady = () -> this.mode.getValue() == STEADY;
    private final java.util.function.BooleanSupplier spike = () -> this.mode.getValue() == SPIKE;
    private final java.util.function.BooleanSupplier air = () -> this.mode.getValue() == AIR;

    public final IntProperty steadyDelay = new IntProperty("SteadyDelay", 100, 0, 400, this.steady);

    public final IntProperty ramp = new IntProperty("Ramp", 10, 2, 50, this.steady);

    public final IntProperty airDelay = new IntProperty("AirDelay", 75, 0, 1000, this.spike);
    public final IntProperty groundDelay = new IntProperty("GroundDelay", 225, 0, 1000, this.spike);

    public final IntProperty randomize = new IntProperty("Randomize", 25, 0, 200);

    public final IntProperty groundTicks = new IntProperty("GroundTicks", 3, 1, 20, this.spike);
    public final IntProperty cooldown = new IntProperty("Cooldown", 475, 0, 2000, this.spike);
    public final IntProperty chance = new IntProperty("Chance", 40, 0, 100, this.spike);

    public final IntProperty airMax = new IntProperty("AirMax", 100, 10, 400, this.air);
    public final IntProperty airChance = new IntProperty("AirChance", 100, 0, 100, this.air);

    public final FloatProperty maxDivergence = new FloatProperty("MaxDivergence", 3.0F, 0.1F, 3.0F, this.spike);

    public final IntProperty latencyBudget = new IntProperty("LatencyBudget", 450, 100, 1200);
    public final BooleanProperty pingAware = new BooleanProperty("PingAware", true);

    public final BooleanProperty requireTarget = new BooleanProperty("RequireTarget", true);
    public final FloatProperty targetRange = new FloatProperty("TargetRange", 5.0F, 1.0F, 12.0F,
            this.requireTarget::getValue);
    public final FloatProperty targetFov = new FloatProperty("TargetFOV", 90.0F, 10.0F, 360.0F,
            this.requireTarget::getValue);

    public final BooleanProperty holdOthers = new BooleanProperty("HoldOthers", true, this.spike);

    public final FloatProperty maxVertical = new FloatProperty("MaxVertical", 2.0F, 0.0F, 2.0F, this.spike);

    public final BooleanProperty waterCheck = new BooleanProperty("WaterCheck", false);

    public final BooleanProperty realtimeDamage = new BooleanProperty("RealtimeDamage", false);
    public final BooleanProperty onlySwords = new BooleanProperty("OnlySwords", false);
    public final BooleanProperty requireAttackKey = new BooleanProperty("RequireAttackKey", false);
    public final BooleanProperty onlyInAir = new BooleanProperty("OnlyInAir", false, this.spike);

    public final BooleanProperty holdTransactions = new BooleanProperty("HoldTransactions", true,
            () -> this.spike.getAsBoolean() && !this.holdOthers.getValue());

    private final DelayLine<Packet<?>> line = new DelayLine<Packet<?>>();

    private final Object lock = new Object();
    private final LatencyRamp latency = new LatencyRamp();

    private volatile long releaseAt;
    private volatile long lastTrigger;
    private volatile int groundedTicks;
    private volatile boolean conditionsMet;
    private volatile int delayBase;
    private volatile int selfEntityId = Integer.MIN_VALUE;
    private volatile int delayCeiling = Integer.MAX_VALUE;

    private volatile long steadyNow;

    private boolean divergenceArmed;
    private double divergence;
    private double lastX;
    private double lastZ;

    private static Field STATUS_ENTITY_ID;
    private static Thread releaser;

    public KnockbackDelay() {
        super("KnockbackDelay", false, false,
                "Holds incoming knockback for a short window so the push lands late");

        atlas.management.PacketHub.registerIncoming("KnockbackDelay", () -> this.line.size(), this::flush);

        atlas.management.PacketHolds.register(() -> {
            int count = this.line.size();
            if (count == 0) {
                return null;
            }
            return new atlas.management.PacketHolds.Hold("KnockbackDelay", atlas.management.PacketHolds.Direction.IN, count,
                    this.line.oldestArrival(), this.mode.getValue() == STEADY ? "the stream, " + this.steadyNow + "ms late"
                    : "own knockback", "when due; at once on a correction or a catch");
        }, new atlas.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            @Override
            public long ceilingMs() {
                return Math.max(Math.max(Math.max(airDelay.getValue(), groundDelay.getValue()), steadyDelay.getValue()),
                        Math.round(airMax.getValue() * 1.35F))
                        + randomize.getValue() + atlas.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                flush();
            }
        });
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == STEADY) {
            return new String[]{this.steadyNow > 0 ? "&c+" + this.steadyNow + "ms" : "Steady"};
        }
        if (!this.line.isEmpty()) {
            return new String[]{"&cholding"};
        }
        if (this.mode.getValue() == AIR) {
            return new String[]{"Air " + this.airMax.getValue() + "ms"};
        }
        return new String[]{this.airDelay.getValue() + "/" + this.groundDelay.getValue() + "ms"};
    }

    @Override
    public void onEnabled() {
        this.releaseAt = 0L;
        this.lastTrigger = 0L;
        this.groundedTicks = 0;
        this.latency.reset();
        this.steadyNow = 0L;
        startReleaser();
    }

    @Override
    public void onDisabled() {
        this.flush();
        this.latency.reset();
        this.steadyNow = 0L;
    }

    public int heldCount() {
        return this.line.size();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        synchronized (this.lock) {
            this.line.clear();
        }
        this.releaseAt = 0L;
        this.groundedTicks = 0;
        this.latency.reset();
        this.steadyNow = 0L;
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.groundedTicks = mc.thePlayer.onGround ? this.groundedTicks + 1 : 0;
        this.selfEntityId = mc.thePlayer.getEntityId();
        atlas.management.LagGuard.wholeStream(this.getName(), this.isEnabled()
                && (this.mode.getValue() != SPIKE || this.holdOthers.getValue()));
        if (!this.isEnabled()) {
            return;
        }
        if (mc.currentScreen != null || mc.thePlayer.isDead || Arbiter.catching()) {
            if (Arbiter.catching() && !this.line.isEmpty()) {
                Arbiter.yielded(this.getName());
            }
            this.conditionsMet = false;
            this.latency.reset();
            this.steadyNow = 0L;
            this.flush();
            return;
        }
        this.evaluateConditions();
        if (this.mode.getValue() == STEADY) {
            double target = this.conditionsMet ? Math.min(this.steadyDelay.getValue(), this.delayCeiling) : 0.0;
            this.steadyNow = Math.round(this.latency.tick(Math.max(0.0, target), this.ramp.getValue(),
                    this.randomize.getValue()));
            return;
        }
        if (!this.line.isEmpty()) {
            this.trackDivergence();
        } else {
            this.divergenceArmed = false;
            this.lastX = mc.thePlayer.posX;
            this.lastZ = mc.thePlayer.posZ;
        }
    }

    private void trackDivergence() {
        if (!this.divergenceArmed) {
            this.divergenceArmed = true;
            this.divergence = 0.0;
            this.lastX = mc.thePlayer.posX;
            this.lastZ = mc.thePlayer.posZ;
            return;
        }
        double dx = mc.thePlayer.posX - this.lastX;
        double dz = mc.thePlayer.posZ - this.lastZ;
        this.lastX = mc.thePlayer.posX;
        this.lastZ = mc.thePlayer.posZ;
        this.divergence += Math.sqrt(dx * dx + dz * dz);
        if (this.divergence > this.maxDivergence.getValue()) {
            this.releaseAt = 1L;
        }
    }

    private void evaluateConditions() {
        this.delayBase = this.mode.getValue() == AIR ? this.airMax.getValue()
                : this.groundedTicks >= this.groundTicks.getValue()
                ? this.groundDelay.getValue()
                : this.airDelay.getValue();
        this.delayCeiling = (int) Math.min(this.computeCeiling(),
                atlas.management.LagGuard.streamAllowanceMs(this.getName()));

        if (this.waterCheck.getValue() && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) {
            this.conditionsMet = false;
            return;
        }
        if (this.onlySwords.getValue() && !ItemUtil.isHoldingSword()) {
            this.conditionsMet = false;
            return;
        }
        if (this.requireAttackKey.getValue()
                && !atlas.util.KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            this.conditionsMet = false;
            return;
        }
        if ((this.mode.getValue() == AIR || this.mode.getValue() == SPIKE && this.onlyInAir.getValue())
                && mc.thePlayer.onGround) {
            this.conditionsMet = false;
            return;
        }
        this.conditionsMet = !this.requireTarget.getValue() || this.findTarget() != null;
    }

    private int computeCeiling() {
        if (!this.pingAware.getValue()) {
            return Integer.MAX_VALUE;
        }
        int ping = Ping.own();
        if (ping <= 0) {
            return Math.min(this.airDelay.getValue(), this.steadyDelay.getValue());
        }
        return this.latencyBudget.getValue() - ping;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S07PacketRespawn
                || packet instanceof S01PacketJoinGame || packet instanceof S40PacketDisconnect) {
            this.flush();
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.isSingleplayer() || mc.thePlayer.ticksExisted < 20) {
            return;
        }
        if (Arbiter.catching()) {
            this.flush();
            return;
        }
        synchronized (this.lock) {
            boolean holding = !this.line.isEmpty();
            if (this.realtimeDamage.getValue() && isOwnHurt(packet)) {
                return;
            }
            if (this.mode.getValue() == STEADY) {
                if (holding || this.steadyNow > 0L) {
                    this.hold(event, packet);
                }
                return;
            }
            this.spikePacket(event, packet, holding);
        }
    }

    private void spikePacket(PacketEvent event, Packet<?> packet, boolean holding) {
        if (holding) {
            boolean push = this.isPlayerKnockbackPacket(packet);
            boolean others = this.mode.getValue() == AIR || this.holdOthers.getValue();
            if (!others && !push && !(packet instanceof S32PacketConfirmTransaction && this.holdTransactions.getValue())) {
                return;
            }
            if (push) {
                this.releaseAt = 1L;
            }
            this.hold(event, packet);
            return;
        }
        if (packet instanceof S00PacketKeepAlive || packet instanceof S32PacketConfirmTransaction
                || PacketUtil.isWorldRenderPacket(packet)) {
            return;
        }
        if (!this.isHoldablePush(packet) || !this.canStart()) {
            return;
        }
        int delay = this.rollDelay();
        if (delay <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!atlas.management.PacketHub.take(event, "KnockbackDelay")) {
            return;
        }
        this.lastTrigger = now;
        this.releaseAt = now + delay;
        this.line.add(packet, now);
        atlas.util.ActionLedger.note(this.getName(), "held-self");
    }

    private void hold(PacketEvent event, Packet<?> packet) {
        if (atlas.management.PacketHub.take(event, "KnockbackDelay")) {
            this.line.add(packet, System.currentTimeMillis());
            return;
        }
        if (PingFix.exempt(packet)) {
            return;
        }
        if (!event.isCancelled()) {
            this.flush();
        }
    }

    private boolean isOwnHurt(Packet<?> packet) {
        return packet instanceof S19PacketEntityStatus && ((S19PacketEntityStatus) packet).getOpCode() == 2
                && statusEntityId((S19PacketEntityStatus) packet) == this.selfEntityId;
    }

    private int rollDelay() {
        int base = Math.min(this.delayBase, this.delayCeiling);
        if (base <= 0) {
            return 0;
        }
        if (this.mode.getValue() == AIR) {
            double factor = Math.max(0.75, Math.min(1.35, Math.exp(java.util.concurrent.ThreadLocalRandom.current()
                    .nextGaussian() * 0.12)));
            return Math.max(1, Math.min((int) Math.round(this.delayBase * factor), this.delayCeiling));
        }
        int spread = Math.min(this.randomize.getValue(), base);
        if (spread <= 0) {
            return base;
        }
        int delay = base - spread + RandomUtil.nextInt(0, spread * 2);
        return Math.max(0, Math.min(delay, this.delayCeiling));
    }

    private boolean canStart() {
        if (!this.conditionsMet) {
            return false;
        }
        if (EdgeGuard.claimsPush()) {
            return false;
        }
        if (this.mode.getValue() == AIR) {
            return RandomUtil.nextInt(0, 100) < this.airChance.getValue();
        }
        if (System.currentTimeMillis() - this.lastTrigger < (long) this.cooldown.getValue()) {
            return false;
        }

        if (FightBrain.forcesKnockbackDelay()) {
            return true;
        }
        return RandomUtil.nextInt(0, 100) <= this.chance.getValue();
    }

    private static int statusEntityId(S19PacketEntityStatus status) {
        try {
            if (STATUS_ENTITY_ID == null) {
                Field field = S19PacketEntityStatus.class.getDeclaredField("entityId");
                field.setAccessible(true);
                STATUS_ENTITY_ID = field;
            }
            return STATUS_ENTITY_ID.getInt(status);
        } catch (Exception ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private boolean isHoldablePush(Packet<?> packet) {
        if (!this.isPlayerKnockbackPacket(packet)) {
            return false;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            double motionY = ((S12PacketEntityVelocity) packet).getMotionY() / 8000.0;
            return this.mode.getValue() == AIR || motionY <= this.maxVertical.getValue();
        }
        return true;
    }

    private boolean isPlayerKnockbackPacket(Packet<?> packet) {
        if (packet instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) packet).getEntityID() == this.selfEntityId;
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            return explosion.func_149149_c() != 0.0F
                    || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F;
        }
        return false;
    }

    private volatile boolean flushRequested;

    private void flush() {
        if (mc.isCallingFromMinecraftThread()) {
            this.flushRequested = true;
            this.startReleaser();
            return;
        }
        this.drainNow();
    }

    private void drainNow() {
        synchronized (this.lock) {
            List<Packet<?>> out = this.line.drain();
            if (!out.isEmpty()) {
                atlas.util.ActionLedger.note(this.getName(), "release-self", out.size());
            }
            for (Packet<?> packet : out) {
                this.processPacketSilent(packet);
            }
            this.releaseAt = 0L;
        }
    }

    private void releaseDue() {
        synchronized (this.lock) {
            if (this.line.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            List<Packet<?>> out;
            if (this.mode.getValue() == STEADY) {
                out = this.line.due(now, this.steadyNow);
            } else {
                out = now >= this.releaseAt ? this.line.drain() : java.util.Collections.<Packet<?>>emptyList();
            }
            if (!out.isEmpty()) {
                atlas.util.ActionLedger.note(this.getName(), "release-self", out.size());
            }
            for (Packet<?> packet : out) {
                this.processPacketSilent(packet);
            }
            if (this.line.isEmpty()) {
                this.releaseAt = 0L;
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processPacketSilent(Packet<?> packet) {
        try {
            if (mc.getNetHandler() != null) {
                ((Packet) packet).processPacket(mc.getNetHandler());
            }
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startReleaser() {
        synchronized (KnockbackDelay.class) {
            if (releaser != null && releaser.isAlive()) {
                return;
            }
            final KnockbackDelay self = this;
            releaser = new Thread(() -> {
                while (true) {
                    try {
                        if (self.flushRequested) {
                            self.flushRequested = false;
                            self.drainNow();
                        } else if (self.isEnabled()) {
                            self.releaseDue();
                        }
                        Thread.sleep(self.line.isEmpty() ? 5L : 1L);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }, "Atlas KnockbackDelay");
            releaser.setDaemon(true);
            releaser.start();
        }
    }

    private Entity findTarget() {
        KillAura killAura = (KillAura) Atlas.moduleManager.modules.get(KillAura.class);
        if (killAura != null && killAura.isEnabled()) {
            Entity entity = killAura.getTarget();
            if (entity != null) {
                return entity;
            }
        }
        if (mc.pointedEntity != null) {
            return mc.pointedEntity;
        }
        if (mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            return mc.objectMouseOver.entityHit;
        }
        return this.scanForTarget();
    }

    private Entity scanForTarget() {
        double range = this.targetRange.getValue();
        double halfFov = this.targetFov.getValue() / 2.0;
        Vec3 eyes = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);

        Entity best = null;
        double bestDistance = range;
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) object;
            if (player == mc.thePlayer || player.deathTime > 0
                    || TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player)
                    || TeamUtil.isBot(player)) {
                continue;
            }
            double distance = RotationUtil.distanceToBox(player, eyes);
            if (distance > bestDistance) {
                continue;
            }
            if (halfFov < 180.0) {
                double dx = player.posX - mc.thePlayer.posX;
                double dz = player.posZ - mc.thePlayer.posZ;
                float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float offset = MathHelper.wrapAngleTo180_float(yawTo - mc.thePlayer.rotationYaw);
                if (Math.abs(offset) > halfFov) {
                    continue;
                }
            }
            best = player;
            bestDistance = distance;
        }
        return best;
    }
}
