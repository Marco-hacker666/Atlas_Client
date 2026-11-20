package atlas.module.modules;

import atlas.util.Ping;
import atlas.util.AsyncLog;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.enums.BlinkModules;
import atlas.events.AttackEvent;
import atlas.events.LeftClickMouseEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.RightClickMouseEvent;
import atlas.events.UpdateEvent;
import atlas.management.Arbiter;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.BlockUtil;
import atlas.util.ChatUtil;
import atlas.util.MoveUtil;
import atlas.util.RandomUtil;
import atlas.util.RotationEngine;
import atlas.util.RotationUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.BlockTNT;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Clutch extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int HORIZON = 60;

    private static final int PLAN_TICKS = 16;
    private static final double FACE_INSET = 0.1;
    private static final int FACE_GRID = 4;

    private static final int ROTATION_PRIORITY = 7;

    private static final int MAX_REFUSALS = 3;

    private static final int PROBLEM_TICKS = 2;

    private static final int TRACE_LIMIT = 800;

    private static final int PRE_AIM_TICKS = 4;

    private static final int SETTLE_TICKS = 3;

    private static final double STEER_STOP = 0.3;

    private static final double ANCHOR_RANGE = 10.0;

    private static final long KNOCKBACK_MILLIS = 1000L;

    private static final int LADDER_TICKS = 12;

    private static final int CLIMB_TICKS = 60;

    private static final float LANE_KEEP = 35.0F;

    private static final double LANE_AHEAD = 3.0;

    private static final int LANE_RESCUE_TICKS = 3;
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    public final ModeProperty trigger = new ModeProperty("trigger", 2, new String[]{"AUTO", "HOLD", "BOTH"});
    public final KeyProperty holdKey = new KeyProperty("hold-key", Keyboard.KEY_NONE,
            () -> this.trigger.getValue() != 0);

    public final ModeProperty mode = new ModeProperty("mode", 1, new String[]{"SILENT", "REAL"});

    public final BooleanProperty resetAngle = new BooleanProperty("reset-angle", true, () -> this.mode.getValue() == 1);

    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"},
            () -> this.mode.getValue() == 0);

    public final FloatProperty reach = new FloatProperty("reach", 4.5F, 3.0F, 5.0F);

    public final IntProperty fov = new IntProperty("fov", 360, 30, 360);

    public final BooleanProperty onlySideways = new BooleanProperty("only-sideways", false);

    public final IntProperty speed = new IntProperty("speed", 30, 5, 180);

    public final IntProperty maxSpeed = new IntProperty("max-speed", 60, 10, 180);

    public final ModeProperty snapbackStyle = new ModeProperty("snapback-style", 1, new String[]{"Speed", "Even"});
    public final IntProperty snapback = new IntProperty("snapback-speed", 20, 1, 100,
            () -> this.snapbackStyle.getValue() == 0);
    public final IntProperty snapbackTicks = new IntProperty("snapback-ticks", 4, 1, 20,
            () -> this.snapbackStyle.getValue() == 1);

    public final IntProperty snapbackDelay = new IntProperty("snapback-delay", 0, 0, 20);

    public final IntProperty snapbackDelayRandom = new IntProperty("snapback-delay-random", 2, 0, 10);

    public final BooleanProperty snapOnJump = new BooleanProperty("snap-on-jump", false);

    public final BooleanProperty disableAfter = new BooleanProperty("disable-after", false);
    public final IntProperty randomization = new IntProperty("rotation-random", 15, 0, 60);

    public final IntProperty turnRandom = new IntProperty("turn-random", 25, 0, 60);

    public final BooleanProperty humanize = new BooleanProperty("humanize", true);

    public final BooleanProperty ease = new BooleanProperty("ease", false, this.humanize::getValue);

    public final FloatProperty edgeFall = new FloatProperty("edge-fall", 1.0F, 0.0F, 10.0F);

    public final BooleanProperty voidOnly = new BooleanProperty("void-only", true);

    public final FloatProperty safeDrop = new FloatProperty("safe-drop", 5.0F, 2.0F, 30.0F,
            () -> !this.voidOnly.getValue());

    public final IntProperty minHeight = new IntProperty("min-height", 0, 0, 20);
    public final IntProperty combatWindow = new IntProperty("combat-window", 2500, 0, 8000);

    public final IntProperty placeInterval = new IntProperty("place-interval", 1, 0, 6);

    public final BooleanProperty safeMode = new BooleanProperty("safe-mode", false);
    public final IntProperty earlyTurn = new IntProperty("early-turn", 45, 10, 180, this.safeMode::getValue);
    public final BooleanProperty chain = new BooleanProperty("chain", true);
    public final IntProperty chainLength = new IntProperty("chain-length", 3, 1, 6, this.chain::getValue);

    public final BooleanProperty keepY = new BooleanProperty("keep-y", false);

    public final BooleanProperty extraBlock = new BooleanProperty("extra-block", true);

    public final BooleanProperty counterKnockback = new BooleanProperty("counter-knockback", true);

    public final IntProperty counterDelay = new IntProperty("counter-delay", 6, 0, 15, this.counterKnockback::getValue);

    public final IntProperty hitSettle = new IntProperty("hit-settle", 2, 0, 6);

    public final BooleanProperty preAim = new BooleanProperty("pre-aim", true);

    public final BooleanProperty autoLadder = new BooleanProperty("auto-ladder", true);

    public final BooleanProperty autoSwitch = new BooleanProperty("auto-switch", true);
    public final BooleanProperty switchBack = new BooleanProperty("switch-back", true);

    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", true, this.autoSwitch::getValue);

    public final BooleanProperty onDepletion = new BooleanProperty("only-on-depletion", false,
            this.autoSwitch::getValue);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty pauseClicker = new BooleanProperty("pause-autoclicker", true);

    public final ModeProperty log = new ModeProperty("log", 2, new String[]{"OFF", "FILE", "CHAT+FILE"});

    private static final class Plan {
        final BlockPos cell;
        final BlockPos support;
        final EnumFacing side;
        final float yaw;
        final float pitch;
        final double cost;

        final int catchTick;

        boolean ladder;

        Plan(BlockPos cell, BlockPos support, EnumFacing side, float yaw, float pitch, double cost, int catchTick) {
            this.cell = cell;
            this.support = support;
            this.side = side;
            this.yaw = yaw;
            this.pitch = pitch;
            this.cost = cost;
            this.catchTick = catchTick;
        }
    }

    private final List<AxisAlignedBB> path = new ArrayList<AxisAlignedBB>();
    private AxisAlignedBB pathStart;
    private int landingTick;
    private double predictedFall;

    private boolean active;
    private Plan plan;

    private float sentYaw;
    private float sentPitch;
    private boolean hasSent;
    private boolean resetting;

    private int cooldown;
    private int lastHurtTime;
    private long lastHurtAt;
    private int lastHurtTick = -1000;

    private final RotationEngine engine = new RotationEngine();

    private int backWait;

    private boolean startedWithBlocks;
    private int previousSlot = -1;
    private boolean slotSwapped;

    private int shownSlot = -1;

    public int getSpoofSlot() {
        return this.isEnabled() && this.itemSpoof.getValue() && this.slotSwapped ? this.shownSlot : -1;
    }
    private boolean clickerPaused;

    private boolean attempt;
    private int attemptPlaced;
    private int attemptRefused;
    private int attemptTick;
    private double attemptStartY;

    private String attemptProblem;

    private String problemKey;
    private int problemRun;
    private String saidProblem;
    private final Set<BlockPos> placed = new HashSet<BlockPos>();

    private BlockPos extraTarget;
    private boolean extraDone;

    private Vec3 lastPosition;

    private Vec3 lastGround;

    private boolean plainJump;
    private boolean wasOnGround;

    private boolean steering;
    private float steerYaw;
    private boolean attemptSteered;

    private int settleTicks;

    private int planLead;

    private static final int LANE_STAIR_CELLS = 16;

    private static final int LANE_HUMAN_TICKS = LANE_RESCUE_TICKS + 3;
    private final atlas.humanize.Humanizer laneHuman = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_SCAFFOLD);
    private TellyLane lane;

    private boolean laneNow;

    private boolean laneKeys;
    private float laneYaw;

    private static final int LANE_BACK_TICKS = 3;

    private static final float LANE_FORWARD_MAX = 60.0F;

    private int laneLeft;

    private static final float LANE_ROUND = 100.0F;
    private static final float LANE_BACK = 80.0F;

    private int laneSide = 1;
    private boolean laneRound;

    private int laneCrossing;

    private double steerX;
    private double steerZ;

    private BlockPos ladderCell;
    private EnumFacing ladderSide;
    private int ladderTick;
    private boolean rodeLadder;

    private float viewYaw;
    private float viewPitch;
    private int saves;
    private int attempts;

    private final Set<BlockPos> savedOn = new HashSet<BlockPos>();
    private int savedAt = -1000;

    private int lastAttackTick = -1000;

    private int lastCorrectedTick = -1000;
    private boolean lastAttackSprinting;

    private boolean inUpdate;
    private int tick;

    private int backTicksLeft;

    private int pauseTicks;

    private final Map<BlockPos, Integer> placedAt = new ConcurrentHashMap<BlockPos, Integer>();

    private final java.util.Set<BlockPos> confirmed = java.util.Collections.newSetFromMap(
            new ConcurrentHashMap<BlockPos, Boolean>());

    private final Map<BlockPos, BlockPos> supportOf = new ConcurrentHashMap<BlockPos, BlockPos>();

    private volatile double sentPosX = Double.NaN;
    private volatile double sentPosY = Double.NaN;
    private volatile double sentPosZ = Double.NaN;

    private PendingClick pendingClick;

    private static final class PendingClick {
        final ItemStack held;
        final BlockPos support;
        final EnumFacing side;
        final Vec3 hitVec;
        final BlockPos cell;
        final boolean urgent;
        final int tick;

        PendingClick(ItemStack held, BlockPos support, EnumFacing side, Vec3 hitVec, BlockPos cell, boolean urgent,
                     int tick) {
            this.held = held;
            this.support = support;
            this.side = side;
            this.hitVec = hitVec;
            this.cell = cell;
            this.urgent = urgent;
            this.tick = tick;
        }
    }

    private final ConcurrentLinkedQueue<BlockPos> refused = new ConcurrentLinkedQueue<BlockPos>();

    private final Map<String, Integer> failedFaces = new HashMap<String, Integer>();
    private static final int FAILED_FACE_TICKS = 10;

    private boolean holdThisTick;

    private BlockPos aimedCell;
    private int aimedTick;

    private boolean quietNotes;

    private float lastTurn;
    private float placedTurn = -1.0F;

    private final Random noise = new Random();
    private double aimU = 0.5;
    private double aimV = 0.5;

    private int seenSupports;
    private int facesAway;
    private int facesFar;
    private int facesBlocked;
    private double nearestFace;
    private int chainNeed;
    private int chainLeft;

    private final List<String> trace = new ArrayList<String>();
    private File logTarget;
    private String lastNote;

    public Clutch() {
        super("Clutch", false, false, "Catches a fall with a block: knocked off a bridge, or off an edge over the void");
    }

    @Override
    public void onEnabled() {
        this.sentYaw = mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0F;
        this.sentPitch = mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0F;
        this.hasSent = false;
        this.resetting = false;
        this.active = false;
        this.plan = null;
        this.cooldown = 0;
        this.attempt = false;
        this.inUpdate = false;
        this.pauseTicks = 0;
        this.placedAt.clear();
        this.refused.clear();
        this.failedFaces.clear();
        this.logTarget = null;
        clearLane();
    }

    @Override
    public void onDisabled() {
        Arbiter.setCatching(getName(), false);
        Arbiter.setViewHeld(getName(), false);
        stopPlacing();
        this.active = false;
        this.plan = null;
        this.steering = false;
        this.settleTicks = 0;
        this.ladderCell = null;
        clearLane();
        abandonAttempt("module turned off");
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        abandonAttempt("world changed");
        clearLane();
        this.active = false;
        this.plan = null;
        this.placed.clear();
        this.placedAt.clear();
        this.refused.clear();
        this.failedFaces.clear();
        this.pauseTicks = 0;
        this.hasSent = false;
        this.resetting = false;
        this.lastGround = null;
        this.steering = false;
        this.settleTicks = 0;
        this.ladderCell = null;
    }

    private void say(String message) {
        trace(message.replaceAll("&[0-9a-fklmnor]", ""));
        if (this.log.getValue() == 2) {
            ChatUtil.sendFormatted("&7[&bClutch&7] " + message);
        }
    }

    private void trace(String line) {
        if (this.log.getValue() == 0) {
            return;
        }
        if (this.trace.size() < TRACE_LIMIT) {
            this.trace.add(LINE_STAMP.format(new Date())
                    + (this.attempt ? String.format(" t%-3d ", this.attemptTick) : "      ") + line);
        }
        if (!this.attempt) {
            flushTrace();
        }
    }

    private void note(String line) {
        if (this.quietNotes) {
            return;
        }
        if (!line.equals(this.lastNote)) {
            this.lastNote = line;
            trace("  post: " + line);
        }
    }

    private void flushTrace() {
        if (this.trace.isEmpty()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "clutch-" + FILE_STAMP.format(new Date()) + ".txt");
        }

        List<String> lines = new ArrayList<String>(this.trace);
        lines.add("");
        AsyncLog.append(this.logTarget, lines);
        this.trace.clear();
    }

    private void abandonAttempt(String why) {
        if (this.attempt) {
            this.attempt = false;
            trace("ended unresolved: " + why);
        }
        Arbiter.setCatching(getName(), false);
    }

    private static String cell(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private String state() {
        return String.format("pos %.2f %.2f %.2f v %+.2f %+.2f %+.2f fall %.1f",
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ, mc.thePlayer.fallDistance)
                + held();
    }

    private String held() {
        StringBuilder text = new StringBuilder();
        if (this.lastAttackTick == this.tick) {
            text.append(this.lastAttackSprinting ? " | swung (sprinting)" : " | swung");
        }
        int blinked = Atlas.blinkManager == null ? 0 : Atlas.blinkManager.blinkedPackets.size();
        if (blinked > 0) {
            text.append(" | blink ").append(Atlas.blinkManager.getBlinkingModule()).append(' ').append(blinked).append('p');
        }
        Module fakeLag = Atlas.moduleManager.modules.get(FakeLag.class);
        if (fakeLag instanceof FakeLag && ((FakeLag) fakeLag).heldCount() > 0) {
            text.append(" | fakelag ").append(((FakeLag) fakeLag).heldCount()).append('p');
        }
        return text.toString();
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.active) {
            event.setCancelled(true);
            return;
        }
        if (this.hasSent) {
            this.resetting = false;
            this.hasSent = false;
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (lookMisses(event.getTarget())) {
            event.setCancelled(true);
            return;
        }

        this.lastAttackTick = this.tick + 1;
        this.lastAttackSprinting = mc.thePlayer.isSprinting();
    }

    private int skippedHits;

    private boolean lookMisses(net.minecraft.entity.Entity target) {
        if (!this.hasSent || target == null || mc.thePlayer == null) {
            return false;
        }
        double grow = target.getCollisionBorderSize() + 0.2;
        AxisAlignedBB box = target.getEntityBoundingBox().expand(grow, grow, grow);
        if (RotationUtil.rayTrace(box, this.sentYaw, this.sentPitch, 6.0) != null) {
            return false;
        }
        this.skippedHits++;
        trace(String.format("    hit skipped: the server's look %.1f/%.1f misses %s (%d so far)",
                this.sentYaw, this.sentPitch, target.getName(), this.skippedHits));
        return true;
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled() || event.isCancelled() || mc.thePlayer == null || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != MovingObjectType.ENTITY) {
            return;
        }
        if (lookMisses(mc.objectMouseOver.entityHit)) {
            event.setCancelled(true);
        }
    }

    private int ping() {
        return Ping.own();
    }

    private int roundTripTicks() {
        int ping = ping();
        return ping <= 0 ? 4 : MathHelper.clamp_int((ping + 49) / 50, 2, 8);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (packet instanceof C03PacketPlayer && ((C03PacketPlayer) packet).isMoving() && !event.isCancelled()) {
                C03PacketPlayer move = (C03PacketPlayer) packet;
                this.sentPosX = move.getPositionX();
                this.sentPosY = move.getPositionY();
                this.sentPosZ = move.getPositionZ();
            }
            if (packet instanceof C03PacketPlayer.C06PacketPlayerPosLook && !this.inUpdate) {
                corrected((C03PacketPlayer.C06PacketPlayerPosLook) packet);
            }
            return;
        }

        if (packet instanceof S23PacketBlockChange && !this.placedAt.isEmpty()) {
            S23PacketBlockChange change = (S23PacketBlockChange) packet;
            BlockPos pos = change.getBlockPosition();
            if (pos != null && change.getBlockState() != null && this.placedAt.containsKey(pos)) {
                if (change.getBlockState().getBlock() == Blocks.air) {
                    this.refused.add(pos);
                } else {
                    this.confirmed.add(pos);
                }
            }
        }
    }

    private void corrected(C03PacketPlayer.C06PacketPlayerPosLook packet) {
        this.lastCorrectedTick = this.tick;
        this.sentYaw = packet.getYaw();
        this.sentPitch = packet.getPitch();
        int ticks = roundTripTicks() + 1;
        this.pauseTicks = Math.max(this.pauseTicks, ticks);
        if (this.attempt) {
            say(String.format("&ecorrected by the server &8(no clicks for %dt) &7%.2f %.2f %.2f",
                    ticks, packet.getPositionX(), packet.getPositionY(), packet.getPositionZ()));
        }
    }

    private void drainRefusals() {
        BlockPos pos;
        while ((pos = this.refused.poll()) != null) {
            Integer at = this.placedAt.remove(pos);
            this.confirmed.remove(pos);
            this.supportOf.remove(pos);
            if (at == null) {
                continue;
            }
            this.placed.remove(pos);
            if (pos.equals(this.ladderCell)) {
                this.ladderCell = null;
            }
            if (this.savedOn.contains(pos) && this.tick - this.savedAt <= 40) {
                this.savedOn.clear();
                this.saves--;
                say(String.format("&cthat save did not stand &8(the server refused %s, %dt after the landing)",
                        cell(pos), this.tick - this.savedAt));
            }
            if (this.attempt) {
                this.attemptRefused++;
                this.pauseTicks = Math.max(this.pauseTicks, roundTripTicks());
                say(String.format("&crefused &7%s &8(placed %dt ago%s)", cell(pos), this.tick - at,
                        this.attemptRefused >= MAX_REFUSALS ? ", clicking stopped for this fall" : ""));
            }
        }
        if (!this.placedAt.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Integer>> it = this.placedAt.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Integer> e = it.next();
                if (this.tick - e.getValue() > 100) {
                    this.confirmed.remove(e.getKey());
                    this.supportOf.remove(e.getKey());
                    it.remove();
                }
            }
        }
    }

    @EventTarget
    public void onLivingUpdate(atlas.events.LivingUpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || !this.hasSent || !mc.thePlayer.isSprinting()
                || !MoveUtil.isMoving()) {
            return;
        }
        float view = this.mode.getValue() == 1 ? this.viewYaw : mc.thePlayer.rotationYaw;
        float moving = MoveUtil.adjustYaw(view, MoveUtil.getForwardValue(), MoveUtil.getLeftValue());
        if (Math.abs(MathHelper.wrapAngleTo180_float(this.sentYaw - moving)) > 90.0F) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.steering) {
            press(this.steerYaw);
            return;
        }
        if (this.settleTicks > 0) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
            return;
        }
        if (this.laneKeys) {
            press(this.laneYaw);
            return;
        }
        if (!MoveUtil.isForwardPressed() || !RotationState.isActived()
                || RotationState.getPriority() != ROTATION_PRIORITY) {
            return;
        }
        if (this.mode.getValue() == 1 && this.hasSent) {
            press(MoveUtil.adjustYaw(this.viewYaw, MoveUtil.getForwardValue(), MoveUtil.getLeftValue()));
        } else if (this.mode.getValue() == 0 && this.moveFix.getValue() == 1) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    private static void press(float worldYaw) {
        float moveYaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        float relative = MathHelper.wrapAngleTo180_float(worldYaw - moveYaw) * 0.017453292F;
        float forward = MathHelper.cos(relative);
        float strafe = -MathHelper.sin(relative);
        float strength = mc.thePlayer.movementInput.sneak ? 0.3F : 1.0F;
        mc.thePlayer.movementInput.moveForward = forward > 0.38F ? strength : forward < -0.38F ? -strength : 0.0F;
        mc.thePlayer.movementInput.moveStrafe = strafe > 0.38F ? strength : strafe < -0.38F ? -strength : 0.0F;
    }

    private boolean steerTo(double x, double z) {
        double dx = x - mc.thePlayer.posX;
        double dz = z - mc.thePlayer.posZ;
        if (dx * dx + dz * dz < STEER_STOP * STEER_STOP) {
            return false;
        }
        this.steerX = x;
        this.steerZ = z;
        this.steerYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        return true;
    }

    private boolean climbing() {
        if (this.ladderCell == null || !this.attempt || mc.thePlayer.onGround) {
            return false;
        }
        double faceX = this.ladderCell.getX() + 0.5 - this.ladderSide.getFrontOffsetX() * 0.8;
        double faceZ = this.ladderCell.getZ() + 0.5 - this.ladderSide.getFrontOffsetZ() * 0.8;
        double dx = faceX - mc.thePlayer.posX;
        double dz = faceZ - mc.thePlayer.posZ;
        if (this.tick - this.ladderTick > CLIMB_TICKS || dx * dx + dz * dz > 2.5 * 2.5
                || mc.thePlayer.posY < this.ladderCell.getY() - 1.5) {
            this.ladderCell = null;
            this.rodeLadder = false;
            return false;
        }
        if (this.rodeLadder && keysAway(dx, dz)) {
            return false;
        }
        return steerTo(faceX, faceZ);
    }

    private boolean decideSteer() {
        if (!this.counterKnockback.getValue() || this.lastGround == null) {
            return false;
        }
        double dx = this.lastGround.xCoord - mc.thePlayer.posX;
        double dz = this.lastGround.zCoord - mc.thePlayer.posZ;
        if (dx * dx + dz * dz > ANCHOR_RANGE * ANCHOR_RANGE) {
            return false;
        }

        if (!this.attemptSteered) {
            boolean hitRecently = System.currentTimeMillis() - this.lastHurtAt < KNOCKBACK_MILLIS;
            boolean carriedAway = mc.thePlayer.motionX * dx + mc.thePlayer.motionZ * dz < 0.0;
            if (!hitRecently || !carriedAway || keysAway(dx, dz)) {
                return false;
            }
            if (this.tick - this.lastHurtTick < this.counterDelay.getValue()) {
                return false;
            }
        }
        return steerTo(this.lastGround.xCoord, this.lastGround.zCoord);
    }

    private boolean keysAway(double dx, double dz) {
        if (!MoveUtil.isForwardPressed()) {
            return false;
        }
        float view = this.mode.getValue() == 1 && this.hasSent ? this.viewYaw : mc.thePlayer.rotationYaw;
        float yaw = MoveUtil.adjustYaw(view, MoveUtil.getForwardValue(), MoveUtil.getLeftValue()) * 0.017453292F;
        return -MathHelper.sin(yaw) * dx + MathHelper.cos(yaw) * dz < 0.0;
    }

    private void predict(boolean steer) {
        this.path.clear();
        this.landingTick = -1;
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        this.pathStart = box;
        double mx = mc.thePlayer.motionX;
        double my = mc.thePlayer.motionY;
        double mz = mc.thePlayer.motionZ;
        double apex = box.minY;

        double push = steer ? (mc.thePlayer.isSprinting() ? 0.026 : 0.02) : 0.0;
        for (int t = 0; t < HORIZON; t++) {
            if (push > 0.0) {
                double dx = this.steerX - (box.minX + box.maxX) / 2.0;
                double dz = this.steerZ - (box.minZ + box.maxZ) / 2.0;
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > STEER_STOP) {
                    mx += dx / distance * push;
                    mz += dz / distance * push;
                }
            }
            double ox = mx;
            double oy = my;
            double oz = mz;
            List<AxisAlignedBB> hits = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box.addCoord(mx, my, mz));
            for (AxisAlignedBB hit : hits) {
                my = hit.calculateYOffset(box, my);
            }
            box = box.offset(0.0, my, 0.0);
            for (AxisAlignedBB hit : hits) {
                mx = hit.calculateXOffset(box, mx);
            }
            box = box.offset(mx, 0.0, 0.0);
            for (AxisAlignedBB hit : hits) {
                mz = hit.calculateZOffset(box, mz);
            }
            box = box.offset(0.0, 0.0, mz);
            this.path.add(box);
            apex = Math.max(apex, box.minY);
            if (oy != my && oy < 0.0) {
                this.landingTick = t;
                break;
            }
            if (box.minY < -64.0) {
                break;
            }
            mx = ox != mx ? 0.0 : mx * 0.91;
            mz = oz != mz ? 0.0 : mz * 0.91;
            my = oy != my ? 0.0 : (my - 0.08) * 0.98;
        }
        double already = mc.thePlayer.motionY < 0.0 ? mc.thePlayer.fallDistance : 0.0;
        AxisAlignedBB end = this.path.isEmpty() ? box : this.path.get(this.path.size() - 1);
        this.predictedFall = already + (apex - end.minY);
    }

    private boolean intoVoid() {
        return this.landingTick < 0;
    }

    private static int catchTick(List<AxisAlignedBB> path, AxisAlignedBB start, BlockPos cell, int limit) {
        double top = cell.getY() + 1.0;
        AxisAlignedBB previous = start;
        for (int t = 0; t < Math.min(limit, path.size()); t++) {
            AxisAlignedBB current = path.get(t);
            if (previous.minY >= top - 1.0E-3 && current.minY <= top) {
                return overColumn(previous, cell) || overColumn(current, cell) ? t : -1;
            }
            previous = current;
        }
        return -1;
    }

    private static boolean overColumn(AxisAlignedBB box, BlockPos cell) {
        return box.maxX > cell.getX() && box.minX < cell.getX() + 1
                && box.maxZ > cell.getZ() && box.minZ < cell.getZ() + 1;
    }

    private static boolean intersects(AxisAlignedBB box, BlockPos cell) {
        return box.intersectsWith(new AxisAlignedBB(cell, cell.add(1, 1, 1)));
    }

    private final List<AxisAlignedBB> opponentBoxes = new ArrayList<AxisAlignedBB>();

    private void updateOpponentBoxes() {
        this.opponentBoxes.clear();
        int lead = Math.round(atlas.util.Ping.own() / 50.0F) + 2;
        for (net.minecraft.entity.player.EntityPlayer p : mc.theWorld.playerEntities) {
            if (p == mc.thePlayer || p.isDead || p.isSpectator() || atlas.util.TeamUtil.isBot(p)
                    || mc.thePlayer.getDistanceSqToEntity(p) > 64.0) {
                continue;
            }
            double speed = Math.min(0.6, Math.hypot(p.posX - p.lastTickPosX, p.posZ - p.lastTickPosZ));

            double grow = Math.min(1.2, 0.2 + (atlas.util.Ping.own() > 150 ? 0.1 : 0.0) + speed * lead);
            AxisAlignedBB drawn = p.getEntityBoundingBox();
            double sx = p.serverPosX / 32.0 - p.posX;
            double sy = p.serverPosY / 32.0 - p.posY;
            double sz = p.serverPosZ / 32.0 - p.posZ;
            AxisAlignedBB server = drawn.offset(sx, sy, sz);
            this.opponentBoxes.add(drawn.union(server).expand(grow, 0.1, grow));
        }
    }

    private boolean opponentIn(BlockPos cell) {
        for (AxisAlignedBB box : this.opponentBoxes) {
            if (intersects(box, cell)) {
                return true;
            }
        }
        return false;
    }

    private boolean holdActive() {
        return this.holdKey.getValue() != Keyboard.KEY_NONE && this.holdKey.isDown();
    }

    private boolean wanted() {
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying || mc.thePlayer.isInWater()
                || mc.thePlayer.isInLava() || mc.thePlayer.isOnLadder() || mc.thePlayer.isRiding()) {
            return false;
        }
        if (scaffoldTelly()) {
            return true;
        }
        int mode = this.trigger.getValue();
        if (mode == 1) {
            return holdActive();
        }
        boolean hit = System.currentTimeMillis() - this.lastHurtAt < this.combatWindow.getValue();

        boolean edge = mc.thePlayer.fallDistance >= this.edgeFall.getValue()
                && !mc.gameSettings.keyBindUseItem.isKeyDown();
        boolean auto = intoVoid()
                ? hit || edge
                : !this.voidOnly.getValue() && hit && this.predictedFall > this.safeDrop.getValue();
        if (auto && this.minHeight.getValue() > 0 && airBelow(this.minHeight.getValue()) < this.minHeight.getValue()) {
            auto = false;
        }
        return mode == 0 ? auto : auto || holdActive();
    }

    private int pickBlockSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        if (usable(mc.thePlayer.inventory.getStackInSlot(current))) {
            return current;
        }
        int best = -1;
        int bestCount = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (usable(stack) && stack.stackSize > bestCount) {
                best = slot;
                bestCount = stack.stackSize;
            }
        }
        return best;
    }

    private static boolean usable(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();

        boolean full = block.getBlockBoundsMinX() <= 0.0 && block.getBlockBoundsMaxX() >= 1.0
                && block.getBlockBoundsMinY() <= 0.0 && block.getBlockBoundsMaxY() >= 1.0
                && block.getBlockBoundsMinZ() <= 0.0 && block.getBlockBoundsMaxZ() >= 1.0;
        return full && block.getMaterial().isSolid() && BlockUtil.isSolid(block)
                && !BlockUtil.isInteractable(block)
                && !(block instanceof BlockFalling) && !(block instanceof BlockTNT);
    }

    private static boolean isLadder(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() == Item.getItemFromBlock(Blocks.ladder);
    }

    private int pickLadderSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        if (isLadder(mc.thePlayer.inventory.getStackInSlot(current))) {
            return current;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isLadder(mc.thePlayer.inventory.getStackInSlot(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private void selectSlot(int slot) {
        if (slot < 0 || mc.thePlayer.inventory.currentItem == slot || !this.autoSwitch.getValue()) {
            return;
        }
        if (this.previousSlot < 0 && this.switchBack.getValue()) {
            this.previousSlot = mc.thePlayer.inventory.currentItem;
        }
        if (!this.slotSwapped) {
            this.shownSlot = mc.thePlayer.inventory.currentItem;
        }
        mc.thePlayer.inventory.currentItem = slot;
        this.slotSwapped = true;
    }

    private void startPlacing() {
        if (this.pauseClicker.getValue() && !this.clickerPaused) {
            Module clicker = Atlas.moduleManager.getModule(AutoClicker.class);
            if (clicker != null && clicker.isEnabled()) {
                clicker.setEnabled(false);
                this.clickerPaused = true;
            }
        }
    }

    private void stopPlacing() {
        if (this.slotSwapped && this.previousSlot >= 0 && this.previousSlot < 9 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = this.previousSlot;
        }
        this.slotSwapped = false;
        this.previousSlot = -1;
        this.shownSlot = -1;
        if (this.clickerPaused) {
            Module clicker = Atlas.moduleManager.getModule(AutoClicker.class);
            if (clicker != null) {
                clicker.setEnabled(true);
            }
            this.clickerPaused = false;
        }
    }

    private Vec3 eyesAt(AxisAlignedBB box) {
        return new Vec3((box.minX + box.maxX) / 2.0, box.minY + mc.thePlayer.getEyeHeight(), (box.minZ + box.maxZ) / 2.0);
    }

    private static Vec3 look(float yaw, float pitch) {
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cosPitch = -MathHelper.cos(-pitch * 0.017453292F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(sinYaw * cosPitch, sinPitch, cosYaw * cosPitch);
    }

    private MovingObjectPosition ray(Vec3 eyes, float yaw, float pitch) {
        Vec3 dir = look(yaw, pitch);
        double r = this.reach.getValue();
        return mc.theWorld.rayTraceBlocks(eyes, eyes.addVector(dir.xCoord * r, dir.yCoord * r, dir.zCoord * r),
                false, false, false);
    }

    private double rotationCost(float yaw, float pitch) {
        return Math.abs(MathHelper.wrapAngleTo180_float(yaw - this.sentYaw)) + Math.abs(pitch - this.sentPitch);
    }

    private static boolean clickable(BlockPos pos) {
        return !BlockUtil.isReplaceable(pos) && !BlockUtil.isInteractable(pos);
    }

    private Plan aim(BlockPos cell, Vec3 eyes, int catchTick) {
        Plan best = null;
        for (EnumFacing toSupport : EnumFacing.values()) {
            if (this.onlySideways.getValue() && toSupport.getAxis() == EnumFacing.Axis.Y) {
                continue;
            }
            BlockPos support = cell.offset(toSupport);
            if (!clickable(support) || faceFailed(support, toSupport.getOpposite())) {
                continue;
            }
            this.seenSupports++;
            Plan candidate = aimFace(cell, support, toSupport.getOpposite(), eyes, catchTick,
                    best == null ? Double.MAX_VALUE : best.cost);
            if (candidate != null && inFov(candidate)) {
                best = candidate;
            }
        }
        return best;
    }

    private Plan aimFace(BlockPos cell, BlockPos support, EnumFacing side, Vec3 eyes, int catchTick, double bound) {
        Plan best = null;

        double bestScore = Double.MAX_VALUE;
        double spread = spreadWeight();
        double reachSq = this.reach.getValue() * this.reach.getValue();
        {
            double nx = side.getFrontOffsetX();
            double ny = side.getFrontOffsetY();
            double nz = side.getFrontOffsetZ();
            double planeX = nx > 0 ? support.getX() + 1 : nx < 0 ? support.getX() : Double.NaN;
            double planeY = ny > 0 ? support.getY() + 1 : ny < 0 ? support.getY() : Double.NaN;
            double planeZ = nz > 0 ? support.getZ() + 1 : nz < 0 ? support.getZ() : Double.NaN;
            if ((!Double.isNaN(planeX) && (eyes.xCoord - planeX) * nx <= 0.0)
                    || (!Double.isNaN(planeY) && (eyes.yCoord - planeY) * ny <= 0.0)
                    || (!Double.isNaN(planeZ) && (eyes.zCoord - planeZ) * nz <= 0.0)) {
                this.facesAway++;
                return null;
            }
            boolean inReach = false;
            double closest = Double.MAX_VALUE;
            boolean traced = false;
            boolean seen = false;

            List<double[]> samples = new ArrayList<double[]>();
            double cheapest = Double.MAX_VALUE;
            for (int i = 0; i <= FACE_GRID; i++) {
                double u = FACE_INSET + (1.0 - 2.0 * FACE_INSET) * i / FACE_GRID;
                for (int j = 0; j <= FACE_GRID; j++) {
                    double v = FACE_INSET + (1.0 - 2.0 * FACE_INSET) * j / FACE_GRID;
                    double px = !Double.isNaN(planeX) ? planeX : support.getX() + u;
                    double py = !Double.isNaN(planeY) ? planeY : support.getY() + (Double.isNaN(planeX) ? v : u);
                    double pz = !Double.isNaN(planeZ) ? planeZ : support.getZ() + v;
                    double dx = px - eyes.xCoord;
                    double dy = py - eyes.yCoord;
                    double dz = pz - eyes.zCoord;
                    double distanceSq = dx * dx + dy * dy + dz * dz;
                    if (distanceSq > reachSq) {
                        closest = Math.min(closest, distanceSq);
                        continue;
                    }
                    inReach = true;
                    float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    yaw = this.sentYaw + MathHelper.wrapAngleTo180_float(yaw - this.sentYaw);
                    double cost = rotationCost(yaw, pitch);
                    if (cost >= bound) {
                        continue;
                    }
                    cheapest = Math.min(cheapest, cost);
                    samples.add(new double[]{yaw, pitch, cost, Math.hypot(u - this.aimU, v - this.aimV)});
                }
            }
            double slack = 0.0;
            if (spread > 0.0 && !samples.isEmpty()) {
                slack = this.randomization.getValue() * 0.5;
                if (catchTick >= 0) {
                    double room = this.maxSpeed.getValue() * Math.max(1, catchTick) - cheapest;
                    slack = Math.max(0.0, Math.min(slack, room * 0.5));
                }
            }
            final double limit = cheapest + slack;

            samples.sort((a, b) -> {
                boolean inA = a[2] <= limit;
                boolean inB = b[2] <= limit;
                if (inA != inB) {
                    return inA ? -1 : 1;
                }
                return inA ? Double.compare(a[3], b[3]) : Double.compare(a[2], b[2]);
            });
            for (double[] sample : samples) {
                traced = true;
                float yaw = (float) sample[0];
                float pitch = (float) sample[1];
                MovingObjectPosition hit = ray(eyes, yaw, pitch);
                if (hit == null || hit.typeOfHit != MovingObjectType.BLOCK
                        || !support.equals(hit.getBlockPos()) || hit.sideHit != side) {
                    continue;
                }
                seen = true;
                bestScore = sample[2];
                best = new Plan(cell, support, side, yaw, pitch, sample[2], catchTick);
                break;
            }
            if (!inReach) {
                this.facesFar++;
                this.nearestFace = Math.min(this.nearestFace, Math.sqrt(closest));
            } else if (traced && !seen) {
                this.facesBlocked++;
            }
        }
        return best;
    }

    private static BlockPos ladderCellOf(AxisAlignedBB box) {
        return new BlockPos(MathHelper.floor_double((box.minX + box.maxX) / 2.0), MathHelper.floor_double(box.minY),
                MathHelper.floor_double((box.minZ + box.maxZ) / 2.0));
    }

    private Plan planLadder() {
        Plan best = null;
        int bestTick = Integer.MAX_VALUE;
        float turn = this.maxSpeed.getValue();
        BlockPos previous = null;
        for (int t = 1; t < Math.min(LADDER_TICKS, this.path.size()) && t <= bestTick; t++) {
            BlockPos cell = ladderCellOf(this.path.get(t));
            if (cell.equals(previous) || !BlockUtil.isReplaceable(cell)) {
                previous = cell;
                continue;
            }
            previous = cell;
            int last = -1;
            for (int k = t - 1; k >= 0; k--) {
                if (!intersects(this.path.get(k), cell)) {
                    last = k;
                    break;
                }
            }
            if (last < 0) {
                continue;
            }
            Vec3 eyes = eyesAt(this.path.get(last));
            for (EnumFacing side : EnumFacing.Plane.HORIZONTAL) {
                BlockPos support = cell.offset(side.getOpposite());
                if (!mc.theWorld.getBlockState(support).getBlock().isNormalCube() || BlockUtil.isInteractable(support)
                        || faceFailed(support, side)) {
                    continue;
                }
                Plan candidate = aimFace(cell, support, side, eyes, last + 1, Double.MAX_VALUE);
                if (candidate == null || candidate.cost > (last + 1) * turn) {
                    continue;
                }
                if (t < bestTick || candidate.cost < best.cost) {
                    candidate.ladder = true;
                    best = candidate;
                    bestTick = t;
                }
            }
        }
        return best;
    }

    private static boolean supported(BlockPos cell) {
        for (EnumFacing facing : EnumFacing.values()) {
            if (clickable(cell.offset(facing))) {
                return true;
            }
        }
        return false;
    }

    private static double nearestBlock(Vec3 eyes, int radius) {
        double best = Double.MAX_VALUE;
        int cx = MathHelper.floor_double(eyes.xCoord);
        int cy = MathHelper.floor_double(eyes.yCoord);
        int cz = MathHelper.floor_double(eyes.zCoord);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int y = cy - radius; y <= cy + radius; y++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!clickable(pos)) {
                        continue;
                    }
                    double dx = Math.max(Math.max(x - eyes.xCoord, eyes.xCoord - (x + 1)), 0.0);
                    double dy = Math.max(Math.max(y - eyes.yCoord, eyes.yCoord - (y + 1)), 0.0);
                    double dz = Math.max(Math.max(z - eyes.zCoord, eyes.zCoord - (z + 1)), 0.0);
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
        }
        return best;
    }

    private void resetDiagnosis() {
        this.seenSupports = 0;
        this.facesAway = 0;
        this.facesFar = 0;
        this.facesBlocked = 0;
        this.nearestFace = Double.MAX_VALUE;
        this.chainNeed = 0;
        this.chainLeft = 0;
    }

    private String[] diagnose(Vec3 eyes) {
        if (this.seenSupports == 0 && this.chainNeed == 0) {
            double nearest = nearestBlock(eyes, 6);
            return new String[]{"none", nearest == Double.MAX_VALUE
                    ? "no block within 6 of the fall"
                    : String.format("no block next to the fall (nearest %.1f from the eyes)", nearest)};
        }
        List<String> parts = new ArrayList<String>();
        String key = null;
        if (this.facesFar > 0) {
            key = "far";
            parts.add(String.format("out of reach (nearest face %.2f, reach %.1f)",
                    this.nearestFace, this.reach.getValue()));
        }
        if (this.facesBlocked > 0) {
            key = key != null ? key : "blocked";
            parts.add(this.facesBlocked + " face" + (this.facesBlocked == 1 ? "" : "s") + " blocked from view");
        }
        if (this.chainNeed > 0) {
            key = key != null ? key : "chain";
            parts.add(String.format("a chain needs %dt, the fall leaves %dt", this.chainNeed, this.chainLeft));
        }
        if (this.facesAway > 0) {
            key = key != null ? key : "away";
            parts.add(this.facesAway + " face" + (this.facesAway == 1 ? "" : "s") + " turned away");
        }
        if (parts.isEmpty()) {
            return new String[]{"none", "nothing to build from within reach"};
        }
        StringBuilder words = new StringBuilder();
        for (String part : parts) {
            words.append(words.length() == 0 ? "" : ", ").append(part);
        }
        return new String[]{key, words.toString()};
    }

    private List<int[]> footprint(int ticks) {
        List<int[]> columns = new ArrayList<int[]>();
        Set<Long> seen = new HashSet<Long>();
        int limit = Math.min(ticks, this.path.size());
        for (int t = 0; t < limit; t++) {
            AxisAlignedBB box = this.path.get(t);
            for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX - 1.0E-7); x++) {
                for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ - 1.0E-7); z++) {
                    if (seen.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) {
                        columns.add(new int[]{x, z});
                    }
                }
            }
        }
        return columns;
    }

    private List<List<BlockPos>> catchCells() {
        List<List<BlockPos>> levels = new ArrayList<List<BlockPos>>();
        if (this.path.isEmpty()) {
            return levels;
        }
        AxisAlignedBB now = this.pathStart;
        int feet = MathHelper.floor_double(now.minY + 1.0E-3);
        int top = Math.min(feet - 1, keepYTop());
        List<int[]> columns = footprint(PLAN_TICKS);
        for (int level = top; level >= feet - 5; level--) {
            List<BlockPos> cells = new ArrayList<BlockPos>();
            for (int[] column : columns) {
                BlockPos cell = new BlockPos(column[0], level, column[1]);
                if (!BlockUtil.isReplaceable(cell) || intersects(now, cell) || opponentIn(cell)) {
                    continue;
                }
                if (catchTick(this.path, this.pathStart, cell, PLAN_TICKS) >= 0) {
                    cells.add(cell);
                }
            }
            if (!cells.isEmpty()) {
                levels.add(landingCellOnly(cells));
            }
        }
        return levels;
    }

    private int keepYTop() {
        return (this.keepY.getValue() || scaffoldTellyKeepY()) && this.lastGround != null && !this.plainJump
                ? MathHelper.floor_double(this.lastGround.yCoord - 1.0E-3) : Integer.MAX_VALUE;
    }

    static final int ONE_CELL_TICKS = 3;

    private List<BlockPos> landingCellOnly(List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            int crossing = catchTick(this.path, this.pathStart, cell, PLAN_TICKS);
            if (crossing < 0 || this.path.isEmpty()) {
                continue;
            }
            AxisAlignedBB box = this.path.get(Math.min(crossing, this.path.size() - 1));
            if (isUnderMiddle(box, cell)) {
                return crossing >= ONE_CELL_TICKS ? Collections.singletonList(cell) : cells;
            }
        }
        return cells;
    }

    static boolean isUnderMiddle(AxisAlignedBB box, BlockPos cell) {
        return MathHelper.floor_double((box.minX + box.maxX) / 2.0) == cell.getX()
                && MathHelper.floor_double((box.minZ + box.maxZ) / 2.0) == cell.getZ();
    }

    static final double PLAIN_JUMP_RISE = 0.3;

    static final double PLAIN_JUMP_SPEED = 0.08;

    static final double PLAIN_JUMP_LOST = 0.15;

    static boolean isPlainJump(double motionY, double motionX, double motionZ, boolean keys) {
        return motionY > PLAIN_JUMP_RISE && !keys && Math.hypot(motionX, motionZ) < PLAIN_JUMP_SPEED;
    }

    static boolean restsOn(AxisAlignedBB box, Set<BlockPos> cells) {
        if (box == null || cells.isEmpty()) {
            return false;
        }
        int y = MathHelper.floor_double(box.minY - 1.0E-3);
        for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX - 1.0E-7); x++) {
            for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ - 1.0E-7); z++) {
                if (cells.contains(new BlockPos(x, y, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    private BlockPos extraCell() {
        if (this.lane != null && !this.plainJump) {
            return null;
        }
        if (!this.extraBlock.getValue() || !this.attempt || this.extraDone || this.attemptPlaced <= 0
                || mc.thePlayer.onGround || this.landingTick < 0 || this.landingTick >= this.path.size()) {
            return null;
        }
        AxisAlignedBB land = this.path.get(this.landingTick);
        int level = MathHelper.floor_double(land.minY - 1.0E-3);
        BlockPos cell = new BlockPos(MathHelper.floor_double((land.minX + land.maxX) / 2.0), level,
                MathHelper.floor_double((land.minZ + land.maxZ) / 2.0));
        if (!BlockUtil.isReplaceable(cell) || intersects(this.pathStart, cell) || opponentIn(cell)
                || catchTick(this.path, this.pathStart, cell, PLAN_TICKS) < 0) {
            return null;
        }

        for (BlockPos pos : this.placed) {
            if (pos.getY() == level && Math.abs(pos.getX() - cell.getX()) <= 1
                    && Math.abs(pos.getZ() - cell.getZ()) <= 1) {
                return cell;
            }
        }
        return null;
    }

    private Plan planCatch(List<List<BlockPos>> levels, Vec3 eyes, int lead) {
        for (List<BlockPos> cells : levels) {
            Plan best = null;
            for (BlockPos cell : cells) {
                int crossing = catchTick(this.path, this.pathStart, cell, PLAN_TICKS);
                if (crossing < lead && !this.laneNow) {
                    continue;
                }
                Plan candidate = aim(cell, eyes, crossing);
                if (candidate != null && (best == null || candidate.cost < best.cost)) {
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private Plan planChain(List<List<BlockPos>> levels, Vec3 eyes, int lead) {
        if (!this.chain.getValue() || this.laneNow) {
            return null;
        }
        int interval = Math.max(1, this.placeInterval.getValue());

        AxisAlignedBB next = this.pathStart;
        for (List<BlockPos> cells : levels) {
            int deadline = PLAN_TICKS;
            for (BlockPos cell : cells) {
                deadline = Math.min(deadline, catchTick(this.path, this.pathStart, cell, PLAN_TICKS));
            }
            Map<BlockPos, Integer> depth = new HashMap<BlockPos, Integer>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
            for (BlockPos cell : cells) {
                depth.put(cell, 0);
                queue.add(cell);
            }
            Plan best = null;
            int bestDepth = Integer.MAX_VALUE;
            while (!queue.isEmpty()) {
                BlockPos cell = queue.poll();
                int d = depth.get(cell);
                if (d > bestDepth) {
                    break;
                }
                if (d > 0) {
                    if (d * interval + lead > deadline) {
                        int need = d * interval + lead;
                        if ((this.chainNeed == 0 || need < this.chainNeed) && supported(cell)) {
                            this.chainNeed = need;
                            this.chainLeft = deadline;
                        }
                        continue;
                    }
                    Plan candidate = aim(cell, eyes, -1);
                    if (candidate != null && (best == null || d < bestDepth || candidate.cost < best.cost)) {
                        best = candidate;
                        bestDepth = d;
                    }
                }
                if (d >= this.chainLength.getValue()) {
                    continue;
                }
                for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
                    BlockPos neighbour = cell.offset(facing);
                    if (!depth.containsKey(neighbour) && BlockUtil.isReplaceable(neighbour)
                            && !intersects(next, neighbour) && !opponentIn(neighbour)) {
                        depth.put(neighbour, d + 1);
                        queue.add(neighbour);
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private final float[] wander = new float[2];
    private final float[] wanderFrom = new float[2];
    private final float[] wanderTo = new float[2];
    private final int[] wanderTick = new int[2];
    private final int[] wanderLength = {1, 1};
    private final java.util.Random turnRandomSource = new java.util.Random();

    private float wander(int i) {
        if (this.wanderTick[i] >= this.wanderLength[i]) {
            this.wanderFrom[i] = this.wander[i];
            this.wanderTo[i] = this.turnRandomSource.nextFloat() * 2.0F - 1.0F;
            this.wanderLength[i] = 2 + this.turnRandomSource.nextInt(3);
            this.wanderTick[i] = 0;
        }
        this.wanderTick[i]++;
        float t = this.wanderTick[i] / (float) this.wanderLength[i];
        this.wander[i] = this.wanderFrom[i] + (this.wanderTo[i] - this.wanderFrom[i]) * t;
        return this.wander[i];
    }

    private void turnTo(float targetYaw, float targetPitch, float step, boolean urgent) {
        if (this.laneNow && this.laneCrossing > LANE_HUMAN_TICKS) {
            float[] look = atlas.humanize.HumanTurn.apply(this.laneHuman, this.sentYaw, this.sentPitch, targetYaw, targetPitch);
            targetYaw = look[0];
            targetPitch = look[1];
        }
        float r = this.turnRandom.getValue() / 100.0F;
        if (r > 0.0F) {
            float u = wander(0);
            float factor = urgent ? 1.0F + r * (u + 1.0F) / 2.0F : 1.0F + r * u;

            step = urgent ? Math.min(step * factor, Math.max(step, this.maxSpeed.getValue()))
                    : Math.min(step * factor, this.maxSpeed.getValue());
            if (targetPitch > 75.0F) {
                targetYaw += wander(1) * r * 6.0F;
            }
        }
        stepRotation(targetYaw, targetPitch, step);
    }

    private void stepRotation(float targetYaw, float targetPitch, float step) {
        if (this.holdThisTick) {
            return;
        }
        Set<RotationEngine.Feature> features = EnumSet.noneOf(RotationEngine.Feature.class);
        if (this.humanize.getValue()) {
            features.add(RotationEngine.Feature.NOISE);
            features.add(RotationEngine.Feature.CURVE);
            if (this.ease.getValue()) {
                features.add(RotationEngine.Feature.EASE);
            }
        }
        float[] next = this.engine.step(this.sentYaw, this.sentPitch, targetYaw, targetPitch, step,
                Math.max(this.speed.getValue(), this.maxSpeed.getValue() * 0.5F), this.randomization.getValue(), true,
                features);
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(next[0] - this.sentYaw));
        for (int guard = 0; guard < 3 && turn > 2.0F
                && (Math.abs(turn - this.placedTurn) < 1.0E-3F
                || atlas.management.PlaceRotations.wouldDuplicate(next[0])
                || this.lane != null && TellyLane.onGrid(next[0])); guard++) {
            double gcd = RotationUtil.gcd();
            next[0] += Math.signum(MathHelper.wrapAngleTo180_float(next[0] - this.sentYaw))
                    * (float) (gcd > 0.0 ? gcd : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(next[0] - this.sentYaw));
        }
        if (turn > 0.0F) {
            this.lastTurn = turn;
        }
        this.sentYaw = next[0];
        this.sentPitch = next[1];
    }

    private void drawAimSpot() {
        double lo = FACE_INSET;
        double hi = 1.0 - FACE_INSET;
        this.aimU = Math.max(lo, Math.min(hi, 0.5 + this.noise.nextGaussian() * 0.18));
        this.aimV = Math.max(lo, Math.min(hi, 0.5 + this.noise.nextGaussian() * 0.18));
    }

    private double spreadWeight() {
        return this.humanize.getValue() ? this.randomization.getValue() * 0.24 : 0.0;
    }

    private boolean inFov(Plan plan) {
        if (this.fov.getValue() >= 360) {
            return true;
        }
        float yaw = this.hasSent ? this.viewYaw : mc.thePlayer.rotationYaw;
        float pitch = this.hasSent ? this.viewPitch : mc.thePlayer.rotationPitch;
        return RotationEngine.angle(plan.yaw, plan.pitch, yaw, pitch) <= this.fov.getValue() / 2.0F;
    }

    private int airBelow(int limit) {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY - 0.01, mc.thePlayer.posZ);
        int air = 0;
        while (air < limit && feet.getY() - air >= 0 && BlockUtil.isReplaceable(feet.down(air))) {
            air++;
        }
        return feet.getY() - air < 0 ? limit : air;
    }

    private float stepFor(Plan plan) {
        if (plan.catchTick < 0 || this.laneNow && lateTurn()) {
            return this.maxSpeed.getValue();
        }
        float needed = (float) plan.cost / Math.max(1, plan.catchTick);
        return Math.max(this.speed.getValue(), Math.min(this.maxSpeed.getValue(), needed * 1.25F));
    }

    private void applyRotation(UpdateEvent event) {
        this.hasSent = true;
        if (this.mode.getValue() == 1) {
            Atlas.rotationManager.setRotation(this.sentYaw, this.sentPitch, ROTATION_PRIORITY, !this.resetting);
        }
        event.setRotation(this.sentYaw, this.sentPitch, ROTATION_PRIORITY);

        event.setPervRotation(this.mode.getValue() == 1 || this.moveFix.getValue() != 0
                ? this.sentYaw : mc.thePlayer.rotationYaw, ROTATION_PRIORITY);
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.POST) {
            this.inUpdate = false;
            return;
        }
        this.inUpdate = true;
        this.tick++;
        if (mc.thePlayer.hurtTime > this.lastHurtTime) {
            this.lastHurtAt = System.currentTimeMillis();
            this.lastHurtTick = this.tick;
        }
        this.lastHurtTime = mc.thePlayer.hurtTime;
        if (this.resetting && this.mode.getValue() == 1
                && (Math.abs(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - this.sentYaw)) > 0.5F
                || Math.abs(mc.thePlayer.rotationPitch - this.sentPitch) > 0.5F)) {
            this.resetting = false;
            this.hasSent = false;
        }
        if (!this.hasSent) {
            this.sentYaw = mc.thePlayer.rotationYaw;
            this.sentPitch = mc.thePlayer.rotationPitch;
            this.engine.reset();
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
        }
        if (this.settleTicks > 0) {
            this.settleTicks--;
        }
        drainRefusals();
        if (mc.thePlayer.onGround) {
            this.lastGround = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            this.plainJump = false;
            this.wasOnGround = true;
        } else {
            if (this.wasOnGround) {
                this.plainJump = isPlainJump(mc.thePlayer.motionY, mc.thePlayer.motionX, mc.thePlayer.motionZ,
                        MoveUtil.isForwardPressed());
            } else if (Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) > PLAIN_JUMP_LOST) {
                this.plainJump = false;
            }
            this.wasOnGround = false;
        }

        this.holdThisTick = false;
        this.laneKeys = false;
        updateLane();
        updateLaneSide();

        updateOpponentBoxes();
        predict(false);

        BlockPos extra = extraCell();
        this.extraTarget = extra;
        boolean want = mc.currentScreen == null && !scaffolding() && (wanted() || extra != null);
        updateAttempt(want);

        Arbiter.setCatching(getName(), this.attempt);
        Arbiter.setViewHeld(getName(), this.hasSent && this.mode.getValue() == 1);
        List<String> yielded = Arbiter.drainYielded();
        if (this.attempt && !yielded.isEmpty()) {
            trace("    stood aside for the catch: " + String.join(", ", yielded));
        }
        this.plan = null;
        this.planLead = 0;
        if (this.attempt && this.ladderCell != null && !this.rodeLadder && mc.thePlayer.isOnLadder()
                && ladderCellOf(mc.thePlayer.getEntityBoundingBox()).equals(this.ladderCell)) {
            this.rodeLadder = true;
            say("&acaught on the ladder &8(climbing)");
        }
        if (this.attempt && this.tick - this.lastCorrectedTick <= 1) {
            this.steering = false;
            trace(state() + " | the server is moving the player: hands off");
            holdRotation(event);
            return;
        }

        this.steering = climbing() || (want && decideSteer());
        if (this.steering) {
            this.attemptSteered = true;
            predict(true);
        }
        this.laneNow = want && this.lane != null && !this.plainJump && !this.steering && this.ladderCell == null;
        if (this.laneNow && MoveUtil.isForwardPressed()) {
            aimAlongLane();
        }

        if (!want) {
            if (this.attempt) {
                trace(state() + " | not needed now" + (this.landingTick >= 0
                        ? String.format(" (lands in %dt, %.1f fall)", this.landingTick, this.predictedFall) : ""));
            }
            this.active = false;
            this.aimedCell = null;
            stopPlacing();

            if (this.hasSent) {
                if (mc.currentScreen != null && scaffoldTelly()) {
                    holdRotation(event);
                    return;
                }

                boolean real = this.mode.getValue() == 1;
                if (real && !this.resetAngle.getValue()) {
                    this.hasSent = false;
                    if (this.disableAfter.getValue() && this.attemptPlaced > 0) {
                        this.setEnabled(false);
                    }
                    return;
                }
                float backYaw = real ? this.sentYaw + MathHelper.wrapAngleTo180_float(this.viewYaw - this.sentYaw)
                        : mc.thePlayer.rotationYaw;
                float backPitch = real ? this.viewPitch : mc.thePlayer.rotationPitch;
                boolean jump = this.snapOnJump.getValue() && mc.gameSettings.keyBindJump.isKeyDown();
                boolean laneJump = this.lane != null && MoveUtil.isForwardPressed()
                        && mc.gameSettings.keyBindJump.isKeyDown();
                if (laneJump) {
                    backYaw = laneHome(laneBackYaw());
                    if (laneJumpAngle() > 0.0F) {
                        aimAlongLane();
                    }
                }
                if (!this.resetting) {
                    int spread = this.snapbackDelayRandom.getValue();
                    this.backWait = this.snapbackDelay.getValue() + (spread > 0 ? this.turnRandomSource.nextInt(spread + 1) : 0);
                    this.backTicksLeft = this.snapbackTicks.getValue();
                }
                this.resetting = true;
                if (this.backWait > 0 && !jump) {
                    this.backWait--;
                    holdRotation(event);
                    return;
                }
                if (jump && !this.holdThisTick) {
                    float toYaw = backYaw;
                    if (laneJump) {
                        toYaw = TellyLane.shortOfHome(this.sentYaw, backYaw, 12.0F + this.turnRandomSource.nextFloat() * 8.0F);
                        toYaw = TellyLane.awayFromHalfTurn(this.sentYaw, toYaw, 150.0F + this.turnRandomSource.nextFloat() * 15.0F);
                        this.engine.reset();
                    }
                    float[] back = RotationEngine.quantize(this.sentYaw, this.sentPitch, toYaw, backPitch);
                    this.sentYaw = back[0];
                    this.sentPitch = back[1];
                } else if (this.snapbackStyle.getValue() == 1) {
                    float left = Math.abs(MathHelper.wrapAngleTo180_float(backYaw - this.sentYaw))
                            + Math.abs(backPitch - this.sentPitch);
                    int ticks = Math.max(1, this.backTicksLeft);
                    stepRotation(backYaw, backPitch, ticks <= 1 ? Math.max(left, 0.5F) + 1.0F : Math.max(0.5F, left / ticks));
                    this.backTicksLeft = ticks - 1;
                } else {
                    stepRotation(backYaw, backPitch, this.snapback.getValue());
                }
                applyRotation(event);
                if (Math.abs(MathHelper.wrapAngleTo180_float(this.sentYaw - backYaw)) < 0.5F
                        && Math.abs(this.sentPitch - backPitch) < 0.5F) {
                    this.resetting = false;
                    if (laneJump && !real) {
                        this.engine.reset();
                        return;
                    }
                    this.hasSent = false;

                    if (this.disableAfter.getValue() && this.attemptPlaced > 0) {
                        this.setEnabled(false);
                    }
                }
            }
            return;
        }
        this.active = true;
        this.resetting = false;
        BlinkModules blinker = Atlas.blinkManager == null ? BlinkModules.NONE : Atlas.blinkManager.getBlinkingModule();
        if (blinker != BlinkModules.NONE) {
            int held = Atlas.blinkManager.blinkedPackets.size();
            Atlas.blinkManager.setBlinkState(false, blinker);
            say(String.format("&e%s was holding %d packets &8(released so the clicks count)", blinker, held));
        }

        int blockSlot = pickBlockSlot();
        int ladderSlot = this.autoLadder.getValue() ? pickLadderSlot() : -1;
        if (!this.autoSwitch.getValue() || this.onDepletion.getValue() && !this.startedWithBlocks) {
            ItemStack held = mc.thePlayer.inventory.getCurrentItem();
            blockSlot = usable(held) ? mc.thePlayer.inventory.currentItem : -1;
            ladderSlot = ladderSlot >= 0 && isLadder(held) ? mc.thePlayer.inventory.currentItem : -1;
        }
        if (blockSlot < 0 && ladderSlot < 0) {
            problem("hotbar", this.autoSwitch.getValue() ? "no blocks in the hotbar"
                    : "not holding blocks (auto-switch is off)");
            holdRotation(event);
            return;
        }
        selectSlot(blockSlot >= 0 ? blockSlot : ladderSlot);
        startPlacing();

        if (this.path.isEmpty()) {
            return;
        }

        Vec3 eyes = eyesAt(this.pathStart);
        if (extra == null && this.steering && !intoVoid() && this.predictedFall <= this.safeDrop.getValue()) {
            trace(state() + String.format(" | steering lands in %dt, %.1f fall", this.landingTick, this.predictedFall)
                    + steerText());
            holdRotation(event);
            return;
        }
        if (extra == null && this.landingTick >= 0 && this.landingTick < this.path.size()
                && this.predictedFall <= this.safeDrop.getValue() && !this.laneNow
                && restsOn(this.path.get(this.landingTick), this.placed)) {
            trace(state() + String.format(" | caught: lands on a placed block in %dt, %.1f fall",
                    this.landingTick, this.predictedFall));
            holdRotation(event);
            return;
        }
        List<List<BlockPos>> levels = this.laneNow && blockSlot >= 0 ? laneLevels() : null;
        if (levels != null && levels.isEmpty()) {
            trace(state() + " | lane: built as far as this jump lands");
            laneStartHome(event);
            return;
        }
        if (levels != null && extra == null && laneWaitsForTop()) {
            trace(state() + " | lane: facing forward, " + this.laneLeft + " to place, layer in " + this.laneCrossing + "t");
            laneFaceForward(event);
            return;
        }
        if (levels == null) {
            this.laneNow = false;
            levels = blockSlot < 0 ? new ArrayList<List<BlockPos>>()
                    : extra != null ? Collections.singletonList(Collections.singletonList(extra)) : catchCells();
        }
        resetDiagnosis();
        String[] why = null;
        if (levels.isEmpty()) {
            why = new String[]{"cells", blockSlot >= 0 ? "no cell under the fall within " + PLAN_TICKS + " ticks"
                    : "no blocks in the hotbar"};
        } else {
            this.plan = planCatch(levels, eyes, 0);
            if (this.plan == null) {
                this.plan = planChain(levels, eyes, 0);
            }
        }
        if (this.plan == null && !levels.isEmpty()) {
            why = diagnose(eyes);

            for (int lead = 1; this.preAim.getValue() && this.plan == null
                    && lead <= PRE_AIM_TICKS && lead < this.path.size(); lead++) {
                Vec3 ahead = eyesAt(this.path.get(lead - 1));
                this.plan = planCatch(levels, ahead, lead);
                if (this.plan == null) {
                    this.plan = planChain(levels, ahead, lead);
                }
                if (this.plan != null) {
                    this.planLead = lead;
                }
            }
        }
        if (this.plan == null && this.laneNow && this.laneCrossing <= LANE_RESCUE_TICKS) {
            this.laneNow = false;
            levels = catchCells();
            if (!levels.isEmpty()) {
                this.plan = planCatch(levels, eyes, 0);
                if (this.plan == null) {
                    this.plan = planChain(levels, eyes, 0);
                }
            }
        }
        if (this.plan == null && !this.laneNow && ladderSlot >= 0 && this.ladderCell == null && extra == null) {
            this.plan = planLadder();
            if (this.plan != null) {
                selectSlot(ladderSlot);
            }
        }
        if (this.plan == null && extra != null) {
            this.extraDone = true;
        }
        if (this.plan == null) {
            trace(state() + " | " + levels(levels) + " | none: " + why[1]
                    + (ladderSlot >= 0 && this.ladderCell == null ? ", no ladder cell either" : "") + steerText());
            if (!this.laneNow) {
                problem(why[0], why[1]);
            }
            lookBack(event, eyes);
            return;
        }
        this.problemKey = null;
        this.problemRun = 0;

        boolean safe = this.safeMode.getValue();
        if (safe) {
            this.quietNotes = true;
            try {
                post();
            } finally {
                this.quietNotes = false;
            }
            if (this.plan == null) {
                lookBack(event, eyes);
                return;
            }
        }
        boolean clickedHeld = this.holdThisTick;

        boolean early = false;
        if (safe && !clickedHeld && this.planLead == 0 && !this.plan.ladder && this.earlyTurn.getValue() < 180
                && this.path.size() > 1 && RotationEngine.angle(this.sentYaw, this.sentPitch,
                this.plan.yaw, this.plan.pitch) > this.earlyTurn.getValue()) {
            Vec3 next = eyesAt(this.path.get(0));

            Plan later = planCatch(levels, next, 1);
            if (later == null && this.plan.catchTick < 0) {
                later = planChain(levels, next, 1);
            }
            if (later != null && later.cell.getY() == this.plan.cell.getY()) {
                this.plan = later;
                early = true;
            }
        }
        float step = stepFor(this.plan);
        turnTo(this.plan.yaw, this.plan.pitch, step, true);
        applyRotation(event);
        this.aimedCell = early ? this.plan.cell : null;
        this.aimedTick = this.tick;
        trace(String.format("%s | %s | %s %s on %s %s%s cost %.0f step %.0f | look %.1f/%.1f -> %.1f/%.1f%s%s%s",
                state(), levels(levels), this.plan.ladder ? "ladder" : this.plan.catchTick < 0 ? "chain" : "catch",
                cell(this.plan.cell),
                cell(this.plan.support), this.plan.side.getName(),
                this.plan.catchTick < 0 ? "" : " t" + this.plan.catchTick, this.plan.cost, step,
                this.sentYaw, this.sentPitch, this.plan.yaw, this.plan.pitch,
                this.planLead > 0 ? " | pre-aim from " + this.planLead + "t ahead (now: " + why[1] + ")" : "",
                this.pauseTicks > 0 ? " | paused " + this.pauseTicks + "t" : "", steerText())
                + (extra != null ? " | extra block under the feet" : "")
                + (clickedHeld ? " | clicked along the look already sent" : early ? " | early turn, click next tick" : ""));

        if (!clickedHeld && !early) {
            post();
        }
    }

    private void clearLane() {
        this.lane = null;
        this.laneNow = false;
        this.laneKeys = false;
    }

    private float laneJumpAngle() {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
        return scaffold == null || this.mode.getValue() == 1 || !scaffold.isWeirdTelly() ? 0.0F
                : scaffold.tellyJumpAngle.getValue();
    }

    private void updateLaneSide() {
        if (this.lane == null || !this.hasSent) {
            return;
        }
        float away = Math.abs(MathHelper.wrapAngleTo180_float(this.sentYaw - mc.thePlayer.rotationYaw));
        if (away > LANE_ROUND && !this.laneRound) {
            this.laneRound = true;
            this.laneSide = -this.laneSide;
        } else if (away < LANE_BACK) {
            this.laneRound = false;
        }
    }

    private float laneBackYaw() {
        float view = this.mode.getValue() == 1 ? this.viewYaw : mc.thePlayer.rotationYaw;
        float home = TellyLane.jumpYaw(view, this.laneSide, laneJumpAngle());
        return this.sentYaw + MathHelper.wrapAngleTo180_float(home - this.sentYaw);
    }

    private float laneHome(float backYaw) {
        return TellyLane.offGrid(backYaw, this.sentYaw, 0.3F + this.turnRandomSource.nextFloat() * 0.4F);
    }

    private boolean lateTurn() {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
        return scaffold != null && scaffold.tellyLateTurn.getValue();
    }

    private boolean laneWaitsForTop() {
        if (!lateTurn() || !MoveUtil.isForwardPressed() || !mc.gameSettings.keyBindJump.isKeyDown()
                || mc.thePlayer.motionY <= 0.0 || mc.thePlayer.hurtTime > 0
                || this.attemptRefused > 0 || this.pauseTicks > 0) {
            return false;
        }
        if (this.hasSent && Math.abs(MathHelper.wrapAngleTo180_float(laneBackYaw() - this.sentYaw)) > LANE_FORWARD_MAX) {
            return false;
        }
        return TellyLane.waits(this.laneCrossing, this.laneLeft);
    }

    private void laneFaceForward(UpdateEvent event) {
        if (!this.hasSent) {
            return;
        }
        float backYaw = laneHome(laneBackYaw());
        float backPitch = this.mode.getValue() == 1 ? this.viewPitch : mc.thePlayer.rotationPitch;
        stepRotation(backYaw, backPitch, Math.max(this.speed.getValue(),
                RotationEngine.angle(this.sentYaw, this.sentPitch, backYaw, backPitch)));
        applyRotation(event);
    }

    private void laneStartHome(UpdateEvent event) {
        boolean jumping = MoveUtil.isForwardPressed() && mc.gameSettings.keyBindJump.isKeyDown();
        if (!jumping || this.landingTick < 1 || this.landingTick > LANE_BACK_TICKS) {
            holdRotation(event);
            return;
        }
        float backYaw = laneHome(laneBackYaw());
        float backPitch = this.mode.getValue() == 1 ? this.viewPitch : mc.thePlayer.rotationPitch;
        float away = RotationEngine.angle(this.sentYaw, this.sentPitch, backYaw, backPitch);
        stepRotation(backYaw, backPitch, Math.max(this.speed.getValue(), away / (this.landingTick + 1)));
        applyRotation(event);
    }

    private void updateLane() {
        if (!scaffoldTelly()) {
            clearLane();
            return;
        }
        if (!mc.thePlayer.onGround) {
            return;
        }
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        int layer = MathHelper.floor_double(box.minY - 1.0E-3);
        double middleX = (box.minX + box.maxX) / 2.0;
        double middleZ = (box.minZ + box.maxZ) / 2.0;
        BlockPos stood = null;
        double nearest = Double.MAX_VALUE;
        int index = TellyLane.OFF;
        for (int x = MathHelper.floor_double(box.minX); x <= MathHelper.floor_double(box.maxX - 1.0E-7); x++) {
            for (int z = MathHelper.floor_double(box.minZ); z <= MathHelper.floor_double(box.maxZ - 1.0E-7); z++) {
                BlockPos cell = new BlockPos(x, layer, z);
                if (BlockUtil.isReplaceable(cell)) {
                    continue;
                }
                double distance = Math.hypot(x + 0.5 - middleX, z + 0.5 - middleZ);
                if (distance < nearest) {
                    nearest = distance;
                    stood = cell;
                }
                if (this.lane != null) {
                    index = Math.max(index, this.lane.indexOf(x, z));
                }
            }
        }
        if (stood == null) {
            return;
        }
        boolean keys = MoveUtil.isForwardPressed();
        float view = this.mode.getValue() == 1 && this.hasSent ? this.viewYaw : mc.thePlayer.rotationYaw;
        float moving = MoveUtil.adjustYaw(view, MoveUtil.getForwardValue(), MoveUtil.getLeftValue());
        boolean turned = this.lane != null && keys
                && Math.abs(MathHelper.wrapAngleTo180_float(moving - this.lane.yaw)) > LANE_KEEP;
        if (this.lane == null || index == TellyLane.OFF || turned) {
            this.lane = keys ? new TellyLane(stood.getX(), stood.getZ(), layer, moving) : null;
            return;
        }
        if (this.lane.diagonal()) {
            this.lane = new TellyLane(stood.getX(), stood.getZ(), layer, this.lane.yaw);
            return;
        }
        this.lane.anchor = index;
        this.lane.layer = layer;
    }

    private void aimAlongLane() {
        double[] point = this.lane.ahead(mc.thePlayer.posX, mc.thePlayer.posZ, LANE_AHEAD);
        this.steerX = point[0];
        this.steerZ = point[1];
        this.laneYaw = (float) Math.toDegrees(Math.atan2(point[1] - mc.thePlayer.posZ,
                point[0] - mc.thePlayer.posX)) - 90.0F;
        this.laneKeys = true;
        predict(true);
    }

    private BlockPos laneCell(int index) {
        int[] column = this.lane.cell(index);
        return new BlockPos(column[0], this.lane.layer, column[1]);
    }

    private int laneIndexAt(AxisAlignedBB box) {
        return this.lane.indexAt((box.minX + box.maxX) / 2.0, (box.minZ + box.maxZ) / 2.0);
    }

    private int crossingTick(double top) {
        AxisAlignedBB previous = this.pathStart;
        for (int t = 0; t < this.path.size(); t++) {
            AxisAlignedBB current = this.path.get(t);
            if (previous.minY >= top - 1.0E-3 && current.minY <= top) {
                return t;
            }
            previous = current;
        }
        return -1;
    }

    private List<List<BlockPos>> laneLevels() {
        double top = this.lane.layer + 1.0;
        if (this.landingTick >= 0 && this.landingTick < this.path.size()
                && this.path.get(this.landingTick).minY >= top - 1.0E-3) {
            BlockPos step = laneStep(top);
            return step == null ? new ArrayList<List<BlockPos>>() : one(step);
        }
        int crossing = crossingTick(top);
        if (crossing < 0) {
            return null;
        }
        this.laneCrossing = crossing;
        if (this.lane.diagonal()) {
            return stairLevels(this.path.get(crossing));
        }
        int stop = laneIndexAt(this.path.get(crossing));
        for (int i = this.lane.anchor; i <= stop; i++) {
            BlockPos cell = laneCell(i);
            if (BlockUtil.isReplaceable(cell)) {
                this.laneLeft = 0;
                for (int j = i; j <= stop; j++) {
                    this.laneLeft += BlockUtil.isReplaceable(laneCell(j)) ? 1 : 0;
                }
                return intersects(this.pathStart, cell) || opponentIn(cell) ? null : one(cell);
            }
        }
        return null;
    }

    private List<List<BlockPos>> stairLevels(AxisAlignedBB landing) {
        int landX = MathHelper.floor_double((landing.minX + landing.maxX) / 2.0);
        int landZ = MathHelper.floor_double((landing.minZ + landing.maxZ) / 2.0);
        int[] head = this.lane.cell(0);
        for (int guard = 0; guard < LANE_STAIR_CELLS; guard++) {
            int[] built = null;
            for (int[] step : this.lane.stepsToward(head[0], head[1], landX, landZ)) {
                if (!BlockUtil.isReplaceable(new BlockPos(step[0], this.lane.layer, step[1]))) {
                    built = step;
                    break;
                }
            }
            if (built == null) {
                break;
            }
            head = built;
        }
        List<int[]> steps = this.lane.stepsToward(head[0], head[1], landX, landZ);
        if (steps.isEmpty()) {
            return new ArrayList<List<BlockPos>>();
        }
        this.laneLeft = this.lane.stepsLeft(head[0], head[1], landX, landZ);
        List<BlockPos> cells = new ArrayList<BlockPos>(2);
        for (int[] step : steps) {
            BlockPos cell = new BlockPos(step[0], this.lane.layer, step[1]);
            if (!intersects(this.pathStart, cell) && !opponentIn(cell)) {
                cells.add(cell);
            }
        }
        return cells.isEmpty() ? null : Collections.singletonList(cells);
    }

    private BlockPos laneStep(double top) {
        if (keepYTop() <= this.lane.layer) {
            return null;
        }
        int crossing = crossingTick(top + 1.0);
        if (crossing < 0) {
            return null;
        }
        AxisAlignedBB land = this.path.get(crossing);
        BlockPos cell = (this.lane.diagonal()
                ? new BlockPos(MathHelper.floor_double((land.minX + land.maxX) / 2.0), this.lane.layer,
                        MathHelper.floor_double((land.minZ + land.maxZ) / 2.0))
                : laneCell(laneIndexAt(land))).up();
        if (!BlockUtil.isReplaceable(cell) || BlockUtil.isReplaceable(cell.down()) || intersects(this.pathStart, cell)
                || opponentIn(cell) || catchTick(this.path, this.pathStart, cell, PLAN_TICKS) < 0) {
            return null;
        }
        this.laneCrossing = crossing;
        this.laneLeft = 1;
        return cell;
    }

    private static List<List<BlockPos>> one(BlockPos cell) {
        return Collections.singletonList(Collections.singletonList(cell));
    }

    private String steerText() {
        return this.steering ? String.format(" | steer %.0f", MathHelper.wrapAngleTo180_float(this.steerYaw)) : "";
    }

    private void lookBack(UpdateEvent event, Vec3 eyes) {
        if (!this.preAim.getValue() || this.lastGround == null) {
            holdRotation(event);
            return;
        }
        double dx = MathHelper.floor_double(this.lastGround.xCoord) + 0.5 - eyes.xCoord;
        double dy = MathHelper.floor_double(this.lastGround.yCoord - 0.5) + 0.5 - eyes.yCoord;
        double dz = MathHelper.floor_double(this.lastGround.zCoord) + 0.5 - eyes.zCoord;
        if (dx * dx + dz * dz > ANCHOR_RANGE * ANCHOR_RANGE) {
            holdRotation(event);
            return;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        yaw = this.sentYaw + MathHelper.wrapAngleTo180_float(yaw - this.sentYaw);
        turnTo(yaw, pitch, Math.max(this.speed.getValue(), this.maxSpeed.getValue() / 2.0F), false);
        applyRotation(event);
    }

    private String levels(List<List<BlockPos>> levels) {
        StringBuilder text = new StringBuilder();
        for (List<BlockPos> cells : levels) {
            int first = PLAN_TICKS;
            for (BlockPos cell : cells) {
                int t = catchTick(this.path, this.pathStart, cell, PLAN_TICKS);
                if (t >= 0) {
                    first = Math.min(first, t);
                }
            }
            text.append(text.length() == 0 ? "" : " ")
                    .append('L').append(cells.get(0).getY()).append('x').append(cells.size()).append('@').append(first);
        }
        return text.toString();
    }

    private void holdRotation(UpdateEvent event) {
        if (this.hasSent) {
            applyRotation(event);
        }
    }

    private void post() {
        if (!this.active || this.plan == null || this.cooldown > 0) {
            return;
        }
        if (this.attemptRefused >= MAX_REFUSALS) {
            note("no click: " + this.attemptRefused + " blocks refused this fall");
            return;
        }
        if (this.pauseTicks > 0) {
            note("no click: waiting out a correction or refusal");
            return;
        }
        if (this.tick - this.lastHurtTick < this.hitSettle.getValue()) {
            note("no click: letting the knockback land (" + (this.tick - this.lastHurtTick) + "t after the hit)");
            return;
        }
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (this.plan.ladder ? !isLadder(held) : !usable(held)) {
            note("no click: not holding " + (this.plan.ladder ? "a ladder" : "blocks"));
            return;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        MovingObjectPosition hit = ray(eyes, this.sentYaw, this.sentPitch);
        if (hit == null || hit.typeOfHit != MovingObjectType.BLOCK) {
            note("turning: the look reaches no block yet");
            return;
        }
        if (!clickable(hit.getBlockPos())) {
            note("turning: the look is on " + cell(hit.getBlockPos()) + ", which cannot be built on");
            return;
        }
        BlockPos cell = hit.getBlockPos().offset(hit.sideHit);
        if (faceFailed(hit.getBlockPos(), hit.sideHit)) {
            note("no click: " + cell(hit.getBlockPos()) + " " + hit.sideHit.getName() + " was just refused by the client");
            return;
        }
        if (this.plan.ladder) {
            postLadder(held, hit, cell);
            return;
        }
        if (!BlockUtil.isReplaceable(cell) || intersects(mc.thePlayer.getEntityBoundingBox(), cell)) {
            note("turning: the look would place at " + cell(cell) + ", which is taken or inside the player");
            return;
        }

        if (!Double.isNaN(this.sentPosX) && intersects(serverBox(), cell)) {
            note("no click: " + cell(cell) + " is inside the player where the server has them");
            return;
        }
        if (opponentIn(cell)) {
            note("no click: an opponent may be standing in " + cell(cell) + " on the server");
            return;
        }
        boolean wanted = cell.equals(this.plan.cell)
                || this.tick - this.aimedTick == 1 && cell.equals(this.aimedCell);
        if (!wanted && !this.laneNow) {
            predict(this.steering);
            wanted = cell.getY() <= keepYTop() && catchTick(this.path, this.pathStart, cell, PLAN_TICKS) >= 0;
        }
        if (!wanted) {
            note("turning: the look would place at " + cell(cell) + ", not under the fall");
            return;
        }
        if (!((ItemBlock) held.getItem()).canPlaceBlockOnSide(mc.theWorld, hit.getBlockPos(),
                hit.sideHit, mc.thePlayer, held)) {
            note("no click: the game would not place at " + cell(cell));
            return;
        }

        boolean urgent = this.plan.catchTick >= 0 && this.plan.catchTick <= 1;
        if (!urgent && ghostDepth(hit.getBlockPos()) >= 2) {
            note("no click: " + cell(hit.getBlockPos()) + " stands on another block of ours the server has not confirmed");
            return;
        }

        this.holdThisTick = true;
        this.placedTurn = this.lastTurn;
        this.pendingClick = new PendingClick(held, hit.getBlockPos(), hit.sideHit, hit.hitVec, cell, urgent, this.tick);
    }

    private int ghostDepth(BlockPos support) {
        int depth = 0;
        BlockPos at = support;
        while (at != null && depth < 3 && this.placedAt.containsKey(at) && !this.confirmed.contains(at)) {
            depth++;
            at = this.supportOf.get(at);
        }
        return depth;
    }

    private AxisAlignedBB serverBox() {
        double half = mc.thePlayer.width / 2.0;
        return new AxisAlignedBB(this.sentPosX - half, this.sentPosY, this.sentPosZ - half,
                this.sentPosX + half, this.sentPosY + mc.thePlayer.height, this.sentPosZ + half).contract(0.001, 0.001, 0.001);
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdateClick(UpdateEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        PendingClick click = this.pendingClick;
        this.pendingClick = null;
        if (click == null || !this.isEnabled() || mc.thePlayer == null || click.tick != this.tick) {
            return;
        }
        float[] look = atlas.management.RotationHelper.packetLook(event);
        MovingObjectPosition hit = ray(mc.thePlayer.getPositionEyes(1.0F), look[0], look[1]);
        boolean same = hit != null && hit.typeOfHit == MovingObjectType.BLOCK
                && hit.getBlockPos().equals(click.support) && hit.sideHit == click.side;
        if (!same && !click.urgent) {
            note("re-aim: the look this packet carries is not on " + cell(click.support) + " " + click.side.getName());
            return;
        }
        this.click(click.held, click.support, click.side, same ? hit.hitVec : click.hitVec, click.cell);
    }

    private void click(ItemStack held, BlockPos support, EnumFacing side, Vec3 hitVec, BlockPos cell) {
        MovingObjectPosition hit = new MovingObjectPosition(hitVec, side, support);
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
            this.supportOf.put(cell, support);
            this.lastNote = null;
            this.attemptPlaced++;
            this.placed.add(cell);
            this.placedAt.put(cell, this.tick);
            if (this.swing.getValue()) {
                mc.thePlayer.swingItem();
            }
            if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                this.cooldown = this.placeInterval.getValue();
            }
            boolean extra = cell.equals(this.extraTarget);
            if (extra) {
                this.extraDone = true;
            }
            drawAimSpot();
            say("&aplaced &7" + cell(cell) + " &8on " + cell(hit.getBlockPos()) + " " + hit.sideHit.getName()
                    + (extra ? " (extra, under the feet)"
                    : this.plan == null ? "" : this.plan.catchTick < 0 ? " (chain)" : " (catch in " + this.plan.catchTick + "t)"));
        } else {
            failFace(hit.getBlockPos(), hit.sideHit);
            note("no click: the client refused the placement at " + cell(cell));
        }
    }

    private static String faceKey(BlockPos support, EnumFacing side) {
        return support.getX() + "," + support.getY() + "," + support.getZ() + "," + side.ordinal();
    }

    private void failFace(BlockPos support, EnumFacing side) {
        this.failedFaces.put(faceKey(support, side), this.tick + FAILED_FACE_TICKS);
        this.plan = null;
    }

    private boolean faceFailed(BlockPos support, EnumFacing side) {
        Integer until = this.failedFaces.get(faceKey(support, side));
        if (until == null) {
            return false;
        }
        if (this.tick >= until) {
            this.failedFaces.remove(faceKey(support, side));
            return false;
        }
        return true;
    }

    private void postLadder(ItemStack held, MovingObjectPosition hit, BlockPos cell) {
        if (!hit.sideHit.getAxis().isHorizontal() || !BlockUtil.isReplaceable(cell)
                || !mc.theWorld.getBlockState(hit.getBlockPos()).getBlock().isNormalCube()) {
            note("turning: the look is not on the side of a solid block");
            return;
        }
        if (intersects(mc.thePlayer.getEntityBoundingBox(), cell)) {
            note("no click: the body is already in " + cell(cell));
            return;
        }
        boolean wanted = cell.equals(this.plan.cell);
        if (!wanted) {
            predict(this.steering);
            for (int t = 1; t < Math.min(LADDER_TICKS, this.path.size()) && !wanted; t++) {
                wanted = ladderCellOf(this.path.get(t)).equals(cell);
            }
        }
        if (!wanted) {
            note("turning: a ladder at " + cell(cell) + " would not be in the fall's way");
            return;
        }
        if (!((ItemBlock) held.getItem()).canPlaceBlockOnSide(mc.theWorld, hit.getBlockPos(),
                hit.sideHit, mc.thePlayer, held)) {
            note("no click: the game would not hang a ladder at " + cell(cell));
            return;
        }
        this.holdThisTick = true;
        this.placedTurn = this.lastTurn;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                hit.getBlockPos(), hit.sideHit, hit.hitVec)) {
            this.lastNote = null;
            this.attemptPlaced++;
            this.placedAt.put(cell, this.tick);
            this.ladderCell = cell;
            this.ladderSide = hit.sideHit;
            this.ladderTick = this.tick;
            if (this.swing.getValue()) {
                mc.thePlayer.swingItem();
            }
            if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                this.cooldown = this.placeInterval.getValue();
            }
            say("&aladder &7" + cell(cell) + " &8on " + cell(hit.getBlockPos()) + " " + hit.sideHit.getName());
        } else {
            failFace(hit.getBlockPos(), hit.sideHit);
            note("no click: the client refused the ladder at " + cell(cell));
        }
    }

    private boolean scaffolding() {
        Module scaffold = Atlas.moduleManager.getModule(Scaffold.class);
        return scaffold != null && scaffold.isEnabled() && !scaffoldTelly();
    }

    private boolean scaffoldTellyKeepY() {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
        return scaffold != null && scaffold.isSpeedTellyKeepY();
    }

    private boolean scaffoldTelly() {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
        return scaffold != null && scaffold.isSpeedTelly();
    }

    private void problem(String key, String detail) {
        if (!this.attempt) {
            return;
        }
        this.attemptProblem = detail;
        this.problemRun = key.equals(this.problemKey) ? this.problemRun + 1 : 1;
        this.problemKey = key;
        if (this.problemRun == PROBLEM_TICKS && !key.equals(this.saidProblem)) {
            this.saidProblem = key;
            say("&e" + detail);
        }
    }

    private void updateAttempt(boolean want) {
        if (want && !this.attempt) {
            this.attempt = true;
            this.attempts++;
            this.attemptPlaced = 0;
            this.attemptRefused = 0;
            this.attemptTick = 0;
            this.attemptSteered = false;
            this.ladderCell = null;
            this.rodeLadder = false;
            this.startedWithBlocks = usable(mc.thePlayer.inventory.getCurrentItem());
            if (!this.hasSent) {
                this.viewYaw = mc.thePlayer.rotationYaw;
                this.viewPitch = mc.thePlayer.rotationPitch;
            }
            this.attemptProblem = null;
            this.problemKey = null;
            this.problemRun = 0;
            this.saidProblem = null;
            this.lastNote = null;
            this.attemptStartY = mc.thePlayer.posY;
            this.lastPosition = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            this.placed.clear();
            this.extraTarget = null;
            this.extraDone = false;
            drawAimSpot();
            long sinceHit = System.currentTimeMillis() - this.lastHurtAt;
            int sinceSwing = this.tick - this.lastAttackTick;
            trace(String.format("ATTEMPT %d | %s | %s | %s | ping %d | reach %.1f interval %d | %s | stood %s",
                    this.attempts, intoVoid() ? "void" : String.format("drop %.1f", this.predictedFall),
                    sinceHit < this.combatWindow.getValue() ? "hit " + sinceHit + "ms ago" : "no hit",
                    sinceSwing <= 20 ? "swung " + sinceSwing + "t ago" + (this.lastAttackSprinting ? " sprinting" : "")
                            : "no swing",
                    ping(), this.reach.getValue(), this.placeInterval.getValue(), state(),
                    this.lastGround == null ? "unknown" : String.format("%.2f %.2f %.2f (%.1f away)",
                            this.lastGround.xCoord, this.lastGround.yCoord, this.lastGround.zCoord,
                            Math.sqrt(Math.pow(this.lastGround.xCoord - mc.thePlayer.posX, 2)
                                    + Math.pow(this.lastGround.zCoord - mc.thePlayer.posZ, 2)))));
            say("&7catching " + (intoVoid() ? "a fall into the void"
                    : String.format("a %.1f block drop", this.predictedFall)));
            return;
        }
        if (!this.attempt) {
            return;
        }
        this.attemptTick++;

        Vec3 now = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        boolean moved = this.lastPosition != null && now.squareDistanceTo(this.lastPosition) > 64.0;
        this.lastPosition = now;
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F || mc.thePlayer.posY < -30.0 || moved
                || Math.abs(mc.thePlayer.posY - this.attemptStartY) > 80.0) {
            this.attempt = false;
            this.ladderCell = null;
            say("&clost &7after " + this.attemptPlaced + " block" + (this.attemptPlaced == 1 ? "" : "s")
                    + (this.attemptRefused > 0 ? ", " + this.attemptRefused + " refused" : "")
                    + (this.attemptProblem != null ? " &8(" + this.attemptProblem + ")" : "")
                    + (moved ? " &8[moved by the server]" : ""));
            return;
        }
        if (mc.thePlayer.onGround) {
            this.attempt = false;
            BlockPos under = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 0.5, mc.thePlayer.posZ);
            boolean ours = false;
            AxisAlignedBB feet = mc.thePlayer.getEntityBoundingBox().offset(0.0, -0.5, 0.0);
            for (BlockPos pos : this.placed) {
                if (pos.equals(under) || intersects(feet, pos)) {
                    ours = true;
                    break;
                }
            }
            boolean ladder = !ours && this.rodeLadder;
            this.ladderCell = null;
            if (ladder) {
                this.saves++;
                this.settleTicks = SETTLE_TICKS;
                say(String.format("&asaved &7by a ladder, climbed out %.1f below the start",
                        this.attemptStartY - mc.thePlayer.posY));
            } else if (ours) {
                this.saves++;
                this.savedOn.clear();
                this.savedOn.addAll(this.placed);
                this.savedAt = this.tick;
                if (this.attemptSteered) {
                    this.settleTicks = SETTLE_TICKS;
                }
                say(String.format("&asaved &7with %d block%s, %.1f below the start%s%s", this.attemptPlaced,
                        this.attemptPlaced == 1 ? "" : "s", this.attemptStartY - mc.thePlayer.posY,
                        this.attemptRefused > 0 ? ", " + this.attemptRefused + " refused" : "",
                        this.attemptSteered ? " &8(steered)" : ""));
            } else {
                say("&7landed on ground that was already there" + (this.attemptSteered ? " &8(steered back)" : ""));

                this.attempts--;
            }
        }
    }

    @Override
    public String[] getSuffix() {
        String score = this.saves + "/" + this.attempts;
        if (this.trigger.getValue() != 0 && this.holdKey.getValue() != Keyboard.KEY_NONE) {
            return new String[]{this.mode.getModeString(), this.holdKey.getKeyName(), score};
        }
        return new String[]{this.mode.getModeString(), score};
    }
}
