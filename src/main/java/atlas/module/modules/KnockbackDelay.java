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

/**
 * Your knockback lands late (2026-10-27 rebuild, owner: "no flags -- the
 * server must believe the latency is real").
 *
 * Everything here is on the receive side: what the server sent is processed
 * later. The server cannot see when this client processes a packet; it sees
 * only the answers -- transaction replies, keep-alive replies, and movement
 * that does or does not show the knockback. A real slow connection makes
 * those answers late together, in the order the packets came, and its delay
 * moves smoothly. Anything else is a fingerprint:
 *  - the old hold kept the knockback and the transactions behind it, but let
 *    keep-alives answer on time and let block changes through: transaction
 *    ping jumped while keep-alive ping did not, and the client acted on block
 *    changes the server's transaction clock said it had not had yet;
 *  - it was only ever late right after a velocity packet: the reply to the
 *    transaction that brackets each knockback was late, no other -- the
 *    correlation a velocity check is built to find;
 *  - it let the queue out from the movement update, after this tick's clicks
 *    had gone, where vanilla processes packets before the tick;
 *  - RealtimeDamage let the hurt arrive two ticks before the push, so
 *    JumpReset jumped before there was anything to reset.
 *
 * Steady: while in a fight, the whole incoming stream runs a
 * set delay late -- every packet, in order, keep-alives and transactions
 * included -- the delay ramping in and out a few milliseconds a tick and
 * wandering a little, as a real round trip does. The knockback is late
 * because everything is: there is no reply the server can single out.
 *
 * Spike (the default since 2026-10-29): the old idea, made consistent: a hold
 * opens on a knockback and everything that arrives behind it waits with it, in
 * order, keep-alives and block changes included (HoldOthers and
 * HoldTransactions still narrow it). Its defaults follow the commercial
 * client's lag-on-hit studied 2026-10-29 (owner's request; behaviour only):
 * 40 % of pushes, at least 475 ms apart, only with an enemy within 5 blocks
 * and 90 degrees; 50-100 ms in the air, 200-250 ms after three ticks on the
 * ground; no early end on drift or on an upward push, no water check. The one
 * difference asked for: a ping budget (LatencyBudget 450), so on a slow
 * connection the hold shrinks instead of stacking on the ping.
 *
 * In both, held packets go out from a thread of their own the moment they are
 * due, through vanilla's own handlers, which queue them for the client thread:
 * they are processed where vanilla processes packets, at the start of the next
 * frame, before the tick. A correction, a respawn, a join or a disconnect lets
 * everything out first; a catch (Arbiter) ends it; a new world drops it.
 *
 * LagGuard (2026-10-29): Steady, and Spike with HoldOthers, hold the whole
 * stream in order, so LagGuard lets them run through the knockback window (a
 * slow connection does not speed up when it is hit) and they ramp to its
 * allowance instead of being cut: nothing in the pause after a correction or
 * once struck out. Spike with only the push held is stopped in the window --
 * a lone late push is the fingerprint.
 *
 * Not seen by other modules: a held packet goes out past the event bus, so a
 * module that reacts to the velocity packet itself (Velocity) does not see a
 * knockback this module held.
 */
public class KnockbackDelay extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int STEADY = 0;
    private static final int SPIKE = 1;
    /* 2026-10-30 (after Slinky's Knockback Delay, owner: "the limit may be higher, not everything that strict"): a hold
       only while in the air -- the push is late where a late push cannot show -- for AirMax times a log-normal
       factor (0.75-1.35) drawn each time, the whole stream held as in Spike. No vertical, cooldown or drift limit. */
    private static final int AIR = 2;
    public final ModeProperty mode = new ModeProperty("Mode", SPIKE, new String[]{"Steady", "Spike", "Air"});
    private final java.util.function.BooleanSupplier steady = () -> this.mode.getValue() == STEADY;
    private final java.util.function.BooleanSupplier spike = () -> this.mode.getValue() == SPIKE;
    private final java.util.function.BooleanSupplier air = () -> this.mode.getValue() == AIR;

    /** Steady: how late the whole stream runs while in a fight, ms. */
    public final IntProperty steadyDelay = new IntProperty("SteadyDelay", 100, 0, 400, this.steady);
    /** Steady: the most the delay changes in one tick, ms -- the ramp in and out of a fight. */
    public final IntProperty ramp = new IntProperty("Ramp", 10, 2, 50, this.steady);

    public final IntProperty airDelay = new IntProperty("AirDelay", 75, 0, 1000, this.spike);
    public final IntProperty groundDelay = new IntProperty("GroundDelay", 225, 0, 1000, this.spike);
    /** Spike: half-width of the window each delay is drawn from. Steady: how far the delay wanders. */
    public final IntProperty randomize = new IntProperty("Randomize", 25, 0, 200);
    /** Consecutive grounded ticks before the ground figure replaces the air one. */
    public final IntProperty groundTicks = new IntProperty("GroundTicks", 3, 1, 20, this.spike);
    public final IntProperty cooldown = new IntProperty("Cooldown", 475, 0, 2000, this.spike);
    public final IntProperty chance = new IntProperty("Chance", 40, 0, 100, this.spike);
    /** Air: the typical hold, ms (each one is drawn around it, 0.75x-1.35x). */
    public final IntProperty airMax = new IntProperty("AirMax", 100, 10, 400, this.air);
    public final IntProperty airChance = new IntProperty("AirChance", 100, 0, 100, this.air);

    /** Spike: blocks of drift allowed before the hold ends early, whatever the timer says. */
    public final FloatProperty maxDivergence = new FloatProperty("MaxDivergence", 3.0F, 0.1F, 3.0F, this.spike);
    /**
     * Total staleness the server's view is allowed, ms. The round trip
     * already spends part of it; the delay may only use what is left.
     */
    public final IntProperty latencyBudget = new IntProperty("LatencyBudget", 450, 100, 1200);
    public final BooleanProperty pingAware = new BooleanProperty("PingAware", true);

    public final BooleanProperty requireTarget = new BooleanProperty("RequireTarget", true);
    public final FloatProperty targetRange = new FloatProperty("TargetRange", 5.0F, 1.0F, 12.0F,
            this.requireTarget::getValue);
    public final FloatProperty targetFov = new FloatProperty("TargetFOV", 90.0F, 10.0F, 360.0F,
            this.requireTarget::getValue);

    /** Spike: hold the rest of the stream behind the knockback (as real latency does), or only the knockback. */
    public final BooleanProperty holdOthers = new BooleanProperty("HoldOthers", true, this.spike);
    /** Spike: knockback with a larger upward part than this is not held. */
    public final FloatProperty maxVertical = new FloatProperty("MaxVertical", 2.0F, 0.0F, 2.0F, this.spike);

    public final BooleanProperty waterCheck = new BooleanProperty("WaterCheck", false);
    /**
     * Your own hurt goes through on time. Off is what a slow connection does
     * (hurt and push arrive together, so JumpReset jumps on the push); on
     * makes the hit feel immediate but the push two ticks after it.
     */
    public final BooleanProperty realtimeDamage = new BooleanProperty("RealtimeDamage", false);
    public final BooleanProperty onlySwords = new BooleanProperty("OnlySwords", false);
    public final BooleanProperty requireAttackKey = new BooleanProperty("RequireAttackKey", false);
    public final BooleanProperty onlyInAir = new BooleanProperty("OnlyInAir", false, this.spike);
    /** Spike with HoldOthers off: the transactions behind the push still wait with it. */
    public final BooleanProperty holdTransactions = new BooleanProperty("HoldTransactions", true,
            () -> this.spike.getAsBoolean() && !this.holdOthers.getValue());

    private final DelayLine<Packet<?>> line = new DelayLine<Packet<?>>();
    /** One hold and release at a time: the network thread holding, the release thread and the client thread letting out. */
    private final Object lock = new Object();
    private final LatencyRamp latency = new LatencyRamp();

    /* Written on the client thread, read on the others. */
    private volatile long releaseAt;
    private volatile long lastTrigger;
    private volatile int groundedTicks;
    private volatile boolean conditionsMet;
    private volatile int delayBase;
    private volatile int selfEntityId = Integer.MIN_VALUE;
    private volatile int delayCeiling = Integer.MAX_VALUE;
    /** Steady: the delay now, ms (ramped). */
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

        /* Incoming holder: one at a time, never a packet another module has taken (PacketHub, 2026-10-18). */
        atlas.management.PacketHub.registerIncoming("KnockbackDelay", () -> this.line.size(), this::flush);
        /* What is held, for PacketHolds (plan step 13). */
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

            /* The longest any packet may wait, and a second more. */
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

    /** Packets currently withheld, for whatever is reporting on causes. */
    public int heldCount() {
        return this.line.size();
    }

    /** A new world: what is held belongs to the one left (a respawn or join already let it out in order). */
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

    // ---- the client thread, once a tick ------------------------------------

    /** Start of the tick: the world-dependent decisions, so the packet handler never reads the world. */
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
            /* A catch (see Arbiter) simulates the fall from the knockback as it arrives. */
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

    /**
     * Spike: ends the hold once the two views of this player have drifted far
     * enough to be worth correcting -- distance, not time, is what the server
     * acts on.
     */
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
            /* Due now: the release thread lets it out, in order. */
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

    /** What the round trip leaves for the delay; at or below zero, nothing is held. */
    private int computeCeiling() {
        if (!this.pingAware.getValue()) {
            return Integer.MAX_VALUE;
        }
        int ping = Ping.own();
        if (ping <= 0) {
            /* No usable reading: the conservative figure. */
            return Math.min(this.airDelay.getValue(), this.steadyDelay.getValue());
        }
        return this.latencyBudget.getValue() - ping;
    }

    // ---- the network thread ------------------------------------------------

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S07PacketRespawn
                || packet instanceof S01PacketJoinGame || packet instanceof S40PacketDisconnect) {
            /* The server stating where this player is, or a new world: everything held goes first, in order,
               and this one after it, on time. */
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
                /* Through on time, ahead of what is held (RealtimeDamage). */
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
                /* HoldOthers off: only the push (and the replies behind it) are late. */
                return;
            }
            if (push) {
                /* A second push inside the window: both go on time, in order (two in one flush would
                   overwrite the first). */
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

    /**
     * Holds this packet behind the rest. Refused (another holder took it, or
     * PingFix keeps keep-alives on time): a keep-alive just goes ahead; anything
     * else is let out with all before it, so the order holds.
     */
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

    /** Spreads the figure the client thread settled over the randomize window. */
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

    /** The part safe to decide on the network thread: a clock, a dice roll, a flag set on tick. */
    private boolean canStart() {
        if (!this.conditionsMet) {
            return false;
        }
        if (this.mode.getValue() == AIR) {
            return RandomUtil.nextInt(0, 100) < this.airChance.getValue();
        }
        if (System.currentTimeMillis() - this.lastTrigger < (long) this.cooldown.getValue()) {
            return false;
        }
        return RandomUtil.nextInt(0, 100) <= this.chance.getValue();
    }

    /** The packet's subject, without asking the world to resolve it. */
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

    /** A push this module is willing to hold, as opposed to merely one aimed here. */
    private boolean isHoldablePush(Packet<?> packet) {
        if (!this.isPlayerKnockbackPacket(packet)) {
            return false;
        }
        if (packet instanceof S12PacketEntityVelocity) {
            /* The wire format is blocks per tick scaled by 8000. */
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

    // ---- letting out -------------------------------------------------------

    /** The client thread asked for everything to go: the release thread does it (see flush). */
    private volatile boolean flushRequested;

    /**
     * Everything held, in order. Off the client thread it goes now: each
     * handler queues itself for the client thread behind what was let out
     * before. On the client thread a handler would run at once, ahead of
     * packets already queued for the next frame -- so there it is handed to
     * the release thread instead, which lets it out a millisecond later.
     */
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

    /** What is due now: Steady by each packet's arrival and the delay now, Spike all at once when the hold ends. */
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

    /**
     * Vanilla's handler, from whichever thread lets the packet out: off the
     * client thread it queues itself for the client thread (and says so with
     * ThreadQuickExitException), in the order handed over.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void processPacketSilent(Packet<?> packet) {
        try {
            if (mc.getNetHandler() != null) {
                ((Packet) packet).processPacket(mc.getNetHandler());
            }
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
            // Queued for the client thread by vanilla, in order; not an error.
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** One daemon thread for every instance's lifetime: lets packets out the moment they are due. */
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

    // ---- the fight -----------------------------------------------------------

    /**
     * Whatever the client is already fighting, falling back to a scan. The
     * aura's own target is preferred because it has already applied the
     * client's team, friend and bot filtering.
     */
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
