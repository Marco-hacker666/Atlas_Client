package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.*;
import atlas.management.RotationHelper;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.*;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import net.minecraft.world.WorldSettings.GameType;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Scaffold extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int ROTATION_GODBRIDGE = 4;
    private static final int ROTATION_SNAP = 7;
    private static final int ROTATION_THREE_FMC = 8;
    private static final int ROTATION_SNAP2 = 9;
    private static final int ROTATION_POLAR = 10;
    private static final int ROTATION_SPEED_TELLY = 12;
    private static final int ROTATION_WEIRD_TELLY = 13;
    /* polar.json's GodBridge technique (LiquidBounce): the Mode index is also
       the ledge action the decision returns ({@link #polarLedgeAction}).
       Modes = [Jump], ForceSneakBelowCount = 5, SneakTime = 1..1. */
    private static final int POLAR_JUMP = 0;
    private static final int POLAR_SNEAK = 1;
    private static final int POLAR_STOP_INPUT = 2;
    private static final int POLAR_BACKWARDS = 3;
    private static final int LEDGE_NONE = -1;
    private static final double[] placeOffsets = new double[]{
            0.03125,
            0.09375,
            0.15625,
            0.21875,
            0.28125,
            0.34375,
            0.40625,
            0.46875,
            0.53125,
            0.59375,
            0.65625,
            0.71875,
            0.78125,
            0.84375,
            0.90625,
            0.96875
    };
    /* S3 (2026-10-07): aim points stay off the outer 15 % of a face, as
       LiquidBounce's trimFace does. */
    private static final double[] aimOffsets = new double[]{
            0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875, 0.53125,
            0.59375, 0.65625, 0.71875, 0.78125, 0.84375
    };
    private int rotationTick = 0;
    private int lastSlot = -1;
    private int blockCount = -1;
    private boolean turnedClutchOn;
    private boolean holdingJump;
    private float yaw = -180.0F;
    private float pitch = 0.0F;
    private boolean canRotate = false;
    private int towerTick = 0;
    private int towerDelay = 0;
    private int stage = 0;
    private int startY = 256;
    private boolean shouldKeepY = false;
    private boolean towering = false;
    private EnumFacing targetFacing = null;
    private int safeStuckTicks = 0;
    private int safeStuckDelayTicks = 0;
    private double safePrevMotionY = 0.0;
    private double savedMotionX;
    private double savedMotionY;
    private double savedMotionZ;
    private boolean safeStuckActive = false;
    private boolean snapRotating = false;
    private boolean placedThisTick = false;
    private int threeFmcAirTicks = 0;
    private int threeFmcGroundTicks = 0;
    private int threeFmcPlaceCooldown = 0;
    private float lastSnapPlaceYaw = Float.NaN;
    private float lastSnapPlacePitch = Float.NaN;
    public final ModeProperty rotationMode = new ModeProperty("rotations", 2, new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS", "GODBRIDGE", "SMOOTH", "Hypixel", "SNAP", "3FMC", "SNAP2", "POLAR", "TELLY", "SPEED-TELLY", "WEIRD-TELLY"})
            /* 2026-10-31 (owner's consolidation): SIDEWAYS and 3FMC retired (indices kept); a config naming one gets
               GODBRIDGE. SNAP and SNAP2 came back 2026-11-01 (owner: "put Scaffold snap back"), SNAP2 hidden as before. */
            .retire("GODBRIDGE", "SIDEWAYS", "3FMC")
            /* 2026-11-04 (owner: "delete Telly, keep Speed Telly"): index 11 stays taken; a config naming it, or the
               name it had before 2026-10-13, gets SPEED-TELLY. 2026-11-06 (owner: "bring speed telly back, call the
               current one weird telly"): WEIRD-TELLY goes on the end; both run on Clutch, only the jump differs. */
            .retire("SPEED-TELLY", "TELLY")
            .alias("B4", "SPEED-TELLY")
            /* 2026-10-08: fewer modes in the menu; the rest still load from old configs. */
            .hide("BACKWARDS", "SIDEWAYS", "SMOOTH", "Hypixel", "3FMC", "SNAP2")
            .alias("GODBIRGDE", "GODBRIDGE");
    public final FloatProperty tellystartrotationminspeed = new FloatProperty("telly-start-rotation-min-speed", 90.0F, 1.0F, 180.0F, () -> this.keepY.getValue() == 3 || this.keepY.getValue() == 4);
    public final FloatProperty tellystartrotationmaxspeed = new FloatProperty("telly-start-rotation-max-speed", 95.0F, 1.0F, 180.0F, () -> this.keepY.getValue() == 3 || this.keepY.getValue() == 4);
    public final FloatProperty tellynormalrotationminspeed = new FloatProperty("telly-normal-rotation-min-speed", 30.0F, 1.0F, 180.0F, () -> this.keepY.getValue() == 3 || this.keepY.getValue() == 4);
    public final FloatProperty tellynormalrotationmaxspeed = new FloatProperty("telly-normal-rotation-max-speed", 35.0F, 1.0F, 180.0F, () -> this.keepY.getValue() == 3 || this.keepY.getValue() == 4);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "REAL"});
    public final ModeProperty sprintMode = new ModeProperty("sprint", 0, new String[]{"NONE", "VANILLA"});
    public final PercentProperty groundMotion = new PercentProperty("ground-motion", 100);
    public final PercentProperty airMotion = new PercentProperty("air-motion", 100);
    public final PercentProperty speedMotion = new PercentProperty("speed-motion", 100);
    public final ModeProperty tower = new ModeProperty("tower", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY"});
    public final BooleanProperty hypixeltower = new BooleanProperty("hypixeltower", false, () -> this.tower.getValue() == 3);
    public final BooleanProperty safe = new BooleanProperty("safe", false, () -> this.tower.getValue() == 3);
    public final IntProperty safeStuckDelayTicksProperty = new IntProperty("safe-delay-ticks", 1, 1, 3, () -> this.tower.getValue() == 3 && this.safe.getValue());
    public final ModeProperty keepY = new ModeProperty("keep-y", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY", "EXTRATELLY"});
    public final BooleanProperty keepYonPress = new BooleanProperty("keep-y-on-press", false, () -> this.keepY.getValue() != 0);
    public final BooleanProperty disableWhileJumpActive = new BooleanProperty("no-keep-y-on-jump-potion", false, () -> this.keepY.getValue() != 0);
    /* Off by default (2026-10-04): up to four blocks in one flying window is
       exactly what Grim's MultiPlace flags. */
    public final BooleanProperty multiplace = new BooleanProperty("multi-place", false);
    public final BooleanProperty safeWalk = new BooleanProperty("safe-walk", true);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", false);
    public final BooleanProperty blockCounter = new BooleanProperty("block-counter", true);
    public final BooleanProperty eagle = new BooleanProperty("eagle", false);
    public final FloatProperty edgeDistance = new FloatProperty("edge-distance", 0.13F, 0.0F, 0.5F, () -> this.eagle.getValue());
    public final IntProperty sneakDelay = new IntProperty("sneak-delay", 80, 0, 500, () -> this.eagle.getValue());
    public final IntProperty blocksPerSneak = new IntProperty("blocks-per-sneak", 1, 1, 5, () -> this.eagle.getValue());
    private boolean eagleSneaking = false;
    private int eagleSneakTicks = 0;
    private long eagleLastSneakTime = 0L;
    private int eagleBlocksPlaced = 0;

    /* ---- Polar: the full GodBridge technique (LiquidBounce polar.json) -----
       Rotations alone are not a Godbridge; the part that keeps the player on
       the block edge is the ledge action. When the next step has no floor and
       the block cannot be placed, LiquidBounce's ScaffoldGodBridgeTechnique
       picks an action (jump / sneak / stop input / step back) so the player
       does not fall off. polar.json runs it with:
         Modes = [Jump], ForceSneakBelowCount = 5, SneakTime = 1..1
       i.e. normally JUMP, but SNEAK when fewer than 5 blocks remain. */
    private final IntProperty polarForceSneakBelowCount = new IntProperty("polar-force-sneak-below-count", 5, 0, 20, this::polarShown);
    private final IntProperty polarSneakTime = new IntProperty("polar-sneak-time", 1, 1, 10, this::polarShown);
    /* polar.json's Modes = [Jump]. A single choice, as the client's other mode
       settings are; the ledge decision reads it, so Sneak, StopInput and
       Backwards can be picked without a rebuild. */
    private final ModeProperty polarModes = new ModeProperty("polar-modes", POLAR_JUMP,
            new String[]{"Jump", "Sneak", "StopInput", "Backwards"},
            this::polarShown);
    /* 2026-11-07 (owner: "stack seven, then jump, as LiquidBounce"): a jump every so many blocks. LiquidBounce has no
       such count: it jumps at a ledge it cannot place at, which reads as a rhythm. 0 leaves only the ledge action. */
    private final IntProperty jumpEveryBlocks = new IntProperty("jump-every-blocks", 7, 0, 20, this::godBridgeShown);
    /* 2026-11-07: placement timing a human bridge has (PlacePace): a crouch of 3-5 ticks at an edge every 3-4.5 s,
       no 1-tick crouches, no click or jump that would put the Speed or Sneak averages of Intave PlacementAnalysis under
       their limits. Off: GODBRIDGE and POLAR exactly as before. */
    public final BooleanProperty legitPace = new BooleanProperty("legit-pace", true, this::godBridgeShown);
    /* 2026-11-11 (owner: "god bridge jumps every 6-8 blocks; it jumped, but the blocks did not go up with it"): a
       GODBRIDGE jump takes the bridge up a block, as the technique does. Off: the 2026-11-07 behaviour, the layer
       the jump left from is kept. */
    public final BooleanProperty jumpBuildsUp = new BooleanProperty("jump-builds-up", true,
            () -> this.rotationMode.getValue() == ROTATION_GODBRIDGE);
    private final PlacePace pace = new PlacePace();
    private boolean paceWasOnGround;
    private long nextSneakEvery = 3500L;
    private final java.util.Random paceRandom = new java.util.Random();
    private int blocksSinceJump = 0;
    private int polarLedgeSneakTicks = 0;
    private boolean polarLedgeStopInput = false;
    private boolean polarLedgeStepBack = false;
    private boolean polarLedgeJump = false;
    public final BooleanProperty espOutline = new BooleanProperty("outline-esp", false);
    public final ModeProperty espColor = new ModeProperty("outline-color", 0, new String[]{"Default", "HUD"}, () -> this.espOutline.getValue());
    private final Map<BlockPos, Long> espHighlight = new HashMap<>();

    /* ---- Clutch's discipline, applied to Scaffold (2026-10-06) ----------
       Players reported flags, ghost blocks, setbacks and refused blocks.
       Clutch already does what fixes those (see its header):
       - the click is the one the sent look makes, decided after every module
         has set this tick's rotation (Priority.LOWEST), and clicks whatever
         that look actually hits, if the block goes where it is wanted;
       - no C08 for a placement the game itself would refuse (a C08 goes out
         whatever onPlayerRightClick answers, and the server may then place a
         block the client does not have: a ghost);
       - a setback or a refused block pauses clicking for a round trip;
       - the look turns at a limited, humanised speed (LiquidBounce's
         RotationsValueGroup does the same) instead of snapping, and never
         repeats the yaw step of the last placement (Grim DuplicateRotPlace). */
    private static final int[] TURNING_MODES = {1, 2, 3, 4, 5, 6};
    public final IntProperty turnSpeed = new IntProperty("turn-speed", 80, 10, 180, this::turnLimited);
    /* Hidden since 2026-10-31: the speed wander and bow are the shared engine's, set in the Rotations module
       for every module on it. Kept so saved configs still load. */
    public final BooleanProperty humanize = new BooleanProperty("humanize", true, () -> false);
    private final atlas.humanize.Humanizer human = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_SCAFFOLD);
    /* 2026-10-17 (a player: "your scaffold rotations need randomization"):
       SNAP and the fixed-target modes aimed at the same yaw and pitch for every
       placement (exactly back, pitch 85). The aim now wanders within
       rotation-random degrees (pitch half that), smoothly, toward a new random
       goal every few ticks -- and only where the look still puts the block
       where it is wanted (lookHit); otherwise the exact aim is used. */
    public final FloatProperty rotationRandom = new FloatProperty("rotation-random", 2.5F, 0.0F, 8.0F,
            () -> this.rotationMode.getValue() != 0);
    private float wanderYaw;
    private float wanderPitch;
    private float wanderGoalYaw;
    private float wanderGoalPitch;
    private int wanderTicks;
    public final BooleanProperty pauseOnCorrection = new BooleanProperty("pause-on-correction", true);
    /* SPEED-TELLY (2026-11-04): walking forward jumps by itself. */
    public final BooleanProperty tellyAutoJump = new BooleanProperty("telly-auto-jump", true, this::isSpeedTellyMode);
    /* 2026-11-06: sprint up the jump and turn near the top, when there is time for it. Off: turn from the first tick. */
    public final BooleanProperty tellyLateTurn = new BooleanProperty("telly-late-turn", true, this::isSpeedTellyMode);
    /* 2026-11-06, WEIRD-TELLY: degrees off the view a jump goes from, sides in turn. 45 drifted about 1.2 blocks a jump. */
    public final IntProperty tellyJumpAngle = new IntProperty("telly-jump-offset", 8, 0, 30, this::isWeirdTellyMode);
    private int tick = 0;
    private int pauseTicks = 0;
    private volatile boolean correctionPending = false;
    private final Map<BlockPos, Integer> placedAt = new ConcurrentHashMap<BlockPos, Integer>();
    private final ConcurrentLinkedQueue<BlockPos> refused = new ConcurrentLinkedQueue<BlockPos>();
    private final Map<String, Integer> failedFaces = new HashMap<String, Integer>();
    private int refusalsAt = -1000;
    private int recentRefusals = 0;
    /* This tick's plan, made at HIGH, clicked at LOWEST. */
    private boolean planReady = false;
    private BlockData planData = null;
    private Vec3 planHitVec = null;
    private boolean planSnapCanPlace = true;
    private boolean planSnapMode = false;
    private boolean planThreeFmc = false;

    private boolean shouldStopSprint() {
        if (this.isThreeFmcMode() && !this.isThreeFmcTellyMode()) {
            return true;
        }
        if (this.isTowering()) {
            return false;
        } else {
            boolean stage = this.keepY.getValue() == 1 || this.keepY.getValue() == 2 || this.keepY.getValue() == 4;
            return (!stage || this.stage <= 0) && this.sprintMode.getValue() == 0;
        }
    }

    /**
     * The aim with this placement's wander added: a smooth drift toward a goal
     * drawn every 4-9 ticks within rotation-random (yaw) and half of it (pitch).
     */
    private float[] wander(float yaw, float pitch) {
        float range = this.rotationRandom.getValue();
        if (--this.wanderTicks <= 0) {
            this.wanderTicks = 4 + (int) (Math.random() * 6);
            this.wanderGoalYaw = (float) ((Math.random() * 2.0 - 1.0) * range);
            this.wanderGoalPitch = (float) ((Math.random() * 2.0 - 1.0) * range * 0.5);
        }
        float pull = 0.25F + (float) Math.random() * 0.2F;
        this.wanderYaw += (this.wanderGoalYaw - this.wanderYaw) * pull;
        this.wanderPitch += (this.wanderGoalPitch - this.wanderPitch) * pull;
        return new float[]{yaw + this.wanderYaw, MathHelper.clamp_float(pitch + this.wanderPitch, -90.0F, 90.0F)};
    }

    /*
     * 2026-10-31: the turn is the shared engine's (RotationHelper). This module works out the look each mode
     * wants and asks for it; the engine turns there as Clutch does (speeding up from rest; speed wander and bow
     * with the Rotations module on), on the mouse grid from the look the server has, with Grim's DuplicateRotPlace
     * guard, turns the keys for SILENT, and hands the look back once this stops asking (switched off, or a tick it
     * does not turn). That replaces this module's humanTurn (a 70-degree cap and a three-tick ease-in, earlier the
     * same day; the engine's acceleration is the ease-in now), its own grid and guard, and handBack on switching
     * off. In the turning modes turn-speed is the engine's cap, on each axis.
     */

    /** move-fix as the engine's movement yaw: NONE the camera's, SILENT the look's with the keys turned, REAL the look's. */
    private int moveMode() {
        switch (this.moveFix.getValue()) {
            case 0:
                return RotationHelper.MOVE_CAMERA;
            case 1:
                return RotationHelper.MOVE_FIX;
            default:
                return RotationHelper.MOVE_LOOK;
        }
    }

    private boolean turnLimited() {
        int mode = this.rotationMode.getValue();
        for (int m : TURNING_MODES) {
            if (m == mode) {
                return true;
            }
        }
        return false;
    }

    /** A round trip in ticks: how long anything the server says takes to be answered. */
    private int roundTripTicks() {
        int ping = Ping.own();
        return ping <= 0 ? 4 : MathHelper.clamp_int((ping + 49) / 50, 2, 8);
    }

    private static String faceKey(BlockPos support, EnumFacing side) {
        return support.getX() + "," + support.getY() + "," + support.getZ() + "," + side.ordinal();
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

    /** Blocks the server took back: pause for a round trip, longer if it keeps happening. */
    private void drainRefusals() {
        BlockPos pos;
        while ((pos = this.refused.poll()) != null) {
            if (this.placedAt.remove(pos) == null) {
                continue;
            }
            if (this.tick - this.refusalsAt > 40) {
                this.recentRefusals = 0;
            }
            this.refusalsAt = this.tick;
            this.recentRefusals++;
            int pause = this.roundTripTicks() * (this.recentRefusals >= 3 ? 3 : 1);
            this.pauseTicks = Math.max(this.pauseTicks, pause);
        }
        if (!this.placedAt.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Integer>> it = this.placedAt.entrySet().iterator();
            while (it.hasNext()) {
                if (this.tick - it.next().getValue() > 100) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Where the sent look lands, if a click there is one this module wants:
     * the planned cell, or another cell of the same layer under the player
     * (where they are, or will be after this tick's motion). Clutch's post()
     * rule: click what the look hits, not what was planned.
     */
    private MovingObjectPosition lookHit(BlockData plan, float yaw, float pitch) {
        return this.lookHit(plan, yaw, pitch, 0.0, 0.0);
    }

    /** {@link #lookHit(BlockData, float, float)} from where the player would be, moved this far: the next step's. */
    private MovingObjectPosition lookHit(BlockData plan, float yaw, float pitch, double movedX, double movedZ) {
        MovingObjectPosition mop;
        if (movedX == 0.0 && movedZ == 0.0) {
            mop = RotationUtil.rayTrace(yaw, pitch, mc.playerController.getBlockReachDistance(), 1.0F);
        } else {
            Vec3 eye = mc.thePlayer.getPositionEyes(1.0F).addVector(movedX, 0.0, movedZ);
            Vec3 look = ((atlas.mixin.IAccessorEntity) mc.thePlayer).callGetVectorForRotation(pitch, yaw);
            double reach = mc.playerController.getBlockReachDistance();
            mop = mc.theWorld.rayTraceBlocks(eye, eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach));
        }
        if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK) {
            return null;
        }
        BlockPos support = mop.getBlockPos();
        if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)) {
            return null;
        }
        BlockPos cell = support.offset(mop.sideHit);
        BlockPos planned = plan.blockPos().offset(plan.facing());
        if (cell.equals(planned)) {
            return mop;
        }
        if (cell.getY() != planned.getY() || !BlockUtil.isReplaceable(cell)) {
            return null;
        }
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().offset(movedX, 0.0, movedZ)
                .addCoord(mc.thePlayer.motionX, 0.0, mc.thePlayer.motionZ);
        boolean under = box.minX < cell.getX() + 1 && box.maxX > cell.getX()
                && box.minZ < cell.getZ() + 1 && box.maxZ > cell.getZ();
        return under ? mop : null;
    }

    private boolean canPlace() {
        BedNuker bedNuker = (BedNuker) Atlas.moduleManager.modules.get(BedNuker.class);
        if (bedNuker.isEnabled() && bedNuker.isReady()) {
            return false;
        } else {
            LongJump longJump = (LongJump) Atlas.moduleManager.modules.get(LongJump.class);
            return !longJump.isEnabled() || !longJump.isAutoMode() || longJump.isJumping();
        }
    }

    private boolean isThreeFmcMode() {
        return this.rotationMode.getValue() == ROTATION_THREE_FMC;
    }

    private boolean isThreeFmcTellyMode() {
        return this.isThreeFmcMode() && (this.keepY.getValue() == 3 || this.keepY.getValue() == 4);
    }

    private void updateThreeFmcState() {
        if (!this.isThreeFmcMode() || mc.thePlayer == null) {
            this.threeFmcAirTicks = 0;
            this.threeFmcGroundTicks = 0;
            this.threeFmcPlaceCooldown = 0;
            return;
        }

        if (mc.thePlayer.onGround) {
            this.threeFmcGroundTicks++;
            this.threeFmcAirTicks = 0;
        } else {
            this.threeFmcAirTicks++;
            this.threeFmcGroundTicks = 0;
        }

        if (this.threeFmcPlaceCooldown > 0) {
            this.threeFmcPlaceCooldown--;
        }
    }

    private void quietThreeFmcMovement() {
        if (!this.isThreeFmcMode() || this.isThreeFmcTellyMode() || mc.thePlayer == null) {
            return;
        }

        mc.thePlayer.setSprinting(false);
        if (mc.gameSettings != null) {
            KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    private boolean canThreeFmcPlaceNow() {
        if (!this.isThreeFmcMode()) {
            return true;
        }
        if (mc.thePlayer == null || this.placedThisTick || this.threeFmcPlaceCooldown > 0) {
            return false;
        }
        if ((!this.isThreeFmcTellyMode() && mc.thePlayer.isSprinting()) || mc.thePlayer.isCollidedHorizontally || mc.thePlayer.hurtTime > 0) {
            return false;
        }
        if (mc.thePlayer.onGround) {
            return Math.abs(mc.thePlayer.motionY) < 1.0E-4 && this.threeFmcGroundTicks > 0;
        }
        return this.isThreeFmcTellyMode() ? this.threeFmcAirTicks > 1 : this.threeFmcAirTicks > 2;
    }

    private EnumFacing getBestFacing(BlockPos blockPos1, BlockPos blockPos3) {
        double offset = 0.0;
        EnumFacing enumFacing = null;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing != EnumFacing.DOWN) {
                BlockPos pos = blockPos1.offset(facing);
                if (pos.getY() <= blockPos3.getY()) {
                    double distance = pos.distanceSqToCenter((double) blockPos3.getX() + 0.5, (double) blockPos3.getY() + 0.5, (double) blockPos3.getZ() + 0.5);
                    if (enumFacing == null || distance < offset || distance == offset && facing == EnumFacing.UP) {
                        offset = distance;
                        enumFacing = facing;
                    }
                }
            }
        }
        return enumFacing;
    }

    /**
     * 2026-11-07 (owner: "it still stacks upward"): a GodBridge jump is to cross a gap, not to climb, so in the air the
     * block goes on the layer the jump left from. Keep-y itself is not used for this: it brings its own auto jump. The jump
     * key held by hand still builds up.
     */
    private boolean godBridgeHoldsLayer() {
        if (this.rotationMode.getValue() == ROTATION_GODBRIDGE && this.jumpBuildsUp.getValue()) {
            /* The block goes under the feet wherever they are: a block up once the jump is a block high. */
            return false;
        }
        return this.lbGodBridge() && !mc.thePlayer.onGround && !mc.gameSettings.keyBindJump.isKeyDown();
    }

    private BlockData getBlockData() {
        return this.getBlockData(0.0, 0.0);
    }

    /** The block to place from, for the column this far from the player. */
    private BlockData getBlockData(double aheadX, double aheadZ) {
        int startY = MathHelper.floor_double(mc.thePlayer.posY);
        boolean holdLayer = (this.stage != 0 || this.godBridgeHoldsLayer()) && !this.shouldKeepY;
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX + aheadX),
                (holdLayer ? Math.min(startY, this.startY) : startY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ + aheadZ)
        );
        if (!BlockUtil.isReplaceable(targetPos)) {
            return null;
        } else {
            ArrayList<BlockPos> positions = new ArrayList<>();
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 0; y++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos pos = targetPos.add(x, y, z);
                        if (!BlockUtil.isReplaceable(pos)
                                && !BlockUtil.isInteractable(pos)
                                && !(
                                mc.thePlayer.getDistance((double) pos.getX() + 0.5, (double) pos.getY() + 0.5, (double) pos.getZ() + 0.5)
                                        > (double) mc.playerController.getBlockReachDistance()
                        )
                                && (!holdLayer || pos.getY() < this.startY)) {
                            for (EnumFacing facing : EnumFacing.VALUES) {
                                if (facing != EnumFacing.DOWN) {
                                    BlockPos blockPos = pos.offset(facing);
                                    if (BlockUtil.isReplaceable(blockPos)) {
                                        positions.add(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (positions.isEmpty()) {
                return null;
            } else {
                positions.sort(
                        Comparator.comparingDouble(
                                o -> o.distanceSqToCenter((double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5)
                        )
                );
                BlockPos blockPos = positions.get(0);
                EnumFacing facing = this.getBestFacing(blockPos, targetPos);
                return facing == null ? null : new BlockData(blockPos, facing);
            }
        }
    }

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (!this.canThreeFmcPlaceNow()) {
            return;
        }
        if (this.pauseTicks > 0 || this.faceFailed(blockPos, enumFacing)) {
            /* Waiting out a setback or a refused block: every click sent now is
               judged from a position or against a block the client does not have. */
            return;
        }
        if (ItemUtil.isHoldingBlock() && this.blockCount > 0) {
            ItemStack held = mc.thePlayer.inventory.getCurrentItem();
            /* A C08 goes out whatever onPlayerRightClick answers. When the game
               would not place here (the cell is taken, or the block would be
               inside an entity), the server may place it anyway from where it
               has the player: a block the client does not have. */
            if (held == null || !(held.getItem() instanceof ItemBlock)
                    || !((ItemBlock) held.getItem()).canPlaceBlockOnSide(mc.theWorld, blockPos, enumFacing, mc.thePlayer, held)) {
                return;
            }
            if (!mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, blockPos, enumFacing, vec3)) {
                this.failedFaces.put(faceKey(blockPos, enumFacing), this.tick + 10);
                return;
            }
            {
                this.placedAt.put(blockPos.offset(enumFacing), this.tick);
                if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                    this.blockCount--;
                }
                this.placedThisTick = true;
                if (this.isThreeFmcMode()) {
                    this.threeFmcPlaceCooldown = 1;
                }
                this.markPlaced(blockPos.offset(enumFacing));
                this.eagleBlocksPlaced++;
                this.blocksSinceJump++;
                if (this.rotationMode.getValue() == ROTATION_GODBRIDGE) {
                    this.run.placed();
                }
                if (this.swing.getValue()) {
                    mc.thePlayer.swingItem();
                } else {
                    PacketUtil.sendPacket(new C0APacketAnimation());
                }
            }
        }
    }

    private MovingObjectPosition getPlacementMop(BlockData blockData, float yaw, float pitch) {
        MovingObjectPosition mop = RotationUtil.rayTrace(yaw, pitch, mc.playerController.getBlockReachDistance(), 1.0F);
        if (mop == null
                || mop.typeOfHit != MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(blockData.blockPos())
                || mop.sideHit != blockData.facing()) {
            return null;
        }
        return mop;
    }

    private boolean isDuplicateSnapRotation(float yaw, float pitch) {
        return !Float.isNaN(this.lastSnapPlaceYaw)
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - this.lastSnapPlaceYaw)) < 0.35F;
    }

    private float[] getSnapRotation(BlockData blockData, float yaw, float pitch) {
        float baseYaw = RotationUtil.quantizeAngle(yaw);
        float basePitch = RotationUtil.quantizeAngle(MathHelper.clamp_float(pitch, -90.0F, 90.0F));

        if (!this.isDuplicateSnapRotation(baseYaw, basePitch)) {
            return new float[]{baseYaw, basePitch};
        }

        for (int i = 0; i < 24; i++) {
            float yawStep = 0.35F + 0.075F * (float) (i / 2);
            float pitchStep = 0.025F + 0.01F * (float) (i / 3);
            float testYaw = RotationUtil.quantizeAngle(baseYaw + (i % 2 == 0 ? yawStep : -yawStep));
            float testPitch = RotationUtil.quantizeAngle(MathHelper.clamp_float(basePitch + (i % 4 < 2 ? pitchStep : -pitchStep), -90.0F, 90.0F));

            if (!this.isDuplicateSnapRotation(testYaw, testPitch) && this.getPlacementMop(blockData, testYaw, testPitch) != null) {
                return new float[]{testYaw, testPitch};
            }
        }

        return null;
    }

    private void rememberSnapRotation() {
        this.lastSnapPlaceYaw = this.yaw;
        this.lastSnapPlacePitch = this.pitch;
    }

    private EnumFacing yawToFacing(float yaw) {
        if (yaw < -135.0F || yaw > 135.0F) {
            return EnumFacing.NORTH;
        } else if (yaw < -45.0F) {
            return EnumFacing.EAST;
        } else {
            return yaw < 45.0F ? EnumFacing.SOUTH : EnumFacing.WEST;
        }
    }

    private double distanceToEdge(EnumFacing enumFacing) {
        switch (enumFacing) {
            case NORTH:
                return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:
                return Math.ceil(mc.thePlayer.posX) - mc.thePlayer.posX;
            case SOUTH:
                return Math.ceil(mc.thePlayer.posZ) - mc.thePlayer.posZ;
            case WEST:
            default:
                return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    private boolean isNearEdge() {
        if (!mc.thePlayer.onGround) {
            return false;
        }
        double fracX = mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        double fracZ = mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
        double threshold = this.edgeDistance.getValue();
        double minDist = Math.min(Math.min(fracX, 1.0 - fracX), Math.min(fracZ, 1.0 - fracZ));
        return minDist <= threshold;
    }

    private boolean shouldSneak() {
        if (!this.eagle.getValue() || !mc.thePlayer.onGround) {
            return false;
        }
        if (this.eagleBlocksPlaced < this.blocksPerSneak.getValue()) {
            return false;
        }
        if (System.currentTimeMillis() - this.eagleLastSneakTime < (long) this.sneakDelay.getValue().intValue()) {
            return false;
        }
        return this.isNearEdge();
    }

    private void updateEagle() {
        if (!this.eagle.getValue()) {
            this.eagleSneaking = false;
            this.eagleSneakTicks = 0;
            return;
        }
        if (this.eagleSneakTicks > 0) {
            this.eagleSneakTicks--;
            if (this.eagleSneakTicks == 0) {
                this.eagleSneaking = false;
            }
            return;
        }
        if (this.shouldSneak()) {
            this.eagleSneaking = true;
            this.eagleSneakTicks = 2;
            this.eagleLastSneakTime = System.currentTimeMillis();
            this.eagleBlocksPlaced = 0;
        }
    }

    private float getSpeed() {
        if (!mc.thePlayer.onGround) {
            return (float) this.airMotion.getValue() / 100.0F;
        } else {
            return MoveUtil.getSpeedLevel() > 0
                    ? (float) this.speedMotion.getValue() / 100.0F
                    : (float) this.groundMotion.getValue() / 100.0F;
        }
    }

    private double getRandomOffset() {
        return 0.2155 - RandomUtil.nextDouble(1.0E-4, 9.0E-4);
    }

    private float getCurrentYaw() {
        return MoveUtil.adjustYaw(
                mc.thePlayer.rotationYaw, (float) MoveUtil.getForwardValue(), (float) MoveUtil.getLeftValue()
        );
    }

    private boolean isDiagonal(float yaw) {
        float absYaw = Math.abs(yaw % 90.0F);
        return absYaw > 20.0F && absYaw < 70.0F;
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && MoveUtil.isForwardPressed() && !PlayerUtil.isAirAbove()) {
            boolean keepY = this.keepY.getValue() == 3 || this.keepY.getValue() == 4;
            boolean tower = this.tower.getValue() == 3;
            return keepY && this.stage > 0 || tower && mc.gameSettings.keyBindJump.isKeyDown();
        } else {
            return false;
        }
    }

    public Scaffold() {
        super("Scaffold", false);
        /* 2026-11-04 SPEED-TELLY (owner: Clutch held on F, walking forward and jumping, is a speed telly; make that a
           Scaffold mode). Clutch's catch does the whole job, with Clutch's settings. Scaffold stays on, for its key, its
           HUD entry and its block counter; the rest of it stands aside. */
        this.pauseWhen(this::isSpeedTellyMode);
    }

    private boolean isSpeedTellyMode() {
        return this.rotationMode.getValue() == ROTATION_SPEED_TELLY || isWeirdTellyMode();
    }

    private boolean godBridgeShown() {
        int mode = this.rotationMode.getValue();
        return mode == ROTATION_GODBRIDGE || mode == ROTATION_POLAR;
    }

    /* 2026-11-08: GODBRIDGE has its own flow (GodBridge); the LiquidBounce ledge settings are POLAR's. */
    private boolean polarShown() {
        return this.rotationMode.getValue() == ROTATION_POLAR;
    }

    private boolean paceOn() {
        return this.legitPace.getValue() && this.lbGodBridge();
    }


    private boolean isWeirdTellyMode() {
        return this.rotationMode.getValue() == ROTATION_WEIRD_TELLY;
    }

    /** On, in WEIRD-TELLY: jumps go from off to the side of the view. */
    public boolean isWeirdTelly() {
        return this.isEnabled() && this.isWeirdTellyMode();
    }

    /** On, in SPEED-TELLY: Clutch treats this as its hold key being down. */
    public boolean isSpeedTelly() {
        return this.isEnabled() && this.isSpeedTellyMode();
    }

    /**
     * SPEED-TELLY with keep-y set, on the terms any keep-y here has: Clutch then stays on the layer stood on. A jump
     * straight up is left out by Clutch itself (plainJump), so towering in place still works.
     */
    public boolean isSpeedTellyKeepY() {
        return this.isSpeedTelly() && this.keepY.getValue() != 0
                && (!this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                && (!this.disableWhileJumpActive.getValue() || !mc.thePlayer.isPotionActive(Potion.jump));
    }

    /*
     * SPEED-TELLY's auto jump: the jump key itself is held down while W is, so to everything that reads it this is the
     * player holding it. Setting movementInput.jump alone jumped, but Clutch's snap-on-jump reads the key, and without it
     * the look did not come back for the next jump (owner: "with auto jump on it has no effect").
     * whenDisabled: it runs while the rest of this module is paused, and lets go when the module goes off.
     */
    @EventTarget(whenDisabled = true)
    public void onTellyTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (this.isEnabled() && mc.thePlayer != null) {
            this.trackPace();
        }
        int jumpKey = mc.gameSettings.keyBindJump.getKeyCode();
        if (this.isSpeedTelly() && this.tellyAutoJump.getValue() && mc.currentScreen == null
                && mc.gameSettings.keyBindForward.isKeyDown() && !mc.gameSettings.keyBindBack.isKeyDown()
                && !mc.gameSettings.keyBindSneak.isKeyDown()) {
            KeyBindUtil.setKeyBindState(jumpKey, true);
            this.holdingJump = true;
        } else if (this.holdingJump) {
            KeyBindUtil.updateKeyState(jumpKey);
            this.holdingJump = false;
        }
    }

    /** The crouches and jumps the server sees, for PlacePace; runs while the module is paused too. */
    private void trackPace() {
        long now = System.currentTimeMillis();
        if (mc.thePlayer.isSneaking()) {
            this.pace.sneaking(now);
        }
        boolean onGround = mc.thePlayer.onGround;
        if (this.paceWasOnGround && !onGround && mc.thePlayer.motionY > 0.2) {
            this.pace.jumped(now);
        }
        this.paceWasOnGround = onGround;
    }

    /**
     * What this client places, as the server gets it: the placement books for legit-pace, from every placement whoever
     * made it (Clutch's telly included). Runs while paused.
     */
    @EventTarget(value = Priority.LOWEST, whenDisabled = true)
    public void onPaceSend(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.SEND || event.isCancelled() || mc.thePlayer == null
                || mc.theWorld == null || !mc.isCallingFromMinecraftThread()) {
            return;
        }
        long now = System.currentTimeMillis();
        Packet<?> packet = event.getPacket();
        if (packet instanceof net.minecraft.network.play.client.C08PacketPlayerBlockPlacement) {
            net.minecraft.network.play.client.C08PacketPlayerBlockPlacement place =
                    (net.minecraft.network.play.client.C08PacketPlayerBlockPlacement) packet;
            int side = place.getPlacedBlockDirection();
            ItemStack stack = place.getStack();
            if (side == 255 || side > 5 || place.getPosition() == null || stack == null
                    || !(stack.getItem() instanceof ItemBlock)) {
                return;
            }
            BlockPos support = place.getPosition();
            BlockPos cell = support.offset(EnumFacing.getFront(side));
            int neighbours = 0;
            for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
                if (!mc.theWorld.isAirBlock(cell.offset(facing))) {
                    neighbours++;
                }
            }
            boolean under = PlacePace.under(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                    cell.getX(), cell.getY(), cell.getZ());
            this.pace.placed(now, under, cell.getY() == support.getY(), neighbours, cell.getX(), cell.getY(), cell.getZ());
        }
    }

    /** SPEED-TELLY runs on Clutch: switched on for it if it was off, and off again afterwards. */
    private void syncClutch() {
        Module clutch = Atlas.moduleManager == null ? null : Atlas.moduleManager.getModule(Clutch.class);
        if (clutch == null) {
            return;
        }
        if (this.isSpeedTelly() && !clutch.isEnabled()) {
            clutch.setEnabled(true);
            this.turnedClutchOn = true;
        } else if (!this.isSpeedTelly() && this.turnedClutchOn) {
            clutch.setEnabled(false);
            this.turnedClutchOn = false;
        }
    }

    @Override
    public void verifyValue(String name) {
        if ("rotations".equals(name) && this.isEnabled()) {
            this.syncClutch();
        }
    }

    public int getSlot() {
        return this.lastSlot;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.placedThisTick = false;
            this.planReady = false;
            this.tick++;
            if (this.pauseTicks > 0) {
                this.pauseTicks--;
            }
            if (this.correctionPending) {
                this.correctionPending = false;
                if (this.pauseOnCorrection.getValue()) {
                    this.pauseTicks = Math.max(this.pauseTicks, this.roundTripTicks() + 1);
                }
            }
            this.drainRefusals();
            this.updateThreeFmcState();
            this.quietThreeFmcMovement();
            if (this.safeStuckDelayTicks > 0) {
                this.safeStuckDelayTicks--;
                if (this.safeStuckDelayTicks <= 0) {
                    this.safeStuckTicks = 1;
                }
            }
            if (this.safeStuckTicks > 0) {
                if (!this.safeStuckActive) {
                    this.savedMotionX = mc.thePlayer.motionX;
                    this.savedMotionY = mc.thePlayer.motionY;
                    this.savedMotionZ = mc.thePlayer.motionZ;
                    this.safeStuckActive = true;
                }
                Atlas.blinkManager.setBlinkState(true, BlinkModules.SCAFFOLD);
                mc.thePlayer.motionX = 0.0;
                mc.thePlayer.motionY = 0.0;
                mc.thePlayer.motionZ = 0.0;
            } else if (this.safeStuckActive) {
                Atlas.blinkManager.setBlinkState(false, BlinkModules.SCAFFOLD);
                mc.thePlayer.motionX = this.savedMotionX;
                mc.thePlayer.motionY = this.savedMotionY;
                mc.thePlayer.motionZ = this.savedMotionZ;
                this.safeStuckActive = false;
            }
            if (this.rotationTick > 0) {
                this.rotationTick--;
            }
            this.updateEagle();
            if (hypixeltower.getValue() && mc.thePlayer.motionY <= 0.0 && Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ) <= 0.02D && mc.thePlayer.motionY >= -0.09 && !(Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode()) ||
                    Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode()) ||
                    Keyboard.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode()) ||
                    Keyboard.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode())) && Keyboard.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
                mc.thePlayer.motionY = -0.38;
            }
            if (mc.thePlayer.onGround) {
                if (this.stage > 0) {
                    this.stage--;
                }
                if (this.stage < 0) {
                    this.stage++;
                }
                if (this.stage == 0
                        && this.keepY.getValue() != 0
                        && (!(Boolean) this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                        && (!this.disableWhileJumpActive.getValue() || !mc.thePlayer.isPotionActive(Potion.jump))
                        && !mc.gameSettings.keyBindJump.isKeyDown()) {
                    this.stage = 1;
                }
                this.startY = this.shouldKeepY ? this.startY : MathHelper.floor_double(mc.thePlayer.posY);
                this.shouldKeepY = false;
                this.towering = false;
            }
            if (this.canPlace()) {
                ItemStack stack = mc.thePlayer.getHeldItem();
                int count = ItemUtil.isBlock(stack) ? stack.stackSize : 0;
                this.blockCount = Math.min(this.blockCount, count);
                if (this.blockCount <= 0) {
                    int slot = mc.thePlayer.inventory.currentItem;
                    if (this.blockCount == 0) {
                        slot--;
                    }
                    for (int i = slot; i > slot - 9; i--) {
                        int hotbarSlot = (i % 9 + 9) % 9;
                        ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hotbarSlot);
                        if (ItemUtil.isBlock(candidate)) {
                            mc.thePlayer.inventory.currentItem = hotbarSlot;
                            this.blockCount = candidate.stackSize;
                            break;
                        }
                    }
                }
                if (this.rotationMode.getValue() == ROTATION_GODBRIDGE) {
                    this.godBridgeRunTick(event);
                    return;
                }
                if (this.lbGodBridge()) {
                    this.godBridgeTick(event);
                    return;
                }
                float currentYaw = this.getCurrentYaw();
                float yawDiffTo180 = RotationUtil.wrapAngleDiff(currentYaw - 180.0F, event.getYaw());
                float diagonalYaw = this.isDiagonal(currentYaw)
                        ? yawDiffTo180
                        : RotationUtil.wrapAngleDiff(currentYaw - 135.0F * ((currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), event.getYaw());
                boolean snapMode = this.rotationMode.getValue() == ROTATION_SNAP || this.rotationMode.getValue() == ROTATION_SNAP2;
                boolean threeFmcMode = this.rotationMode.getValue() == ROTATION_THREE_FMC;
                boolean threeFmcTelly = this.isThreeFmcTellyMode();
                this.snapRotating = false;
                if (!this.canRotate) {
                    switch (this.rotationMode.getValue()) {
                        case 1:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                            }
                            break;
                        case 2:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                            }
                            break;
                        case 3:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                            }
                            break;
                        case 5:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                float targetYaw = this.isDiagonal(currentYaw) ? diagonalYaw : yawDiffTo180;
                                float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - this.yaw);
                                float pitchDiff = MathHelper.wrapAngleTo180_float(85.0F - this.pitch);
                                float yawTolerance = this.rotationTick >= 2 ? RandomUtil.nextFloat(tellystartrotationminspeed.getValue(), tellystartrotationmaxspeed.getValue()) : RandomUtil.nextFloat(tellynormalrotationminspeed.getValue(), tellynormalrotationmaxspeed.getValue());
                                float pitchTolerance = this.rotationTick >= 2 ? RandomUtil.nextFloat(tellystartrotationminspeed.getValue(), tellystartrotationmaxspeed.getValue()) : RandomUtil.nextFloat(tellynormalrotationminspeed.getValue(), tellynormalrotationmaxspeed.getValue());
                                this.yaw = RotationUtil.quantizeAngle(this.yaw + RotationUtil.clampAngle(yawDiff, yawTolerance));
                                this.pitch = RotationUtil.quantizeAngle(this.pitch + RotationUtil.clampAngle(pitchDiff, pitchTolerance));
                            }
                            break;
                        case 6:
                            //idk what to put here so imma just do the same as sideways for now
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                            }
                            break;
                        case ROTATION_SNAP:
                        case ROTATION_SNAP2:
                            this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                            this.pitch = RotationUtil.quantizeAngle(85.0F);
                            break;
                        case ROTATION_THREE_FMC:
                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(event.getYaw());
                                this.pitch = RotationUtil.quantizeAngle(event.getPitch());
                            }
                            break;
                    }
                }
                BlockData blockData = this.getBlockData();
                /* The aim is measured from the look already sent. */
                float refYaw = event.getYaw();
                float refPitch = event.getPitch();
                float aimDiff = Float.MAX_VALUE;
                Vec3 hitVec = null;
                if (blockData != null) {
                    double[] x = aimOffsets;
                    double[] y = aimOffsets;
                    double[] z = aimOffsets;
                    switch (blockData.facing()) {
                        case NORTH:
                            z = new double[]{0.0};
                            break;
                        case EAST:
                            x = new double[]{1.0};
                            break;
                        case SOUTH:
                            z = new double[]{1.0};
                            break;
                        case WEST:
                            x = new double[]{0.0};
                            break;
                        case DOWN:
                            y = new double[]{0.0};
                            break;
                        case UP:
                            y = new double[]{1.0};
                    }
                    float bestYaw = -180.0F;
                    float bestPitch = 0.0F;
                    float bestDiff = 0.0F;
                    for (double dx : x) {
                        for (double dy : y) {
                            for (double dz : z) {
                                double relX = (double) blockData.blockPos().getX() + dx - mc.thePlayer.posX;
                                double relY = (double) blockData.blockPos().getY() + dy - mc.thePlayer.posY - (double) mc.thePlayer.getEyeHeight();
                                double relZ = (double) blockData.blockPos().getZ() + dz - mc.thePlayer.posZ;
                                float baseYaw = RotationUtil.wrapAngleDiff(this.yaw, event.getYaw());
                                float[] rotations = RotationUtil.getRotationsTo(relX, relY, relZ, baseYaw, this.pitch);
                                MovingObjectPosition mop = RotationUtil.rayTrace(rotations[0], rotations[1], mc.playerController.getBlockReachDistance(), 1.0F);
                                if (mop != null
                                        && mop.typeOfHit == MovingObjectType.BLOCK
                                        && mop.getBlockPos().equals(blockData.blockPos())
                                        && mop.sideHit == blockData.facing()) {
                                    /* S3: the least turn from the look the server already has
                                       (it was from the last target), so the aim stays put --
                                       LiquidBounce's NearestRotation / Stabilized. */
                                    float totalDiff = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - refYaw))
                                            + Math.abs(rotations[1] - refPitch);
                                    if (bestYaw == -180.0F && bestPitch == 0.0F || totalDiff < bestDiff) {
                                        bestYaw = rotations[0];
                                        bestPitch = rotations[1];
                                        bestDiff = totalDiff;
                                        hitVec = mop.hitVec;
                                    }
                                }
                            }
                        }
                    }
                    if (bestYaw != -180.0F || bestPitch != 0.0F) {
                        this.yaw = bestYaw;
                        this.pitch = bestPitch;
                        this.canRotate = true;
                        aimDiff = bestDiff;
                    } else if (threeFmcMode) {
                        this.canRotate = false;
                    }
                }
                boolean towerRotating = this.towering || this.isTowering();
                boolean snapAlreadyLooking = false;
                boolean snapCanPlace = true;
                if (snapMode && !towerRotating && blockData != null) {
                    MovingObjectPosition currentMop = this.getPlacementMop(blockData, event.getYaw(), event.getPitch());
                    if (currentMop != null) {
                        float[] snapRotation = this.getSnapRotation(blockData, event.getYaw(), event.getPitch());
                        if (snapRotation == null) {
                            snapCanPlace = false;
                            hitVec = null;
                        } else {
                            this.yaw = snapRotation[0];
                            this.pitch = snapRotation[1];
                            this.canRotate = true;
                            MovingObjectPosition snapMop = this.getPlacementMop(blockData, this.yaw, this.pitch);
                            hitVec = snapMop != null ? snapMop.hitVec : currentMop.hitVec;
                            this.snapRotating = true;
                            int snapDelay = this.rotationMode.getValue() == ROTATION_SNAP2 ? 0 : 1;
                            if (this.rotationTick > snapDelay) {
                                this.rotationTick = snapDelay;
                            }
                        }
                    } else if (hitVec != null && this.canRotate) {
                        float[] snapRotation = this.getSnapRotation(blockData, this.yaw, this.pitch);
                        if (snapRotation == null) {
                            snapCanPlace = false;
                            hitVec = null;
                        } else {
                            this.yaw = snapRotation[0];
                            this.pitch = snapRotation[1];
                            MovingObjectPosition snapMop = this.getPlacementMop(blockData, this.yaw, this.pitch);
                            if (snapMop != null) {
                                hitVec = snapMop.hitVec;
                            }
                            this.snapRotating = true;
                            int snapDelay = this.rotationMode.getValue() == ROTATION_SNAP2 ? 0 : 1;
                            if (this.rotationTick > snapDelay) {
                                this.rotationTick = snapDelay;
                            }
                        }
                    }
                }
                if (this.canRotate && MoveUtil.isForwardPressed() && Math.abs(MathHelper.wrapAngleTo180_float(yawDiffTo180 - this.yaw)) < 90.0F) {
                    switch (this.rotationMode.getValue()) {
                        case 2:
                            this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                            break;
                        case 3:
                            this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                    }
                }
                float placeYaw = this.yaw;
                float placePitch = this.pitch;
                if (this.rotationMode.getValue() != 0 && (!snapMode || this.snapRotating || towerRotating)) {
                    float targetYaw = this.yaw;
                    float targetPitch = this.pitch;
                    if (this.rotationRandom.getValue() > 0.0F && blockData != null && !towerRotating) {
                        float[] wandered = this.wander(targetYaw, targetPitch);
                        if (this.lookHit(blockData, wandered[0], wandered[1]) != null) {
                            targetYaw = wandered[0];
                            targetPitch = wandered[1];
                        }
                    }
                    if ((!threeFmcMode || threeFmcTelly) && this.towering && (mc.thePlayer.motionY > 0.0 || mc.thePlayer.posY > (double) (this.startY + 1))) {
                        float yawDiff = MathHelper.wrapAngleTo180_float(this.yaw - event.getYaw());
                        float tolerance = this.rotationTick >= 2 ? RandomUtil.nextFloat(tellystartrotationminspeed.getValue(), tellystartrotationmaxspeed.getValue()) : RandomUtil.nextFloat(tellynormalrotationminspeed.getValue(), tellynormalrotationmaxspeed.getValue());
                        if (Math.abs(yawDiff) > tolerance) {
                            float clampedYaw = RotationUtil.clampAngle(yawDiff, tolerance);
                            targetYaw = RotationUtil.quantizeAngle(event.getYaw() + clampedYaw);
                            this.rotationTick = Math.max(this.rotationTick, 1);
                        }
                    }
                    if (towerRotating && this.isTowering()) {
                        if (!threeFmcMode || threeFmcTelly) {
                            float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - event.getYaw());
                            targetYaw = RotationUtil.quantizeAngle(event.getYaw() + yawDelta * RandomUtil.nextFloat(0.98F, 0.99F));
                            targetPitch = RotationUtil.quantizeAngle(RandomUtil.nextFloat(30.0F, 80.0F));
                        }
                        this.rotationTick = 3;
                        this.towering = true;
                    }
                    /* On the mouse grid from what the server was last sent
                       (RotationEngine): the modes above round to a fixed
                       0.0096, which is no one's sensitivity. The click is then
                       re-aimed along the rotation actually sent, so the face
                       and the point it claims are the ones that rotation hits. */
                    if (this.turnLimited() && !towerRotating && blockData != null
                            && this.lookHit(blockData, event.getYaw(), event.getPitch()) != null) {
                        /* S3: the look the server already has puts a block where
                           it is wanted -- keep it. No turn: the click is made with
                           the very look the server knows, and the aim does not
                           wander from block to block. */
                        targetYaw = event.getYaw();
                        targetPitch = event.getPitch();
                    }
                    /* The shared engine turns (in the turning modes at most turn-speed a tick on each axis --
                       LiquidBounce: RotationsValueGroup), keeps the grid and the DuplicateRotPlace guard. */
                    float cap = this.turnLimited() && !towerRotating ? this.turnSpeed.getValue() : RotationHelper.NO_LIMIT;
                    if (!snapMode) {
                        /* 2026-11-05 (owner): Humanization's aim error on the way to a block, none within HumanTurn.SETTLED
                           of it. Kept only while the look still lands on the face, as the wander above, so an overshoot
                           never costs a placement. A snap has one tick and is left exact. */
                        float[] aimed = atlas.humanize.HumanTurn.apply(this.human, event.getYaw(), event.getPitch(),
                                targetYaw, targetPitch);
                        if (blockData == null || this.lookHit(blockData, aimed[0], aimed[1]) != null) {
                            targetYaw = aimed[0];
                            targetPitch = aimed[1];
                        }
                    }
                    /* A snap lands in the tick it is made: the click waits only snapDelay ticks for it. */
                    float[] onGrid = RotationHelper.request(this, targetYaw, targetPitch, 3, this.moveMode(), false, cap,
                            snapMode ? SNAP_STEP : MIN_STEP);
                    if (onGrid == null) {
                        /* A higher priority has the look this tick. */
                        onGrid = RotationEngine.quantize(event.getYaw(), event.getPitch(), targetYaw, targetPitch);
                    }
                    targetYaw = onGrid[0];
                    targetPitch = onGrid[1];
                    placeYaw = targetYaw;
                    placePitch = targetPitch;
                    if (this.moveFix.getModeString().equals("REAL")) {
                        /* REAL: the camera turns as well, spread over this tick's frames by
                           RotationManager; the mouse still works (not forced). Movement
                           follows it like a real head turn, so no strafe fix (onMoveInput
                           only fixes SILENT). */
                        Atlas.rotationManager.setRotation(targetYaw, targetPitch, 3, false);
                    }
                }
                /* The click is made at LOWEST (onUpdateClick), once every
                   module has set this tick's rotation: the look checked is
                   then the one the movement packet will really carry. */
                this.planData = blockData;
                this.planHitVec = hitVec;
                this.planSnapCanPlace = snapCanPlace;
                this.planSnapMode = snapMode;
                this.planThreeFmc = threeFmcMode;
                this.planReady = true;
            }
        }
    }


    /**
     * The click for this tick's plan, after every module has set the rotation
     * (Priority.LOWEST): the look checked is the one the movement packet after
     * it will carry. Clutch does the same in post().
     */
    @EventTarget(Priority.LOWEST)
    public void onUpdateClick(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || !this.planReady) {
            return;
        }
        this.planReady = false;
        BlockData blockData = this.planData;
        Vec3 hitVec = this.planHitVec;
        boolean snapCanPlace = this.planSnapCanPlace;
        boolean snapMode = this.planSnapMode;
        boolean threeFmcMode = this.planThreeFmc;
        /* The look this tick's movement packet carries -- ours, or a
           higher-priority module's, or the camera's when no mode turned
           this tick. The click must be one that look makes: the face it
           names and the point it claims, where the ray actually lands.
           Until 2026-10-04 only 3FMC checked; every other mode kept the
           planned hitVec when the sent look missed the face (towering,
           a telly clamp, a snap that did not rotate) and clicked anyway,
           which the server judges against the look it has -- Grim
           RotationPlace, or the block undone (49 Scaffold REJECTs in the
           flag logs). NONE never turns, so it is left as it was.
           Since 2026-10-06 this runs at LOWEST, after every module's
           rotation, and the click is wherever that look lands if the block
           goes somewhere wanted (lookHit), as Clutch's post() does. */
        float[] packet = RotationHelper.packetLook(event);
        float sentYaw = packet[0];
        float sentPitch = packet[1];
        MovingObjectPosition along = null;
        if (this.rotationMode.getValue() != 0 && blockData != null && hitVec != null) {
            along = this.lookHit(blockData, sentYaw, sentPitch);
            hitVec = along == null ? null : along.hitVec;
        }
        if (blockData != null && hitVec != null && this.paceOn() && mc.thePlayer.onGround
                && this.paceHolds(along != null ? along.getBlockPos() : blockData.blockPos(),
                along != null ? along.sideHit : blockData.facing(), sentYaw)) {
            /* legit-pace: this block now would be faster than a human bridge is allowed. On the ground only, where the
               edge crouch holds the player (in GODBRIDGE the fall guard's stop, never a crouch); in the air a block
               late is a fall. */
            hitVec = null;
        }
        if (blockData != null && hitVec != null && snapCanPlace && this.rotationTick <= 0) {
            if (along != null) {
                this.place(along.getBlockPos(), along.sideHit, along.hitVec);
            } else {
                this.place(blockData.blockPos(), blockData.facing(), hitVec);
            }
            if (snapMode) {
                this.rememberSnapRotation();
            }
            if (this.multiplace.getValue() && !snapMode) {
                for (int i = 0; i < 3; i++) {
                    blockData = this.getBlockData();
                    if (blockData == null) {
                        break;
                    }
                    /* Along the sent look, not this.yaw: that one may be
                       a clamp or a quantisation away from what went out. */
                    MovingObjectPosition mop = this.rotationMode.getValue() != 0
                            ? this.getPlacementMop(blockData, sentYaw, sentPitch)
                            : RotationUtil.rayTrace(this.yaw, this.pitch, mc.playerController.getBlockReachDistance(), 1.0F);
                    if (mop != null
                            && mop.typeOfHit == MovingObjectType.BLOCK
                            && mop.getBlockPos().equals(blockData.blockPos())
                            && mop.sideHit == blockData.facing()) {
                        this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    } else {
                        /* The rotation that would reach this one is
                           never sent: the server would judge the click
                           against the rotation it has, which misses.
                           Wait for a tick that aims at it. */
                        break;
                    }
                }
            }
        }
        if (this.targetFacing != null) {
            if (threeFmcMode) {
                this.targetFacing = null;
            } else if (this.rotationTick <= 0 && !this.placedThisTick) {
                int playerBlockX = MathHelper.floor_double(mc.thePlayer.posX);
                int playerBlockY = MathHelper.floor_double(mc.thePlayer.posY);
                int playerBlockZ = MathHelper.floor_double(mc.thePlayer.posZ);
                BlockPos belowPlayer = new BlockPos(playerBlockX, playerBlockY - 1, playerBlockZ);
                /* Only a click the sent look makes (2026-10-04): the
                   face is tower EXTRA's, the hit point where that look
                   lands on it. BlockUtil.getHitVec placed a point on the
                   face whatever the look was. NONE keeps the old click. */
                if (this.rotationMode.getValue() != 0) {
                    MovingObjectPosition towerMop = this.getPlacementMop(
                            new BlockData(belowPlayer, this.targetFacing), sentYaw, sentPitch);
                    if (towerMop != null) {
                        this.place(belowPlayer, this.targetFacing, towerMop.hitVec);
                    }
                } else {
                    hitVec = BlockUtil.getHitVec(belowPlayer, this.targetFacing, this.yaw, this.pitch);
                    this.place(belowPlayer, this.targetFacing, hitVec);
                }
            }
            this.targetFacing = null;
        } else if ((this.keepY.getValue() == 2 || this.keepY.getValue() == 4) && this.stage > 0 && !mc.thePlayer.onGround) {
            int nextBlockY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
            if (nextBlockY <= this.startY && mc.thePlayer.posY > (double) (this.startY + 1)) {
                this.shouldKeepY = true;
                blockData = this.getBlockData();
                if (blockData != null && this.rotationTick <= 0 && !this.placedThisTick) {
                    MovingObjectPosition mop = this.rotationMode.getValue() != 0
                            ? this.getPlacementMop(blockData, sentYaw, sentPitch)
                            : this.getPlacementMop(blockData, this.yaw, this.pitch);
                    if (mop != null) {
                        this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    }
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        /* Network thread: only hand things over. */
        Packet<?> packet = event.getPacket();
        if (packet instanceof S08PacketPlayerPosLook) {
            this.correctionPending = true;
        } else if (packet instanceof S23PacketBlockChange && !this.placedAt.isEmpty()) {
            S23PacketBlockChange change = (S23PacketBlockChange) packet;
            BlockPos pos = change.getBlockPosition();
            if (pos != null && change.getBlockState() != null
                    && change.getBlockState().getBlock() == Blocks.air && this.placedAt.containsKey(pos)) {
                this.refused.add(pos);
            }
        }
    }

    @EventTarget
    public void onStrafe(StrafeEvent event) {
        if (this.isEnabled()) {
            if (this.safeStuckTicks > 0) {
                event.setForward(0.0F);
                event.setStrafe(0.0F);
                return;
            }
            if (this.isThreeFmcMode() && !this.isThreeFmcTellyMode()) {
                this.towerTick = 0;
                this.towerDelay = 0;
                return;
            }
            if (!mc.thePlayer.isCollidedHorizontally
                    && mc.thePlayer.hurtTime <= 5
                    && !mc.thePlayer.isPotionActive(Potion.jump)
                    && mc.gameSettings.keyBindJump.isKeyDown()
                    && ItemUtil.isHoldingBlock()) {
                int yState = (int) (mc.thePlayer.posY % 1.0 * 100.0);
                switch (this.tower.getValue()) {
                    case 1:
                        switch (this.towerTick) {
                            case 0:
                                if (mc.thePlayer.onGround) {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = -0.0784000015258789;
                                }
                                return;
                            case 1:
                                if (yState == 0 && PlayerUtil.isAirBelow()) {
                                    this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                    this.towerTick = 2;
                                    mc.thePlayer.motionY = 0.42F;
                                    if (MoveUtil.isForwardPressed()) {
                                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                                    } else {
                                        MoveUtil.setSpeed(0.0);
                                        event.setForward(0.0F);
                                        event.setStrafe(0.0F);
                                    }
                                    return;
                                } else {
                                    this.towerTick = 0;
                                    return;
                                }
                            case 2:
                                this.towerTick = 3;
                                mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0;
                                return;
                            case 3:
                                this.towerTick = 1;
                                mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                                return;
                            default:
                                this.towerTick = 0;
                                return;
                        }
                    case 2:
                        switch (this.towerTick) {
                            case 0:
                                if (mc.thePlayer.onGround) {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = -0.0784000015258789;
                                }
                                return;
                            case 1:
                                if (yState == 0 && PlayerUtil.isAirBelow()) {
                                    this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                    if (!MoveUtil.isForwardPressed()) {
                                        this.towerDelay = 2;
                                        MoveUtil.setSpeed(0.0);
                                        event.setForward(0.0F);
                                        event.setStrafe(0.0F);
                                        EnumFacing facing = this.yawToFacing(MathHelper.wrapAngleTo180_float(this.yaw - 180.0F));
                                        double distance = this.distanceToEdge(facing);
                                        if (distance > 0.1) {
                                            if (mc.thePlayer.onGround) {
                                                Vec3i directionVec = facing.getDirectionVec();
                                                double offset = Math.min(this.getRandomOffset(), distance - 0.05);
                                                double jitter = RandomUtil.nextDouble(0.02, 0.03);
                                                AxisAlignedBB nextBox = mc.thePlayer
                                                        .getEntityBoundingBox()
                                                        .offset((double) directionVec.getX() * (offset - jitter), 0.0, (double) directionVec.getZ() * (offset - jitter));
                                                if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, nextBox).isEmpty()) {
                                                    mc.thePlayer.motionY = -0.0784000015258789;
                                                    mc.thePlayer
                                                            .setPosition(nextBox.minX + (nextBox.maxX - nextBox.minX) / 2.0, nextBox.minY, nextBox.minZ + (nextBox.maxZ - nextBox.minZ) / 2.0);
                                                }
                                                return;
                                            }
                                        } else {
                                            this.towerTick = 2;
                                            this.targetFacing = facing;
                                            mc.thePlayer.motionY = 0.42F;
                                        }
                                        return;
                                    } else {
                                        this.towerTick = 2;
                                        this.towerDelay++;
                                        mc.thePlayer.motionY = 0.42F;
                                        MoveUtil.setSpeed(MoveUtil.getSpeed(), MoveUtil.getMoveYaw());
                                        return;
                                    }
                                } else {
                                    this.towerTick = 0;
                                    this.towerDelay = 0;
                                    return;
                                }
                            case 2:
                                this.towerTick = 3;
                                mc.thePlayer.motionY = mc.thePlayer.motionY - RandomUtil.nextDouble(0.00101, 0.00109);
                                return;
                            case 3:
                                if (this.towerDelay >= 4) {
                                    this.towerTick = 4;
                                    this.towerDelay = 0;
                                } else {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                                }
                                return;
                            case 4:
                                this.towerTick = 5;
                                return;
                            case 5:
                                if (!PlayerUtil.isAirBelow()) {
                                    this.towerTick = 0;
                                } else {
                                    this.towerTick = 1;
                                    mc.thePlayer.motionY -= 0.08;
                                    mc.thePlayer.motionY *= 0.98F;
                                    mc.thePlayer.motionY -= 0.08;
                                    mc.thePlayer.motionY *= 0.98F;
                                }
                                return;
                            default:
                                this.towerTick = 0;
                                this.towerDelay = 0;
                                return;
                        }
                    default:
                        this.towerTick = 0;
                        this.towerDelay = 0;
                }
            } else {
                this.towerTick = 0;
                this.towerDelay = 0;
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.safeStuckTicks > 0) {
                mc.thePlayer.movementInput.moveForward = 0.0f;
                mc.thePlayer.movementInput.moveStrafe = 0.0f;
                mc.thePlayer.movementInput.jump = false;
                mc.thePlayer.movementInput.sneak = false;
                return;
            }
            this.applyPolarLedgeInput();
            this.applyGodBridgeInput();
            this.quietThreeFmcMovement();
            /* SILENT's keys are turned by the shared engine (RotationHelper). */
            if (mc.thePlayer.onGround && this.stage > 0 && MoveUtil.isForwardPressed()) {
                mc.thePlayer.movementInput.jump = true;
            }
            if (this.rotationMode.getValue() == ROTATION_GODBRIDGE) {
                /* GODBRIDGE never crouches (2026-11-08, owner: "god bridge is defined by NOT crouching"). The last
                   guard is a stop instead, as late as it can be: the walk keys are let go only when one more tick of
                   walking and the slide after it would leave the box with no floor, and friction holds the player on the
                   block. Not at the early edge test the crouch used: the god bridge look places only with the feet well
                   over the edge, so stopping there would never place. eagle is not used here. */
                if (this.safeWalk.getValue() && this.wouldFallOnNextStep() && !this.godBridgePlacesAhead()) {
                    mc.thePlayer.movementInput.moveForward = 0.0F;
                    mc.thePlayer.movementInput.moveStrafe = 0.0F;
                }
                return;
            }
            if (this.safeWalk.getValue() && !mc.thePlayer.movementInput.sneak && this.edgeSneak()) {
                /* S1 (2026-10-07): the edge is held by a real crouch, the way
                   Vape's Legit/EdgeSneak and LiquidBounce's Ledge do it, and
                   vanilla's own sneak rule then keeps the player on the block.
                   Scaled as vanilla scales a held sneak key. */
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
            }
            if (this.eagleSneaking && !mc.thePlayer.movementInput.sneak) {
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
            }
        }
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (this.isEnabled()) {
            if (this.safeStuckTicks > 0) {
                mc.thePlayer.motionX = 0.0;
                mc.thePlayer.motionY = 0.0;
                mc.thePlayer.motionZ = 0.0;
                this.safeStuckTicks--;
            }
            this.quietThreeFmcMovement();
            float speed = this.isThreeFmcMode() && !this.isThreeFmcTellyMode() ? 1.0F : this.getSpeed();
            if (speed != 1.0F) {
                if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
                    mc.thePlayer.movementInput.moveForward = mc.thePlayer.movementInput.moveForward * (1.0F / (float) Math.sqrt(2.0));
                    mc.thePlayer.movementInput.moveStrafe = mc.thePlayer.movementInput.moveStrafe * (1.0F / (float) Math.sqrt(2.0));
                }
                mc.thePlayer.movementInput.moveForward *= speed;
                mc.thePlayer.movementInput.moveStrafe *= speed;
            }
            if (this.shouldStopSprint()) {
                mc.thePlayer.setSprinting(false);
            }

            if (this.safe.getValue() && this.tower.getValue() == 3 && mc.gameSettings.keyBindJump.isKeyDown()) {
                float moveYaw = this.getCurrentYaw();
                boolean diagonal = this.isDiagonal(moveYaw);
                if (diagonal && !mc.thePlayer.onGround) {
                    double motionY = mc.thePlayer.motionY;
                    if (this.safePrevMotionY > 0.0 && motionY <= 0.0) {
                        double motionXZ = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
                        double motionXZSpeedBps = motionXZ * 20.0;
                        if (this.safeStuckDelayTicks <= 0 && this.safeStuckTicks <= 0 && motionXZSpeedBps >= 4.67) {
                            this.safeStuckDelayTicks = this.safeStuckDelayTicksProperty.getValue();
                        }
                    }
                    this.safePrevMotionY = motionY;
                } else {
                    this.safePrevMotionY = mc.thePlayer.motionY;
                }
            } else {
                this.safePrevMotionY = mc.thePlayer.motionY;
            }
        }
    }

    /** Until when the edge crouch is held after the edge is left (Vape's "sneak delay"). */
    private long edgeSneakUntil = 0L;

    /*
     * 2026-10-31 (owner: "GodBridge does not look like GodBridge, use LiquidBounce"; asked: the fixed look and
     * LiquidBounce's jump or sneak at the ledge, not a turn to aim; diagonal straight back as LiquidBounce): GODBRIDGE
     * is now what POLAR already was -- LiquidBounce's GodBridge technique whole. The look never leaves the fixed one
     * (S4d's re-aim is gone), and when it cannot place for the next step at the edge, the ledge action (polar-modes,
     * polar-force-sneak-below-count, polar-sneak-time) jumps or sneaks.
     */
    /* 2026-10-31: a turn from rest may be this big (the engine's own is 35), as before the move onto the engine:
       a GodBridge side switch (about 90 degrees) or a change of direction has to land within a tick or two, or the
       block is not placed in time. */
    private static final float MIN_STEP = 70.0F;
    /** SNAP / SNAP2: any turn in one tick (the engine's acceleration does not apply to a snap). */
    private static final float SNAP_STEP = 360.0F;

    private boolean lbGodBridge() {
        return this.godBridgeShown();
    }

    /** GodBridge: the look of this run (2026-11-07). */
    private final GodBridgeLook godBridge = new GodBridgeLook();
    /** GODBRIDGE (2026-11-08): the run, and this tick's plan for the input stage. */
    private final GodBridge run = new GodBridge();
    private GodBridge.Plan runPlan;

    /**
     * GODBRIDGE, a tick (2026-11-08): the block for where the player will be next tick; GodBridge plans the look, the
     * crouch, the jump and the click from what is read here; the look goes through RotationHelper (whole mouse counts
     * from the look the server has, DuplicateRotPlace); the click is onUpdateClick's, along the look really sent, and
     * only on a tick the plan clicks. legit-pace's books say when a crouch is due and whether a jump has room.
     */
    private void godBridgeRunTick(UpdateEvent event) {
        BlockData target = this.getBlockData(mc.thePlayer.motionX, mc.thePlayer.motionZ);
        boolean forward = MoveUtil.isForwardPressed();
        long now = System.currentTimeMillis();
        boolean paced = this.paceOn();
        GodBridge.Input in = new GodBridge.Input();
        in.x = mc.thePlayer.posX;
        in.z = mc.thePlayer.posZ;
        in.onGround = mc.thePlayer.onGround;
        in.forward = forward;
        in.moveYaw = this.getCurrentYaw();
        in.sentYaw = event.getYaw();
        in.sentPitch = event.getPitch();
        in.hasTarget = target != null;
        in.edge = in.onGround && forward && target != null && this.edgeSneak();
        in.lookPlaces = target != null && this.run.running()
                && this.lookHit(target, this.run.lookYaw(), this.run.lookPitch()) != null;
        if (target != null && !in.lookPlaces && this.run.running()) {
            in.placePitch = this.godBridgePlacePitch(target, this.run.lookYaw(), this.run.lookPitch());
        }
        if (in.onGround && forward && this.run.running() && !in.lookPlaces && Float.isNaN(in.placePitch)) {
            /* Nothing places from here yet: the pitch that will, from the next step, so the look is on its way. */
            double[] step = this.godBridgeNextStep();
            BlockData ahead = this.getBlockData(step[0], step[1]);
            if (ahead != null) {
                in.aheadPitch = this.godBridgePlacePitch(ahead, this.run.lookYaw(), this.run.lookPitch(), step[0], step[1]);
            }
        }
        /* No crouch in GODBRIDGE (2026-11-08): the pace is kept with jumps, the next block forecast one gap ahead. */
        in.paceJump = paced && this.pace.jumpWanted(now, this.pace.lastGap(230L), event.getYaw(), this.speedLevel());
        in.jumpEvery = this.jumpEveryBlocks.getValue();
        in.now = now;
        GodBridge.Plan plan = this.run.tick(in);
        this.runPlan = plan;
        if (plan.look) {
            float[] aimed = atlas.humanize.HumanTurn.apply(this.human, event.getYaw(), event.getPitch(), plan.yaw, plan.pitch);
            float[] sent = RotationHelper.request(this, aimed[0], aimed[1], 3, this.moveMode(), false,
                    plan.maxTurn, Math.min(MIN_STEP, plan.maxTurn));
            if (sent != null && this.moveFix.getModeString().equals("REAL")) {
                Atlas.rotationManager.setRotation(sent[0], sent[1], 3, false);
            }
            this.yaw = plan.yaw;
            this.pitch = plan.pitch;
            this.canRotate = true;
        }
        this.planData = target;
        this.planHitVec = target == null || !plan.click ? null : new Vec3(target.blockPos()).addVector(0.5, 0.5, 0.5);
        this.planSnapCanPlace = true;
        this.planSnapMode = false;
        this.planThreeFmc = false;
        this.planReady = true;
    }

    /** At this yaw, the pitch nearest {@code from} (within 8, 68 to 86) whose ray places the target from here; NaN for none. */
    private float godBridgePlacePitch(BlockData target, float yaw, float from) {
        return this.godBridgePlacePitch(target, yaw, from, 0.0, 0.0);
    }

    /** The same from where the player would be, moved this far. */
    private float godBridgePlacePitch(BlockData target, float yaw, float from, double movedX, double movedZ) {
        if (this.lookHit(target, yaw, from, movedX, movedZ) != null) {
            return from;
        }
        for (int i = 1; i <= GODBRIDGE_PITCH_STEPS; i++) {
            float away = (i + 1) / 2 * GODBRIDGE_PITCH_STEP * (i % 2 == 0 ? -1.0F : 1.0F);
            float pitch = from + away;
            if (pitch < GODBRIDGE_PITCH_MIN || pitch > GODBRIDGE_PITCH_MAX) {
                continue;
            }
            if (this.lookHit(target, yaw, pitch, movedX, movedZ) != null) {
                return pitch;
            }
        }
        return Float.NaN;
    }

    /**
     * Where one more tick of walking carries the player: this motion (what friction left of the last step) plus a
     * tick of walking input along the way the keys walk. {dx, dz}.
     */
    private double[] godBridgeNextStep() {
        double radians = Math.toRadians(this.getCurrentYaw());
        return new double[]{mc.thePlayer.motionX - Math.sin(radians) * 0.1, mc.thePlayer.motionZ + Math.cos(radians) * 0.1};
    }

    /**
     * GODBRIDGE, 2026-11-11 (owner: slow, a stop at every block): whether the walk may go on although the fall guard
     * would stop it -- the next step still has floor under the box, and from there the run's look, with a pitch it
     * can reach by then, places the block that step needs. Then the block goes down next tick and there is nothing to
     * stop for. Not while the look still turns onto the run's, and not when legit-pace would hold that block: those
     * keep the stop.
     */
    private boolean godBridgePlacesAhead() {
        GodBridge.Plan plan = this.runPlan;
        if (!this.run.running() || plan == null || !plan.look || plan.maxTurn < 180.0F) {
            return false;
        }
        double[] step = this.godBridgeNextStep();
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().expand(-0.02, 0.0, -0.02).offset(step[0], -1.0, step[1]);
        if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty()) {
            return false;
        }
        BlockData target = this.getBlockData(step[0], step[1]);
        if (target == null) {
            return true;
        }
        if (this.paceOn() && this.paceHolds(target.blockPos(), target.facing(), this.run.lookYaw())) {
            return false;
        }
        float pitch = this.godBridgePlacePitch(target, this.run.lookYaw(), this.run.lookPitch(), step[0], step[1]);
        return !Float.isNaN(pitch) && Math.abs(pitch - this.run.lookPitch()) <= 2.0F * GodBridge.PITCH_FIX_MAX;
    }

    private static final int GODBRIDGE_PITCH_STEPS = 64;
    private static final float GODBRIDGE_PITCH_STEP = 0.25F;
    private static final float GODBRIDGE_PITCH_MIN = 68.0F;
    private static final float GODBRIDGE_PITCH_MAX = 86.0F;

    /** GODBRIDGE's crouch and jump, at the input stage, so vanilla acts on them. */
    private void applyGodBridgeInput() {
        GodBridge.Plan plan = this.runPlan;
        if (this.rotationMode.getValue() != ROTATION_GODBRIDGE || plan == null) {
            return;
        }
        if (plan.stop) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
        }
        if (plan.jump && !plan.stop && mc.thePlayer.onGround && !mc.thePlayer.isInWater()) {
            mc.thePlayer.movementInput.jump = true;
        }
    }

    /**
     * S4 (2026-10-07): LiquidBounce GodBridge's look
     * (ScaffoldGodBridgeTechnique.getRotations). Facing back along the walk,
     * rounded to 45 degrees. Straight: 45 degrees off it, to the side of the
     * row the player is on (swapped when leaning off the block with air
     * ahead), pitch 75.7. Diagonal: straight back, pitch 75.6. No keys: the
     * corner of the aimed side, pitch 75.
     */
    private float[] godBridgeRotation(float aimYaw) {
        if (!MoveUtil.isForwardPressed()) {
            this.godBridge.release();
            float axis = (float) Math.floor(aimYaw / 90.0F) * 90.0F;
            return new float[]{axis + 45.0F, 75.0F};
        }
        return this.godBridge.look(this.getCurrentYaw(), mc.thePlayer.posX, mc.thePlayer.posZ);
    }

    /**
     * GODBRIDGE and POLAR, a tick of them (2026-11-07, owner: "redo all of it"), in LiquidBounce's order, none of the other
     * modes' state: the block for where the player will be next tick; the look, fixed by the walk and sent whether or not
     * a block is due, so it is ready at the edge; the ledge; then the click at LOWEST, along whatever look really went
     * out (onUpdateClick, lookHit), or none. The look is never aimed at the block and takes no wander or aim error: a
     * godbridge is a look held still.
     */
    private void godBridgeTick(UpdateEvent event) {
        BlockData target = this.getBlockData(mc.thePlayer.motionX, mc.thePlayer.motionZ);
        boolean forward = MoveUtil.isForwardPressed();
        float[] look = forward || target != null ? this.godBridgeRotation(event.getYaw()) : null;
        if (!forward && target == null) {
            this.godBridge.release();
        }
        this.updatePolarLedge(target, look);
        if (look != null) {
            float[] sent = RotationHelper.request(this, look[0], look[1], 3, this.moveMode(), false,
                    RotationHelper.NO_LIMIT, MIN_STEP);
            if (sent != null && this.moveFix.getModeString().equals("REAL")) {
                Atlas.rotationManager.setRotation(sent[0], sent[1], 3, false);
            }
            this.yaw = look[0];
            this.pitch = look[1];
            this.canRotate = true;
        }
        this.planData = target;
        /* A placeholder: onUpdateClick replaces it with where the sent look lands, or clicks nothing. */
        this.planHitVec = target == null ? null : new Vec3(target.blockPos()).addVector(0.5, 0.5, 0.5);
        this.planSnapCanPlace = true;
        this.planSnapMode = false;
        this.planThreeFmc = false;
        this.planReady = true;
    }

    /**
     * Whether to crouch for the edge this tick. Vape's test: on the ground,
     * the player's box shrunk by 0.2 a side and moved by this tick's motion
     * and one block down touches nothing -- the next step has no floor. Once
     * off the edge the crouch is kept for a random 100-200 ms, so it does not
     * flicker on and off at the rim.
     */
    private boolean edgeSneak() {
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            this.edgeSneakUntil = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (this.floorlessNextStep()) {
            this.edgeSneakUntil = now + 100L + (long) (Math.random() * 100.0);
            return true;
        }
        return now < this.edgeSneakUntil;
    }

    /** Vape's test with no hold: the box shrunk by 0.2 a side, moved by this tick's motion and one block down, touches nothing. */
    private boolean floorlessNextStep() {
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            return false;
        }
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().expand(-0.2, 0.0, -0.2)
                .offset(mc.thePlayer.motionX, -1.0, mc.thePlayer.motionZ);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty();
    }

    /**
     * GODBRIDGE's fall guard: whether walking on one more tick and then letting go would carry the player's box past
     * any floor. Ground friction keeps 0.546 of the motion a tick, so letting go slides about 1.2 times the motion
     * after the step; the step is this motion plus one tick of walking input (0.098 a tick at walk speed).
     */
    private boolean wouldFallOnNextStep() {
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            return false;
        }
        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        double ix = 0.0;
        double iz = 0.0;
        float input = (float) Math.sqrt(forward * forward + strafe * strafe);
        if (input > 1.0E-3F) {
            double radians = Math.toRadians(mc.thePlayer.rotationYaw);
            double sin = Math.sin(radians);
            double cos = Math.cos(radians);
            double scale = 0.098 / Math.max(1.0F, input);
            ix = (strafe * cos - forward * sin) * scale;
            iz = (forward * cos + strafe * sin) * scale;
        }
        double travel = 1.0 + 0.546 / (1.0 - 0.546);
        double dx = (mc.thePlayer.motionX + ix) * travel;
        double dz = (mc.thePlayer.motionZ + iz) * travel;
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().expand(-0.02, 0.0, -0.02).offset(dx, -1.0, dz);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty();
    }

    /** The Speed potion level as PlacePace reads it (the amplifier: the stricter of Intave's two readings). */
    private int speedLevel() {
        PotionEffect speed = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed);
        return speed == null ? 0 : speed.getAmplifier();
    }

    /**
     * Polar / GodBridge ledge decision, run once per tick from onUpdate.
     *
     * Mirrors LiquidBounce's ScaffoldGodBridgeTechnique.ledge(): when the next
     * step has no floor (the {@link #edgeSneak()} test is the same "no floor one
     * block down" check that clipLedged uses) the player is about to walk off,
     * so we choose an action that holds the edge. With polar.json's config that
     * is normally a jump, but a sneak when fewer than
     * {@link #polarForceSneakBelowCount} blocks remain. The chosen action is
     * stored and applied at the input stage by {@link #applyPolarLedgeInput()}.
     *
     * The rules themselves are {@link #polarLedgeAction}, which has no client
     * in it and so can be tested; this method only reads the live state and
     * stores the action for {@link #applyPolarLedgeInput()}.
     */
    private void updatePolarLedge(BlockData blockData, float[] godBridgeLook) {
        if (!this.lbGodBridge()) {
            this.polarLedgeSneakTicks = 0;
            this.polarLedgeStopInput = false;
            this.polarLedgeStepBack = false;
            this.polarLedgeJump = false;
            this.blocksSinceJump = 0;
            return;
        }
        // A pending sneak keeps its tick budget until it runs out.
        if (this.polarLedgeSneakTicks > 0) {
            this.polarLedgeStopInput = false;
            this.polarLedgeStepBack = false;
            this.polarLedgeJump = false;
            return;
        }
        this.polarLedgeStopInput = false;
        this.polarLedgeStepBack = false;
        this.polarLedgeJump = false;
        boolean onGround = mc.thePlayer.onGround;
        boolean forward = MoveUtil.isForwardPressed();
        boolean hasTarget = blockData != null && godBridgeLook != null;
        /* edgeSneak() moves the safe-walk timer, so it is only asked while
           actually bridging -- the same order as before. */
        boolean onEdge = onGround && forward && hasTarget && this.edgeSneak();
        boolean canPlaceFromLook = hasTarget
                && this.lookHit(blockData, godBridgeLook[0], godBridgeLook[1]) != null;
        if (onEdge && !canPlaceFromLook) {
            this.godBridge.missed(this.tick);
        }
        boolean paced = this.paceOn();
        long now = System.currentTimeMillis();
        int action = polarLedgeAction(onGround, forward, hasTarget, onEdge, canPlaceFromLook,
                this.blockCount, this.polarForceSneakBelowCount.getValue(),
                this.polarModes.getValue(), this.jumpBoostAmplifier());
        if (paced) {
            action = pacedLedgeAction(action, this.pace.roomForJump(),
                    onEdge && this.pace.sneakDue(now, this.nextSneakEvery));
        }
        switch (action) {
            case POLAR_SNEAK:
                this.polarLedgeSneakTicks = paced
                        ? Math.max(this.polarSneakTime.getValue(), PlacePace.MIN_SNEAK_TICKS + this.paceRandom.nextInt(3))
                        : this.polarSneakTime.getValue();
                if (paced) {
                    this.nextSneakEvery = 3000L + this.paceRandom.nextInt(1500);
                }
                break;
            case POLAR_STOP_INPUT:
                this.polarLedgeStopInput = true;
                break;
            case POLAR_BACKWARDS:
                this.polarLedgeStepBack = true;
                break;
            case POLAR_JUMP:
                this.polarLedgeJump = true;
                break;
            default:
                break;
        }
        int every = this.jumpEveryBlocks.getValue();
        if (!forward) {
            this.blocksSinceJump = 0;
        } else if (this.polarLedgeSneakTicks == 0 && (!paced || this.pace.roomForJump())
                && jumpDue(this.blocksSinceJump, every, onGround, this.jumpBoostAmplifier())) {
            this.polarLedgeStopInput = false;
            this.polarLedgeStepBack = false;
            this.polarLedgeJump = true;
            this.blocksSinceJump = 0;
        }
    }

    /**
     * legit-pace on the ledge action (2026-11-07): a crouch that is due is taken here, at an edge, where a player
     * crouches; and a jump that would put the next 750 ms under Intave's 300 ms a block becomes a crouch, which also
     * keeps the player on the edge. Other actions are left as they were.
     */
    static int pacedLedgeAction(int action, boolean roomForJump, boolean sneakDueAtEdge) {
        if (action == POLAR_JUMP && !roomForJump) {
            return POLAR_SNEAK;
        }
        if (sneakDueAtEdge && (action == LEDGE_NONE || action == POLAR_JUMP)) {
            return POLAR_SNEAK;
        }
        return action;
    }

    /** Whether a block clicked on this face now would break the PlacePace books, as the server will judge it. */
    private boolean paceHolds(BlockPos support, EnumFacing side, float yaw) {
        BlockPos cell = support.offset(side);
        int neighbours = 0;
        for (EnumFacing facing : EnumFacing.Plane.HORIZONTAL) {
            if (!mc.theWorld.isAirBlock(cell.offset(facing))) {
                neighbours++;
            }
        }
        boolean under = PlacePace.under(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ,
                cell.getX(), cell.getY(), cell.getZ());
        PotionEffect speed = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed);
        return this.pace.wouldFlag(System.currentTimeMillis(), yaw, under, cell.getY() == support.getY(), neighbours,
                /* The lower of the two readings of Intave's level (amplifier or amplifier + 1): the stricter limit. */
                speed == null ? 0 : speed.getAmplifier());
    }

    /** Enough blocks since the last jump, on the ground, and a jump that does not go high enough to miss the layer. */
    static boolean jumpDue(int placed, int every, boolean onGround, int jumpBoostAmplifier) {
        return every > 0 && placed >= every && onGround && jumpApexHeight(jumpBoostAmplifier) < 2.0;
    }

    /**
     * LiquidBounce's ScaffoldGodBridgeTechnique.ledge as a decision with no
     * client in it, so its rules can be tested. Returns the ledge action to
     * take ({@link #POLAR_JUMP}, {@link #POLAR_SNEAK}, {@link
     * #POLAR_STOP_INPUT}, {@link #POLAR_BACKWARDS}) or {@link #LEDGE_NONE}.
     *
     * LiquidBounce's conditions, in its order:
     * - only on the ground, walking forward, with a target to place and about
     *   to leave the floor (its clipLedged snapshot of the next tick);
     * - not at all while the look already places the block
     *   (targetFulfillsRequirements && isValidCrosshairTarget -> NO_LEDGE).
     *   While that look hits, the block goes down and the player never really
     *   leaves the floor, so there is nothing to jump for: this is why polar
     *   jumps at some edges rather than at every block, and why a diagonal --
     *   whose straight-back look rarely hits the side block -- is exactly the
     *   case that gets the jump or the sneak;
     * - SNEAK below forceSneakBelowCount blocks, otherwise the configured mode;
     * - and a jump that would carry two blocks (Jump Boost) becomes a sneak
     *   instead (jumpApexHeight() >= 2).
     *
     * @param jumpBoostAmplifier the player's Jump Boost amplifier, or -1 for none
     */
    static int polarLedgeAction(boolean onGround, boolean forwardPressed, boolean hasTarget,
            boolean onEdge, boolean canPlaceFromLook, int blockCount, int forceSneakBelowCount,
            int configuredMode, int jumpBoostAmplifier) {
        if (!onGround || !forwardPressed || !hasTarget || !onEdge) {
            return LEDGE_NONE;
        }
        if (canPlaceFromLook) {
            return LEDGE_NONE;
        }
        int mode = blockCount < forceSneakBelowCount ? POLAR_SNEAK : configuredMode;
        if (mode == POLAR_JUMP && jumpApexHeight(jumpBoostAmplifier) >= 2.0) {
            mode = POLAR_SNEAK;
        }
        return mode;
    }

    /**
     * How high a jump reaches, LiquidBounce's jumpApexHeight(): the jump
     * velocity -- 0.42, plus 0.1 a level of Jump Boost (EntityLivingBase.jump)
     * -- stepped down by gravity and air drag, summed while it still rises.
     *
     * @param jumpBoostAmplifier the player's Jump Boost amplifier, or -1 for none
     */
    static double jumpApexHeight(int jumpBoostAmplifier) {
        double motion = 0.42 + (jumpBoostAmplifier >= 0 ? 0.1 * (jumpBoostAmplifier + 1) : 0.0);
        double height = 0.0;
        while (motion > 0.0) {
            height += motion;
            motion = (motion - 0.08) * 0.98;
        }
        return height;
    }

    /** The player's Jump Boost level, or -1 when they have none. */
    private int jumpBoostAmplifier() {
        PotionEffect jumpBoost = mc.thePlayer.getActivePotionEffect(Potion.jump);
        return jumpBoost == null ? -1 : jumpBoost.getAmplifier();
    }

    /**
     * Applies the ledge action chosen by {@link #updatePolarLedge(BlockData,
     * float[])} at the movement-input stage, so vanilla itself acts on it.
     * SNEAK is a real crouch (which vanilla slows and, with safe-walk, keeps on
     * the block); JUMP triggers a vanilla jump; the stop-input / step-back
     * actions zero the player's movement so no block is walked off.
     */
    private void applyPolarLedgeInput() {
        if (!this.lbGodBridge()) {
            return;
        }
        if (this.polarLedgeSneakTicks > 0) {
            this.polarLedgeSneakTicks--;
            if (!mc.thePlayer.movementInput.sneak) {
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
            }
            return;
        }
        if (this.polarLedgeStopInput) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
        }
        if (this.polarLedgeStepBack) {
            mc.thePlayer.movementInput.moveForward *= -0.5F;
        }
        if (this.polarLedgeJump && mc.thePlayer.onGround && !mc.thePlayer.isInWater()) {
            // Let vanilla's onLivingUpdate perform the jump exactly once, the
            // same way keep-y/tower does (see onMoveInput). Calling jump()
            // directly here would leave jumpTicks at 0, so a held jump key
            // would fire a second jump in the same update -> double height.
            mc.thePlayer.movementInput.jump = true;
        }
    }

    @EventTarget
    public void onSafeWalk(SafeWalkEvent event) {
        /* S1 (2026-10-07): no silent clamp. Until now safe-walk stopped the
           player at the edge without crouching -- a stop Grim's movement
           simulation cannot produce, so every such edge was a "Simulation"
           and a disputed onGround ("GroundSpoof claimed true"), measured
           2026-10-06. The edge is now held by a real crouch (onMoveInput),
           and a crouching player keeps to the edge by vanilla's own rule. */
        if (this.isEnabled() && this.safeWalk.getValue() && mc.thePlayer.isSneaking()) {
            event.setSafeWalk(true);
        }
    }

    /* Runs while SPEED-TELLY has the rest of this module paused. */
    @EventTarget(whenDisabled = true)
    public void onRender(Render2DEvent event) {
        if (this.isEnabled()) {
            if (this.blockCounter.getValue()) {
                int count = 0;
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                    if (stack != null && stack.stackSize > 0) {
                        Item item = stack.getItem();
                        if (item instanceof ItemBlock) {
                            Block block = ((ItemBlock) item).getBlock();
                            if (!BlockUtil.isInteractable(block) && BlockUtil.isSolid(block)) {
                                count += stack.stackSize;
                            }
                        }
                    }
                }
                HUD hud = (HUD) Atlas.moduleManager.modules.get(HUD.class);
                float scale = hud.scale.getValue();
                GlStateManager.pushMatrix();
                GlStateManager.scale(scale, scale, 0.0F);
                GlStateManager.disableDepth();
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                mc.fontRendererObj
                        .drawString(
                                String.format("%d block%s left", count, count != 1 ? "s" : ""),
                                ((float) new ScaledResolution(mc).getScaledWidth() / 2.0F + (float) mc.fontRendererObj.FONT_HEIGHT * 1.5F) / scale,
                                (float) new ScaledResolution(mc).getScaledHeight() / 2.0F / scale - (float) mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1.0F,
                                (count > 0 ? Color.WHITE.getRGB() : new Color(255, 85, 85).getRGB()) | -1090519040,
                                hud.shadow.getValue()
                        );
                GlStateManager.disableBlend();
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            }
        }
    }

    private void markPlaced(BlockPos pos) {
        if (this.espOutline.getValue()) {
            this.espHighlight.put(pos, System.currentTimeMillis());
        }
    }

    @EventTarget(whenDisabled = true)
    public void onRender3D(Render3DEvent event) {
        if (!this.espOutline.getValue() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (this.espHighlight.isEmpty()) {
            return;
        }
        int themeColor;
        if (this.espColor.getValue() == 1) {
            HUD hud = (HUD) Atlas.moduleManager.modules.get(HUD.class);
            themeColor = hud.getColor(0L).getRGB();
        } else {
            themeColor = Color.CYAN.getRGB();
        }
        int peakAlpha = 210;
        ThemeStyle themed = ThemeStyle.active(BlockColors.class);
        if (themed != null) {
            themeColor = themed.color(new Color(themeColor), null, 0).getRGB();
            peakAlpha = Math.round(themed.lineAlpha255() * 210 / 255.0F);
        }
        Iterator<Map.Entry<BlockPos, Long>> iterator = this.espHighlight.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            long time = System.currentTimeMillis() - entry.getValue();
            if (time > 750L) {
                iterator.remove();
                continue;
            }
            int currentAlpha = (int) (peakAlpha - (time / 750.0 * peakAlpha));
            if (currentAlpha <= 0) {
                iterator.remove();
                continue;
            }
            RenderUtil.renderBlock(
                    entry.getKey(),
                    (themeColor & 0xFFFFFF) | (currentAlpha << 24),
                    true,
                    false);
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isEnabled()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            this.lastSlot = event.setSlot(this.lastSlot);
            event.setCancelled(true);
        }
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer != null) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
        } else {
            this.lastSlot = -1;
        }
        this.blockCount = -1;
        this.rotationTick = 3;
        this.yaw = -180.0F;
        this.pitch = 0.0F;
        this.godBridge.release();
        this.run.release();
        this.runPlan = null;
        this.canRotate = false;
        this.towerTick = 0;
        this.towerDelay = 0;
        this.towering = false;
        this.safeStuckTicks = 0;
        this.safeStuckDelayTicks = 0;
        this.safePrevMotionY = 0.0;
        this.safeStuckActive = false;
        this.eagleSneaking = false;
        this.eagleSneakTicks = 0;
        this.eagleBlocksPlaced = 0;
        this.eagleLastSneakTime = 0L;
        this.snapRotating = false;
        this.threeFmcAirTicks = 0;
        this.threeFmcGroundTicks = 0;
        this.threeFmcPlaceCooldown = 0;
        this.lastSnapPlaceYaw = Float.NaN;
        this.lastSnapPlacePitch = Float.NaN;
        this.espHighlight.clear();
        this.tick = 0;
        this.pauseTicks = 0;
        this.correctionPending = false;
        this.placedAt.clear();
        this.refused.clear();
        this.failedFaces.clear();
        this.recentRefusals = 0;
        this.planReady = false;
        this.planData = null;
        this.planHitVec = null;
        this.syncClutch();
    }

    @Override
    public void onDisabled() {
        this.syncClutch();
        if (mc.thePlayer != null && this.lastSlot != -1 && !this.isSpeedTellyMode()) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        /* The shared engine hands the look back to the camera by itself once this stops asking. */
        Atlas.blinkManager.setBlinkState(false, BlinkModules.SCAFFOLD);
        if (this.safeStuckActive && mc.thePlayer != null) {
            mc.thePlayer.motionX = this.savedMotionX;
            mc.thePlayer.motionY = this.savedMotionY;
            mc.thePlayer.motionZ = this.savedMotionZ;
        }
        this.safeStuckTicks = 0;
        this.safeStuckDelayTicks = 0;
        this.safePrevMotionY = 0.0;
        this.safeStuckActive = false;
        this.eagleSneaking = false;
        this.eagleSneakTicks = 0;
        this.threeFmcAirTicks = 0;
        this.threeFmcGroundTicks = 0;
        this.threeFmcPlaceCooldown = 0;
        this.planReady = false;
        this.planData = null;
        this.planHitVec = null;
    }

    public int getBlockCount() {
        return this.blockCount;
    }

    public static class BlockData {
        private final BlockPos blockPos;
        private final EnumFacing facing;

        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.facing = enumFacing;
        }

        public BlockPos blockPos() {
            return this.blockPos;
        }

        public EnumFacing facing() {
            return this.facing;
        }
    }
}
