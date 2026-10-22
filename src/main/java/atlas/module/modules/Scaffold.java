package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.*;
import atlas.management.PlaceRotations;
import atlas.management.RotationState;
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
import java.util.EnumSet;
import java.util.Set;
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
    /* 2026-10-13: after OpenB4's Scaffold (Strict rotations with "Jump facing
       forward" and "Legit motion", its defaults). See b4Plan. */
    private static final int ROTATION_B4 = 11;
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
    public final ModeProperty rotationMode = new ModeProperty("rotations", 2, new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS", "GODBRIDGE", "SMOOTH", "Hypixel", "SNAP", "3FMC", "SNAP2", "POLAR", "TELLY"})
            /* 2026-10-08: fewer modes in the menu; the rest still load from old configs. */
            .hide("BACKWARDS", "SIDEWAYS", "SMOOTH", "Hypixel", "3FMC", "SNAP2")
            .alias("GODBIRGDE", "GODBRIDGE")
            /* 2026-10-13: B4 renamed TELLY by the owner. */
            .alias("B4", "TELLY");
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
    private final IntProperty polarForceSneakBelowCount = new IntProperty("polar-force-sneak-below-count", 5, 0, 20, () -> this.rotationMode.getValue() == ROTATION_POLAR);
    private final IntProperty polarSneakTime = new IntProperty("polar-sneak-time", 1, 1, 10, () -> this.rotationMode.getValue() == ROTATION_POLAR);
    /* polar.json's Modes = [Jump]. A single choice, as the client's other mode
       settings are; the ledge decision reads it, so Sneak, StopInput and
       Backwards can be picked without a rebuild. */
    private final ModeProperty polarModes = new ModeProperty("polar-modes", POLAR_JUMP,
            new String[]{"Jump", "Sneak", "StopInput", "Backwards"},
            () -> this.rotationMode.getValue() == ROTATION_POLAR);
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
    public final BooleanProperty humanize = new BooleanProperty("humanize", true, this::turnLimited);
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
    /* B4: on each jump off the ground, face forward for the jump and turn back
       over a fixed few ticks before placing again (OpenB4 "Jump facing
       forward"). */
    public final BooleanProperty b4Telly = new BooleanProperty("telly-jump", true,
            () -> this.rotationMode.getValue() == ROTATION_B4);
    /* B4 state. The look the last block was placed with and when; the resting
       pitch and yaw offset drawn per enable; the telly turn in progress. */
    private float b4AimYaw = Float.NaN;
    private float b4AimPitch = 0.0F;
    private long b4PlacedAt = 0L;
    private float b4RestPitch = 86.0F;
    private float b4YawOffset = 0.0F;
    private int b4Script = 0;
    private float b4ScriptYaw = 0.0F;
    private float b4TurnSign = 1.0F;
    private int b4Ticks = 0;
    private final RotationEngine engine = new RotationEngine();
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
        if (this.rotationMode.getValue() == ROTATION_B4) {
            /* B4: with move-fix the keys follow the look sent, so facing back
               they move the player backwards and vanilla itself stops the
               sprint; facing forward on a telly jump it keeps it. */
            return false;
        }
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
        MovingObjectPosition mop = RotationUtil.rayTrace(yaw, pitch, mc.playerController.getBlockReachDistance(), 1.0F);
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
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().addCoord(mc.thePlayer.motionX, 0.0, mc.thePlayer.motionZ);
        boolean under = box.minX < cell.getX() + 1 && box.maxX > cell.getX()
                && box.minZ < cell.getZ() + 1 && box.maxZ > cell.getZ();
        return under ? mop : null;
    }

    /**
     * B4 rotations (2026-10-13), after OpenB4's Scaffold with its defaults
     * (Strict, Jump facing forward, Legit motion). The click itself is the
     * usual one (onUpdateClick: along the look actually sent, only where it
     * lands on a wanted face); this decides the look:
     *
     *  - TELLY (b4-telly): on the tick a jump leaves the ground while moving
     *    forward, face forward for that tick and the next, then turn back in
     *    three steps (105-120, 33-35, 29-33 degrees; pitch 40-45, 50-55,
     *    65-70) -- about 180 in all -- and click nothing until the turn is
     *    done. The jump's sprint boost goes forward because the look is
     *    forward when it is made (move-fix); facing back, the keys move the
     *    player backwards and vanilla drops the sprint by itself.
     *  - HOLD: the look already sent reaches a wanted face -> keep it.
     *  - AIM: else the point on the target face, from a 5x5 grid of points on
     *    it (jittered), whose ray from the eyes hits that face away from its
     *    edges, with the yaw nearest the way behind the player (in the air:
     *    nearest the last placing look).
     *  - KEEP: no aim found, but a block was placed within holdTime (710 ms
     *    sprinting, 460 standing, 110 walking) -> the look it was placed
     *    with, with a tiny jitter: no big turn between blocks.
     *  - REST: otherwise behind the movement at a pitch drawn 84.5-87.5 --
     *    near where the next block will need it.
     * The first two ticks after enabling turn only part of the way (0.3,
     * 0.55, 0.8). Every look is on the mouse grid from the one sent.
     */
    private void b4Plan(UpdateEvent event, BlockData blockData) {
        this.b4Ticks++;
        float fromYaw = event.getYaw();
        float fromPitch = event.getPitch();
        float forward = this.getCurrentYaw();
        float behind = forward + 180.0F;
        boolean clickable = true;
        float targetYaw;
        float targetPitch;
        Vec3 hitVec = null;

        if (this.b4Telly.getValue() && this.b4Script == 0 && mc.thePlayer.onGround && MoveUtil.isForwardPressed()
                && (mc.gameSettings.keyBindJump.isKeyDown() || this.stage > 0)) {
            /* This tick's movement input jumps (the key, or keep-y's own jump). */
            this.b4Script = 5;
            this.b4TurnSign = RandomUtil.nextFloat(0.0F, 1.0F) < 0.5F ? -1.0F : 1.0F;
            this.b4ScriptYaw = forward;
        }
        if (this.b4Script > 0) {
            switch (this.b4Script) {
                case 5:
                case 4:
                    this.b4ScriptYaw = forward;
                    targetPitch = RandomUtil.nextFloat(20.0F, 25.0F);
                    break;
                case 3:
                    this.b4ScriptYaw += this.b4TurnSign * RandomUtil.nextFloat(105.0F, 120.0F);
                    targetPitch = RandomUtil.nextFloat(40.0F, 45.0F);
                    break;
                case 2:
                    this.b4ScriptYaw += this.b4TurnSign * RandomUtil.nextFloat(33.0F, 35.0F);
                    targetPitch = RandomUtil.nextFloat(50.0F, 55.0F);
                    break;
                default:
                    this.b4ScriptYaw += this.b4TurnSign * RandomUtil.nextFloat(29.0F, 33.0F);
                    targetPitch = RandomUtil.nextFloat(65.0F, 70.0F);
                    break;
            }
            this.b4Script--;
            targetYaw = this.b4ScriptYaw + RandomUtil.nextFloat(-0.5F, 0.5F);
            clickable = false;
        } else {
            MovingObjectPosition held = blockData != null ? this.lookHit(blockData, fromYaw, fromPitch) : null;
            float[] aim = held == null && blockData != null
                    ? this.b4Aim(blockData, mc.thePlayer.onGround || Float.isNaN(this.b4AimYaw) ? behind : this.b4AimYaw,
                    fromYaw, fromPitch)
                    : null;
            long sincePlace = System.currentTimeMillis() - this.b4PlacedAt;
            if (held != null) {
                targetYaw = fromYaw;
                targetPitch = fromPitch;
                hitVec = held.hitVec;
            } else if (aim != null) {
                targetYaw = aim[0];
                targetPitch = aim[1];
            } else if (!Float.isNaN(this.b4AimYaw) && sincePlace < this.b4HoldTime()) {
                targetYaw = this.b4AimYaw;
                targetPitch = this.b4AimPitch;
                if (sincePlace > 5L && RandomUtil.nextFloat(0.0F, 1.0F) > 0.3F) {
                    targetYaw += RandomUtil.nextFloat(0.05F, 0.5F) * (RandomUtil.nextFloat(0.0F, 1.0F) < 0.5F ? -1.0F : 1.0F);
                    targetPitch += RandomUtil.nextFloat(-0.5F, 0.5F);
                }
            } else {
                targetYaw = behind + this.b4YawOffset;
                targetPitch = this.b4RestPitch + RandomUtil.nextFloat(-0.5F, 0.5F);
            }
        }
        targetYaw = fromYaw + MathHelper.wrapAngleTo180_float(targetYaw - fromYaw);
        targetPitch = MathHelper.clamp_float(targetPitch, -90.0F, 90.0F);
        if (this.b4Ticks <= 3) {
            /* A gentle start: part of the way on the first ticks. */
            float share = (this.b4Ticks == 1 ? 0.3F : this.b4Ticks == 2 ? 0.55F : 0.8F)
                    + RandomUtil.nextFloat(-0.02F, 0.02F);
            targetYaw = fromYaw + share * (targetYaw - fromYaw);
            targetPitch = fromPitch + share * (targetPitch - fromPitch);
        }
        float[] onGrid = RotationEngine.quantize(fromYaw, fromPitch, targetYaw, targetPitch);
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(onGrid[0] - fromYaw));
        for (int guard = 0; guard < 3 && turn > 2.0F && PlaceRotations.wouldDuplicate(onGrid[0]); guard++) {
            /* Grim DuplicateRotPlace, as in the other modes. */
            double gcd = RotationUtil.gcd();
            onGrid[0] += Math.signum(MathHelper.wrapAngleTo180_float(onGrid[0] - fromYaw)) * (float) (gcd > 0.0 ? gcd : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(onGrid[0] - fromYaw));
        }
        if (turn > 0.5F) {
            this.b4YawOffset = RandomUtil.nextFloat(-0.5F, 0.5F);
        }
        this.yaw = onGrid[0];
        this.pitch = onGrid[1];
        this.canRotate = true;
        event.setRotation(onGrid[0], onGrid[1], 3);
        if (this.moveFix.getValue() != 0) {
            event.setPervRotation(onGrid[0], 3);
        }
        if (this.moveFix.getModeString().equals("REAL")) {
            Atlas.rotationManager.setRotation(onGrid[0], onGrid[1], 3, false);
        }
        if (!clickable) {
            /* Turning on a telly jump: no click at all this tick. */
            this.planReady = false;
            return;
        }
        if (blockData != null && hitVec == null) {
            /* A placeholder only: onUpdateClick clicks where the sent look
               lands, if that is a wanted face, and nothing otherwise. */
            hitVec = new Vec3(blockData.blockPos()).addVector(0.5, 0.5, 0.5);
        }
        this.planData = blockData;
        this.planHitVec = hitVec;
        this.planSnapCanPlace = true;
        this.planSnapMode = false;
        this.planThreeFmc = false;
        this.planReady = true;
    }

    /** How long B4 keeps the last placing look when nothing is aimed (OpenB4's holdTime). */
    private long b4HoldTime() {
        if (mc.thePlayer.isSprinting()) {
            return 710L;
        }
        return MoveUtil.isMoving() ? 110L : 460L;
    }

    /**
     * The look at the target face nearest {@code baseYaw}: from 25 jittered
     * points on the face, those whose ray from the eyes hits that very face
     * at least 0.06 from its edges. Null when none does.
     */
    private float[] b4Aim(BlockData data, float baseYaw, float fromYaw, float fromPitch) {
        BlockPos pos = data.blockPos();
        EnumFacing face = data.facing();
        double cx = pos.getX() + 0.5 + 0.5 * face.getFrontOffsetX();
        double cy = pos.getY() + 0.5 + 0.5 * face.getFrontOffsetY();
        double cz = pos.getZ() + 0.5 + 0.5 * face.getFrontOffsetZ();
        double reach = mc.playerController.getBlockReachDistance();
        float[] best = null;
        float bestDiff = Float.MAX_VALUE;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                double a = i * 0.245 + RandomUtil.nextDouble(-0.04, 0.04);
                double b = j * 0.245 + RandomUtil.nextDouble(-0.04, 0.04);
                double px = cx;
                double py = cy;
                double pz = cz;
                if (face.getAxis() == EnumFacing.Axis.Y) {
                    px += a;
                    pz += b;
                } else if (face.getAxis() == EnumFacing.Axis.Z) {
                    px += a;
                    py += b;
                } else {
                    pz += a;
                    py += b;
                }
                float[] r = RotationUtil.getRotationsTo(px - mc.thePlayer.posX,
                        py - mc.thePlayer.posY - (double) mc.thePlayer.getEyeHeight(),
                        pz - mc.thePlayer.posZ, fromYaw, fromPitch);
                MovingObjectPosition mop = RotationUtil.rayTrace(r[0], r[1], reach, 1.0F);
                if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK || !mop.getBlockPos().equals(pos)
                        || mop.sideHit != face || !b4AwayFromEdge(mop.hitVec, pos)) {
                    continue;
                }
                float diff = Math.abs(MathHelper.wrapAngleTo180_float(r[0] - baseYaw));
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = r;
                }
            }
        }
        return best;
    }

    /** A hit point not within 0.06 of an edge of its block (OpenB4's awayFromEdge). */
    private static boolean b4AwayFromEdge(Vec3 hit, BlockPos pos) {
        double[] parts = {hit.xCoord - pos.getX(), hit.yCoord - pos.getY(), hit.zCoord - pos.getZ()};
        for (double f : parts) {
            if (f != 0.0 && f != 1.0 && (f <= 0.06 || f >= 0.94)) {
                return false;
            }
        }
        return true;
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

    private BlockData getBlockData() {
        int startY = MathHelper.floor_double(mc.thePlayer.posY);
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                (this.stage != 0 && !this.shouldKeepY ? Math.min(startY, this.startY) : startY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
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
                                && (this.stage == 0 || this.shouldKeepY || pos.getY() < this.startY)) {
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
    }

    public int getSlot() {
        return this.lastSlot;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (this.placedThisTick && this.rotationMode.getValue() == ROTATION_B4) {
                /* Last tick's click was made along the look then sent (this.yaw). */
                this.b4PlacedAt = System.currentTimeMillis();
                this.b4AimYaw = this.yaw;
                this.b4AimPitch = this.pitch;
            }
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
                        case ROTATION_GODBRIDGE: // God Bridge Mode
                        case ROTATION_POLAR: // Polar: LiquidBounce GodBridge technique
                            // 1. SNAP YAW TO NEAREST 45-DEGREE DIAGONAL
                            // This finds if you are facing 45, 135, -45, or -135 and locks you there perfectly.
                            float roundedYaw = Math.round(currentYaw / 45.0f) * 45.0f;
                            this.yaw = RotationUtil.quantizeAngle(roundedYaw);

                            // 2. SET THE GODBRIDGE PITCH
                            if (this.pitch == 0.0F || !this.canRotate) {
        /*
           75.6f to 79.5f is the "Golden Range" for Godbridging.
           - 75.6f is good for high CPS/Short Drag
           - 79.3f is common for "Telly" or standard Godbridge
        */
                                float godBridgePitch = 79.3f;
                                this.pitch = RotationUtil.quantizeAngle(godBridgePitch);
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
                if (this.rotationMode.getValue() == ROTATION_B4) {
                    this.b4Plan(event, blockData);
                    return;
                }

                boolean godBridge = this.rotationMode.getValue() == ROTATION_GODBRIDGE
                        || this.rotationMode.getValue() == ROTATION_POLAR;
                float[] godBridgeLook = godBridge && blockData != null ? this.godBridgeRotation(this.yaw) : null;
                this.updatePolarLedge(blockData, godBridgeLook);
                /* The aim is measured from the look already sent -- or, in
                   GodBridge, from the fixed look, so a correction stays as
                   close to it as the edge allows (S4c). */
                float refYaw = godBridgeLook != null ? godBridgeLook[0] : event.getYaw();
                float refPitch = godBridgeLook != null ? godBridgeLook[1] : event.getPitch();
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
                if (godBridgeLook != null) {
                    /* S4 (2026-10-07): LiquidBounce's GodBridge. The look is
                       fixed by the walking direction, not aimed at the block:
                       it barely moves from tick to tick, and the click comes
                       only when that look lands on a wanted face
                       (onUpdateClick, lookHit). Until now this mode re-aimed
                       at the block every tick -- 30-37 degree turns on the
                       click ticks in the 2026-10-07 Pika log. */
                    float[] fixed = godBridgeLook;
                    if (this.rotationMode.getValue() != ROTATION_POLAR
                            && aimDiff != Float.MAX_VALUE
                            && this.lookHit(blockData, fixed[0], fixed[1]) == null) {
                        /* S4d (2026-10-08): the fixed look misses the face from
                           here: aim at the point on it nearest that look
                           (this.yaw/pitch, from the face loop above, measured
                           from the fixed look) and place there. No turn limit
                           and no ledge jump any more -- the user wants the
                           block aimed at and placed, not a jump. */
                        fixed = new float[]{this.yaw, this.pitch};
                    }
                    this.yaw = fixed[0];
                    this.pitch = fixed[1];
                    this.canRotate = true;
                    if (hitVec == null) {
                        /* A placeholder only: onUpdateClick replaces it with
                           where the sent look lands, or clicks nothing. */
                        hitVec = new Vec3(blockData.blockPos()).addVector(0.5, 0.5, 0.5);
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
                    if (this.turnLimited() && !towerRotating && !godBridge && blockData != null
                            && this.lookHit(blockData, event.getYaw(), event.getPitch()) != null) {
                        /* S3: the look the server already has puts a block where
                           it is wanted -- keep it. No turn: the click is made with
                           the very look the server knows, and the aim does not
                           wander from block to block. */
                        targetYaw = event.getYaw();
                        targetPitch = event.getPitch();
                    } else if (this.turnLimited() && !towerRotating) {
                        /* From the look the server has, at most turn-speed a
                           tick (LiquidBounce: RotationsValueGroup). */
                        Set<RotationEngine.Feature> features = EnumSet.noneOf(RotationEngine.Feature.class);
                        if (this.humanize.getValue()) {
                            features.add(RotationEngine.Feature.NOISE);
                            features.add(RotationEngine.Feature.CURVE);
                        }
                        float speed = this.turnSpeed.getValue();
                        float[] stepped = this.engine.step(event.getYaw(), event.getPitch(), targetYaw, targetPitch,
                                speed, Math.max(10.0F, speed * 0.5F), 0, true, features);
                        targetYaw = stepped[0];
                        targetPitch = stepped[1];
                    }
                    float[] onGrid = RotationEngine.quantize(event.getYaw(), event.getPitch(), targetYaw, targetPitch);
                    targetYaw = onGrid[0];
                    targetPitch = onGrid[1];
                    float turn = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - event.getYaw()));
                    for (int guard = 0; guard < 3 && turn > 2.0F && PlaceRotations.wouldDuplicate(targetYaw); guard++) {
                        /* Grim DuplicateRotPlace: the same yaw step as at the
                           last judged placement. One mouse count more breaks it. */
                        double gcd = RotationUtil.gcd();
                        targetYaw += Math.signum(MathHelper.wrapAngleTo180_float(targetYaw - event.getYaw()))
                                * (float) (gcd > 0.0 ? gcd : 0.01);
                        turn = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - event.getYaw()));
                    }
                    placeYaw = targetYaw;
                    placePitch = targetPitch;
                    event.setRotation(targetYaw, targetPitch, 3);
                    if (this.moveFix.getValue() != 0) {
                        event.setPervRotation(targetYaw, 3);
                    }
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
        float sentYaw = event.getNewYaw();
        float sentPitch = event.getNewPitch();
        MovingObjectPosition along = null;
        if (this.rotationMode.getValue() != 0 && blockData != null && hitVec != null) {
            along = this.lookHit(blockData, sentYaw, sentPitch);
            hitVec = along == null ? null : along.hitVec;
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
            this.quietThreeFmcMovement();
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 3.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
            if (mc.thePlayer.onGround && this.stage > 0 && MoveUtil.isForwardPressed()) {
                mc.thePlayer.movementInput.jump = true;
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

    /** GodBridge: which side of the block row the player walks on. */
    private boolean godBridgeRightSide;

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
            float axis = (float) Math.floor(aimYaw / 90.0F) * 90.0F;
            return new float[]{axis + 45.0F, 75.0F};
        }
        float movingYaw = Math.round((this.getCurrentYaw() + 180.0F) / 45.0F) * 45.0F;
        if (movingYaw % 90.0F != 0.0F) {
            return new float[]{movingYaw, 75.6F};
        }
        if (mc.thePlayer.onGround) {
            double x = mc.thePlayer.posX;
            double y = mc.thePlayer.posY;
            double z = mc.thePlayer.posZ;
            double rad = Math.toRadians(movingYaw);
            this.godBridgeRightSide = Math.floor(x + Math.cos(rad) * 0.5) != Math.floor(x)
                    || Math.floor(z + Math.sin(rad) * 0.5) != Math.floor(z);
            EnumFacing toward = EnumFacing.fromAngle(movingYaw);
            BlockPos ahead = new BlockPos(x + toward.getFrontOffsetX() * 0.6, y, z + toward.getFrontOffsetZ() * 0.6);
            if (mc.theWorld.isAirBlock(new BlockPos(x, y, z).down()) && mc.theWorld.isAirBlock(ahead.down())) {
                this.godBridgeRightSide = !this.godBridgeRightSide;
            }
        }
        return new float[]{movingYaw + (this.godBridgeRightSide ? 45.0F : -45.0F), 75.7F};
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
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().expand(-0.2, 0.0, -0.2)
                .offset(mc.thePlayer.motionX, -1.0, mc.thePlayer.motionZ);
        if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty()) {
            this.edgeSneakUntil = now + 100L + (long) (Math.random() * 100.0);
            return true;
        }
        return now < this.edgeSneakUntil;
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
        if (this.rotationMode.getValue() != ROTATION_POLAR) {
            this.polarLedgeSneakTicks = 0;
            this.polarLedgeStopInput = false;
            this.polarLedgeStepBack = false;
            this.polarLedgeJump = false;
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
        switch (polarLedgeAction(onGround, forward, hasTarget, onEdge, canPlaceFromLook,
                this.blockCount, this.polarForceSneakBelowCount.getValue(),
                this.polarModes.getValue(), this.jumpBoostAmplifier())) {
            case POLAR_SNEAK:
                this.polarLedgeSneakTicks = this.polarSneakTime.getValue();
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
        if (this.rotationMode.getValue() != ROTATION_POLAR) {
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

    @EventTarget
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
        this.b4AimYaw = Float.NaN;
        this.b4PlacedAt = 0L;
        this.b4RestPitch = RandomUtil.nextFloat(84.5F, 87.5F);
        this.b4YawOffset = RandomUtil.nextFloat(-0.5F, 0.5F);
        this.b4Script = 0;
        this.b4Ticks = 0;
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && this.lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
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
