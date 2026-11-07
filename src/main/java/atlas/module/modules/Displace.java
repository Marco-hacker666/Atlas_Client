package atlas.module.modules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.Render3DEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.management.RotationHelper;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ItemListProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.CombatTargeting;
import atlas.util.MoveUtil;
import atlas.util.RotationUtil;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

/**
 * @author strangerrrrs
 */
public class Displace extends Module {
    private static final int DISPLACE_WINDOW_TICKS = 10;
    private static final int VOID_SCAN_DIRECTIONS = 32;
    private static final int VOID_SCAN_RINGS = 12;
    private static final int VOID_SCAN_DEPTH = 10;
    private static final double VOID_SCAN_STEP = 0.5D;
    private static final double DYNAMIC_SCAN_STEP = 0.5D;
    private static final double DYNAMIC_SCAN_DISTANCE = 6.0D;
    private static final double DYNAMIC_SCAN_SIDE_STEP = 0.45D;
    private static final double DYNAMIC_WALL_CHECK_STEP = 0.25D;
    private static final double DYNAMIC_COLLISION_INSET = 0.03D;
    private static final long ARROW_FADE_MS = 250L;
    private static final double ARROW_FORWARD_GAP = 0.24D;
    private static final double ARROW_BODY_LENGTH = 0.74D;
    private static final double ARROW_BODY_HALF_HEIGHT = 0.08D;
    private static final double ARROW_HEAD_BACKSET = 0.18D;
    private static final double ARROW_HEAD_LENGTH = 0.52D;
    private static final double ARROW_HEAD_HALF_HEIGHT = 0.30D;
    /** A turn at least this big moves the player sideways enough to take the step back next tick. */
    private static final float COMPENSATE_TURN = 45.0F;
    private static final double[] VOID_SCAN_X = new double[VOID_SCAN_DIRECTIONS];
    private static final double[] VOID_SCAN_Z = new double[VOID_SCAN_DIRECTIONS];

    public final ModeProperty dynamicAngle =
            new ModeProperty("Dynamic-angle", 0, new String[] {"Static", "Dynamic"});
    public final FloatProperty yawOffset =
            new FloatProperty("Yaw-offset", 90F, 0F, 180F);
    public final FloatProperty delay = new FloatProperty("Delay", 0F, 0F, 500F);
    public final ModeProperty direction =
            new ModeProperty(
                    "Direction", 0, new String[] {"Left", "Right", "Random", "With-strafe", "Against-strafe"});
    public final BooleanProperty showDirection = new BooleanProperty("Show-direction", true);
    public final BooleanProperty findVoid =
            new BooleanProperty("Find-void", false, () -> dynamicAngle.getValue() == 0);
    public final BooleanProperty blink = new BooleanProperty("Blink", false);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("Ignore-teammates", true);
    public final BooleanProperty hasKnockback = new BooleanProperty("Has-knockback", false);
    public final BooleanProperty itemWhitelistToggle = new BooleanProperty("Item-whitelist", false);
    public final ItemListProperty itemWhitelist = new ItemListProperty("Whitelisted-items", "");
    /* 2026-10-22 (owner: "someone says Displace no longer works"). The hit is judged from the turned look,
       and Grim and Polar check that look's ray against the target's hitbox: 90 degrees off it cannot meet a
       box about +-9 degrees wide at 2.5 blocks, so the hit is refused and nothing is displaced. On: the turn
       goes only as far as the look still meets the box (with a margin for the target moving). Off: as before. */
    public final BooleanProperty keepHitbox = new BooleanProperty("Keep-hitbox", false);

    /* 2026-11-05 (owner: "fill in Displace", after reading OpenB4, Yuri, Slinky and Raven; behaviour only).
       Hit-mode Every is what this was: every hit waits a tick behind a turned look. Effective keeps the turn for a hit
       that can do something: the target at or under Max-hurt-time, this player sprinting or with Knockback, and
       Hit-cooldown since the last turn. Every other hit goes at once, on the normal look.
       Angle-random, Direction-lock and Only-facing-away work in both, and are off by default.
       Direction Random and the strafe ways are for when there is no void to aim for. */
    public final ModeProperty hitMode = new ModeProperty("Hit-mode", 0, new String[] {"Every", "Effective"});
    public final IntProperty maxHurtTime = new IntProperty("Max-hurt-time", 3, 0, 10, () -> hitMode.getValue() == 1 || voidMode());
    public final IntProperty hitCooldown = new IntProperty("Hit-cooldown", 500, 0, 3000, () -> hitMode.getValue() == 1 || voidMode());
    /* 2026-11-05 (owner: "a Void mode: hit them into the void, only at the edge"). Hitflick mode VOID: only a target
       with a drop within Void-edge blocks of it, the way clear, turns; the turn goes at the nearest drop, the smaller
       turn winning a tie; every other hit goes at once. Max-hurt-time and Hit-cooldown apply. Void-max-turn is how far
       off the line to the target a drop may be; Keep-hitbox caps it at KEEP_HITBOX_TURN, as a wider turn cannot land. */
    public final FloatProperty voidEdge = new FloatProperty("Void-edge", 3.0F, 1.0F, 6.0F, Displace::voidMode);
    public final FloatProperty voidMaxTurn = new FloatProperty("Void-max-turn", 90.0F, 10.0F, 150.0F, Displace::voidMode);
    private static final float KEEP_HITBOX_TURN = 25.0F;
    public final FloatProperty angleRandom = new FloatProperty("Angle-random", 0F, 0F, 15F);
    public final BooleanProperty directionLock = new BooleanProperty("Direction-lock", false);
    public final BooleanProperty onlyFacingAway = new BooleanProperty("Only-facing-away", false);
    /* 2026-11-07 (owner chose "only turn up close"). A look that still has to meet the box can turn about 22 degrees at
       1 block, 11 at 2 and 8 at 3, so far hits paid the tick for almost nothing. Above this distance the hit goes at
       once, on the normal look. 0 is off: any distance. */
    public final FloatProperty turnRange = new FloatProperty("Turn-range", 0F, 0F, 6F);

    private long lastTurnMs = 0L;
    private int lastTurnTick = 0;
    private int lockedTarget = -1;
    private Float lockedVoidYaw = null;
    private boolean randomLeft = true;
    private float lastStrafe = 0.0F;

    private boolean displaceThisTick = false;
    private boolean active = false;
    private boolean hasKB = false;
    private boolean compensateNextTick = false;
    private boolean displaceLeft = false;
    /* 2026-11-04 (owner: "make it work", then "can't every hit count?"). A hit counts from the look the server had last,
       so a look turned every other tick only ever caught some hits. Now the look follows the hits: every hit is held at
       the click (holdHit), the look turns in that tick's update, and the hit goes out at the start of the next tick,
       the first packet after the turned one. It costs each hit one tick, two for KillAura's. */
    private Entity heldTarget = null;
    private boolean hitDue = false;
    private boolean sendingHit = false;
    private boolean turnedFar = false;
    private boolean releaseBlinkNextGameTick = false;
    private boolean blinkingModule = false;
    private Float dynamicVoidYaw = null;
    private Float renderDisplaceYaw = null;
    private EntityPlayer renderTarget = null;
    private Float fadingDisplaceYaw = null;
    private EntityPlayer fadingTarget = null;
    private long arrowFadeStartMs = 0L;
    private Float lastRenderedDisplaceYaw = null;
    private EntityPlayer lastRenderedTarget = null;
    private long lastRenderedArrowMs = 0L;
    private int tickCounter;
    private final Map<Integer, Integer> targetWindowStartTicks = new HashMap<>();
    private static final Minecraft mc = Minecraft.getMinecraft();

    static {
        for (int i = 0; i < VOID_SCAN_DIRECTIONS; i++) {
            double angle = Math.PI * 2.0D * (double) i / (double) VOID_SCAN_DIRECTIONS;
            VOID_SCAN_X[i] = Math.cos(angle);
            VOID_SCAN_Z[i] = Math.sin(angle);
        }
    }

    public Displace() {
        super("Displace", false);
    }

    @Override
    public String[] getSuffix() {
        int ms = Math.round(delay.getValue());
        return new String[] {ms + "ms"};
    }

    @Override
    public void onEnabled() {
        displaceThisTick = false;
        active = false;
        hasKB = false;
        compensateNextTick = false;
        heldTarget = null;
        hitDue = false;
        releaseBlinkNextGameTick = false;
        dynamicVoidYaw = null;
        renderDisplaceYaw = null;
        renderTarget = null;
        clearArrowState();
        tickCounter = 0;
        targetWindowStartTicks.clear();
        clearTurnState();
        releaseBlink();
    }

    @Override
    public void onDisabled() {
        active = false;
        compensateNextTick = false;
        heldTarget = null;
        hitDue = false;
        releaseBlinkNextGameTick = false;
        dynamicVoidYaw = null;
        renderDisplaceYaw = null;
        renderTarget = null;
        clearArrowState();
        targetWindowStartTicks.clear();
        clearTurnState();
        releaseBlink();
    }

    private void clearTurnState() {
        lastTurnMs = 0L;
        lastTurnTick = 0;
        lockedTarget = -1;
        lockedVoidYaw = null;
        lastStrafe = 0.0F;
    }

    /** The yaw nearest to want, toward it from the target, whose ray from the eyes still meets the target's box. */
    static float clampToHitbox(Entity target, float want, float pitch) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        float base = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float diff = MathHelper.wrapAngleTo180_float(want - base);
        float sign = Math.signum(diff);
        float most = Math.abs(diff);
        AxisAlignedBB box = target.getEntityBoundingBox().expand(0.1, 0.1, 0.1);
        if (RotationUtil.rayTrace(box, base, pitch, 6.0) == null) {
            /* The pitch is off the box too: none of these yaws would count. */
            return base;
        }
        if (RotationUtil.rayTrace(box, base + sign * most, pitch, 6.0) != null) {
            return want;
        }
        float low = 0.0F;
        float high = most;
        for (int i = 0; i < 10; i++) {
            float mid = (low + high) / 2.0F;
            if (RotationUtil.rayTrace(box, base + sign * mid, pitch, 6.0) != null) {
                low = mid;
            } else {
                high = mid;
            }
        }
        /* The hit goes out next tick, judged from this look; the target moves meanwhile. */
        return base + sign * low * 0.75F;
    }

    private static int msToTicks(double ms) {
        if (ms <= 0.0D) {
            return 0;
        }
        return (int) Math.ceil(ms / 50.0D);
    }

    private boolean anyMovementKey() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
    }

    private boolean isDynamicAngle() {
        return dynamicAngle.getValue() == 1;
    }

    private Float findStaticVoidYaw(EntityPlayer target) {
        if (target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        double bestX = 0.0D;
        double bestZ = 0.0D;
        double bestScore = Double.MAX_VALUE;

        for (int ring = 1; ring <= VOID_SCAN_RINGS; ring++) {
            double radius = (double) ring * VOID_SCAN_STEP;
            boolean foundInRing = false;

            for (int i = 0; i < VOID_SCAN_DIRECTIONS; i++) {
                double x = target.posX + VOID_SCAN_X[i] * radius;
                double z = target.posZ + VOID_SCAN_Z[i] * radius;
                if (!isVoidColumn(x, target.posY, z)) {
                    continue;
                }

                double playerDx = x - mc.thePlayer.posX;
                double playerDz = z - mc.thePlayer.posZ;
                double playerDistSq = playerDx * playerDx + playerDz * playerDz;
                double score = radius * radius * 1000.0D + playerDistSq;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestZ = z;
                    foundInRing = true;
                }
            }

            if (foundInRing) {
                break;
            }
        }

        if (bestScore == Double.MAX_VALUE) {
            return null;
        }

        updateDisplaceSide(target, bestX, bestZ);

        double dx = bestX - target.posX;
        double dz = bestZ - target.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001D) {
            return null;
        }

        double aimRadius = Math.min(dist, Math.max(0.35D, (double) target.width * 0.5D + 0.15D));
        double aimX = target.posX + dx / dist * aimRadius;
        double aimZ = target.posZ + dz / dist * aimRadius;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double adx = aimX - eyes.xCoord;
        double ady = (target.posY + (double) target.getEyeHeight() * 0.5D) - eyes.yCoord;
        double adz = aimZ - eyes.zCoord;
        return (float) Math.toDegrees(Math.atan2(adz, adx)) - 90.0F;
    }

    private Float findDynamicVoidYaw(EntityPlayer target) {
        if (target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        double bestForwardX = 0.0D;
        double bestForwardZ = 0.0D;
        double bestScore = 0.0D;

        for (int i = 0; i < VOID_SCAN_DIRECTIONS; i++) {
            double forwardX = VOID_SCAN_X[i];
            double forwardZ = VOID_SCAN_Z[i];
            double score = scoreVoidPath(target, forwardX, forwardZ);

            if (score > bestScore) {
                bestScore = score;
                bestForwardX = forwardX;
                bestForwardZ = forwardZ;
            }
        }

        if (bestScore <= 0.0D) {
            return null;
        }

        updateDisplaceSide(target, target.posX + bestForwardX, target.posZ + bestForwardZ);
        return yawFromForward(bestForwardX, bestForwardZ);
    }

    private float yawFromForward(double forwardX, double forwardZ) {
        return (float) (Math.toDegrees(Math.atan2(forwardZ, forwardX)) - 90.0D);
    }

    private double scoreVoidPath(EntityPlayer target, double forwardX, double forwardZ) {
        double sideX = -forwardZ;
        double sideZ = forwardX;
        double score = 0.0D;
        double checkedForward = 0.0D;
        int consecutiveCenterVoid = 0;
        AxisAlignedBB baseCollisionBox =
                target
                        .getEntityBoundingBox()
                        .contract(DYNAMIC_COLLISION_INSET, 0.0D, DYNAMIC_COLLISION_INSET);

        for (int step = 1; step <= (int) (DYNAMIC_SCAN_DISTANCE / DYNAMIC_SCAN_STEP); step++) {
            double forward = (double) step * DYNAMIC_SCAN_STEP;
            if (!isDynamicPathClear(
                    target, baseCollisionBox, forwardX, forwardZ, checkedForward, forward)) {
                break;
            }
            checkedForward = forward;

            boolean centerVoid = false;

            for (int side = -1; side <= 1; side++) {
                double sideOffset = (double) side * DYNAMIC_SCAN_SIDE_STEP;
                double x = target.posX + forwardX * forward + sideX * sideOffset;
                double z = target.posZ + forwardZ * forward + sideZ * sideOffset;
                if (isVoidColumn(x, target.posY, z)) {
                    double laneWeight = side == 0 ? 1.4D : 1.0D;
                    score += laneWeight * (DYNAMIC_SCAN_DISTANCE + DYNAMIC_SCAN_STEP - forward);
                    centerVoid |= side == 0;
                }
            }

            if (centerVoid) {
                consecutiveCenterVoid++;
                score += consecutiveCenterVoid * 2.0D;
            } else {
                consecutiveCenterVoid = 0;
            }
        }

        return score;
    }

    private boolean isDynamicPathClear(
            EntityPlayer target,
            AxisAlignedBB baseCollisionBox,
            double forwardX,
            double forwardZ,
            double fromForward,
            double toForward) {
        for (double forward = fromForward + DYNAMIC_WALL_CHECK_STEP;
             forward <= toForward + 1.0E-4D;
             forward += DYNAMIC_WALL_CHECK_STEP) {
            AxisAlignedBB checkBox =
                    baseCollisionBox.offset(forwardX * forward, 0.0D, forwardZ * forward);
            if (hasBlockCollision(target, checkBox)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasBlockCollision(EntityPlayer target, AxisAlignedBB box) {
        int minX = MathHelper.floor_double(box.minX);
        int maxX = MathHelper.floor_double(box.maxX + 1.0D);
        int minY = MathHelper.floor_double(box.minY);
        int maxY = MathHelper.floor_double(box.maxY + 1.0D);
        int minZ = MathHelper.floor_double(box.minZ);
        int maxZ = MathHelper.floor_double(box.maxZ + 1.0D);

        List<AxisAlignedBB> collisions = new ArrayList<AxisAlignedBB>();
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int blockX = minX; blockX < maxX; blockX++) {
            for (int blockZ = minZ; blockZ < maxZ; blockZ++) {
                if (!mc.theWorld.isBlockLoaded(blockPos.set(blockX, 64, blockZ))) {
                    return true;
                }

                for (int blockY = minY; blockY < maxY; blockY++) {
                    if (blockY < 0 || blockY >= 256) {
                        return true;
                    }

                    blockPos.set(blockX, blockY, blockZ);
                    IBlockState state = mc.theWorld.getBlockState(blockPos);
                    state
                            .getBlock()
                            .addCollisionBoxesToList(mc.theWorld, blockPos, state, box, collisions, target);
                    if (!collisions.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isVoidColumn(double x, double y, double z) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        int startY = MathHelper.floor_double(y) - 1;
        int endY = Math.max(0, startY - VOID_SCAN_DEPTH);

        for (int blockY = startY; blockY >= endY; blockY--) {
            if (!mc.theWorld.isAirBlock(new BlockPos(blockX, blockY, blockZ))) {
                return false;
            }
        }
        return true;
    }

    private void updateDisplaceSide(EntityPlayer target, double voidX, double voidZ) {
        double targetDx = target.posX - mc.thePlayer.posX;
        double targetDz = target.posZ - mc.thePlayer.posZ;
        double voidDx = voidX - mc.thePlayer.posX;
        double voidDz = voidZ - mc.thePlayer.posZ;
        double cross = targetDx * voidDz - targetDz * voidDx;
        displaceLeft = cross < 0.0D;
    }

    private float getFixedDisplaceYaw() {
        float baseYaw = RotationUtil.customRots ? RotationUtil.serverYaw : mc.thePlayer.rotationYaw;
        float offset = yawOffset.getValue();
        return displaceLeft ? baseYaw - offset : baseYaw + offset;
    }

    private void clearActiveState() {
        startArrowFade();
        active = false;
        displaceThisTick = false;
        compensateNextTick = false;
        dynamicVoidYaw = null;
        renderDisplaceYaw = null;
        renderTarget = null;
    }

    private void clearFadingArrow() {
        fadingDisplaceYaw = null;
        fadingTarget = null;
        arrowFadeStartMs = 0L;
    }

    private void clearArrowState() {
        clearFadingArrow();
        lastRenderedDisplaceYaw = null;
        lastRenderedTarget = null;
        lastRenderedArrowMs = 0L;
    }

    private void startArrowFade() {
        long nowMs = System.currentTimeMillis();
        if (lastRenderedDisplaceYaw != null
                && lastRenderedTarget != null
                && !lastRenderedTarget.isDead
                && nowMs - lastRenderedArrowMs <= ARROW_FADE_MS) {
            fadingDisplaceYaw = lastRenderedDisplaceYaw;
            fadingTarget = lastRenderedTarget;
            arrowFadeStartMs = nowMs;
        }
        lastRenderedDisplaceYaw = null;
        lastRenderedTarget = null;
        lastRenderedArrowMs = 0L;
    }

    private void pruneTargetDelayStates() {
        if (mc.theWorld == null) {
            targetWindowStartTicks.clear();
            return;
        }

        Iterator<Map.Entry<Integer, Integer>> iterator = targetWindowStartTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer)
                    || entity.isDead
                    || ((EntityPlayer) entity).deathTime != 0) {
                iterator.remove();
            }
        }
    }

    private boolean shouldDisplaceInCurrentWindow(EntityPlayer target, int currentTick) {
        if (target == null) {
            return true;
        }

        int targetId = target.getEntityId();
        Integer windowStartTick = targetWindowStartTicks.get(targetId);
        if (windowStartTick == null || currentTick - windowStartTick >= DISPLACE_WINDOW_TICKS) {
            targetWindowStartTicks.put(targetId, currentTick);
            return true;
        }

        int delayTicks = msToTicks(delay.getValue());
        if (delayTicks <= 0) {
            return true;
        }

        int elapsed = currentTick - windowStartTick;
        return elapsed >= delayTicks;
    }

    private boolean passesItemCondition() {
        if (!hasKnockback.getValue() && !itemWhitelistToggle.getValue()) {
            return true;
        }
        boolean kbPass = !hasKnockback.getValue() || EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        boolean wlPass = !itemWhitelistToggle.getValue() || itemWhitelist.matches(mc.thePlayer.getHeldItem());
        return kbPass || wlPass;
    }

    /** Called for every attack before anything is sent. True: this module sends the hit itself, behind the turned look. */
    public boolean holdHit(Entity target) {
        if (sendingHit || !this.isEnabled() || mc.thePlayer == null || !(target instanceof EntityPlayer)) {
            return false;
        }
        if (heldTarget != null) {
            /* A second click would land first, along the look before the turn. */
            return target == heldTarget;
        }
        if (Hitflick.standsAside()) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) target;
        if (!passesItemCondition() || !CombatTargeting.isTrackablePlayer(player, ignoreTeammates.getValue())
                || (EnchantmentHelper.getKnockbackModifier(mc.thePlayer) <= 0 && !anyMovementKey())
                || !turnWorthwhile(player)
                || !shouldDisplaceInCurrentWindow(player, tickCounter)) {
            return false;
        }
        heldTarget = target;
        hitDue = false;
        return true;
    }

    private boolean turnWorthwhile(EntityPlayer player) {
        if (!DisplacePlan.inTurnRange(mc.thePlayer.getDistanceToEntity(player), turnRange.getValue())) {
            return false;
        }
        if (onlyFacingAway.getValue() && !DisplacePlan.facingAway(
                MathHelper.wrapAngleTo180_float(player.rotationYaw - mc.thePlayer.rotationYaw))) {
            return false;
        }
        boolean edge = voidMode();
        if (hitMode.getValue() != 0 || edge) {
            if (!DisplacePlan.worthTurning(player.hurtTime, maxHurtTime.getValue(), mc.thePlayer.isSprinting(),
                    EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0, System.currentTimeMillis() - lastTurnMs,
                    hitCooldown.getValue())) {
                return false;
            }
        }
        return !edge || findEdgeVoidYaw(player) != null;
    }

    /** Hitflick is on its VOID mode. */
    static boolean voidMode() {
        if (Atlas.moduleManager == null) {
            return false;
        }
        Hitflick flick = (Hitflick) Atlas.moduleManager.modules.get(Hitflick.class);
        return flick != null && flick.mode.getValue() == Hitflick.MODE_VOID;
    }

    /** VOID: the yaw of the knock at the nearest drop past this target, or null when it is not at an edge. */
    private Float findEdgeVoidYaw(EntityPlayer target) {
        float aim = (float) Math.toDegrees(Math.atan2(target.posZ - mc.thePlayer.posZ, target.posX - mc.thePlayer.posX)) - 90.0F;
        float maxTurn = keepHitbox.getValue() ? Math.min(voidMaxTurn.getValue(), KEEP_HITBOX_TURN) : voidMaxTurn.getValue();
        AxisAlignedBB box = target.getEntityBoundingBox().contract(DYNAMIC_COLLISION_INSET, 0.0D, DYNAMIC_COLLISION_INSET);
        double bestCost = Double.MAX_VALUE;
        double bestX = 0.0D;
        double bestZ = 0.0D;
        for (int i = 0; i < VOID_SCAN_DIRECTIONS; i++) {
            float turn = Math.abs(MathHelper.wrapAngleTo180_float(yawFromForward(VOID_SCAN_X[i], VOID_SCAN_Z[i]) - aim));
            if (turn > maxTurn) {
                continue;
            }
            double drop = edgeDistance(target, box, VOID_SCAN_X[i], VOID_SCAN_Z[i]);
            if (drop < 0.0D) {
                continue;
            }
            double cost = DisplacePlan.edgeCost(drop, turn);
            if (cost < bestCost) {
                bestCost = cost;
                bestX = VOID_SCAN_X[i];
                bestZ = VOID_SCAN_Z[i];
            }
        }
        if (bestCost == Double.MAX_VALUE) {
            return null;
        }
        updateDisplaceSide(target, target.posX + bestX, target.posZ + bestZ);
        return yawFromForward(bestX, bestZ);
    }

    /** Blocks along this way to the first column with nothing under it, or -1: none within Void-edge, or a wall first. */
    private double edgeDistance(EntityPlayer target, AxisAlignedBB box, double forwardX, double forwardZ) {
        double checked = 0.0D;
        for (double forward = VOID_SCAN_STEP; forward <= voidEdge.getValue() + 1.0E-4D; forward += VOID_SCAN_STEP) {
            if (!isDynamicPathClear(target, box, forwardX, forwardZ, checked, forward)) {
                return -1.0D;
            }
            checked = forward;
            if (isVoidColumn(target.posX + forwardX * forward, target.posY, target.posZ + forwardZ * forward)) {
                return forward;
            }
        }
        return -1.0D;
    }

    /** The direction of the last turn holds for this target (Direction-lock). */
    private boolean directionLocked(EntityPlayer target) {
        return directionLock.getValue()
                && DisplacePlan.lockHolds(lockedTarget, target.getEntityId(), lastTurnTick, tickCounter);
    }

    private void releaseBlink() {
        if (!blinkingModule) return;
        Atlas.blinkManager.setBlinkState(false, BlinkModules.DISPLACE);
        blinkingModule = false;
    }

    @EventTarget(value = Priority.HIGHEST, whenDisabled = true)
    public void onGameTick(TickEvent e) {
        if (e.getType() != EventType.PRE) return;
        if (releaseBlinkNextGameTick) {
            releaseBlink();
            releaseBlinkNextGameTick = false;
        }
        if (hitDue) {
            hitDue = false;
            Entity target = heldTarget;
            heldTarget = null;
            if (this.isEnabled() && target != null && !target.isDead && mc.thePlayer != null
                    && mc.thePlayer.getDistanceToEntity(target) <= 6.0F) {
                sendingHit = true;
                try {
                    mc.playerController.attackEntity(mc.thePlayer, target);
                } finally {
                    sendingHit = false;
                }
            }
        }
    }

    @EventTarget
    public void onRenderWorld(Render3DEvent e) {
        if (!this.isEnabled()) return;
        if (!showDirection.getValue()) {
            clearArrowState();
            return;
        }

        long nowMs = System.currentTimeMillis();
        boolean activeArrow =
                active && renderDisplaceYaw != null && renderTarget != null && !renderTarget.isDead;
        Float arrowYaw = renderDisplaceYaw;
        EntityPlayer arrowTarget = renderTarget;
        float alpha = 1.0F;

        if (activeArrow) {
            clearFadingArrow();
        } else {
            if (fadingDisplaceYaw == null || fadingTarget == null || fadingTarget.isDead) {
                clearFadingArrow();
                return;
            }

            long fadeElapsedMs = nowMs - arrowFadeStartMs;
            if (fadeElapsedMs >= ARROW_FADE_MS) {
                clearFadingArrow();
                return;
            }

            arrowYaw = fadingDisplaceYaw;
            arrowTarget = fadingTarget;
            alpha = 1.0F - (float) fadeElapsedMs / (float) ARROW_FADE_MS;
        }

        float partialTicks = e.getPartialTicks();
        double centerX =
                arrowTarget.lastTickPosX + (arrowTarget.posX - arrowTarget.lastTickPosX) * partialTicks;
        double centerY =
                arrowTarget.lastTickPosY
                        + (arrowTarget.posY - arrowTarget.lastTickPosY) * partialTicks
                        + (double) arrowTarget.height * 0.5D;
        double centerZ =
                arrowTarget.lastTickPosZ + (arrowTarget.posZ - arrowTarget.lastTickPosZ) * partialTicks;

        double yawRad = Math.toRadians(arrowYaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        double baseOffset = (double) arrowTarget.width * 0.5D + ARROW_FORWARD_GAP;
        double tailX = centerX + forwardX * baseOffset;
        double tailZ = centerZ + forwardZ * baseOffset;
        double bodyEndX = tailX + forwardX * ARROW_BODY_LENGTH;
        double bodyEndZ = tailZ + forwardZ * ARROW_BODY_LENGTH;
        double headBackX = tailX + forwardX * (ARROW_BODY_LENGTH - ARROW_HEAD_BACKSET);
        double headBackZ = tailZ + forwardZ * (ARROW_BODY_LENGTH - ARROW_HEAD_BACKSET);
        double tipX = bodyEndX + forwardX * ARROW_HEAD_LENGTH;
        double tipZ = bodyEndZ + forwardZ * ARROW_HEAD_LENGTH;

        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        GL11.glPushMatrix();
        GL11.glPushAttrib(
                GL11.GL_ENABLE_BIT
                        | GL11.GL_COLOR_BUFFER_BIT
                        | GL11.GL_LINE_BIT
                        | GL11.GL_DEPTH_BUFFER_BIT
                        | GL11.GL_CURRENT_BIT);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.82F * alpha);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex3d(tailX - viewerX, centerY - viewerY, tailZ - viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, -ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, -ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        arrowVertex(headBackX, centerY, headBackZ, -ARROW_HEAD_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        GL11.glVertex3d(tipX - viewerX, centerY - viewerY, tipZ - viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, -ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        GL11.glVertex3d(tipX - viewerX, centerY - viewerY, tipZ - viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        GL11.glVertex3d(tipX - viewerX, centerY - viewerY, tipZ - viewerZ);
        arrowVertex(headBackX, centerY, headBackZ, ARROW_HEAD_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        GL11.glEnd();

        GL11.glLineWidth(2.0F);
        GL11.glColor4f(0.0F, 0.0F, 0.0F, 0.95F * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex3d(tailX - viewerX, centerY - viewerY, tailZ - viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, -ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        arrowVertex(headBackX, centerY, headBackZ, -ARROW_HEAD_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        GL11.glVertex3d(tipX - viewerX, centerY - viewerY, tipZ - viewerZ);
        arrowVertex(headBackX, centerY, headBackZ, ARROW_HEAD_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        arrowVertex(bodyEndX, centerY, bodyEndZ, ARROW_BODY_HALF_HEIGHT, viewerX, viewerY, viewerZ);
        GL11.glEnd();

        GL11.glPopAttrib();
        GL11.glPopMatrix();

        if (activeArrow) {
            lastRenderedDisplaceYaw = arrowYaw;
            lastRenderedTarget = arrowTarget;
            lastRenderedArrowMs = nowMs;
        }
    }

    private void arrowVertex(
            double x,
            double y,
            double z,
            double verticalOffset,
            double viewerX,
            double viewerY,
            double viewerZ) {
        GL11.glVertex3d(x - viewerX, y + verticalOffset - viewerY, z - viewerZ);
    }

    @EventTarget(Priority.LOWEST)
    public void onPostInput(MoveInputEvent e) {
        if (!this.isEnabled()) return;
        if (!active) {
            compensateNextTick = false;
            return;
        }

        if (compensateNextTick && !displaceThisTick) {
            compensateNextTick = false;
            if (displaceLeft) {
                mc.thePlayer.movementInput.moveStrafe = -1;
            } else {
                mc.thePlayer.movementInput.moveStrafe = 1;
            }
            return;
        }

        if (!displaceThisTick || !anyMovementKey()) return;
        if (hasKB) {
            /* The enchant pushes without a sprint: the keys only keep their way against the turned yaw. */
            if (renderDisplaceYaw != null) {
                MoveUtil.fixStrafe(renderDisplaceYaw);
            }
            return;
        }

        /* Sprint's part of the knockback needs forward along the turned yaw. */
        mc.thePlayer.movementInput.moveForward = 1;
        compensateNextTick = turnedFar;
    }

    @EventTarget(Priority.HIGH)
    public void onSendPacket(PacketEvent e) {
        if (!this.isEnabled()) return;
        if (!blink.getValue() || !active || !displaceThisTick || releaseBlinkNextGameTick) {
            return;
        }
        if (!(e.getPacket() instanceof C03PacketPlayer)) {
            return;
        }
        if (blinkingModule) return;

        Atlas.blinkManager.setBlinkState(true, BlinkModules.DISPLACE);
        blinkingModule = true;
        releaseBlinkNextGameTick = true;
    }



    @EventTarget(Priority.LOWEST)
    public void onUpdateLowest(UpdateEvent e) {
        if (!this.isEnabled()) return;
        if (e.getType() != EventType.PRE) return;

        tickCounter++;
        pruneTargetDelayStates();
        displaceThisTick = false;
        /* Whatever happens below, a held hit goes out next tick: it is the player's. */
        hitDue = heldTarget != null;

        if (!passesItemCondition()) {
            clearActiveState();
            return;
        }

        EntityPlayer target = heldTarget instanceof EntityPlayer ? (EntityPlayer) heldTarget : null;
        if (target == null
                && (mc.gameSettings.keyBindAttack.isKeyDown() || atlas.util.Auras.target() != null)) {
            target = CombatTargeting.findClosestTarget(9.0, ignoreTeammates.getValue());
        }

        boolean hasKBEnchant = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        active = target != null && (hasKBEnchant || anyMovementKey());
        if (!active) {
            clearActiveState();
            return;
        }

        boolean locked = directionLocked(target);
        boolean edge = voidMode();
        if (heldTarget != null && !locked) {
            /* Rolled before the turn, so the side a lock then holds is the side this turn takes. */
            randomLeft = Math.random() < 0.5D;
        }
        /* VOID always asks afresh: holdHit found a drop now, and the last turn's may be behind the target. */
        dynamicVoidYaw =
                !edge && locked && lockedVoidYaw != null
                        ? lockedVoidYaw
                        : edge
                        ? findEdgeVoidYaw(target)
                        : isDynamicAngle()
                        ? findDynamicVoidYaw(target)
                        : findVoid.getValue() ? findStaticVoidYaw(target) : null;
        /* The keys, not moveStrafing: this module's own step back after a turn sets that, and would pick the next side. */
        boolean strafeLeft = mc.gameSettings.keyBindLeft.isKeyDown();
        if (strafeLeft != mc.gameSettings.keyBindRight.isKeyDown()) {
            lastStrafe = strafeLeft ? 1.0F : -1.0F;
        }
        /* Dynamic with no void in reach did nothing at all, so on open ground the module looked dead: it now turns by
           Yaw-offset and Direction, as Static does. */
        if (dynamicVoidYaw == null) {
            displaceLeft = DisplacePlan.left(direction.getValue(), lastStrafe, randomLeft);
        }
        if (edge && dynamicVoidYaw == null) {
            /* VOID never turns on open ground. A hit held just before the edge moved away goes on the normal look. */
            renderDisplaceYaw = null;
            renderTarget = null;
            return;
        }
        renderDisplaceYaw = dynamicVoidYaw != null ? dynamicVoidYaw : getFixedDisplaceYaw();
        renderTarget = target;

        hasKB = hasKBEnchant;
        /* A hit held just before a catch began still goes, next tick, on the catch's look. */
        if (heldTarget == null || Hitflick.standsAside()) {
            return;
        }

        Float turnVoidYaw = dynamicVoidYaw;
        /* The pitch the server has: with Silent Aura the camera's can be far off the target. */
        float[] wanted = Hitflick.humanTurn(renderDisplaceYaw + DisplacePlan.jitter(angleRandom.getValue(), Math.random()),
                RotationHelper.serverPitch());
        float wantedYaw = keepHitbox.getValue() ? clampToHitbox(target, wanted[0], wanted[1]) : wanted[0];
        /* Through the shared engine (2026-11-05): whole mouse counts, its wander with Rotations on, and the look handed
           back gradually after, not in one jump. The hit goes next tick, so the turn gets this one tick. */
        float[] look = RotationHelper.request(this, wantedYaw, wanted[1], 100, RotationHelper.MOVE_LOOK, false,
                RotationHelper.NO_LIMIT, RotationHelper.NO_LIMIT);
        if (look == null) {
            return;
        }
        renderDisplaceYaw = look[0];
        lastTurnMs = System.currentTimeMillis();
        if (!locked) {
            lockedVoidYaw = turnVoidYaw;
        }
        lockedTarget = target.getEntityId();
        lastTurnTick = tickCounter;
        float turn = MathHelper.wrapAngleTo180_float(renderDisplaceYaw - mc.thePlayer.rotationYaw);
        displaceLeft = turn < 0.0F;
        turnedFar = Math.abs(turn) >= COMPENSATE_TURN;
        /* fixMovement here did nothing: this tick's keys are read after this event. The move has to go along the look
           the server is told (MOVE_LOOK), and onPostInput sets the keys for it. */
        displaceThisTick = true;
    }

    /* 2026-10-31 (audit): a world unload leaves its entities alive (isDead stays false), and one kept here held the
       whole old world in memory -- for good while this module was off. Dropped when a world loads. */
    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.renderTarget = null;
        this.fadingTarget = null;
        this.lastRenderedTarget = null;
    }
}
