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
import atlas.sim.Impulses;
import atlas.sim.MoveModel;
import atlas.sim.PlayerSim;
import atlas.sim.SelfCheck;
import atlas.sim.SimPlayer;
import atlas.util.AsyncLog;
import atlas.util.render.DeviceProfile;
import atlas.util.render.MobileSafe;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
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
 * is? SELF lines are ticks more than a thousandth off, with the closest keys and the modules that changed the move;
 * each SUM ends with "self off/ticks worst plain off/ticks". Plain ticks should be 0 off; off on others names the
 * module.
 *
 * 2026-11-12, its tick put right. A tick here runs: UpdateEvent PRE (EntityPlayerSP.onUpdate's head), the move, the
 * movement packet, UpdateEvent POST. The check took its start before PRE and its end before the next PRE, which is
 * one move -- but held it against the yaw and the sprint of the packet after that one, a tick late. Standing still or
 * walking straight it made no difference; turning while walking, every move looked a little off. Now a move is taken
 * as a server takes it, from one movement packet to the next: just before any module has UpdateEvent POST
 * (ActionWatch.beforeUpdatePost) the move that packet reported is checked from the state kept at the same point one
 * tick before, with that packet's yaw and the sprint and sneak sent with it; then the state is kept for the next.
 * Everything a module did in between -- at POST, in the game's tick, at PRE, during the move -- is in the stretch, and
 * so are the attacks, knockback and explosions of it (atlas.sim.Impulses).
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
    private final FpsGate fps = new FpsGate(LOW_FPS, LOW_FPS_SECONDS, HIGH_FPS, HIGH_FPS_SECONDS);
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
     * The self check's start: the player as the last movement packet left it, kept just before any module has
     * UpdateEvent POST (endOfTick), and the tick it was kept on -- a move is checked only from the tick right before
     * (a tick TickBase skips, or one the simulation does not cover, leaves nothing to check).
     */
    private PlayerSim selfStart;
    private int selfStartTick;
    /* 2026-11-12, after GrimAC's guesses: the attacks sent and the knockback or explosions received from one
       movement packet to the next go with the move between them. */
    private final Impulses impulses = new Impulses();
    /** This player's entity id as the client thread last saw it, for the network thread to compare with. */
    private static volatile int playerId = Integer.MIN_VALUE;
    /** The module that cancelled the last knockback packet, for the line that says it was not kept. */
    private static volatile String knockbackHeldBy;
    private int kbOffered;
    private int kbIgnored;
    /** Self check ticks and offs by module that changed the movement, {ticks, off}. */
    private final Map<String, int[]> selfBy = new TreeMap<String, int[]>();
    /** The sprint and sneak state the sent packets gave the server; null until one is seen. */
    private volatile Boolean serverSprint;
    private volatile Boolean serverSneak;
    private int selfTicks;
    private int selfOff;
    private int selfPlainTicks;
    private int selfPlainOff;
    private double selfWorst;
    private long selfLineSecond;
    /** This tick's move as the client made it, for the SELF line: its yaw, sprint and sneak once the keys were
        final, and the tick they are of. */
    private float moveYaw = Float.NaN;
    private boolean moveSprinting;
    private boolean moveSneaking;
    private int moveTick = Integer.MIN_VALUE;
    private int selfLinesThisSecond;
    /** Ticks and misses by what was running. */
    private final Map<String, int[]> byTag = new TreeMap<String, int[]>();

    public MoveCheck() {
        atlas.util.ActionWatch.beforeUpdatePost = this::endOfTick;
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
            on = !this.fps.slow() && !DeviceProfile.isMobile();
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
        this.fps.sample(Minecraft.getDebugFPS());
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
    private final AlertLimiter alerts = new AlertLimiter(ALERT_EVERY_MS);

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

    /**
     * {@code tag}: the modules that changed the move ("plain": none seen). {@code why}: what is known of the cause
     * besides ("" for nothing); with neither a module nor a cause the prediction itself may be what is off, and the
     * line says so.
     */
    private void alert(String tag, String what, String why) {
        if (!chatOn() || mc.thePlayer == null) {
            return;
        }
        int held = this.alerts.pass(tag, System.currentTimeMillis());
        if (held < 0) {
            return;
        }
        String who = !"plain".equals(tag) ? "&f" + tag
                : why.isEmpty() ? "&7no module &8(the prediction may be off)" : "&7no module seen";
        String more = held > 0 ? String.format(" &8+%d more", held) : "";
        atlas.util.ChatUtil.sendFormatted("&7[&cSim&7] " + who + " &7" + what + why + more);
    }

    private void reset() {
        this.alerts.clear();
        this.impulses.clear();
        knockbackHeldBy = null;
        this.logFile = null;
        this.modulesLogged = "";
        this.sampled = false;
        this.lastSentYaw = Float.NaN;
        this.selfStart = null;
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
        this.serverSprint = null;
        this.serverSneak = null;
        /* Nothing the old world sent reaches the new one's first moves (review, PR #38). */
        playerId = Integer.MIN_VALUE;
        knockbackHeldBy = null;
        this.impulses.clear();
    }

    /* First: Velocity cancels the packet at HIGH, and the motion it would have set still counts as a push. */
    @EventTarget(Priority.HIGHEST)
    /* Packet handlers read the running state the client thread last set: active() may reset() the counts, which only
       the client thread may do (review, PR #38; a RECEIVE runs on the network thread). */
    public void onPacket(PacketEvent event) {
        if (!running) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.correctedAt = System.currentTimeMillis();
        } else if (event.getPacket() instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == playerId) {
            this.knockedAt = System.currentTimeMillis();
            /* What the server applied, whatever a module does to it on the way in (the self check grades that). */
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
            knockbackHeldBy = null;
            this.impulses.velocity(velocity.getMotionX() / 8000.0, velocity.getMotionY() / 8000.0,
                    velocity.getMotionZ() / 8000.0);
        } else if (event.getPacket() instanceof S27PacketExplosion) {
            this.knockedAt = System.currentTimeMillis();
            S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
            this.impulses.explosion(explosion.func_149149_c(), explosion.func_149144_d(), explosion.func_149147_e());
        }
    }

    /**
     * A module cancelled this packet on its way in (EventManager, which alone knows who). A knockback for this
     * player that never reaches the game is the commonest reason one is "not kept": the name goes on that line. It
     * is cleared by the next knockback to arrive (onPacket, which runs before any module can cancel). Any thread.
     */
    public static void packetHeld(String module, Packet<?> packet) {
        if (running && packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == playerId) {
            knockbackHeldBy = module;
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
            this.impulses.attack();
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
        }
    }

    /**
     * The end of a tick as a server sees one: this tick's move is made and its movement packet is out, and no module
     * has had this UpdateEvent POST yet (ActionWatch.beforeUpdatePost). The move is checked from the state kept here
     * one tick ago, with the yaw the packet carried; then the state is kept for the next move. What modules changed
     * since the last time here is the move's tag; the attacks, knockback and explosions since then are what else may
     * explain it.
     */
    private void endOfTick(UpdateEvent event) {
        Map<String, java.util.TreeSet<String>> movers = atlas.util.ActionWatch.takeTickMovers();
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            this.selfStart = null;
            return;
        }
        playerId = mc.thePlayer.getEntityId();
        /* Closed every tick, checked or not, so nothing waits past the move it belongs to. */
        SelfCheck.Extra extra = this.impulses.close();
        int tick = mc.thePlayer.ticksExisted;
        boolean supported = SimPlayer.supported(mc.thePlayer);
        PlayerSim before = this.selfStart;
        boolean checked = before != null && supported && tick - this.selfStartTick == 1
                && selfCheck(before, event.getYaw(), extra, changes(movers));
        if (!checked) {
            /* What came in with a move nobody checked is not held against the next one. */
            this.impulses.skip();
        }
        this.selfStart = supported ? SimPlayer.state(mc.thePlayer) : null;
        this.selfStartTick = tick;
    }

    /** The module changes of a move, as "Speed:move,sprint MoveFix:input"; "plain" for none. */
    private static String changes(Map<String, java.util.TreeSet<String>> movers) {
        if (movers.isEmpty()) {
            return "plain";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, java.util.TreeSet<String>> entry : movers.entrySet()) {
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
     * The self check of the move just made: from {@code before}, with the yaw sent and the sprint and sneak the
     * server has, to where the player is; {@code tag} names the modules that changed movement, input, sprint, push or
     * look on the way. Not after a correction (the start is then not where the server has the player): false, the
     * move was left out.
     */
    private boolean selfCheck(final PlayerSim before, final float sentYaw, final SelfCheck.Extra extra, String tag) {
        long now = System.currentTimeMillis();
        if (now - this.correctedAt < AFTER_CORRECTION_MS) {
            return false;
        }
        final boolean sprint = this.serverSprint != null ? this.serverSprint : mc.thePlayer.isSprinting();
        final boolean sneak = this.serverSneak != null ? this.serverSneak : mc.thePlayer.isSneaking();
        final PlayerSim.World world = SimPlayer.world(mc.theWorld, mc.thePlayer);
        final double x = mc.thePlayer.posX;
        final double y = mc.thePlayer.getEntityBoundingBox().minY;
        final double z = mc.thePlayer.posZ;
        SelfCheck.Result result = SelfCheck.check(before, world, sentYaw, sprint, sneak, x, y, z, extra);
        Impulses.Knockback knockback = this.impulses.settle(result,
                () -> SelfCheck.fitsWithVelocity(before, world, sentYaw, sprint, sneak, x, y, z, extra));
        if (knockback == Impulses.Knockback.TAKEN) {
            this.kbOffered++;
        } else if (knockback == Impulses.Knockback.IGNORED) {
            this.kbOffered++;
            this.kbIgnored++;
            String held = knockbackHeldBy;
            String kbTag = held == null ? tag : tag.equals("plain") ? held + ":knockback" : tag + " " + held + ":knockback";
            write(String.format("KB not taken: %.3f %.3f %.3f over two moves | %s", extra.velocity[0],
                    extra.velocity[1], extra.velocity[2], kbTag));
            alert(kbTag, "&ckept no knockback", "");
        }
        boolean off = result.offset > SelfCheck.OFF;
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
            return true;
        }
        /* What tells a cause apart without a module's name: the move ran by another yaw than the packet carried, or
           with another sprint or sneak than the server was told. Only when this tick's move was seen. */
        boolean seen = this.moveTick == mc.thePlayer.ticksExisted && !Float.isNaN(this.moveYaw);
        StringBuilder why = new StringBuilder();
        if (seen && Math.abs(MathHelper.wrapAngleTo180_float(this.moveYaw - sentYaw)) > 0.01F) {
            why.append(String.format(" &8(moved by yaw %.1f, sent %.1f)", MathHelper.wrapAngleTo180_float(this.moveYaw),
                    MathHelper.wrapAngleTo180_float(sentYaw)));
        }
        if (seen && this.moveSprinting != sprint) {
            why.append(sprint ? " &8(the server has a sprint the move did not)" : " &8(sprinted, the server not told)");
        }
        if (seen && this.moveSneaking != sneak) {
            why.append(sneak ? " &8(the server has a sneak the move did not)" : " &8(sneaked, the server not told)");
        }
        alert(tag, String.format("off &f%.4f &8(keys %s)", result.offset, result.keys()), why.toString());
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
                    x - result.end.x(), y - result.end.y(), z - result.end.z(),
                    result.keys(), MathHelper.wrapAngleTo180_float(sentYaw),
                    seen ? MathHelper.wrapAngleTo180_float(this.moveYaw) : Float.NaN,
                    mc.thePlayer.onGround ? "ground " : "air ", sprint ? "sprint " : "", sneak ? "sneak " : "",
                    !seen ? "? " : this.moveSprinting ? "sprint " : "walk ", before.moveSpeed, before.airSpeed, tag));
        }
        return true;
    }

    /* LOWEST: the keys, the sprint and whatever a module set are final; the game's own move is next. */
    @EventTarget(Priority.LOWEST)
    public void onLivingUpdate(LivingUpdateEvent event) {
        this.simPredicted = null;
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.moveYaw = SimPlayer.moveYaw(mc.thePlayer);
        this.moveSprinting = mc.thePlayer.isSprinting();
        this.moveSneaking = mc.thePlayer.isSneaking();
        this.moveTick = mc.thePlayer.ticksExisted;
        if (!SimPlayer.supported(mc.thePlayer)) {
            return;
        }
        /* The game is past its water push, its push out of blocks and its sprint rule: only the move is run. */
        SimPlayer.Snapshot now = SimPlayer.snapshot(mc.thePlayer, PlayerSim.Stage.MOVE);
        now.sim.tick(now.world, now.input);
        this.simPredicted = now.sim;
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
