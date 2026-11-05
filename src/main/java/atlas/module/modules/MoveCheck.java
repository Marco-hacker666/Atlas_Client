package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.management.Arbiter;
import atlas.module.Module;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.AsyncLog;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
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
                && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == mc.thePlayer.getEntityId()) {
            this.knockedAt = System.currentTimeMillis();
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
        }
    }

    private void finish(float sentYaw) {
        long now = System.currentTimeMillis();
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
        write(String.format("SUM ticks %d skipped %d look-miss %d cam-miss %d plain-miss %d/%d big-turn %d sprint-change %d%s",
                this.ticks, this.skipped, this.lookMisses, this.cameraMisses, this.plainMisses, this.plainTicks,
                this.bigTurns, this.sprintChanges, tags));
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
        this.byTag.clear();
    }

    private void write(String line) {
        if (this.logFile == null) {
            this.logFile = new File(LOG_DIR, "moves-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logFile, LINE_STAMP.format(new Date()) + " " + line);
    }
}
