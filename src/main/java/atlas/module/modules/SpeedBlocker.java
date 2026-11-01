package atlas.module.modules;

import atlas.Atlas;
import atlas.combat.SpeedblockPlan;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.management.Arbiter;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.BlockUtil;
import atlas.util.CombatTargeting;
import atlas.util.MoveUtil;
import atlas.util.PlaceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speedblocker: a block in the path of a player running in (2026-11-01).
 *
 * A sprint in 1.8 is momentum. Walk the target into a block and the server
 * ends its sprint, so the hit it was carrying loses most of the knockback and
 * the player who placed the block gets the first clean hit of the exchange.
 * That is the speedblock of the box fight, and in a stick fight (knockback
 * sticks, no damage taken) it is the only way to break the other player's run
 * without a hit of your own.
 *
 * Runs at UpdateEvent PRE, where AutoBlockIn clicks from: the player has not
 * moved yet this tick, so the eyes are where the server has them and the
 * click goes out before the movement packet that carries the rotation the
 * server judges the click by (PlaceUtil's timing note; corrected 2026-10-02
 * against Grim RotationPlace).
 *
 * Each tick:
 *
 *  1. from each nearby player's last two positions, its horizontal velocity,
 *     smoothed so a position correction does not read as a run;
 *  2. the player closing on this one fastest, above min-approach, is the
 *     target; one more than two blocks above or below is left alone;
 *  3. its motion gives the cells its feet enter next (SpeedblockPlan), the
 *     cells it would reach sooner than lead ticks are skipped so the block is
 *     there before it is, and the first that is open, outside both bodies and
 *     within reach is the goal -- a block inside the target's box is refused
 *     by the server, one in the cell it is leaving does nothing;
 *  4. the placement is made against a face a ray along the rotation about to
 *     be sent actually hits, turning at most speed degrees a tick.
 *
 * The server's answer is read too: a block change into a cell still waiting
 * means the placement landed, one that comes back replaceable means it did
 * not (a claim, a region), and a teleport drops the aim the click was built
 * from. A cell that took max-retries clicks is left alone for retry-cooldown:
 * a placement the server ignores stays open on this side and would otherwise
 * be retried every tick. The held item is given back after restore-delay
 * ticks without a placement.
 */
public class SpeedBlocker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Same priority as the other placement modules (AutoBlockIn, BedDefender). */
    private static final int PRIORITY = 6;
    /** Ticks a support face is skipped after a click on it was answered false. */
    private static final int FAILED_FACE_TICKS = 10;
    /** Ticks an attempt is remembered before it is forgotten. */
    private static final int ATTEMPT_TICKS = 100;
    /** Ticks a placement waits for the server's answer before it is forgotten. */
    private static final int ANSWER_TICKS = 30;
    /** A target farther than this above or below is not on this floor. */
    private static final double SAME_FLOOR = 2.5;

    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    /** How far to look for a player coming in. */
    public final FloatProperty scanRange = new FloatProperty("scan-range", 6.0F, 3.0F, 12.0F);
    /** Blocks a tick the target has to close on us before it is worth a block. */
    public final FloatProperty minApproach = new FloatProperty("min-approach", 0.1F, 0.01F, 0.3F);
    /** Ticks of the target's motion to aim ahead of, so the block is there before it is. */
    public final IntProperty lead = new IntProperty("lead", 1, 0, 5);
    /** Blocks to look ahead along the motion for the first open cell. */
    public final FloatProperty reachAhead = new FloatProperty("reach-ahead", 2.5F, 1.0F, 4.0F);
    public final ModeProperty height = new ModeProperty("height", 0, new String[]{"FEET", "DOUBLE"});
    /** Degrees of turn per tick. */
    public final IntProperty speed = new IntProperty("speed", 45, 5, 180);
    public final IntProperty placeInterval = new IntProperty("place-interval", 2, 1, 10);
    public final IntProperty swapDelay = new IntProperty("swap-delay", 1, 0, 3);
    /** Ticks the block stays in the hand after the last placement, then the old slot comes back. */
    public final IntProperty restoreDelay = new IntProperty("restore-delay", 8, 0, 60);
    public final IntProperty maxRetries = new IntProperty("max-retries", 3, 1, 10);
    /** Milliseconds a cell that took max-retries is left alone. */
    public final IntProperty retryCooldown = new IntProperty("retry-cooldown", 2000, 250, 10000);
    public final BooleanProperty requireSprint = new BooleanProperty("require-sprint", false);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty targetInvisible = new BooleanProperty("target-invisible", false);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT", "REAL"});
    public final BooleanProperty debug = new BooleanProperty("debug", false);
    public final BooleanProperty debugChat = new BooleanProperty("debug-chat", false, this.debug::getValue);

    /** Each player's last position and the tick it was read, by entity id. */
    private final Map<Integer, double[]> lastSeen = new HashMap<Integer, double[]>();
    /** Smoothed horizontal velocity, blocks a tick, by entity id. */
    private final Map<Integer, double[]> velocities = new HashMap<Integer, double[]>();
    /** Clips per cell, and cells left alone until a time (ms). */
    private final Map<BlockPos, Integer> attempts = new ConcurrentHashMap<BlockPos, Integer>();
    private final Map<BlockPos, Long> refusedUntil = new ConcurrentHashMap<BlockPos, Long>();
    /** Placements sent and not yet answered: cell -> tick sent. */
    private final Map<BlockPos, Integer> pending = new ConcurrentHashMap<BlockPos, Integer>();
    /** Support faces whose click was answered false: key -> tick they may be used again. */
    private final Map<PlaceUtil.FaceKey, Integer> failedFaces = new ConcurrentHashMap<PlaceUtil.FaceKey, Integer>();

    private final PlaceUtil.Stats stats = new PlaceUtil.Stats();
    private final PlaceUtil.Trace trace = new PlaceUtil.Trace("[SB]", this.debug, this.debugChat);

    private volatile int tick;
    /** Volatile: the packet handler drops it from the network thread. */
    private volatile PlaceUtil.Aim locked;
    private int lastPlaceTick = -100;
    private int swapWait;
    private int lastSlot = -1;
    /** Tick the held slot goes back, 0 when it is not to be touched. */
    private int restoreAt;
    /* Yaw steps, for Grim's duplicate-rotation check (its memory is
       server-side, so these are kept across enables). */
    private float placedTurn = -1.0F;
    private String targetName;

    public SpeedBlocker() {
        super("SpeedBlocker", false, false, "Puts a block in the path of a player running in, to break the sprint");
    }

    @Override
    public void onEnabled() {
        this.resetState();
        this.trace.reset();
        this.lastSlot = -1;
        this.restoreAt = 0;
        this.trace.log(this.tick, "enabled", "");
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && this.lastSlot >= 0 && this.lastSlot <= 8) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.lastSlot = -1;
        this.restoreAt = 0;
        this.resetState();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
    }

    /**
     * The server's answer to a placement. A teleport means this side and the
     * server disagree about where the player stands, so the eyes the click was
     * built from are stale and the waits are dropped; a block change into a
     * cell still waiting is the placement landing -- or, when the cell comes
     * back still replaceable, the server rejecting it (a claim, a region).
     *
     * Runs on the network thread: the maps are concurrent and ChatUtil queues
     * its line for the client thread, so nothing here touches the world.
     */
    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof S08PacketPlayerPosLook) {
            this.pending.clear();
            this.locked = null;
            return;
        }
        if (!(packet instanceof S23PacketBlockChange)) {
            return;
        }
        S23PacketBlockChange change = (S23PacketBlockChange) packet;
        BlockPos pos = change.getBlockPosition();
        if (this.pending.remove(pos) == null) {
            return;
        }
        if (!BlockUtil.isReplaceable(change.getBlockState().getBlock())) {
            /* The cell holds a block now: the placement landed. */
            this.attempts.remove(pos);
            this.refusedUntil.remove(pos);
            return;
        }
        int failed = this.attempts.merge(pos, 1, Integer::sum);
        if (failed >= this.maxRetries.getValue()) {
            this.refuse(pos);
        }
    }

    private void resetState() {
        this.locked = null;
        this.lastSeen.clear();
        this.velocities.clear();
        this.attempts.clear();
        this.refusedUntil.clear();
        this.pending.clear();
        this.failedFaces.clear();
        this.swapWait = 0;
        this.lastPlaceTick = -100;
        this.targetName = null;
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
            return;
        }
        try {
            this.update(event);
        } catch (RuntimeException e) {
            this.trace.log(this.tick, "EXCEPTION", e.toString());
            throw e;
        }
    }

    private void update(UpdateEvent event) {
        this.tick++;
        this.prune();
        this.track();
        this.swapWait = Math.max(0, this.swapWait - 1);
        if (Arbiter.catching()) {
            this.locked = null;
            this.trace.log(this.tick, "stopped", "standing aside for " + Arbiter.catcher());
            this.restoreDue();
            return;
        }

        EntityPlayer target = this.findTarget();
        List<BlockPos> goals = target != null ? this.goals(target) : null;
        if (goals == null || goals.isEmpty()) {
            this.locked = null;
            this.trace.log(this.tick, "waiting", target == null ? "no one coming in" : "no open cell in its path");
            this.restoreDue();
            return;
        }
        this.trace.log(this.tick, "target", target.getName() + ", " + goals.size() + " goal(s)");

        int slot = PlaceUtil.bestBlockSlot();
        if (slot < 0) {
            this.locked = null;
            this.trace.log(this.tick, "stopped", "no usable blocks in the hotbar");
            this.restoreDue();
            return;
        }
        if (mc.thePlayer.inventory.currentItem != slot) {
            if (this.lastSlot < 0) {
                this.lastSlot = mc.thePlayer.inventory.currentItem;
            }
            mc.thePlayer.inventory.currentItem = slot;
            this.swapWait = this.swapDelay.getValue();
            this.trace.log(this.tick, "swap", "slot " + slot + ", waiting " + this.swapWait);
        }
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();

        /* The rotation and eye position the server holds. */
        float serverYaw = event.getYaw();
        float serverPitch = event.getPitch();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double reach = this.range.getValue();
        this.stats.reset();
        PlaceUtil.Aim aim = PlaceUtil.bestAim(goals, eye, reach, serverYaw, serverPitch, this.locked, stack,
                this.failedFaces.keySet(), this.stats);
        this.locked = aim;
        if (aim == null) {
            this.trace.log(this.tick, "no candidate", this.stats.candidates + " candidate(s), "
                    + this.stats.rays + " ray(s)");
            this.restoreDue();
            return;
        }

        /* The rotation this tick's movement packet will carry. */
        float[] rotation = aim.current ? new float[]{serverYaw, serverPitch}
                : PlaceUtil.step(serverYaw, serverPitch, aim.yaw, aim.pitch, this.speed.getValue());
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw));
        /* Also against the server's own memory (PlaceRotations, 2026-10-04). */
        if (turn > 2.0F && (Math.abs(turn - this.placedTurn) < 1.0E-3F
                || atlas.management.PlaceRotations.wouldDuplicate(rotation[0]))) {
            /* Grim DuplicateRotPlace flags a placement whose yaw step equals the
               one before the previous placement; one mouse count more breaks the tie. */
            double gcd = atlas.util.RotationUtil.gcd();
            rotation[0] += Math.signum(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw))
                    * (float) (gcd > 0.0 ? gcd : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw));
        }

        /* Click before the tick's movement packet, along the rotation that
           packet carries, from where the player stands now (PlaceUtil). */
        if (this.swapWait <= 0 && this.tick - this.lastPlaceTick >= this.placeInterval.getValue()) {
            MovingObjectPosition hit = PlaceUtil.verify(eye, reach, rotation[0], rotation[1], aim.support, aim.face);
            if (hit != null) {
                PlaceUtil.Aim shot = aim.verified(hit.hitVec, true);
                if (PlaceUtil.click(shot)) {
                    this.lastPlaceTick = this.tick;
                    this.locked = null;
                    this.placedTurn = turn;
                    this.restoreAt = this.tick + this.restoreDelay.getValue();
                    this.targetName = target.getName();
                    this.pending.put(shot.cell, this.tick);
                    this.count(shot.cell);
                    this.trace.log(this.tick, "success", "block in " + fmt(shot.cell) + " ahead of "
                            + target.getName());
                } else {
                    this.invalidate(shot, "click returned false");
                }
            }
        }
        event.setRotation(rotation[0], rotation[1], PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? rotation[0] : mc.thePlayer.rotationYaw, PRIORITY);
        if (this.moveFix.getModeString().equals("REAL")) {
            /* REAL: the camera turns as well, spread over this tick's frames by
               RotationManager; the mouse still works (not forced). */
            Atlas.rotationManager.setRotation(rotation[0], rotation[1], PRIORITY, false);
        }
        this.restoreDue();
    }

    /** Neatness for the maps that keep time; called once a tick. */
    private void prune() {
        long now = System.currentTimeMillis();
        this.refusedUntil.entrySet().removeIf(entry -> now >= entry.getValue());
        this.attempts.entrySet().removeIf(entry -> this.tick - entry.getValue() > ATTEMPT_TICKS);
        this.pending.entrySet().removeIf(entry -> this.tick - entry.getValue() > ANSWER_TICKS);
        this.failedFaces.entrySet().removeIf(entry -> this.tick >= entry.getValue());
    }

    /** Reads every player's velocity from the move since the last tick. */
    private void track() {
        Set<Integer> alive = new HashSet<Integer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            int id = player.getEntityId();
            alive.add(id);
            double[] last = this.lastSeen.get(id);
            if (last != null && this.tick - (int) last[2] == 1) {
                double vx = player.posX - last[0];
                double vz = player.posZ - last[1];
                double[] previous = this.velocities.get(id);
                if (previous != null) {
                    /* Smoothing: a one-tick position correction is not a run. */
                    vx = vx * 0.65 + previous[0] * 0.35;
                    vz = vz * 0.65 + previous[1] * 0.35;
                }
                this.velocities.put(id, new double[]{vx, vz});
            } else {
                this.velocities.remove(id);
            }
            this.lastSeen.put(id, new double[]{player.posX, player.posZ, this.tick});
        }
        this.lastSeen.keySet().removeIf(id -> !alive.contains(id));
        this.velocities.keySet().removeIf(id -> !alive.contains(id));
    }

    /** The player closing on this one fastest, or null. */
    private EntityPlayer findTarget() {
        EntityPlayer best = null;
        double bestApproach = this.minApproach.getValue();
        double scan = this.scanRange.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || !CombatTargeting.isTrackablePlayer(player, this.ignoreTeammates.getValue())) {
                continue;
            }
            if (!this.targetInvisible.getValue() && player.isInvisible()) {
                continue;
            }
            if (this.requireSprint.getValue() && !player.isSprinting()) {
                continue;
            }
            double dx = mc.thePlayer.posX - player.posX;
            double dz = mc.thePlayer.posZ - player.posZ;
            if (dx * dx + dz * dz > scan * scan) {
                continue;
            }
            if (Math.abs(player.getEntityBoundingBox().minY - mc.thePlayer.getEntityBoundingBox().minY) > SAME_FLOOR) {
                continue;
            }
            double[] motion = this.velocities.get(player.getEntityId());
            if (motion == null) {
                continue;
            }
            double approach = SpeedblockPlan.approach(motion[0], motion[1], dx, dz);
            if (approach > bestApproach) {
                bestApproach = approach;
                best = player;
            }
        }
        return best;
    }

    /** The cells the target's feet enter next that are worth and able to take a block. */
    private List<BlockPos> goals(EntityPlayer target) {
        List<BlockPos> goals = new ArrayList<BlockPos>();
        double[] motion = this.velocities.get(target.getEntityId());
        if (motion == null) {
            return goals;
        }
        int feetY = MathHelper.floor_double(target.getEntityBoundingBox().minY + 1.0E-3);
        boolean doubleHeight = this.height.getModeString().equals("DOUBLE");
        double leadDistance = this.lead.getValue() * SpeedblockPlan.speed(motion[0], motion[1]);
        /* Never lead past half the lookahead, or a high lead leaves no cell at all. */
        leadDistance = Math.min(leadDistance, this.reachAhead.getValue() * 0.5);
        List<int[]> cells = SpeedblockPlan.path(target.posX, target.posZ, feetY, motion[0], motion[1],
                leadDistance, this.reachAhead.getValue(), 2, doubleHeight);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double reach = this.range.getValue() + 0.87;
        for (int[] cell : cells) {
            BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
            if (!BlockUtil.isReplaceable(pos) || this.refusedUntil.containsKey(pos)
                    || this.pending.containsKey(pos)) {
                continue;
            }
            /* A block in either body is refused or pushes the player: skip it
               and let the next cell along the path be the goal. */
            if (PlaceUtil.boxIntersects(mc.thePlayer.getEntityBoundingBox(), pos)
                    || PlaceUtil.boxIntersects(target.getEntityBoundingBox(), pos)) {
                continue;
            }
            if (eye.squareDistanceTo(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) > reach * reach) {
                continue;
            }
            goals.add(pos);
        }
        return goals;
    }

    /** A click answered false: drop this face for a moment, count it against the cell. */
    private void invalidate(PlaceUtil.Aim aim, String why) {
        this.failedFaces.put(aim.key(), this.tick + FAILED_FACE_TICKS);
        this.locked = null;
        int failed = this.attempts.merge(aim.cell, 1, Integer::sum);
        if (failed >= this.maxRetries.getValue()) {
            this.refuse(aim.cell);
        }
        this.trace.log(this.tick, "failure", why + " at " + fmt(aim.cell) + " (" + failed + "x), re-planning");
    }

    /**
     * A click the server may still ignore leaves the cell open here; counting
     * every click keeps that cell from being retried every tick forever.
     */
    private void count(BlockPos cell) {
        if (this.attempts.merge(cell, 1, Integer::sum) >= this.maxRetries.getValue()) {
            this.refuse(cell);
        }
    }

    private void refuse(BlockPos cell) {
        this.refusedUntil.put(cell, System.currentTimeMillis() + this.retryCooldown.getValue());
        this.attempts.remove(cell);
        this.trace.log(this.tick, "refused", fmt(cell) + " for " + this.retryCooldown.getValue() + "ms");
    }

    /** Gives the held slot back once the placement is over. */
    private void restoreDue() {
        if (this.restoreAt == 0 || this.tick < this.restoreAt) {
            return;
        }
        this.restoreAt = 0;
        if (mc.thePlayer != null && this.lastSlot >= 0 && this.lastSlot <= 8) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.lastSlot = -1;
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() && this.moveFix.getValue() == 1 && RotationState.isActived()
                && RotationState.getPriority() == PRIORITY && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.targetName == null || this.tick - this.lastPlaceTick > 20) {
            return new String[0];
        }
        return new String[]{this.targetName};
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
