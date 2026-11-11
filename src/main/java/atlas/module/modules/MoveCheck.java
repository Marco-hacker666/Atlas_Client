package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LivingUpdateEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.management.Arbiter;
import atlas.module.Module;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.sim.PlayerSim;
import atlas.sim.SelfCheck;
import atlas.sim.SimPlayer;
import atlas.util.AsyncLog;
import net.minecraft.client.Minecraft;
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
 */
public class MoveCheck extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int MISS_LINES_A_SECOND = 4;
    private static final long AFTER_CORRECTION_MS = 300L;
    private static final long AFTER_KNOCKBACK_MS = 100L;
    private static final float BIG_TURN = 30.0F;

    public final FloatProperty threshold = new FloatProperty("threshold", 0.02F, 0.005F, 0.2F);
    public final IntProperty logEvery = new IntProperty("log-every", 30, 5, 300);

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
    /** The state at the end of the last tick, for the self check; null when there is none to start from. */
    private PlayerSim selfBefore;
    private int selfBeforeTick;
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
        super("MoveCheck", false, true, "Logs moves that do not fit the look sent. Changes nothing.");
    }

    @Override
    public void onEnabled() {
        this.logFile = null;
        this.modulesLogged = "";
        this.sampled = false;
        this.lastSentYaw = Float.NaN;
        this.selfBefore = null;
        this.serverSprint = null;
        this.serverSneak = null;
        clearCounts();
        this.summaryAt = System.currentTimeMillis();
    }

    @Override
    public void onDisabled() {
        this.sampled = false;
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
        this.selfBefore = null;
        this.serverSprint = null;
        this.serverSneak = null;
    }

    /* First: Velocity cancels the packet at HIGH, and the motion it would have set still counts as a push. */
    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.correctedAt = System.currentTimeMillis();
        } else if (event.getPacket() instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()
                || event.getPacket() instanceof S27PacketExplosion) {
            this.knockedAt = System.currentTimeMillis();
        }
    }

    /* Last: a sprint or sneak change another module cancels never reaches the server. */
    @EventTarget(Priority.LOWEST)
    public void onSend(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled()
                || !(event.getPacket() instanceof C0BPacketEntityAction)) {
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
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
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
     * The self check for this tick, then this tick's end kept for the next. Only from the tick just before: a tick
     * skipped (TickBase), a correction, knockback or a move the simulation does not cover leaves nothing to check.
     */
    private void selfCheck(float sentYaw) {
        long now = System.currentTimeMillis();
        PlayerSim before = this.selfBefore;
        int beforeTick = this.selfBeforeTick;
        boolean supported = SimPlayer.supported(mc.thePlayer);
        this.selfBefore = supported ? SimPlayer.state(mc.thePlayer) : null;
        this.selfBeforeTick = mc.thePlayer.ticksExisted;
        if (before == null || !supported || mc.thePlayer.ticksExisted - beforeTick != 1
                || now - this.correctedAt < AFTER_CORRECTION_MS || now - this.knockedAt < AFTER_KNOCKBACK_MS) {
            return;
        }
        boolean sprint = this.serverSprint != null ? this.serverSprint : mc.thePlayer.isSprinting();
        boolean sneak = this.serverSneak != null ? this.serverSneak : mc.thePlayer.isSneaking();
        SelfCheck.Result result = SelfCheck.check(before, SimPlayer.world(mc.theWorld, mc.thePlayer), sentYaw, sprint,
                sneak, mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ);
        boolean off = result.offset > SelfCheck.OFF;
        String tag = tag();
        boolean plain = tag.equals("plain");
        this.selfTicks++;
        this.selfOff += off ? 1 : 0;
        this.selfWorst = Math.max(this.selfWorst, result.offset);
        if (plain) {
            this.selfPlainTicks++;
            this.selfPlainOff += off ? 1 : 0;
        }
        if (!off) {
            return;
        }
        long second = now / 1000L;
        if (second != this.selfLineSecond) {
            this.selfLineSecond = second;
            this.selfLinesThisSecond = 0;
        }
        if (this.selfLinesThisSecond++ < MISS_LINES_A_SECOND) {
            /* What tells a cause apart: the move's own yaw against the packet's, the client's sprint against the
               server's, and the ground speed the last tick left for this move. */
            write(String.format("SELF off %.4f (x %.4f y %.4f z %.4f) keys %s | yaw %.1f move-yaw %.1f %s%s%s| client %s"
                            + "| speed %.4f air %.4f | %s", result.offset,
                    mc.thePlayer.posX - result.end.x(), mc.thePlayer.getEntityBoundingBox().minY - result.end.y(),
                    mc.thePlayer.posZ - result.end.z(), result.keys(), MathHelper.wrapAngleTo180_float(sentYaw),
                    Float.isNaN(this.moveYaw) ? Float.NaN : MathHelper.wrapAngleTo180_float(this.moveYaw),
                    mc.thePlayer.onGround ? "ground " : "air ", sprint ? "sprint " : "", sneak ? "sneak " : "",
                    this.moveSprinting ? "sprint " : "walk ", before.moveSpeed, before.airSpeed, tag));
        }
    }

    /* LOWEST: the keys, the sprint and whatever a module set are final; the game's own move is next. */
    @EventTarget(Priority.LOWEST)
    public void onLivingUpdate(LivingUpdateEvent event) {
        this.simPredicted = null;
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.moveYaw = SimPlayer.inputAsMoved(mc.thePlayer).yaw;
        this.moveSprinting = mc.thePlayer.isSprinting();
        if (!SimPlayer.supported(mc.thePlayer)) {
            return;
        }
        PlayerSim sim = SimPlayer.state(mc.thePlayer);
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
        if (now - this.summaryAt >= this.logEvery.getValue() * 1000L) {
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
        float limit = this.threshold.getValue();
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
            if (module.isEnabled() && module != this) {
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
        write(String.format("SUM ticks %d skipped %d look-miss %d cam-miss %d plain-miss %d/%d big-turn %d sprint-change %d%s"
                        + " | sim %d/%d worst %.4f plain %d/%d | self %d/%d worst %.4f plain %d/%d",
                this.ticks, this.skipped, this.lookMisses, this.cameraMisses, this.plainMisses, this.plainTicks,
                this.bigTurns, this.sprintChanges, tags, this.simOff, this.simTicks, this.simWorst, this.simPlainOff,
                this.simPlainTicks, this.selfOff, this.selfTicks, this.selfWorst, this.selfPlainOff,
                this.selfPlainTicks));
        clearCounts();
    }

    private void clearCounts() {
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
        this.byTag.clear();
    }

    private void write(String line) {
        if (this.logFile == null) {
            this.logFile = new File(LOG_DIR, "moves-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logFile, LINE_STAMP.format(new Date()) + " " + line);
    }
}
