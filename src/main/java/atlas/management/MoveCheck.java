package atlas.management;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LivingUpdateEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.module.modules.*;
import atlas.sim.PlayerSim;
import atlas.sim.SelfCheck;
import atlas.sim.SimPlayer;
import atlas.util.AsyncLog;
import atlas.util.render.DeviceProfile;
import atlas.util.render.MobileSafe;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * Logs whether this player's moves fit the look it sends (2026-11-05, owner: mitigation may come from silent flags on
 * moves). Log only: it changes nothing and sends nothing. A silent flag cannot be seen from here, so this asks the
 * question a re-simulating server asks: with the yaw in the movement packet, does any of the nine key combinations
 * explain the move just made? A move that fits the camera and not the sent look is what a rotation with no working
 * move-fix looks like. A move that fits neither has speed from somewhere else, or is one the model does not cover: the
 * rate on plain ticks, with no module turning, says how far to trust it.
 *
 * moves-*.txt: MODULES on each change of what is on, MISS for a tick that does not fit (at most a few a second), SUM
 * every log-every seconds.
 *
 * 2026-11-11: it also checks the movement simulation (atlas.sim.PlayerSim) against the game. Each tick, once the keys
 * are final, the simulation runs that tick ahead; after the move the place it gave is held against where the player
 * really is. SIM lines are ticks more than a thousandth of a block off (at most a few a second), and each SUM ends
 * with "sim off/ticks worst plain off/ticks": on plain ticks (no module steering the move) it should be 0 off. A
 * module that pushes the player itself (Speed, a velocity module) is off by design -- the simulation does not see it.
 *
 * 2026-11-11, the self check (atlas.sim.SelfCheck): the question a re-simulating anticheat (GrimAC) asks, asked here.
 * From the state at the end of the last tick, with the yaw this tick's packet carried and the sprint and sneak state
 * the packets told the server, does any input (every key combination, a jump, an item's slowdown) end where the player
 * is? SELF lines are ticks more than a thousandth off, with the closest keys and what was steering (tag()); each SUM
 * ends with "self off/ticks worst plain off/ticks". Plain ticks should be 0 off; off on others names the module.
 *
 * 2026-11-12 (owner: "make the simulation the default, not a feature"): no longer a module. It is part of the client
 * and runs by itself, set by Client Settings > Performance > Move check (Auto / On / Off). Auto runs it except on a
 * phone (util.render.DeviceProfile) or where the game runs under 100 fps, so a weak device does not pay for it. The
 * lines are written only while the Logs module is on, as for every session log.
 */
public final class MoveCheck {
    public static final String SETTING = "move-check";
    public static final String[] MODES = {"Auto", "On", "Off"};
    private static volatile int setting = -1;
    private static volatile boolean running;
    /* Auto pauses after LOW_FPS_SECONDS in a row under LOW_FPS, and resumes after HIGH_FPS_SECONDS at HIGH_FPS. */
    private static final int LOW_FPS = 100;
    private static final int LOW_FPS_SECONDS = 5;
    private static final int HIGH_FPS = 115;
    private static final int HIGH_FPS_SECONDS = 10;
    private boolean slow;
    private int lowSeconds;
    private int highSeconds;
    private long fpsSampledAt;
    private boolean wasActive;
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int MISS_LINES_A_SECOND = 4;
    private static final long AFTER_CORRECTION_MS = 300L;
    private static final long AFTER_KNOCKBACK_MS = 100L;
    private static final float BIG_TURN = 30.0F;

    /** How many blocks off a move has to be to count as not fitting (was the "threshold" setting). */
    private static final float THRESHOLD = 0.02F;
    /** Seconds between SUM lines (was "log-every"). */
    private static final int LOG_EVERY = 30;

    private File logFile;
    private volatile long correctedAt;
    private volatile long knockedAt;

    private final MoveModel.State state = new MoveModel.State();
    private boolean sampled;
    private double startX;
    private double startY;
    private double startZ;
    private float lastSentYaw = Float.NaN;
    private boolean lastSprinting;

    private String modulesLogged = "";
    private long summaryAt;
    private long lineSecond;
    private int linesThisSecond;
    private int ticks;
    private int skipped;
    private int lookMisses;
    private int cameraMisses;
    private int bigTurns;
    private int sprintChanges;
    private int plainTicks;
    private int plainMisses;
    /** Where the simulation put this tick's move; null when it was not asked (a move it does not cover). */
    private PlayerSim simPredicted;
    private int simTicks;
    private int simOff;
    private int simPlainTicks;
    private int simPlainOff;
    private double simWorst;
    private static final double SIM_OFF = 0.001;
    /*
     * 2026-11-11 (second form): the start state is taken at the start of UpdateEvent PRE, before any module has that
     * event (ActionWatch.beforeUpdatePre): the move just made, nothing changed after it. Taken at POST, a motion a
     * module set at PRE (Speed and the like) was already in it and so never showed. The check of a move runs at its
     * tick's POST (the yaw sent is final there), from the start one tick before to where that tick's move ended.
     */
    private PlayerSim selfStart;
    /*
     * 2026-11-12, after GrimAC's guesses: what acted on the motion between one start of tick and the next. Attacks
     * sent and a knockback or explosion received in that window go with the move that follows it. A knockback the
     * client cannot place in time is offered to that move and to the next one; never taken by either, it is logged as
     * a KB line -- what a server checking knockback would flag.
     */
    private volatile int attacksSinceStart;
    private int attacksPending;
    private volatile double[] velocitySinceStart;
    private double[] velocityCarry;
    private double[] velocityOffer;
    private boolean velocityOfferLast;
    private volatile double[] explosionSinceStart;
    private double[] explosionCarry;
    private double[] explosionOffer;
    private int kbOffered;
    private int kbIgnored;
    private int selfStartTick;
    /** The move to check at this tick's POST: from, to, and what modules changed between. */
    private PlayerSim pendingFrom;
    private double pendingX;
    private double pendingY;
    private double pendingZ;
    private java.util.Map<String, java.util.TreeSet<String>> pendingMovers;
    /** Self check ticks and offs by module that changed the movement, {ticks, off}. */
    private final Map<String, int[]> selfBy = new TreeMap<String, int[]>();
    private final Runnable startOfTick = this::startOfTick;
    /** The sprint and sneak state the sent packets gave the server; null until one is seen. */
    private volatile Boolean serverSprint;
    private volatile Boolean serverSneak;
    private int selfTicks;
    private int selfOff;
    private int selfPlainTicks;
    private int selfPlainOff;
    private double selfWorst;
    private long selfLineSecond;
    /** This tick's move as the client made it, for the SELF line: its yaw and sprint once the keys were final. */
    private float moveYaw = Float.NaN;
    private boolean moveSprinting;
    private int selfLinesThisSecond;
    /** Ticks and misses by what was running. */
    private final Map<String, int[]> byTag = new TreeMap<String, int[]>();

    public MoveCheck() {
        atlas.util.ActionWatch.beforeUpdatePre = this.startOfTick;
        reset();
    }

    public static void setSetting(int mode) {
        setting = Math.max(0, Math.min(MODES.length - 1, mode));
    }

    /** Whether the checks ran on the last tick asked. */
    public static boolean running() {
        return running;
    }

    /** "auto · running", "off · paused": for the menu and the bug report. */
    public static String describe() {
        int mode = setting < 0 ? MobileSafe.readMode(SETTING, MODES) : setting;
        return MODES[mode].toLowerCase() + (running ? " · running" : " · paused");
    }

    /**
     * Whether the checks run now: On or Off as set; Auto, unless a phone or a game under 100 fps (5 s in a row
     * pauses it, 10 s at 115 or more resumes it, so its own small cost does not flip it). Starting again begins from
     * a clean count.
     */
    private boolean active() {
        int mode = setting;
        if (mode < 0) {
            mode = MobileSafe.readMode(SETTING, MODES);
            setting = mode;
        }
        boolean on;
        if (mode == 1) {
            on = true;
        } else if (mode == 2) {
            on = false;
        } else {
            sampleFps();
            on = !this.slow && !DeviceProfile.isMobile();
        }
        if (on && !this.wasActive) {
            reset();
        }
        this.wasActive = on;
        running = on;
        return on;
    }

    private void sampleFps() {
        long now = System.currentTimeMillis();
        if (now - this.fpsSampledAt < 1000L || mc.theWorld == null) {
            return;
        }
        this.fpsSampledAt = now;
        int fps = Minecraft.getDebugFPS();
        if (!this.slow) {
            this.lowSeconds = fps < LOW_FPS ? this.lowSeconds + 1 : 0;
            if (this.lowSeconds >= LOW_FPS_SECONDS) {
                this.slow = true;
                this.highSeconds = 0;
            }
        } else {
            this.highSeconds = fps >= HIGH_FPS ? this.highSeconds + 1 : 0;
            if (this.highSeconds >= HIGH_FPS_SECONDS) {
                this.slow = false;
                this.lowSeconds = 0;
            }
        }
    }

    /*
     * 2026-11-12 (owner: say it in chat when a move breaks the simulation): a line in the player's own chat, never
     * sent to the server. Once per cause every ALERT_EVERY_MS at most, with how many more there were since; set by
     * Client Settings > Performance > Move check chat.
     */
    public static final String CHAT_SETTING = "move-check-chat";
    public static final String[] CHAT_MODES = {"On", "Off"};
    private static volatile int chatSetting = -1;
    private static final long ALERT_EVERY_MS = 3000L;
    private final Map<String, long[]> alerted = new java.util.HashMap<String, long[]>();

    public static void setChat(int mode) {
        chatSetting = Math.max(0, Math.min(CHAT_MODES.length - 1, mode));
    }

    private static boolean chatOn() {
        int mode = chatSetting;
        if (mode < 0) {
            mode = MobileSafe.readMode(CHAT_SETTING, CHAT_MODES);
            chatSetting = mode;
        }
        return mode == 0;
    }

    /** {@code tag}: the modules that changed the move ("plain": none, so the prediction itself may be off). */
    private void alert(String tag, String what) {
        if (!chatOn() || mc.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long[] state = this.alerted.get(tag);
        if (state == null) {
            state = new long[]{0L, 0L};
            this.alerted.put(tag, state);
        }
        if (now - state[0] < ALERT_EVERY_MS) {
            state[1]++;
            return;
        }
        String who = "plain".equals(tag) ? "&7no module &8(the prediction may be off)" : "&f" + tag;
        String more = state[1] > 0 ? String.format(" &8+%d more", state[1]) : "";
        state[0] = now;
        state[1] = 0L;
        atlas.util.ChatUtil.sendFormatted("&7[&cSim&7] " + who + " &7" + what + more);
    }

    private void reset() {
        this.alerted.clear();
        this.attacksSinceStart = 0;
        this.attacksPending = 0;
        this.velocitySinceStart = null;
        this.velocityCarry = null;
        this.velocityOffer = null;
        this.explosionSinceStart = null;
        this.explosionCarry = null;
        this.explosionOffer = null;
        this.logFile = null;
        this.modulesLogged = "";
        this.sampled = false;
        this.lastSentYaw = Float.NaN;
        this.selfStart = null;
        this.pendingFrom = null;
        this.serverSprint = null;
        this.serverSneak = null;
        clearCounts();
        this.summaryAt = System.currentTimeMillis();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        /* What is counted so far belongs to the game that ended. */
        if (this.ticks + this.skipped > 0) {
            summary();
            this.summaryAt = System.currentTimeMillis();
        }
        this.sampled = false;
        this.lastSentYaw = Float.NaN;
        this.selfStart = null;
        this.pendingFrom = null;
        this.serverSprint = null;
        this.serverSneak = null;
    }

    /* First: Velocity cancels the packet at HIGH, and the motion it would have set still counts as a push. */
    @EventTarget(Priority.HIGHEST)
    /* Packet handlers read the running state the client thread last set: active() may reset() the counts, which only
       the client thread may do (review, PR #38; a RECEIVE runs on the network thread). */
    public void onPacket(PacketEvent event) {
        if (!running || mc.thePlayer == null) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.correctedAt = System.currentTimeMillis();
        } else if (event.getPacket() instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()) {
            this.knockedAt = System.currentTimeMillis();
            /* What the server applied, whatever a module does to it on the way in (the self check grades that). */
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
            this.velocitySinceStart = new double[]{velocity.getMotionX() / 8000.0, velocity.getMotionY() / 8000.0,
                    velocity.getMotionZ() / 8000.0};
        } else if (event.getPacket() instanceof S27PacketExplosion) {
            this.knockedAt = System.currentTimeMillis();
            S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
            this.explosionSinceStart = new double[]{explosion.func_149149_c(), explosion.func_149144_d(),
                    explosion.func_149147_e()};
        }
    }

    /* Last: a sprint or sneak change another module cancels never reaches the server. */
    @EventTarget(Priority.LOWEST)
    public void onSend(PacketEvent event) {
        if (!running || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            this.attacksSinceStart++;
            return;
        }
        if (!(event.getPacket() instanceof C0BPacketEntityAction)) {
            return;
        }
        switch (((C0BPacketEntityAction) event.getPacket()).getAction()) {
            case START_SPRINTING:
                this.serverSprint = Boolean.TRUE;
                break;
            case STOP_SPRINTING:
                this.serverSprint = Boolean.FALSE;
                break;
            case START_SNEAKING:
                this.serverSneak = Boolean.TRUE;
                break;
            case STOP_SNEAKING:
                this.serverSneak = Boolean.FALSE;
                break;
            default:
                break;
        }
    }

    /* LOWEST: after anything that sets the motion at PRE. POST pairs with it; a tick TickBase skips has neither. */
    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            this.startX = mc.thePlayer.posX;
            this.startY = mc.thePlayer.posY;
            this.startZ = mc.thePlayer.posZ;
            this.state.motionX = mc.thePlayer.motionX;
            this.state.motionZ = mc.thePlayer.motionZ;
            this.state.onGround = mc.thePlayer.onGround;
            this.state.slipperiness = mc.theWorld.getBlockState(new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1,
                    MathHelper.floor_double(mc.thePlayer.posZ))).getBlock().slipperiness;
            this.sampled = true;
            return;
        }
        if (event.getType() == EventType.POST && this.sampled) {
            this.sampled = false;
            finish(event.getYaw());
            selfCheck(event.getYaw());
        }
    }

    /**
     * Before any module has this tick's UpdateEvent PRE: where the move just made ended, checked at POST from the
     * state kept one tick ago; and that state for the next. Only from the tick just before (a tick skipped by TickBase
     * or a move the simulation does not cover leaves nothing to check).
     */
    private void startOfTick() {
        java.util.Map<String, java.util.TreeSet<String>> movers = atlas.util.ActionWatch.takeTickMovers();
        this.pendingFrom = null;
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            this.selfStart = null;
            return;
        }
        /* The window that just closed goes with the move just made. A knockback not taken by its own move is offered
           once more, to the next. */
        this.attacksPending = this.attacksSinceStart;
        this.attacksSinceStart = 0;
        this.velocityOfferLast = this.velocitySinceStart == null && this.velocityCarry != null;
        this.velocityOffer = this.velocitySinceStart != null ? this.velocitySinceStart : this.velocityCarry;
        this.velocityCarry = this.velocitySinceStart;
        this.velocitySinceStart = null;
        this.explosionOffer = this.explosionSinceStart != null ? this.explosionSinceStart : this.explosionCarry;
        this.explosionCarry = this.explosionSinceStart;
        this.explosionSinceStart = null;
        int tick = mc.thePlayer.ticksExisted;
        boolean supported = SimPlayer.supported(mc.thePlayer);
        if (this.selfStart != null && supported && tick - this.selfStartTick == 1) {
            this.pendingFrom = this.selfStart;
            this.pendingX = mc.thePlayer.posX;
            this.pendingY = mc.thePlayer.getEntityBoundingBox().minY;
            this.pendingZ = mc.thePlayer.posZ;
            this.pendingMovers = movers;
        }
        this.selfStart = supported ? SimPlayer.state(mc.thePlayer) : null;
        this.selfStartTick = tick;
    }

    /** The module changes of a move, as "Speed:move,sprint MoveFix:input"; "plain" for none. MoveCheck left out. */
    private String movers(java.util.Map<String, java.util.TreeSet<String>> during,
                          java.util.Map<String, java.util.TreeSet<String>> turning) {
        java.util.Map<String, java.util.TreeSet<String>> all = new TreeMap<String, java.util.TreeSet<String>>();
        for (java.util.Map<String, java.util.TreeSet<String>> part : java.util.Arrays.asList(during, turning)) {
            for (Map.Entry<String, java.util.TreeSet<String>> entry : part.entrySet()) {
                if (entry.getKey().equals("MoveCheck")) {
                    continue;
                }
                java.util.TreeSet<String> kinds = all.get(entry.getKey());
                if (kinds == null) {
                    kinds = new java.util.TreeSet<String>();
                    all.put(entry.getKey(), kinds);
                }
                kinds.addAll(entry.getValue());
            }
        }
        if (all.isEmpty()) {
            return "plain";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, java.util.TreeSet<String>> entry : all.entrySet()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(entry.getKey()).append(':');
            boolean first = true;
            for (String kind : entry.getValue()) {
                out.append(first ? "" : ",").append(kind);
                first = false;
            }
        }
        return out.toString();
    }

    /**
     * The self check of this tick's move, at its POST: from the start state, with the yaw sent and the sprint and
     * sneak the server has; tagged with the modules that changed movement, input, sprint, push or look on the way
     * (from the last start of tick, and this PRE's turns). Not after a correction or knockback.
     */
    private void selfCheck(float sentYaw) {
        long now = System.currentTimeMillis();
        PlayerSim before = this.pendingFrom;
        this.pendingFrom = null;
        /* After a correction the start is not where the server has the player. Knockback no longer skips: it is offered
           to the check (2026-11-12). */
        if (before == null || now - this.correctedAt < AFTER_CORRECTION_MS) {
            return;
        }
        boolean sprint = this.serverSprint != null ? this.serverSprint : mc.thePlayer.isSprinting();
        boolean sneak = this.serverSneak != null ? this.serverSneak : mc.thePlayer.isSneaking();
        SelfCheck.Extra extra = new SelfCheck.Extra();
        extra.attacks = this.attacksPending;
        extra.velocity = this.velocityOffer;
        extra.explosion = this.explosionOffer;
        SelfCheck.Result result = SelfCheck.check(before, SimPlayer.world(mc.theWorld, mc.thePlayer), sentYaw, sprint,
                sneak, this.pendingX, this.pendingY, this.pendingZ, extra);
        if (this.velocityOffer != null) {
            if (result.velocity) {
                this.velocityCarry = null;
                this.kbOffered++;
            } else if (this.velocityOfferLast) {
                this.kbOffered++;
                this.kbIgnored++;
                String kbTag = movers(this.pendingMovers, atlas.util.ActionWatch.peekTickMovers());
                write(String.format("KB not taken: %.3f %.3f %.3f over two moves | %s", this.velocityOffer[0],
                        this.velocityOffer[1], this.velocityOffer[2], kbTag));
                alert(kbTag, "&ckept no knockback");
            }
        }
        if (result.explosion) {
            this.explosionCarry = null;
        }
        boolean off = result.offset > SelfCheck.OFF;
        String tag = movers(this.pendingMovers, atlas.util.ActionWatch.peekTickMovers());
        boolean plain = tag.equals("plain");
        this.selfTicks++;
        this.selfOff += off ? 1 : 0;
        this.selfWorst = Math.max(this.selfWorst, result.offset);
        if (plain) {
            this.selfPlainTicks++;
            this.selfPlainOff += off ? 1 : 0;
        } else {
            for (String part : tag.split(" ")) {
                String module = part.substring(0, part.indexOf(':'));
                int[] counts = this.selfBy.get(module);
                if (counts == null) {
                    counts = new int[2];
                    this.selfBy.put(module, counts);
                }
                counts[0]++;
                counts[1] += off ? 1 : 0;
            }
        }
        if (!off) {
            return;
        }
        alert(tag, String.format("off &f%.4f &8(keys %s)", result.offset, result.keys()));
        long second = now / 1000L;
        if (second != this.selfLineSecond) {
            this.selfLineSecond = second;
            this.selfLinesThisSecond = 0;
        }
        if (this.selfLinesThisSecond++ < MISS_LINES_A_SECOND) {
            /* What tells a cause apart: the move's own yaw against the packet's, the client's sprint against the
               server's, the ground speed the last tick left for this move, and who changed what. */
            write(String.format("SELF off %.4f (x %.4f y %.4f z %.4f) keys %s | yaw %.1f move-yaw %.1f %s%s%s| client %s"
                            + "| speed %.4f air %.4f | %s", result.offset,
                    this.pendingX - result.end.x(), this.pendingY - result.end.y(), this.pendingZ - result.end.z(),
                    result.keys(), MathHelper.wrapAngleTo180_float(sentYaw),
                    Float.isNaN(this.moveYaw) ? Float.NaN : MathHelper.wrapAngleTo180_float(this.moveYaw),
                    mc.thePlayer.onGround ? "ground " : "air ", sprint ? "sprint " : "", sneak ? "sneak " : "",
                    this.moveSprinting ? "sprint " : "walk ", before.moveSpeed, before.airSpeed, tag));
        }
    }

    /* LOWEST: the keys, the sprint and whatever a module set are final; the game's own move is next. */
    @EventTarget(Priority.LOWEST)
    public void onLivingUpdate(LivingUpdateEvent event) {
        this.simPredicted = null;
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.moveYaw = SimPlayer.inputAsMoved(mc.thePlayer).yaw;
        this.moveSprinting = mc.thePlayer.isSprinting();
        if (!SimPlayer.supported(mc.thePlayer)) {
            return;
        }
        PlayerSim sim = SimPlayer.stateInMove(mc.thePlayer);
        sim.tick(SimPlayer.world(mc.theWorld, mc.thePlayer), SimPlayer.inputAsMoved(mc.thePlayer));
        this.simPredicted = sim;
    }

    /** The simulation's place for this tick against the real one. */
    private void checkSim(long now) {
        PlayerSim predicted = this.simPredicted;
        this.simPredicted = null;
        if (predicted == null || now - this.correctedAt < AFTER_CORRECTION_MS || now - this.knockedAt < AFTER_KNOCKBACK_MS) {
            return;
        }
        double dx = mc.thePlayer.posX - predicted.x();
        double dy = mc.thePlayer.getEntityBoundingBox().minY - predicted.y();
        double dz = mc.thePlayer.posZ - predicted.z();
        double error = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean off = error > SIM_OFF;
        boolean plain = tag().equals("plain");
        this.simTicks++;
        this.simOff += off ? 1 : 0;
        this.simWorst = Math.max(this.simWorst, error);
        if (plain) {
            this.simPlainTicks++;
            this.simPlainOff += off ? 1 : 0;
        }
        if (!off) {
            return;
        }
        long second = now / 1000L;
        if (second != this.lineSecond) {
            this.lineSecond = second;
            this.linesThisSecond = 0;
        }
        if (this.linesThisSecond++ < MISS_LINES_A_SECOND) {
            write(String.format("SIM off %.4f (x %.4f y %.4f z %.4f) | %s%s%s| %s", error, dx, dy, dz,
                    mc.thePlayer.onGround ? "ground " : "air ", mc.thePlayer.isSprinting() ? "sprint " : "",
                    mc.thePlayer.isCollidedHorizontally ? "wall " : "", tag()));
        }
    }

    private void finish(float sentYaw) {
        long now = System.currentTimeMillis();
        checkSim(now);
        if (mc.thePlayer.ticksExisted % 20 == 0) {
            logModulesIfChanged();
        }
        float turn = Float.isNaN(this.lastSentYaw) ? 0.0F
                : Math.abs(MathHelper.wrapAngleTo180_float(sentYaw - this.lastSentYaw));
        this.lastSentYaw = sentYaw;
        boolean sprinting = mc.thePlayer.isSprinting();
        if (sprinting != this.lastSprinting) {
            this.sprintChanges++;
            this.lastSprinting = sprinting;
        }
        if (turn >= BIG_TURN) {
            this.bigTurns++;
        }
        if (leftOut(now)) {
            this.skipped++;
        } else {
            double moveX = mc.thePlayer.posX - this.startX;
            double moveZ = mc.thePlayer.posZ - this.startZ;
            this.state.sprinting = sprinting;
            this.state.sneaking = mc.thePlayer.isSneaking();
            this.state.usingItem = mc.thePlayer.isUsingItem();
            /* Rising after the move, not just higher: a step up a slab rises too, and has no jump boost. */
            this.state.jumped = this.state.onGround && mc.thePlayer.motionY > 0.2;
            this.state.moveSpeed = mc.thePlayer.getAIMoveSpeed();
            double lookError = MoveModel.bestError(this.state, sentYaw, moveX, moveZ);
            double cameraError = MoveModel.bestError(this.state, mc.thePlayer.rotationYaw, moveX, moveZ);
            record(now, lookError, cameraError, sentYaw, turn);
        }
        if (now - this.summaryAt >= LOG_EVERY * 1000L) {
            summary();
            this.summaryAt = now;
        }
    }

    /** Ticks the model does not cover. */
    private boolean leftOut(long now) {
        return mc.thePlayer.isCollidedHorizontally || mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || mc.thePlayer.isOnLadder() || mc.thePlayer.isRiding() || mc.thePlayer.capabilities.isFlying
                || mc.thePlayer.isPlayerSleeping() || now - this.correctedAt < AFTER_CORRECTION_MS
                || now - this.knockedAt < AFTER_KNOCKBACK_MS;
    }

    private void record(long now, double lookError, double cameraError, float sentYaw, float turn) {
        float limit = THRESHOLD;
        boolean lookMiss = lookError > limit;
        boolean cameraMiss = cameraError > limit;
        boolean turned = Math.abs(MathHelper.wrapAngleTo180_float(sentYaw - mc.thePlayer.rotationYaw)) >= 1.0F;
        String tag = tag();
        this.ticks++;
        this.lookMisses += lookMiss ? 1 : 0;
        this.cameraMisses += cameraMiss ? 1 : 0;
        int[] counts = this.byTag.get(tag);
        if (counts == null) {
            counts = new int[2];
            this.byTag.put(tag, counts);
        }
        counts[0]++;
        counts[1] += lookMiss ? 1 : 0;
        if (!turned && tag.equals("plain")) {
            this.plainTicks++;
            this.plainMisses += lookMiss ? 1 : 0;
        }
        if (!lookMiss) {
            return;
        }
        long second = now / 1000L;
        if (second != this.lineSecond) {
            this.lineSecond = second;
            this.linesThisSecond = 0;
        }
        if (this.linesThisSecond++ < MISS_LINES_A_SECOND) {
            write(String.format("MISS %s look-err %.3f cam-err %.3f | yaw sent %.1f cam %.1f turn %.1f | %s%s%s%s | %s",
                    cameraMiss ? "SPEED" : "LOOK", lookError, cameraError, MathHelper.wrapAngleTo180_float(sentYaw),
                    MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw), turn,
                    this.state.sprinting ? "sprint " : "", this.state.onGround ? "ground " : "air ",
                    this.state.jumped ? "jump " : "", this.state.usingItem ? "use " : "", tag));
        }
    }

    /** What may be steering the move this tick. */
    private String tag() {
        StringBuilder tag = new StringBuilder();
        if (Arbiter.catching()) {
            tag.append("catch ");
        }
        if (on(Scaffold.class)) {
            tag.append("scaffold ");
        }
        if (atlas.util.Auras.target() != null) {
            tag.append("aura ");
        }
        if (on(Hitflick.class)) {
            tag.append("hitflick ");
        }
        return tag.length() == 0 ? "plain" : tag.toString().trim();
    }

    private static boolean on(Class<? extends Module> type) {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(type);
        return module != null && module.isEnabled();
    }

    /** One line for each set of modules that are on, so every session can be compared. */
    private void logModulesIfChanged() {
        if (Atlas.moduleManager == null) {
            return;
        }
        StringBuilder on = new StringBuilder();
        for (Module module : names().values()) {
            if (module.isEnabled()) {
                on.append(module.getName()).append(' ');
            }
        }
        String now = on.toString().trim();
        if (!now.equals(this.modulesLogged)) {
            this.modulesLogged = now;
            write("MODULES " + now);
        }
    }

    private static Map<String, Module> names() {
        Map<String, Module> out = new TreeMap<String, Module>();
        for (Module module : Atlas.moduleManager.modules.values()) {
            out.put(module.getName(), module);
        }
        return out;
    }

    private void summary() {
        StringBuilder tags = new StringBuilder();
        for (Map.Entry<String, int[]> entry : this.byTag.entrySet()) {
            tags.append(" | ").append(entry.getKey()).append(' ').append(entry.getValue()[1]).append('/')
                    .append(entry.getValue()[0]);
        }
        StringBuilder by = new StringBuilder();
        for (Map.Entry<String, int[]> entry : this.selfBy.entrySet()) {
            by.append(' ').append(entry.getKey()).append(' ').append(entry.getValue()[1]).append('/')
                    .append(entry.getValue()[0]);
        }
        write(String.format("SUM ticks %d skipped %d look-miss %d cam-miss %d plain-miss %d/%d big-turn %d sprint-change %d%s"
                        + " | sim %d/%d worst %.4f plain %d/%d | self %d/%d worst %.4f plain %d/%d | kb-missed %d/%d | by%s",
                this.ticks, this.skipped, this.lookMisses, this.cameraMisses, this.plainMisses, this.plainTicks,
                this.bigTurns, this.sprintChanges, tags, this.simOff, this.simTicks, this.simWorst, this.simPlainOff,
                this.simPlainTicks, this.selfOff, this.selfTicks, this.selfWorst, this.selfPlainOff,
                this.selfPlainTicks, this.kbIgnored, this.kbOffered, by.length() == 0 ? " -" : by));
        clearCounts();
    }

    private void clearCounts() {
        this.kbOffered = 0;
        this.kbIgnored = 0;
        this.ticks = 0;
        this.skipped = 0;
        this.lookMisses = 0;
        this.cameraMisses = 0;
        this.bigTurns = 0;
        this.sprintChanges = 0;
        this.plainTicks = 0;
        this.plainMisses = 0;
        this.simTicks = 0;
        this.simOff = 0;
        this.simPlainTicks = 0;
        this.simPlainOff = 0;
        this.simWorst = 0.0;
        this.selfTicks = 0;
        this.selfOff = 0;
        this.selfPlainTicks = 0;
        this.selfPlainOff = 0;
        this.selfWorst = 0.0;
        this.selfBy.clear();
        this.byTag.clear();
    }

    private void write(String line) {
        if (this.logFile == null) {
            this.logFile = new File(LOG_DIR, "moves-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logFile, LINE_STAMP.format(new Date()) + " " + line);
    }
}
