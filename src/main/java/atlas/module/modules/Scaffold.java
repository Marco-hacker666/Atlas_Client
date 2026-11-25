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
import atlas.sim.PlayerSim;
import atlas.sim.SimPlayer;
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
    private static final int ROTATION_TELLY = 11;
    private static final int ROTATION_SPEED_TELLY = 12;
    private static final int ROTATION_WEIRD_TELLY = 13;
    private static final int ROTATION_GODBRIDGE2 = 14;
    private static final int ROTATION_MOONWALK = 15;

    private static final int ROTATION_SNAP_SPEED = 16;

    private int snapSide = 0;

    private int snapBackSide = 1;

    private float snapLastYaw = Float.NaN;

    private final SnapBridge.YawWindow snapYaw = new SnapBridge.YawWindow();

    private boolean snapWasGround = true;

    private boolean snapAligned = true;
    private java.io.File snapTraceFile;

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
    public final ModeProperty rotationMode = new ModeProperty("rotations", 2, new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS", "GODBRIDGE", "SMOOTH", "Hypixel", "SNAP", "3FMC", "SNAP2", "POLAR", "TELLY", "SPEED-TELLY", "WEIRD-TELLY", "GODBRIDGE2", "MOONWALK", "SNAP-SPEED"})

            .retire("GODBRIDGE", "SIDEWAYS", "3FMC", "POLAR")

            .alias("B4", "SPEED-TELLY")

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
    public final ModeProperty tower = new ModeProperty("tower", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY", "PULLDOWN"});
    public final FloatProperty pulldownTrigger = new FloatProperty("tower-pulldown-trigger", 0.1F, 0.0F, 0.2F,
            () -> this.tower.getValue() == 4);
    public final BooleanProperty hypixeltower = new BooleanProperty("hypixeltower", false, () -> this.tower.getValue() == 3);
    public final BooleanProperty safe = new BooleanProperty("safe", false, () -> this.tower.getValue() == 3);
    public final IntProperty safeStuckDelayTicksProperty = new IntProperty("safe-delay-ticks", 1, 1, 3, () -> this.tower.getValue() == 3 && this.safe.getValue());
    public final ModeProperty keepY = new ModeProperty("keep-y", 0, new String[]{"NONE", "VANILLA", "EXTRA", "TELLY", "EXTRATELLY"});
    public final BooleanProperty keepYonPress = new BooleanProperty("keep-y-on-press", false, () -> this.keepY.getValue() != 0);
    public final BooleanProperty disableWhileJumpActive = new BooleanProperty("no-keep-y-on-jump-potion", false, () -> this.keepY.getValue() != 0);

    public final BooleanProperty multiplace = new BooleanProperty("multi-place", false);
    public final ModeProperty placement = new ModeProperty("placement", 0, new String[]{"LINE", "CLASSIC"});
    public final IntProperty linePredict = new IntProperty("line-predict", 1, 0, 3, this::lineOn);
    public final IntProperty expand = new IntProperty("expand", 0, 0, 6, this::lineOn);
    public final BooleanProperty down = new BooleanProperty("down", false, this::lineOn);
    public final BooleanProperty stabilize = new BooleanProperty("stabilize", false, this::lineOn);
    private final java.util.ArrayDeque<BlockPos> recentPlaced = new java.util.ArrayDeque<BlockPos>();
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

    private final IntProperty retiredPolarSneakBelow = new IntProperty("polar-force-sneak-below-count", 5, 0, 20, () -> false);
    private final IntProperty retiredPolarSneakTime = new IntProperty("polar-sneak-time", 1, 1, 10, () -> false);
    private final ModeProperty retiredPolarModes = new ModeProperty("polar-modes", 0,
            new String[]{"Jump", "Sneak", "StopInput", "Backwards"}, () -> false);

    private final IntProperty jumpEveryBlocks = new IntProperty("jump-every-blocks", 7, 0, 20, this::godBridgeShown);

    private final BooleanProperty godBridgeTrace = new BooleanProperty("godbridge-trace", true, this::isRunMode);
    private java.io.File godBridgeTraceFile;
    private int lastPlacedTick = -1000;

    public final BooleanProperty legitPace = new BooleanProperty("legit-pace", true, this::godBridgeShown);

    public final BooleanProperty jumpBuildsUp = new BooleanProperty("jump-builds-up", true, this::isRunMode);

    public final BooleanProperty edgeStop = new BooleanProperty("edge-stop", true, this::isRunMode);
    private final PlacePace pace = new PlacePace();
    private boolean paceWasOnGround;
    private final java.util.Random paceRandom = new java.util.Random();
    public final BooleanProperty espOutline = new BooleanProperty("outline-esp", false);
    public final ModeProperty espColor = new ModeProperty("outline-color", 0, new String[]{"Default", "HUD"}, () -> this.espOutline.getValue());
    private final Map<BlockPos, Long> espHighlight = new HashMap<>();

    private static final int[] TURNING_MODES = {1, 2, 3, 4, 5, 6, ROTATION_MOONWALK};
    public final IntProperty turnSpeed = new IntProperty("turn-speed", 80, 10, 180, this::turnLimited);
    public final BooleanProperty steadyYaw = new BooleanProperty("steady-yaw", false, this::steadyYawShown);
    public final IntProperty maxPlaceTurn = new IntProperty("max-place-turn", 0, 0, 90,
            () -> this.rotationMode.getValue() != 0);
    private float lastSentYaw = Float.NaN;
    private float lastSentPitch = Float.NaN;

    public final BooleanProperty humanize = new BooleanProperty("humanize", true, () -> false);
    private final atlas.humanize.Humanizer human = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_SCAFFOLD);

    public final FloatProperty rotationRandom = new FloatProperty("rotation-random", 2.5F, 0.0F, 8.0F,
            () -> this.rotationMode.getValue() != 0);
    private float wanderYaw;
    private float wanderPitch;
    private float wanderGoalYaw;
    private float wanderGoalPitch;
    private int wanderTicks;
    public final BooleanProperty pauseOnCorrection = new BooleanProperty("pause-on-correction", true);

    public final BooleanProperty tellyAutoJump = new BooleanProperty("telly-auto-jump", true, this::isSpeedTellyMode);

    public final BooleanProperty tellyLateTurn = new BooleanProperty("telly-late-turn", true, this::isSpeedTellyMode);

    public final IntProperty tellyJumpAngle = new IntProperty("telly-jump-offset", 8, 0, 30, this::isWeirdTellyMode);

    public final IntProperty tellyTurnSpeed = new IntProperty("telly-turn-speed", 135, 30, 180,
            () -> this.rotationMode.getValue() == ROTATION_TELLY);

    public final ModeProperty snapMove = new ModeProperty("snap-move", SnapBridge.JUMP, new String[]{"Jump", "Ground"},
            () -> this.rotationMode.getValue() == ROTATION_SNAP_SPEED);

    public final ModeProperty snapTakeOff = new ModeProperty("snap-takeoff", SnapBridge.TAKEOFF_FORWARD,
            new String[]{"Forward", "Diagonal"},
            () -> this.rotationMode.getValue() == ROTATION_SNAP_SPEED && this.snapMove.getValue() == SnapBridge.JUMP);

    public final IntProperty snapSmooth = new IntProperty("snap-smooth", 45, 15, 180,
            () -> this.rotationMode.getValue() == ROTATION_SNAP_SPEED);

    public final BooleanProperty snapTrace = new BooleanProperty("snap-trace", true,
            () -> this.rotationMode.getValue() == ROTATION_SNAP_SPEED);

    private final IntProperty gb2JumpAfterMin = new IntProperty("gb2-jump-after-min", 8, 1, 30, this::isGodBridge2Mode);
    private final IntProperty gb2JumpAfterMax = new IntProperty("gb2-jump-after-max", 8, 1, 30, this::isGodBridge2Mode);
    private final IntProperty gb2JumpDelayMin = new IntProperty("gb2-jump-delay-min", 0, 0, 10, this::isGodBridge2Mode);
    private final IntProperty gb2JumpDelayMax = new IntProperty("gb2-jump-delay-max", 0, 0, 10, this::isGodBridge2Mode);
    private final IntProperty gb2ResetAfterMin = new IntProperty("gb2-reset-after-jumps-min", 7, 1, 30, this::isGodBridge2Mode);
    private final IntProperty gb2ResetAfterMax = new IntProperty("gb2-reset-after-jumps-max", 8, 1, 30, this::isGodBridge2Mode);
    private final IntProperty gb2StartDelayMin = new IntProperty("gb2-start-delay-min", 10, 0, 40, this::isGodBridge2Mode);
    private final IntProperty gb2StartDelayMax = new IntProperty("gb2-start-delay-max", 13, 0, 40, this::isGodBridge2Mode);
    private final BooleanProperty gb2StartCrouch = new BooleanProperty("gb2-start-crouch", true, this::isGodBridge2Mode);
    private final GodBridgeRhythm rhythm = new GodBridgeRhythm();

    private int lastRotationMode = -1;

    private Object lastPlayer;

    private final FloatProperty moonWidthMin = new FloatProperty("moonwalk-width-min", 0.05F, 0.0F, 0.2F, this::isMoonWalkMode);
    private final FloatProperty moonWidthMax = new FloatProperty("moonwalk-width-max", 0.1F, 0.0F, 0.2F, this::isMoonWalkMode);
    private final FloatProperty moonMiddleMin = new FloatProperty("moonwalk-middle-min", 0.5F, 0.3F, 0.7F, this::isMoonWalkMode);
    private final FloatProperty moonMiddleMax = new FloatProperty("moonwalk-middle-max", 0.5F, 0.3F, 0.7F, this::isMoonWalkMode);
    private final IntProperty moonRedrawMin = new IntProperty("moonwalk-redraw-min", 15, 1, 40, this::isMoonWalkMode);
    private final IntProperty moonRedrawMax = new IntProperty("moonwalk-redraw-max", 30, 1, 40, this::isMoonWalkMode);
    private final MoonWalk moonWalk = new MoonWalk();
    private int tick = 0;
    private int pauseTicks = 0;
    private volatile boolean correctionPending = false;
    private final Map<BlockPos, Integer> placedAt = new ConcurrentHashMap<BlockPos, Integer>();
    private final ConcurrentLinkedQueue<BlockPos> refused = new ConcurrentLinkedQueue<BlockPos>();
    private final Map<String, Integer> failedFaces = new HashMap<String, Integer>();
    private int refusalsAt = -1000;
    private int recentRefusals = 0;

    private boolean planReady = false;
    private BlockData planData = null;
    private Vec3 planHitVec = null;
    private boolean planSnapCanPlace = true;
    private boolean planSnapMode = false;
    private boolean planThreeFmc = false;

    private boolean shouldStopSprint() {
        if (this.isSnapSpeedMode()) {
            return false;
        }
        if (this.isThreeFmcMode() && !this.isThreeFmcTellyMode()) {
            return true;
        }
        if (this.isTowering()) {
            return false;
        } else {
            boolean stage = this.keepY.getValue() == 1 || this.keepY.getValue() == 2 || this.keepY.getValue() == 4
                    || this.rotationMode.getValue() == ROTATION_TELLY;
            return (!stage || this.stage <= 0) && this.sprintMode.getValue() == 0;
        }
    }

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

    /** The modes that hold the bridge yaw (or its diagonal) and look for the block with the pitch. */
    private boolean steadyYawShown() {
        int mode = this.rotationMode.getValue();
        return mode == 1 || mode == 2 || mode == 3 || mode == 5 || mode == 6;
    }

    private boolean steadyYawOn() {
        return this.steadyYaw.getValue() && this.steadyYawShown();
    }

    /**
     * Keeps the bridge yaw and finds the pitch that lands on the planned face: the stretch of pitches that hit
     * it nearest to the current pitch, and inside that stretch the current pitch if it still hits (else the
     * nearest one a little inside the stretch, so the sent, grid-snapped look still hits). Null when no pitch
     * at that yaw reaches the face.
     *
     * @return {yaw, pitch} or null
     */
    private float[] steadyAim(BlockData plan, float yaw, float currentPitch) {
        float bestLo = Float.NaN;
        float bestHi = Float.NaN;
        float bestGap = Float.MAX_VALUE;
        float runLo = Float.NaN;
        for (int i = 0; i <= 120; i++) {
            float p = 30.0F + i * 0.5F;
            boolean hits = p < 90.0F && this.lookHit(plan, yaw, p) != null;
            if (hits && Float.isNaN(runLo)) {
                runLo = p;
            }
            if (!hits && !Float.isNaN(runLo)) {
                float runHi = p - 0.5F;
                float gap = currentPitch < runLo ? runLo - currentPitch : currentPitch > runHi ? currentPitch - runHi : 0.0F;
                if (gap < bestGap) {
                    bestGap = gap;
                    bestLo = runLo;
                    bestHi = runHi;
                }
                runLo = Float.NaN;
            }
        }
        if (Float.isNaN(bestLo)) {
            return null;
        }
        for (int i = 0; i < 9 && bestLo - 0.05F >= 30.0F && this.lookHit(plan, yaw, bestLo - 0.05F) != null; i++) {
            bestLo -= 0.05F;
        }
        for (int i = 0; i < 9 && bestHi + 0.05F < 90.0F && this.lookHit(plan, yaw, bestHi + 0.05F) != null; i++) {
            bestHi += 0.05F;
        }
        float margin = Math.min(0.3F, (bestHi - bestLo) / 2.0F);
        float pitch = Math.max(bestLo + margin, Math.min(bestHi - margin, currentPitch));
        if (this.lookHit(plan, yaw, pitch) == null) {
            pitch = (bestLo + bestHi) / 2.0F;
            if (this.lookHit(plan, yaw, pitch) == null) {
                return null;
            }
        }
        return new float[]{yaw, pitch};
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

    private MovingObjectPosition lookHit(BlockData plan, float yaw, float pitch) {
        return this.lookHit(plan, yaw, pitch, 0.0, 0.0);
    }

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
        if (this.lineOn() || cell.getY() != planned.getY() || !BlockUtil.isReplaceable(cell)) {
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
                if (pos.getY() <= blockPos3.getY() && BlockUtil.isReplaceable(pos)) {
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

    private boolean godBridgeHoldsLayer() {
        if (this.isRunMode() && this.jumpBuildsUp.getValue()) {
            return false;
        }
        return (this.lbGodBridge() || this.isGodBridge2Mode()) && !mc.thePlayer.onGround
                && !mc.gameSettings.keyBindJump.isKeyDown();
    }

    private BlockData getBlockData() {
        return this.getBlockData(0.0, 0.0);
    }

    private boolean solidUnderFeet() {
        BlockPos under = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1.0E-3, mc.thePlayer.posZ).down();
        return !BlockUtil.isReplaceable(under);
    }

    private boolean lineOn() {
        return this.placement.getValue() == 0 && !this.isRunMode();
    }

    private boolean goingDown() {
        return this.lineOn() && this.down.getValue() && mc.gameSettings.keyBindSneak.isKeyDown()
                && !mc.gameSettings.keyBindJump.isKeyDown();
    }

    private BlockData getBlockData(double aheadX, double aheadZ) {
        if (this.lineOn()) {
            return this.lineBlockData(aheadX, aheadZ);
        }
        return this.classicBlockData(aheadX, aheadZ);
    }

    private LinePlacement.Line walkingLine() {
        int[] last = null;
        int[] previous = null;
        java.util.Iterator<BlockPos> it = this.recentPlaced.descendingIterator();
        if (it.hasNext()) {
            BlockPos a = it.next();
            last = new int[]{a.getX(), a.getY(), a.getZ()};
            if (it.hasNext()) {
                BlockPos b = it.next();
                previous = new int[]{b.getX(), b.getY(), b.getZ()};
            }
        }
        return LinePlacement.walkingLine(mc.thePlayer.posX, mc.thePlayer.posZ, this.getCurrentYaw(),
                MoveUtil.isForwardPressed(), last, previous);
    }

    private BlockData lineBlockData(double aheadX, double aheadZ) {
        int predict = this.linePredict.getValue();
        double px = mc.thePlayer.posX + mc.thePlayer.motionX * predict + aheadX;
        double pz = mc.thePlayer.posZ + mc.thePlayer.motionZ * predict + aheadZ;
        int feetY = MathHelper.floor_double(mc.thePlayer.posY);
        boolean down = this.goingDown();
        boolean holdLayer = (this.stage != 0 || this.godBridgeHoldsLayer()) && !this.shouldKeepY;
        int ty = down ? feetY - 2 : (holdLayer ? Math.min(feetY, this.startY) : feetY) - 1;
        BlockPos target = new BlockPos(MathHelper.floor_double(px), ty, MathHelper.floor_double(pz));
        LinePlacement.Line line = this.walkingLine();
        if (!BlockUtil.isReplaceable(target)) {
            int reach = this.expand.getValue();
            if (reach <= 0 || down || !MoveUtil.isForwardPressed()) {
                return null;
            }
            double r = Math.toRadians(this.getCurrentYaw());
            for (int i = 1; i <= reach; i++) {
                BlockPos ahead = new BlockPos(MathHelper.floor_double(px - Math.sin(r) * i), ty,
                        MathHelper.floor_double(pz + Math.cos(r) * i));
                if (!BlockUtil.isReplaceable(ahead)) {
                    continue;
                }
                BlockData data = this.bestFace(ahead, false);
                if (data != null) {
                    return data;
                }
            }
            return null;
        }
        for (int[] c : LinePlacement.candidates(target.getX(), target.getY(), target.getZ(), line)) {
            BlockPos cell = new BlockPos(c[0], c[1], c[2]);
            if (!BlockUtil.isReplaceable(cell)) {
                continue;
            }
            BlockData data = this.bestFace(cell, down);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    private BlockData bestFace(BlockPos cell, boolean awayFacesToo) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double reach = mc.playerController.getBlockReachDistance();
        BlockData best = null;
        double bestFacing = Double.NEGATIVE_INFINITY;
        for (EnumFacing side : EnumFacing.VALUES) {
            BlockPos support = cell.offset(side.getOpposite());
            if (BlockUtil.isReplaceable(support) || BlockUtil.isInteractable(support)
                    || !eyeReaches(eyes, support, reach)) {
                continue;
            }
            Vec3i n = side.getDirectionVec();
            double fx = support.getX() + 0.5 + n.getX() * 0.5;
            double fy = support.getY() + 0.5 + n.getY() * 0.5;
            double fz = support.getZ() + 0.5 + n.getZ() * 0.5;
            double facing = LinePlacement.facing(eyes.xCoord, eyes.yCoord, eyes.zCoord, fx, fy, fz,
                    n.getX(), n.getY(), n.getZ());
            if (!awayFacesToo && facing < 0.0) {
                continue;
            }
            if (facing > bestFacing) {
                bestFacing = facing;
                best = new BlockData(support, side);
            }
        }
        return best;
    }

    private BlockData classicBlockData(double aheadX, double aheadZ) {
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
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            double reach = mc.playerController.getBlockReachDistance();
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 0; y++) {
                    for (int z = -4; z <= 4; z++) {
                        BlockPos pos = targetPos.add(x, y, z);
                        if (!BlockUtil.isReplaceable(pos)
                                && !BlockUtil.isInteractable(pos)
                                && eyeReaches(eyes, pos, reach)
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

    static boolean eyeReaches(Vec3 eyes, BlockPos pos, double reach) {
        double dx = Math.max(pos.getX() - eyes.xCoord, Math.max(0.0, eyes.xCoord - (pos.getX() + 1)));
        double dy = Math.max(pos.getY() - eyes.yCoord, Math.max(0.0, eyes.yCoord - (pos.getY() + 1)));
        double dz = Math.max(pos.getZ() - eyes.zCoord, Math.max(0.0, eyes.zCoord - (pos.getZ() + 1)));
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (!this.canThreeFmcPlaceNow()) {
            return;
        }
        if (this.pauseTicks > 0 || this.faceFailed(blockPos, enumFacing)) {
            return;
        }
        if (ItemUtil.isHoldingBlock() && this.blockCount > 0) {
            ItemStack held = mc.thePlayer.inventory.getCurrentItem();

            if (held == null || !(held.getItem() instanceof ItemBlock)) {
                return;
            }
            if (!((ItemBlock) held.getItem()).canPlaceBlockOnSide(mc.theWorld, blockPos, enumFacing, mc.thePlayer, held)) {
                this.failedFaces.put(faceKey(blockPos, enumFacing), this.tick + 10);
                return;
            }
            if (!mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, blockPos, enumFacing, vec3)) {
                this.failedFaces.put(faceKey(blockPos, enumFacing), this.tick + 10);
                return;
            }
            {
                this.placedAt.put(blockPos.offset(enumFacing), this.tick);
                this.recentPlaced.addLast(blockPos.offset(enumFacing));
                while (this.recentPlaced.size() > 4) {
                    this.recentPlaced.removeFirst();
                }
                if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
                    this.blockCount--;
                }
                this.placedThisTick = true;
                this.lastPlacedTick = this.tick;
                this.walkOnSince = -1;
                if (this.isThreeFmcMode()) {
                    this.threeFmcPlaceCooldown = 1;
                }
                this.markPlaced(blockPos.offset(enumFacing));
                this.eagleBlocksPlaced++;
                if (this.isRunMode()) {
                    this.run.placed();
                }
                if (this.isGodBridge2Mode()) {
                    this.rhythm.placed();
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

        this.pauseWhen(this::isSpeedTellyMode);
    }

    private boolean isSnapSpeedMode() {
        return this.rotationMode.getValue() == ROTATION_SNAP_SPEED;
    }

    private boolean isSpeedTellyMode() {
        return this.rotationMode.getValue() == ROTATION_SPEED_TELLY || isWeirdTellyMode();
    }

    private boolean godBridgeShown() {
        int mode = this.rotationMode.getValue();
        return mode == ROTATION_GODBRIDGE;
    }

    private boolean isRunMode() {
        int mode = this.rotationMode.getValue();
        return mode == ROTATION_GODBRIDGE || mode == ROTATION_GODBRIDGE2;
    }

    private boolean isGodBridge2Mode() {
        return this.rotationMode.getValue() == ROTATION_GODBRIDGE2;
    }

    private boolean isMoonWalkMode() {
        return this.rotationMode.getValue() == ROTATION_MOONWALK;
    }

    private boolean paceOn() {
        return this.legitPace.getValue() && this.lbGodBridge();
    }

    private boolean isWeirdTellyMode() {
        return this.rotationMode.getValue() == ROTATION_WEIRD_TELLY;
    }

    public boolean isWeirdTelly() {
        return this.isEnabled() && this.isWeirdTellyMode();
    }

    public boolean isSpeedTelly() {
        return this.isEnabled() && this.isSpeedTellyMode();
    }

    public boolean isSpeedTellyKeepY() {
        return this.isSpeedTelly() && this.keepY.getValue() != 0
                && (!this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                && (!this.disableWhileJumpActive.getValue() || !mc.thePlayer.isPotionActive(Potion.jump));
    }

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
        if ("rotations".equals(name)) {
            this.noteRotationMode();
        }
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
                        && (this.keepY.getValue() != 0 || this.rotationMode.getValue() == ROTATION_TELLY
                        || this.isSnapSpeedMode() && SnapBridge.autoJump(this.snapMove.getValue(), true))
                        && (!(Boolean) this.keepYonPress.getValue() || PlayerUtil.isUsingItem())
                        && (!this.disableWhileJumpActive.getValue() || !mc.thePlayer.isPotionActive(Potion.jump))
                        && !mc.gameSettings.keyBindJump.isKeyDown()) {
                    this.stage = 1;
                }
                this.startY = this.shouldKeepY ? this.startY : MathHelper.floor_double(mc.thePlayer.posY);
                this.shouldKeepY = false;
                this.towering = false;
            }

            this.runPlan = null;
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
                if (this.isRunMode()) {
                    this.godBridgeRunTick(event);
                    return;
                }
                if (this.rotationMode.getValue() == ROTATION_TELLY) {
                    this.tellyTick(event);
                    return;
                }
                float currentYaw = this.getCurrentYaw();
                float yawDiffTo180 = RotationUtil.wrapAngleDiff(currentYaw - 180.0F, event.getYaw());
                float diagonalYaw = this.isDiagonal(currentYaw)
                        ? yawDiffTo180
                        : RotationUtil.wrapAngleDiff(currentYaw - 135.0F * ((currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), event.getYaw());
                boolean snapMode = this.rotationMode.getValue() == ROTATION_SNAP || this.rotationMode.getValue() == ROTATION_SNAP2
                        || this.isSnapSpeedMode();
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
                        case ROTATION_MOONWALK:
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

                            if (this.yaw == -180.0F && this.pitch == 0.0F) {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                                this.pitch = RotationUtil.quantizeAngle(85.0F);
                            } else {
                                this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                            }
                            break;
                        case ROTATION_SNAP:
                        case ROTATION_SNAP2:
                        case ROTATION_SNAP_SPEED:
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

                float refYaw = event.getYaw();
                float refPitch = event.getPitch();
                if (this.isSnapSpeedMode()) {
                    if (this.snapSide == 0) {
                        this.snapSide = this.paceRandom.nextBoolean() ? 1 : -1;
                        this.snapBackSide = this.paceRandom.nextBoolean() ? 1 : -1;
                    }
                    refYaw = RotationUtil.wrapAngleDiff(SnapBridge.backDiagonal(currentYaw, this.snapBackSide),
                            event.getYaw());
                }
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
                    if (this.steadyYawOn() && !snapMode && !threeFmcMode) {
                        float holdYaw = RotationUtil.quantizeAngle(this.rotationMode.getValue() == 2 ? yawDiffTo180 : diagonalYaw);
                        float[] steady = this.steadyAim(blockData, holdYaw, event.getPitch());
                        if (steady != null) {
                            MovingObjectPosition steadyHit = this.lookHit(blockData, steady[0], steady[1]);
                            this.yaw = steady[0];
                            this.pitch = steady[1];
                            this.canRotate = true;
                            hitVec = steadyHit.hitVec;
                            aimDiff = Math.abs(MathHelper.wrapAngleTo180_float(steady[0] - refYaw)) + Math.abs(steady[1] - refPitch);
                        }
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
                        case ROTATION_MOONWALK:
                            this.yaw = RotationUtil.quantizeAngle(yawDiffTo180);
                            break;
                        case 3:
                            this.yaw = RotationUtil.quantizeAngle(diagonalYaw);
                    }
                }
                float placeYaw = this.yaw;
                float placePitch = this.pitch;
                if (this.rotationMode.getValue() != 0 && (!snapMode || this.snapRotating || towerRotating
                        || this.isSnapSpeedMode())) {
                    float targetYaw = this.yaw;
                    float targetPitch = this.pitch;
                    if (this.rotationRandom.getValue() > 0.0F && blockData != null && !towerRotating) {
                        float[] wandered = this.wander(targetYaw, targetPitch);
                        if (this.steadyYawOn()) {
                            wandered[0] = targetYaw;
                        }
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

                    if (this.turnLimited() && !towerRotating && blockData != null
                            && this.lookHit(blockData, event.getYaw(), event.getPitch()) != null) {
                        targetYaw = event.getYaw();
                        targetPitch = event.getPitch();
                    }

                    float cap = this.turnLimited() && !towerRotating ? this.turnSpeed.getValue() : RotationHelper.NO_LIMIT;
                    if (!snapMode) {
                        float[] aimed = atlas.humanize.HumanTurn.apply(this.human, event.getYaw(), event.getPitch(),
                                targetYaw, targetPitch);
                        if (blockData == null || this.lookHit(blockData, aimed[0], aimed[1]) != null) {
                            targetYaw = aimed[0];
                            targetPitch = aimed[1];
                        }
                    }
                    int moveYaw = this.moveMode();
                    float minStep = snapMode ? SNAP_STEP : MIN_STEP;
                    float walkYaw = currentYaw;
                    boolean takeOff = false;
                    if (this.isSnapSpeedMode() && !towerRotating) {
                        moveYaw = RotationHelper.MOVE_FIX;
                        boolean onGround = mc.thePlayer.onGround;

                        float lastStep = Float.isNaN(this.snapLastYaw) ? 0.0F
                                : Math.abs(MathHelper.wrapAngleTo180_float(event.getYaw() - this.snapLastYaw));
                        this.snapLastYaw = event.getYaw();
                        this.snapYaw.add(lastStep);
                        if (onGround && !this.snapWasGround) {
                            this.snapSide = -this.snapSide;
                        }
                        this.snapWasGround = onGround;
                        boolean walking = MoveUtil.isForwardPressed();
                        takeOff = SnapBridge.forward(this.snapMove.getValue(), onGround, walking);

                        boolean preTurn = !onGround && !this.snapRotating
                                && SnapBridge.autoJump(this.snapMove.getValue(), walking)
                                && SnapBridge.landingSoon(mc.thePlayer.posY, mc.thePlayer.motionY, this.startY,
                                SnapBridge.PRE_TURN_TICKS);
                        float ahead = SnapBridge.takeOffYaw(this.snapTakeOff.getValue(), walkYaw, this.snapSide);
                        if (takeOff || preTurn || onGround && !this.snapRotating) {
                            targetYaw = RotationUtil.wrapAngleDiff(ahead, event.getYaw());
                            targetPitch = mc.thePlayer.rotationPitch;
                        } else if (!this.snapRotating) {
                            float back = RotationUtil.wrapAngleDiff(SnapBridge.backDiagonal(walkYaw, this.snapBackSide),
                                    event.getYaw());
                            if (Math.abs(MathHelper.wrapAngleTo180_float(back - event.getYaw())) > SnapBridge.HOLD_SLACK) {
                                targetYaw = back;
                                targetPitch = SnapBridge.HOLD_PITCH;
                            } else {
                                targetYaw = event.getYaw();
                                targetPitch = event.getPitch();
                            }
                        }
                        float turn = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - event.getYaw()));
                        float limit;
                        if (takeOff) {
                            limit = RotationHelper.NO_LIMIT;
                        } else if (preTurn) {
                            limit = SnapBridge.PRE_TURN_STEP;
                        } else {
                            limit = SnapBridge.smoothCap(turn, this.snapSmooth.getValue());
                        }

                        cap = limit;
                        minStep = takeOff ? SNAP_STEP : limit >= SnapBridge.BIG_TURN_STEP ? limit : RotationHelper.ACCEL;
                    }

                    float[] onGrid = RotationHelper.request(this, targetYaw, targetPitch, 3, moveYaw, false, cap,
                            minStep);
                    if (this.isSnapSpeedMode()) {
                        float[] sent = onGrid != null ? onGrid : new float[]{event.getYaw(), event.getPitch()};
                        this.snapAligned = SnapBridge.aligned(sent[0], walkYaw);
                        this.snapSpeedTrace(blockData, targetYaw, targetPitch, sent, takeOff, cap);
                    }
                    if (onGrid == null) {
                        onGrid = RotationEngine.quantize(event.getYaw(), event.getPitch(), targetYaw, targetPitch);
                    }
                    targetYaw = onGrid[0];
                    targetPitch = onGrid[1];
                    placeYaw = targetYaw;
                    placePitch = targetPitch;
                    if (this.moveFix.getModeString().equals("REAL")) {
                        Atlas.rotationManager.setRotation(targetYaw, targetPitch, 3, false);
                    }
                }

                this.planData = blockData;
                this.planHitVec = hitVec;
                this.planSnapCanPlace = snapCanPlace;
                this.planSnapMode = snapMode;
                this.planThreeFmc = threeFmcMode;
                this.planReady = true;
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdateClick(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        float[] sentNow = RotationHelper.packetLook(event);
        float turned = Float.isNaN(this.lastSentYaw) ? 0.0F
                : Math.max(Math.abs(MathHelper.wrapAngleTo180_float(sentNow[0] - this.lastSentYaw)),
                Math.abs(sentNow[1] - this.lastSentPitch));
        this.lastSentYaw = sentNow[0];
        this.lastSentPitch = sentNow[1];
        if (!this.planReady) {
            return;
        }
        this.planReady = false;
        BlockData blockData = this.planData;
        Vec3 hitVec = this.planHitVec;
        boolean snapCanPlace = this.planSnapCanPlace;
        boolean snapMode = this.planSnapMode;
        boolean threeFmcMode = this.planThreeFmc;

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
            hitVec = null;
        }
        // the look turned too far this tick: wait for the next one (a turn and a place in one tick stand out)
        if (hitVec != null && this.maxPlaceTurn.getValue() > 0 && turned > this.maxPlaceTurn.getValue()) {
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

                    MovingObjectPosition mop = this.rotationMode.getValue() != 0
                            ? this.getPlacementMop(blockData, sentYaw, sentPitch)
                            : RotationUtil.rayTrace(this.yaw, this.pitch, mc.playerController.getBlockReachDistance(), 1.0F);
                    if (mop != null
                            && mop.typeOfHit == MovingObjectType.BLOCK
                            && mop.getBlockPos().equals(blockData.blockPos())
                            && mop.sideHit == blockData.facing()) {
                        this.place(blockData.blockPos(), blockData.facing(), mop.hitVec);
                    } else {
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
                    case 4:
                        this.towerTick = 0;
                        this.towerDelay = 0;
                        if (!mc.thePlayer.onGround && mc.thePlayer.motionY > -0.2
                                && mc.thePlayer.motionY < this.pulldownTrigger.getValue() && this.solidUnderFeet()) {
                            mc.thePlayer.motionY = -1.0;
                        }
                        return;
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
            this.noteRotationMode();
            if (this.safeStuckTicks > 0) {
                mc.thePlayer.movementInput.moveForward = 0.0f;
                mc.thePlayer.movementInput.moveStrafe = 0.0f;
                mc.thePlayer.movementInput.jump = false;
                mc.thePlayer.movementInput.sneak = false;
                return;
            }
            this.applyGodBridgeInput();
            this.quietThreeFmcMovement();

            if (mc.thePlayer.onGround && this.stage > 0 && !this.goingDown() && MoveUtil.isForwardPressed()
                    && (!this.isSnapSpeedMode() || this.snapAligned)) {
                mc.thePlayer.movementInput.jump = true;
            }
            this.applyMoonWalkInput();
            if (this.isRunMode()) {
                if ((this.safeWalk.getValue() || this.edgeStop.getValue()) && this.godBridgeWouldFall()
                        && !this.godBridgePlacesAhead()) {
                    mc.thePlayer.movementInput.moveForward = 0.0F;
                    mc.thePlayer.movementInput.moveStrafe = 0.0F;
                }
                return;
            }

            boolean tellyWalk = (this.rotationMode.getValue() == ROTATION_TELLY
                    || this.isSnapSpeedMode() && this.snapMove.getValue() == SnapBridge.JUMP)
                    && MoveUtil.isForwardPressed();
            if (this.goingDown()) {
                if (mc.thePlayer.movementInput.sneak) {
                    mc.thePlayer.movementInput.sneak = false;
                    mc.thePlayer.movementInput.moveForward /= 0.3F;
                    mc.thePlayer.movementInput.moveStrafe /= 0.3F;
                }
                return;
            }
            if (this.safeWalk.getValue() && !tellyWalk && !mc.thePlayer.movementInput.sneak && this.edgeSneak(true)) {
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
            }
            if (this.eagleSneaking && !mc.thePlayer.movementInput.sneak) {
                mc.thePlayer.movementInput.sneak = true;
                mc.thePlayer.movementInput.moveForward *= 0.3F;
                mc.thePlayer.movementInput.moveStrafe *= 0.3F;
            }
            if (!tellyWalk && !this.isMoonWalkMode()) {
                this.applyStabilize();
            }
        }
    }

    private void applyStabilize() {
        if (!this.lineOn() || !this.stabilize.getValue() || !mc.thePlayer.onGround
                || mc.thePlayer.movementInput.jump || mc.thePlayer.movementInput.moveForward <= 0.0F
                || mc.thePlayer.movementInput.moveStrafe != 0.0F) {
            return;
        }
        LinePlacement.Line line = this.walkingLine();
        float side = LinePlacement.stabilizeKey(line, mc.thePlayer.posX, mc.thePlayer.posZ,
                mc.thePlayer.motionX, mc.thePlayer.motionZ, mc.thePlayer.rotationYaw);
        if (side != 0.0F) {
            mc.thePlayer.movementInput.moveStrafe = mc.thePlayer.movementInput.sneak ? side * 0.3F : side;
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

    private long edgeSneakUntil = 0L;

    private static final float MIN_STEP = 70.0F;

    private static final float SNAP_STEP = 360.0F;

    public boolean runFallen() {
        return this.isEnabled() && this.isRunMode() && mc.thePlayer != null && !mc.thePlayer.onGround
                && mc.thePlayer.motionY < 0.0 && mc.thePlayer.posY < (double) this.startY - 0.05;
    }

    private boolean lbGodBridge() {
        return this.godBridgeShown();
    }

    private final GodBridge run = new GodBridge();
    private GodBridge.Plan runPlan;

    private void godBridgeRunTick(UpdateEvent event) {
        if (this.runFallen() && Atlas.moduleManager.getModule(Clutch.class).isEnabled()) {
            this.runPlan = null;
            this.planReady = false;
            return;
        }
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
        in.edge = in.onGround && forward && target != null && this.edgeSneak(false);
        in.lookPlaces = target != null && this.run.running()
                && this.godBridgeHitsFirmly(target, this.run.lookYaw(), this.run.lookPitch(), 0.0, 0.0);
        if (target != null && !in.lookPlaces && this.run.running()) {
            in.placePitch = this.godBridgePlacePitch(target, this.run.lookYaw(), this.run.lookPitch());
        }
        if (in.onGround && forward && this.run.running() && !in.lookPlaces && Float.isNaN(in.placePitch)) {
            double[] step = this.godBridgeNextStep(false);
            BlockData ahead = this.getBlockData(step[0], step[1]);
            if (ahead != null) {
                in.aheadPitch = this.godBridgePlacePitch(ahead, this.run.lookYaw(), this.run.lookPitch(), step[0], step[1]);
            }
        }

        in.paceJump = paced && this.pace.jumpWanted(now, this.pace.lastGap(230L), event.getYaw(), this.speedLevel());
        in.jumpEvery = this.jumpEveryBlocks.getValue();
        if (this.isGodBridge2Mode()) {
            in.straightBack = true;
            in.paceJump = false;
            in.jumpEvery = 0;
        }
        in.now = now;
        GodBridge.Plan plan = this.run.tick(in);
        this.runPlan = plan;
        godBridgeTrace(in, plan, target, event);
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

    private void godBridgeTrace(GodBridge.Input in, GodBridge.Plan plan, BlockData target, UpdateEvent event) {
        if (!this.godBridgeTrace.getValue() || !in.forward && !this.run.running()) {
            return;
        }
        if (this.godBridgeTraceFile == null) {
            this.godBridgeTraceFile = new java.io.File("./config/Atlas/",
                    "godbridge-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
        }
        String cell = target == null ? "-" : target.blockPos().offset(target.facing()).getX() + ","
                + target.blockPos().offset(target.facing()).getY() + "," + target.blockPos().offset(target.facing()).getZ()
                + " " + target.facing().getName();
        AsyncLog.append(this.godBridgeTraceFile, String.format(
                "%s t%d pos %.3f %.3f %.3f v %.3f %.3f %.3f %s%s | tgt %s | edge %s places %s pp %.1f ap %.1f | sent %.1f/%.1f"
                        + " plan %.1f/%.1f max %.0f%s%s%s | placed %dt ago",
                new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()), this.tick,
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.motionX, mc.thePlayer.motionY,
                mc.thePlayer.motionZ, mc.thePlayer.onGround ? "ground" : "air", in.forward ? "" : " no-W", cell,
                in.edge ? "y" : "n", in.lookPlaces ? "y" : "n", in.placePitch, in.aheadPitch,
                MathHelper.wrapAngleTo180_float(event.getYaw()), event.getPitch(),
                MathHelper.wrapAngleTo180_float(plan.yaw), plan.pitch, plan.maxTurn, plan.stop ? " STOP" : "",
                plan.jump ? " JUMP" : "", plan.click ? " click" : "", this.tick - this.lastPlacedTick));
    }

    private float godBridgePlacePitch(BlockData target, float yaw, float from) {
        return this.godBridgePlacePitch(target, yaw, from, 0.0, 0.0);
    }

    private float godBridgePlacePitch(BlockData target, float yaw, float from, double movedX, double movedZ) {
        float firm = this.godBridgeScanPitch(target, yaw, from, movedX, movedZ, true);
        return Float.isNaN(firm) ? this.godBridgeScanPitch(target, yaw, from, movedX, movedZ, false) : firm;
    }

    private float godBridgeScanPitch(BlockData target, float yaw, float from, double movedX, double movedZ,
                                     boolean firm) {
        if (this.godBridgeHits(target, yaw, from, movedX, movedZ, firm)) {
            return from;
        }
        for (int i = 1; i <= GODBRIDGE_PITCH_STEPS; i++) {
            float away = (i + 1) / 2 * GODBRIDGE_PITCH_STEP * (i % 2 == 0 ? -1.0F : 1.0F);
            float pitch = from + away;
            if (pitch < GODBRIDGE_PITCH_MIN || pitch > GODBRIDGE_PITCH_MAX) {
                continue;
            }
            if (this.godBridgeHits(target, yaw, pitch, movedX, movedZ, firm)) {
                return pitch;
            }
        }
        return Float.NaN;
    }

    private boolean godBridgeHits(BlockData target, float yaw, float pitch, double movedX, double movedZ, boolean firm) {
        return firm ? this.godBridgeHitsFirmly(target, yaw, pitch, movedX, movedZ)
                : this.lookHit(target, yaw, pitch, movedX, movedZ) != null;
    }

    private boolean godBridgeHitsFirmly(BlockData target, float yaw, float pitch, double movedX, double movedZ) {
        float m = GODBRIDGE_AIM_MARGIN;
        return this.lookHit(target, yaw, pitch, movedX, movedZ) != null
                && this.lookHit(target, yaw, pitch - m, movedX, movedZ) != null
                && this.lookHit(target, yaw, pitch + m, movedX, movedZ) != null
                && this.lookHit(target, yaw - m, pitch, movedX, movedZ) != null
                && this.lookHit(target, yaw + m, pitch, movedX, movedZ) != null;
    }

    private double[] godBridgeNextStep(boolean atMoveInput) {
        if (SimPlayer.supported(mc.thePlayer)) {
            SimPlayer.Snapshot now = SimPlayer.snapshot(mc.thePlayer,
                    atMoveInput ? PlayerSim.Stage.INPUT : PlayerSim.Stage.START);
            now.input.jump = false;
            double x = now.sim.x();
            double z = now.sim.z();
            now.sim.tick(now.world, now.input);
            return new double[]{now.sim.x() - x, now.sim.z() - z};
        }

        double radians = Math.toRadians(this.getCurrentYaw());
        return new double[]{mc.thePlayer.motionX - Math.sin(radians) * 0.1, mc.thePlayer.motionZ + Math.cos(radians) * 0.1};
    }

    private boolean godBridgeWouldFall() {
        if (!SimPlayer.supported(mc.thePlayer)) {
            return this.wouldFallOnNextStep();
        }
        if (!mc.thePlayer.onGround) {
            return false;
        }
        SimPlayer.Snapshot now = SimPlayer.snapshot(mc.thePlayer, PlayerSim.Stage.INPUT);
        return !PlayerSim.stopsOnFloor(now.sim, now.world, now.input, 0.02, 0.99);
    }

    private boolean godBridgePlacesAhead() {
        if (this.walkOnSince >= 0 && this.tick - this.walkOnSince > 2) {
            this.walkOnSince = -1;
            this.walkOnBlockedUntil = this.tick + 20;
        }
        if (this.tick < this.walkOnBlockedUntil) {
            return false;
        }
        boolean ahead = this.godBridgeForecastPlaces();
        if (ahead && this.walkOnSince < 0) {
            this.walkOnSince = this.tick;
        }
        return ahead;
    }

    private int walkOnSince = -1;
    private int walkOnBlockedUntil;

    private boolean godBridgeForecastPlaces() {
        GodBridge.Plan plan = this.runPlan;
        if (!this.run.running() || plan == null || !plan.look || plan.maxTurn < 180.0F) {
            return false;
        }
        double[] step = this.godBridgeNextStep(true);
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().expand(-0.02, 0.0, -0.02).offset(step[0], -1.0, step[1]);
        if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty()) {
            return false;
        }
        BlockData target = this.getBlockData(step[0], step[1]);
        if (target == null) {
            return false;
        }

        if (!ItemUtil.isHoldingBlock() || this.blockCount <= 0 || this.pauseTicks > 0 || !this.canPlace()) {
            return false;
        }
        float pitch = this.godBridgePlacePitch(target, this.run.lookYaw(), this.run.lookPitch(), step[0], step[1]);

        if (Float.isNaN(pitch) || Math.abs(pitch - this.run.lookPitch()) > GodBridge.EDGE_PITCH_FIX_MAX) {
            return false;
        }

        MovingObjectPosition hit = this.lookHit(target, this.run.lookYaw(), pitch, step[0], step[1]);
        if (hit == null || this.faceFailed(hit.getBlockPos(), hit.sideHit)) {
            return false;
        }

        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock) || !((ItemBlock) held.getItem())
                .canPlaceBlockOnSide(mc.theWorld, hit.getBlockPos(), hit.sideHit, mc.thePlayer, held)) {
            return false;
        }
        if (this.paceOn() && this.paceHolds(hit.getBlockPos(), hit.sideHit, this.run.lookYaw())) {
            return false;
        }
        for (double k : AHEAD_SPREAD) {
            MovingObjectPosition further = this.lookHit(target, this.run.lookYaw(), pitch, step[0] * k, step[1] * k);
            if (further == null || !further.getBlockPos().equals(hit.getBlockPos()) || further.sideHit != hit.sideHit) {
                return false;
            }
        }
        return true;
    }

    private static final double[] AHEAD_SPREAD = {1.15};

    private static final int GODBRIDGE_PITCH_STEPS = 64;
    private static final float GODBRIDGE_PITCH_STEP = 0.25F;
    private static final float GODBRIDGE_PITCH_MIN = 68.0F;
    private static final float GODBRIDGE_PITCH_MAX = 86.0F;

    private static final float GODBRIDGE_AIM_MARGIN = 0.3F;

    private void applyGodBridgeInput() {
        GodBridge.Plan plan = this.runPlan;
        if (plan == null && this.isGodBridge2Mode() && !MoveUtil.isForwardPressed()) {
            this.rhythm.reset();
        }
        if (!this.isRunMode() || plan == null) {
            return;
        }
        if (this.rotationMode.getValue() == ROTATION_GODBRIDGE2) {
            this.applyRhythm(plan);
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

    private static final double SLIDE_STOPPED = 0.03;

    private void applyRhythm(GodBridge.Plan plan) {
        MovementInput keys = mc.thePlayer.movementInput;
        GodBridgeRhythm.Input in = new GodBridgeRhythm.Input();
        in.forward = MoveUtil.isForwardPressed();
        in.jumpKey = mc.gameSettings.keyBindJump.isKeyDown();
        in.onGround = mc.thePlayer.onGround;

        in.diagonal = this.run.running() ? GodBridge.diagonal(this.run.backYaw())
                : GodBridge.diagonal(Math.round((this.getCurrentYaw() + 180.0F) / 45.0F) * 45.0F);
        in.settled = plan.look && plan.maxTurn >= 180.0F;
        double speed = Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ);
        in.sliding = mc.thePlayer.onGround && speed > SLIDE_STOPPED;

        in.canJump = !plan.stop && mc.thePlayer.onGround && !mc.thePlayer.isInWater();
        GodBridgeRhythm.Keys out = this.rhythm.tick(in, this.rhythmSettings());
        if (out.hold || plan.stop) {
            keys.moveForward = 0.0F;
            keys.moveStrafe = 0.0F;
        }
        if (out.noStrafe && keys.moveForward != 0.0F) {
            keys.moveStrafe = 0.0F;
        }
        if (out.sneak && !keys.sneak) {
            keys.sneak = true;
            keys.moveForward *= 0.3F;
            keys.moveStrafe *= 0.3F;
        }
        if (out.jump && !out.hold && !plan.stop && mc.thePlayer.onGround && !mc.thePlayer.isInWater()) {
            keys.jump = true;
        }
    }

    private GodBridgeRhythm.Settings rhythmSettings() {
        GodBridgeRhythm.Settings s = new GodBridgeRhythm.Settings();
        s.jumpAfterMin = this.gb2JumpAfterMin.getValue();
        s.jumpAfterMax = this.gb2JumpAfterMax.getValue();
        s.jumpDelayMin = this.gb2JumpDelayMin.getValue();
        s.jumpDelayMax = this.gb2JumpDelayMax.getValue();
        s.resetAfterMin = this.gb2ResetAfterMin.getValue();
        s.resetAfterMax = this.gb2ResetAfterMax.getValue();
        s.startDelayMin = this.gb2StartDelayMin.getValue();
        s.startDelayMax = this.gb2StartDelayMax.getValue();
        s.startCrouch = this.gb2StartCrouch.getValue();
        return s;
    }

    private void applyMoonWalkInput() {
        if (!this.isMoonWalkMode()) {
            return;
        }
        MovementInput keys = mc.thePlayer.movementInput;
        if (!mc.thePlayer.onGround || keys.moveForward <= 0.0F || keys.moveStrafe != 0.0F || keys.sneak || keys.jump
                || mc.thePlayer.isInWater()) {
            this.moonWalk.reset();
            return;
        }
        MoonWalk.Settings s = new MoonWalk.Settings();
        s.widthMin = this.moonWidthMin.getValue();
        s.widthMax = this.moonWidthMax.getValue();
        s.middleMin = this.moonMiddleMin.getValue();
        s.middleMax = this.moonMiddleMax.getValue();
        s.redrawMin = this.moonRedrawMin.getValue();
        s.redrawMax = this.moonRedrawMax.getValue();
        keys.moveStrafe = this.moonWalk.strafe(mc.thePlayer.rotationYaw, mc.thePlayer.posX, mc.thePlayer.posZ,
                mc.thePlayer.motionX, mc.thePlayer.motionZ, s);
    }

    private boolean tellyBack;

    boolean tellyReturning;
    private float tellyForwardYaw;
    private float tellyForwardPitch;

    void noteRotationMode() {
        int mode = this.rotationMode.getValue();

        Object player = mc == null ? null : mc.thePlayer;
        if (mode == this.lastRotationMode && player == this.lastPlayer) {
            return;
        }
        this.lastRotationMode = mode;
        if (player != this.lastPlayer) {
            this.recentPlaced.clear();
        }
        this.lastPlayer = player;
        this.rhythm.reset();
        this.moonWalk.reset();
        this.tellyBack = false;
        this.tellyReturning = false;
    }

    private void tellyTick(UpdateEvent event) {
        this.noteRotationMode();
        boolean back = TellyBridge.placing(mc.thePlayer.onGround, mc.thePlayer.motionY, MoveUtil.isForwardPressed());
        BlockData target = back ? this.getBlockData() : null;
        float aimYaw;
        float aimPitch;
        Vec3 hitVec = null;
        boolean real = this.moveFix.getModeString().equals("REAL");
        if (back && !this.tellyBack) {
            if (!this.tellyReturning) {
                this.tellyForwardYaw = mc.thePlayer.rotationYaw;
                this.tellyForwardPitch = mc.thePlayer.rotationPitch;
            }
            this.tellyReturning = real;
        }
        this.tellyBack = back;
        if (back) {
            aimYaw = RotationUtil.wrapAngleDiff(this.getCurrentYaw() - 180.0F, event.getYaw());
            aimPitch = 80.0F;
            float[] aim = target == null ? null : this.tellyAim(target, aimYaw, aimPitch, event.getYaw(), event.getPitch());
            if (aim != null) {
                aimYaw = aim[0];
                aimPitch = aim[1];
                MovingObjectPosition mop = this.lookHit(target, aimYaw, aimPitch);
                hitVec = mop != null ? mop.hitVec : null;
            }
        } else if (this.tellyReturning && real) {
            aimYaw = RotationUtil.wrapAngleDiff(this.tellyForwardYaw, event.getYaw());
            aimPitch = this.tellyForwardPitch;
            if (Math.abs(MathHelper.wrapAngleTo180_float(event.getYaw() - this.tellyForwardYaw)) < 1.0F
                    && Math.abs(event.getPitch() - this.tellyForwardPitch) < 1.0F) {
                this.tellyReturning = false;
            }
        } else {
            this.tellyReturning = false;
            aimYaw = mc.thePlayer.rotationYaw;
            aimPitch = mc.thePlayer.rotationPitch;
        }
        float cap = this.tellyTurnSpeed.getValue();
        float[] sent = RotationHelper.request(this, aimYaw, aimPitch, 3, this.moveMode(), false, cap,
                Math.min(MIN_STEP, cap));
        if (sent != null && this.moveFix.getModeString().equals("REAL")) {
            Atlas.rotationManager.setRotation(sent[0], sent[1], 3, false);
        }
        this.yaw = aimYaw;
        this.pitch = aimPitch;
        this.canRotate = true;
        this.planData = target;
        this.planHitVec = hitVec;
        this.planSnapCanPlace = true;
        this.planSnapMode = false;
        this.planThreeFmc = false;
        this.planReady = true;
    }

    private void snapSpeedTrace(BlockData target, float wantYaw, float wantPitch, float[] sent, boolean takeOff, float cap) {
        if (!this.snapTrace.getValue() || !MoveUtil.isForwardPressed()) {
            return;
        }
        if (this.snapTraceFile == null) {
            this.snapTraceFile = new java.io.File("./config/Atlas/",
                    "snapspeed-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
        }
        String cell = target == null ? "-" : target.blockPos().offset(target.facing()).getX() + ","
                + target.blockPos().offset(target.facing()).getY() + "," + target.blockPos().offset(target.facing()).getZ()
                + " " + target.facing().getName();
        AsyncLog.append(this.snapTraceFile, String.format(
                "%s t%d pos %.3f %.3f %.3f v %.3f %.3f %.3f %s%s | tgt %s snap %s | walk %.1f want %.1f/%.1f sent %.1f/%.1f"
                        + " cap %.0f%s%s | rot5s %.0f | layer %d stage %d | placed %dt ago",
                new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date()), this.tick,
                mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ, mc.thePlayer.motionX, mc.thePlayer.motionY,
                mc.thePlayer.motionZ, mc.thePlayer.onGround ? "ground" : "air", mc.thePlayer.isSprinting() ? " sprint" : "",
                cell, this.snapRotating ? "y" : "n", MathHelper.wrapAngleTo180_float(this.getCurrentYaw()),
                MathHelper.wrapAngleTo180_float(wantYaw), wantPitch, MathHelper.wrapAngleTo180_float(sent[0]), sent[1],
                cap, takeOff ? " TAKEOFF" : "", this.snapAligned ? "" : " not-aligned", this.snapYaw.sum(),
                this.startY, this.stage,
                this.tick - this.lastPlacedTick));
    }

    private float[] tellyAim(BlockData target, float baseYaw, float basePitch, float refYaw, float refPitch) {
        double[] xs = aimOffsets;
        double[] ys = aimOffsets;
        double[] zs = aimOffsets;
        switch (target.facing()) {
            case NORTH:
                zs = new double[]{0.0};
                break;
            case EAST:
                xs = new double[]{1.0};
                break;
            case SOUTH:
                zs = new double[]{1.0};
                break;
            case WEST:
                xs = new double[]{0.0};
                break;
            case DOWN:
                ys = new double[]{0.0};
                break;
            default:
                ys = new double[]{1.0};
        }
        float[] best = null;
        float bestDiff = Float.MAX_VALUE;
        BlockPos pos = target.blockPos();
        for (double dx : xs) {
            for (double dy : ys) {
                for (double dz : zs) {
                    double relX = pos.getX() + dx - mc.thePlayer.posX;
                    double relY = pos.getY() + dy - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                    double relZ = pos.getZ() + dz - mc.thePlayer.posZ;
                    float[] r = RotationUtil.getRotationsTo(relX, relY, relZ, baseYaw, basePitch);
                    MovingObjectPosition mop = RotationUtil.rayTrace(r[0], r[1],
                            mc.playerController.getBlockReachDistance(), 1.0F);
                    if (mop == null || mop.typeOfHit != MovingObjectType.BLOCK || !pos.equals(mop.getBlockPos())
                            || mop.sideHit != target.facing()) {
                        continue;
                    }
                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(r[0] - refYaw)) + Math.abs(r[1] - refPitch);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        best = r;
                    }
                }
            }
        }
        return best;
    }

    private boolean edgeSneak(boolean atMoveInput) {
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            this.edgeSneakUntil = 0L;
            return false;
        }
        long now = System.currentTimeMillis();
        if (this.floorlessNextStep(atMoveInput)) {
            this.edgeSneakUntil = now + 100L + (long) (Math.random() * 100.0);
            return true;
        }
        return now < this.edgeSneakUntil;
    }

    private boolean floorlessNextStep(boolean atMoveInput) {
        if (!mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            return false;
        }
        if (SimPlayer.supported(mc.thePlayer)) {
            SimPlayer.Snapshot now = SimPlayer.snapshot(mc.thePlayer,
                    atMoveInput ? PlayerSim.Stage.INPUT : PlayerSim.Stage.START);
            now.input.jump = false;
            now.sim.tick(now.world, now.input);
            return !now.sim.hasFloor(now.world, 0.2);
        }
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().expand(-0.2, 0.0, -0.2)
                .offset(mc.thePlayer.motionX, -1.0, mc.thePlayer.motionZ);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty();
    }

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

    private int speedLevel() {
        PotionEffect speed = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed);
        return speed == null ? 0 : speed.getAmplifier();
    }

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

                speed == null ? 0 : speed.getAmplifier());
    }

    @EventTarget
    public void onSafeWalk(SafeWalkEvent event) {
        if (this.isEnabled() && this.safeWalk.getValue() && mc.thePlayer.isSneaking() && !this.goingDown()) {
            event.setSafeWalk(true);
        }
    }

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
        this.snapSide = 0;
        this.snapWasGround = true;
        this.snapAligned = true;
        this.snapTraceFile = null;
        this.snapLastYaw = Float.NaN;
        this.snapYaw.clear();

        this.walkOnSince = -1;
        this.walkOnBlockedUntil = 0;
        if (mc.thePlayer != null) {
            this.lastSlot = mc.thePlayer.inventory.currentItem;
        } else {
            this.lastSlot = -1;
        }
        this.blockCount = -1;
        this.rotationTick = 3;
        this.yaw = -180.0F;
        this.pitch = 0.0F;
        this.run.release();
        this.rhythm.reset();
        this.moonWalk.reset();
        this.tellyBack = false;
        this.tellyReturning = false;
        this.lastRotationMode = -1;
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
        this.recentPlaced.clear();
        this.refused.clear();
        this.failedFaces.clear();
        this.recentRefusals = 0;
        this.planReady = false;
        this.planData = null;
        this.planHitVec = null;
        this.lastSentYaw = Float.NaN;
        this.lastSentPitch = Float.NaN;
        this.syncClutch();
    }

    @Override
    public void onDisabled() {
        this.snapSide = 0;
        this.snapWasGround = true;
        this.snapAligned = true;
        this.snapTraceFile = null;
        this.snapLastYaw = Float.NaN;
        this.snapYaw.clear();

        this.syncClutch();
        if (mc.thePlayer != null && this.lastSlot != -1 && !this.isSpeedTellyMode()) {
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
        this.lastSentYaw = Float.NaN;
        this.lastSentPitch = Float.NaN;
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
