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
import atlas.management.RotationHelper;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.BlockUtil;
import atlas.util.CombatTargeting;
import atlas.util.PlaceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
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
 * 2026-11-01 (owner: "make it better"):
 *  - positions are the server's, not the screen's: another player is drawn
 *    gliding three ticks behind the position the server sent (interpolation),
 *    so velocity, path and the box to keep clear of are read from serverPos;
 *  - the lead grows with ping (lead-ping): the block is on the server half a
 *    round trip after the click, so the target has run that much further;
 *  - only a target about to arrive is blocked (contact-ticks): one still six
 *    blocks out at walking pace got a block it would never reach;
 *  - the look goes through the shared engine (RotationHelper): turned as a
 *    hand does at speed degrees a tick at most, on the mouse grid with the
 *    duplicate-rotation guard, keys turned for SILENT, handed back after; the
 *    click goes along the look the engine returns, the one the packet carries;
 *  - a scroll while the block is held picks the slot that comes back.
 *
 * Prediction and fewer actions (2026-11-01, owner: "make it complete, it is
 * unique, it must really help, as undetectable as it can be"):
 *  - the run is simulated tick by tick under vanilla movement (SpeedblockPlan
 *    .simulate): the target keeps the keys it holds relative to its look
 *    (W, W+A ...) and, while it is chasing, keeps its look on this player, so
 *    a strafe that curls in is followed; sprint or walk, ground or air;
 *  - a cell counts from the tick the front of the target's box reaches it, and
 *    only if the block is in the target's world by then: half our round trip,
 *    a server tick and half the target's round trip (its tab ping), plus lead;
 *  - cells this player's own next steps run into are left alone;
 *  - blocks-per-run blocks a run (DOUBLE: both cells), then nothing more for
 *    that target until it stops coming in;
 *  - the hotbar changes only once there is a face to place on, and the held
 *    item comes back restore-delay ticks after (3 by default: the sword is
 *    wanted for the hit the block sets up);
 *  - show-plan draws the predicted run and the chosen cell.
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
    public final FloatProperty scanRange = new FloatProperty("scan-range", 10.0F, 3.0F, 16.0F);
    /** Blocks a tick the target has to close on us before it is worth a block. */
    public final FloatProperty minApproach = new FloatProperty("min-approach", 0.1F, 0.01F, 0.3F);
    /** Extra ticks of margin: the block is to be in the target's world this much before it gets there. */
    public final IntProperty lead = new IntProperty("lead", 0, 0, 5);
    /** Time the block by both pings (ours and the target's tab ping); off: one tick plus lead. */
    public final BooleanProperty leadPing = new BooleanProperty("lead-ping", true);
    /** How far ahead the run is predicted, ticks: a target further off than this is not blocked yet. */
    public final IntProperty contactTicks = new IntProperty("contact-ticks", 30, 2, 60);
    /* 2026-11-02 (owner: "Auto and Press modes"). Auto is what it always did. Press, first "only while the key is
       held"; then (owner: "press the key and a block goes out at once, and it really stops them; it may try again
       and again until the enemy is within 2 blocks"): a press starts a burst at once (PressBurst), held or not.
       The burst follows the runner coming in, blocks it at the soonest cell a block can still be in time for, and
       blocks it again whenever its run is open (it went round, or the block was taken back) -- no blocks-per-run,
       no contact-ticks wait. It ends when the runner is within stop-distance (a block then lands after it is
       through), or press-time after the key was last down. With no burst nothing is turned or swapped. */
    public final ModeProperty trigger = new ModeProperty("trigger", 0, new String[]{"Auto", "Press"});
    public final atlas.property.properties.KeyProperty pressKey = new atlas.property.properties.KeyProperty("press-key",
            org.lwjgl.input.Keyboard.KEY_R, () -> this.trigger.getValue() == 1);
    public final FloatProperty pressTime = new FloatProperty("press-time", 3.0F, 0.5F, 10.0F,
            () -> this.trigger.getValue() == 1);
    public final FloatProperty stopDistance = new FloatProperty("stop-distance", 2.0F, 1.0F, 4.0F,
            () -> this.trigger.getValue() == 1);
    public final ModeProperty height = new ModeProperty("height", 0, new String[]{"FEET", "DOUBLE"});
    /** Blocks one run gets (DOUBLE: pairs), then nothing more for that target until it stops coming in. */
    public final IntProperty blocksPerRun = new IntProperty("blocks-per-run", 1, 1, 3);
    /** Draws the predicted run and the chosen cell. */
    public final BooleanProperty showPlan = new BooleanProperty("show-plan", false);
    /* 2026-11-01 (owner: "add the recorder"): every moving player in scan-range, predicted and then checked against
       where it really went (RunRecorder), into config/Atlas/speedblock-runs-*.csv; switching it off (or the module)
       prints the model's mean error against a straight line's in chat. Off by default: a file a fight. */
    public final BooleanProperty recordRuns = new BooleanProperty("record-runs", false);
    /** Degrees of turn per tick. */
    public final IntProperty speed = new IntProperty("speed", 90, 5, 180);
    public final IntProperty placeInterval = new IntProperty("place-interval", 2, 1, 10);
    public final IntProperty swapDelay = new IntProperty("swap-delay", 0, 0, 3);
    /** Ticks the block stays in the hand after the last placement, then the old slot comes back. */
    public final IntProperty restoreDelay = new IntProperty("restore-delay", 3, 0, 60);
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
    /* 2026-11-02 (review): {clicks, tick of the last one}. It held only the clicks, and prune() took them for a
       tick (tick - clicks > 100): past the first 100 ticks every entry went every tick, so max-retries and
       retry-cooldown never came into play. */
    private final Map<BlockPos, int[]> attempts = new ConcurrentHashMap<BlockPos, int[]>();
    private final Map<BlockPos, Long> refusedUntil = new ConcurrentHashMap<BlockPos, Long>();
    /** Placements sent and not yet answered: cell -> tick sent. */
    /* {tick sent, target id}. 2026-11-02 (review): a run's block counts once the server shows it in place (landed,
       drained on the client thread), not at the click: a block the server took back used up the run. A block still
       waiting for its answer counts toward the run too, so no second one goes out meanwhile. */
    private final Map<BlockPos, int[]> pending = new ConcurrentHashMap<BlockPos, int[]>();
    private final java.util.Queue<Integer> landed = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
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
    /* 2026-11-01 (owner: "SpeedBlocker keeps me holding blocks, I cannot switch to my weapon"): the slot came back
       only restore-delay ticks after a placement, so a swap that never led to one (the runner turned, the cell
       came too late) kept the block in hand for good, and every key or scroll was taken as the slot to give back.
       Now the hand goes back restore-delay ticks after the block was last wanted or placed, whatever happened, and
       the player's own key or scroll always wins: the hand is theirs, and not taken again for HANDS_OFF ticks. */
    /** The last tick a block was wanted in hand (an aim to place). */
    private int wantedTick = -100;
    /** Until this tick the hotbar is the player's: they picked a slot themselves. */
    private int handsOffUntil;
    private static final int HANDS_OFF = 20;
    private String targetName;
    /** Blocks placed in the target's current run, and the last tick it was coming in, by entity id. */
    private final Map<Integer, int[]> runs = new HashMap<Integer, int[]>();
    /** A run ends when the target has not been coming in for this many ticks. */
    private static final int RUN_GAP = 10;
    /** For show-plan: the predicted feet positions (x, y, z) and the chosen cell; read on the render thread. */
    private volatile double[][] planPath;
    /* 2026-11-01 (owner: "start predicting from further off, or always show where enemies may go"): with show-plan,
       the run of everyone moving within scan-range, not only the target's; scan-range 6 -> 10 and contact-ticks
       20 -> 30 so a run is followed from further off and the cell is chosen (and the look turned) earlier. */
    private volatile List<double[][]> otherPaths = java.util.Collections.emptyList();
    private volatile BlockPos planCell;
    /* 2026-11-01 (owner's test, placedebug 22:05-22:06: a target for a tick or two, "0 candidate(s)", one swap and no
       click): a strafing runner's approach dipped under min-approach between ticks and it was dropped; a runner in
       the air had its cells in the air, with nothing to click on; and the cell chosen when the hand changed was
       dropped as "too late" while the look was still turning to it. Now a target is kept while it still closes in
       (TARGET_HOLD ticks), the cells are on the floor it runs on, and the cell committed to keeps its place while
       it is still on the run, open and in reach. */
    private int lastTargetId = -1;
    /* 2026-11-02 (review): the last tick the target closed in above min-approach by itself. The hold counted from
       the last tick it was chosen and gave it +1.0, so it was chosen every tick, the hold never ran out, and a
       faster runner never got the target. */
    private int lastQualifiedTick = -100;
    private static final int TARGET_HOLD = 6;
    private BlockPos committed;
    private int committedTarget = -1;
    private int committedUntil;
    /** Why the last plan had no cell, for the trace. */
    private String goalWhy = "";
    private final atlas.combat.PressBurst press = new atlas.combat.PressBurst();
    /** Press: the run is followed this far ahead and this many cells along it, from further off than Auto. */
    private static final int PRESS_TICKS = 40;
    private static final int PRESS_CELLS = 16;
    /** Press: the soonest cells in time, in path order; the cheapest turn among them is placed. */
    private static final int PRESS_GOALS = 4;
    /** A block at the feet this high or lower is stepped onto, not run into. */
    private static final double STEP_HEIGHT = 0.6;

    public SpeedBlocker() {
        super("SpeedBlocker", false, false, "Puts a block in the path of a player running in, to break the sprint");
    }

    @Override
    public void onEnabled() {
        this.resetState();
        this.trace.reset();
        this.lastSlot = -1;
        this.handsOffUntil = 0;
        this.trace.log(this.tick, "enabled", "");
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && this.lastSlot >= 0 && this.lastSlot <= 8) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.lastSlot = -1;
        this.resetState();
        this.endRecording();
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
        int[] sent = this.pending.remove(pos);
        if (sent == null) {
            return;
        }
        if (!BlockUtil.isReplaceable(change.getBlockState().getBlock())) {
            /* The cell holds a block now: the placement landed, and counts for that target's run. */
            this.attempts.remove(pos);
            this.refusedUntil.remove(pos);
            this.landed.add(sent[1]);
            return;
        }
        if (this.bump(pos) >= this.maxRetries.getValue()) {
            this.refuse(pos);
        }
    }

    private void resetState() {
        this.locked = null;
        this.lastSeen.clear();
        this.velocities.clear();
        this.runs.clear();
        this.planPath = null;
        this.planCell = null;
        this.committed = null;
        this.lastTargetId = -1;
        this.landed.clear();
        this.attempts.clear();
        this.refusedUntil.clear();
        this.pending.clear();
        this.failedFaces.clear();
        this.swapWait = 0;
        this.lastPlaceTick = -100;
        this.targetName = null;
        this.press.reset();
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
        this.predictEveryone();
        this.record();
        this.swapWait = Math.max(0, this.swapWait - 1);
        boolean pressMode = this.trigger.getValue() == 1;
        /* Before anything returns, so a press is never missed (onUpdate runs with no screen open: a key typed in
           chat is not a press). */
        boolean bursting = pressMode && this.press.update(this.tick, this.pressKey.isDown(),
                Math.round(this.pressTime.getValue() * 20.0F));
        if (Arbiter.catching()) {
            this.locked = null;
            this.trace.log(this.tick, "stopped", "standing aside for " + Arbiter.catcher());
            this.restoreDue();
            return;
        }
        if (pressMode && !bursting) {
            /* Press, no burst: nothing turned or swapped (the engine hands the look back, the slot comes back). */
            this.locked = null;
            this.committed = null;
            this.planCell = null;
            this.planPath = null;
            this.trace.log(this.tick, "waiting", "press-key not pressed");
            this.restoreDue();
            return;
        }

        EntityPlayer target = pressMode ? this.pressTarget() : this.findTarget(false);
        List<BlockPos> goals = target != null ? this.goals(target, pressMode) : null;
        if (goals == null || goals.isEmpty()) {
            this.locked = null;
            if (target == null) {
                this.planPath = null;
                this.planCell = null;
            }
            this.trace.log(this.tick, "waiting", target == null ? "no one coming in"
                    : "no open cell in its path -- " + this.goalWhy);
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
        /* Planned with the block the hotbar would give; the hand changes only once there is a face to place on. */
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);

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
                    + this.stats.rays + " ray(s) for " + goals.size() + " cell(s), first " + fmt(goals.get(0)));
            this.restoreDue();
            return;
        }
        this.planCell = aim.cell;
        if (!aim.cell.equals(this.committed) || this.committedTarget != target.getEntityId()) {
            this.committed = aim.cell;
            this.committedTarget = target.getEntityId();
            this.committedUntil = this.tick + this.needTicks(target) + 10;
        }
        if (mc.thePlayer.inventory.currentItem != slot) {
            if (this.tick < this.handsOffUntil) {
                /* The player just picked their slot: not taken from them. */
                this.trace.log(this.tick, "waiting", "the hotbar is the player's for now");
                this.restoreDue();
                return;
            }
            if (this.lastSlot < 0) {
                this.lastSlot = mc.thePlayer.inventory.currentItem;
            }
            mc.thePlayer.inventory.currentItem = slot;
            this.swapWait = this.swapDelay.getValue();
            this.trace.log(this.tick, "swap", "slot " + slot + ", waiting " + this.swapWait);
        }
        this.wantedTick = this.tick;

        /* The rotation this tick's movement packet will carry: the shared engine's (2026-11-01), which keeps
           the grid and Grim's duplicate-rotation guard. Null when a higher priority has the look this tick. */
        float[] rotation = RotationHelper.request(this, aim.current ? serverYaw : aim.yaw,
                aim.current ? serverPitch : aim.pitch, PRIORITY, this.moveMode(), false, this.speed.getValue());
        if (rotation == null) {
            this.trace.log(this.tick, "waiting", "another module has the look");
            this.restoreDue();
            return;
        }

        /* Click before the tick's movement packet, along the rotation that
           packet carries, from where the player stands now (PlaceUtil). */
        if (this.swapWait <= 0 && this.tick - this.lastPlaceTick >= this.placeInterval.getValue()) {
            MovingObjectPosition hit = PlaceUtil.verify(eye, reach, rotation[0], rotation[1], aim.support, aim.face);
            if (hit != null) {
                PlaceUtil.Aim shot = aim.verified(hit.hitVec, true);
                if (PlaceUtil.click(shot)) {
                    this.lastPlaceTick = this.tick;
                    this.committed = null;
                    this.locked = null;
                    this.targetName = target.getName();
                    this.pending.put(shot.cell, new int[]{this.tick, target.getEntityId()});
                    this.count(shot.cell);
                    this.trace.log(this.tick, "success", "block in " + fmt(shot.cell) + " ahead of "
                            + target.getName());
                } else {
                    this.invalidate(shot, "click returned false");
                }
            }
        }
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
        this.attempts.entrySet().removeIf(entry -> this.tick - entry.getValue()[1] > ATTEMPT_TICKS);
        this.pending.entrySet().removeIf(entry -> this.tick - entry.getValue()[0] > ANSWER_TICKS);
        Integer landedFor;
        while ((landedFor = this.landed.poll()) != null) {
            int[] run = this.runs.get(landedFor);
            if (run != null) {
                run[0]++;
            }
        }
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
            /* 2026-11-01 (owner: "it did not place at all"): a server sends a player's position every second tick
               (the entity tracker's update interval for players), so a one-tick difference was 0, then twice the
               speed: the run predicted from it jumped about every tick, the chosen cell with it, and the look never
               settled on one long enough to click. Velocity is now the change between two updates over the ticks
               between them; between updates it is kept. lastSeen: {x, z, tick of that update, last tick seen}. */
            double[] last = this.lastSeen.get(id);
            double sx = serverX(player);
            double sz = serverZ(player);
            if (last == null || this.tick - (int) last[3] > 1) {
                this.velocities.remove(id);
                this.lastSeen.put(id, new double[]{sx, sz, this.tick, this.tick});
                continue;
            }
            last[3] = this.tick;
            if (sx == last[0] && sz == last[1]) {
                if (this.tick - (int) last[2] > 3) {
                    /* No update for longer than a tracker interval or two: it is standing. */
                    this.velocities.remove(id);
                }
                continue;
            }
            int dt = Math.max(1, this.tick - (int) last[2]);
            double vx = (sx - last[0]) / dt;
            double vz = (sz - last[1]) / dt;
            if (vx * vx + vz * vz > 1.5 * 1.5) {
                /* A teleport, not a run. */
                this.velocities.remove(id);
            } else {
                double[] previous = this.velocities.get(id);
                if (previous != null) {
                    /* Smoothing: one odd update is not a run. */
                    vx = vx * 0.65 + previous[0] * 0.35;
                    vz = vz * 0.65 + previous[1] * 0.35;
                }
                this.velocities.put(id, new double[]{vx, vz});
            }
            last[0] = sx;
            last[1] = sz;
            last[2] = this.tick;
        }
        this.lastSeen.keySet().removeIf(id -> !alive.contains(id));
        this.velocities.keySet().removeIf(id -> !alive.contains(id));
    }

    /**
     * The player closing on this one fastest, or null. Press: from as far as scan-range (no contact-ticks wait),
     * no blocks-per-run, and nobody within stop-distance.
     */
    private EntityPlayer findTarget(boolean press) {
        EntityPlayer best = null;
        double bestOwn = 0.0;
        double bestApproach = this.minApproach.getValue();
        double stop = this.stopDistance.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!this.inSight(player) || this.requireSprint.getValue() && !player.isSprinting()) {
                continue;
            }
            double dx = mc.thePlayer.posX - this.nowX(player);
            double dz = mc.thePlayer.posZ - this.nowZ(player);
            if (press && dx * dx + dz * dz < stop * stop) {
                /* Too close: a block placed now lands after it is through. */
                continue;
            }
            double[] motion = this.velocities.get(player.getEntityId());
            if (motion == null) {
                continue;
            }
            double approach = SpeedblockPlan.approach(motion[0], motion[1], dx, dz);
            /* The target of the last ticks stays while it still closes in at all: a strafe dips under min-approach. */
            boolean held = player.getEntityId() == this.lastTargetId && this.tick - this.lastQualifiedTick <= TARGET_HOLD
                    && approach > 0.0;
            /* Ticks until it is on top of this player at that speed: too far off is not worth a block yet. */
            double gap = Math.max(0.0, Math.sqrt(dx * dx + dz * dz) - 0.6);
            if (!press && approach > 0.0 && gap / approach > this.contactTicks.getValue()) {
                continue;
            }
            if (!press && (approach > this.minApproach.getValue() || held)) {
                int[] run = this.runs.get(player.getEntityId());
                if (run == null || this.tick - run[1] > RUN_GAP) {
                    run = new int[]{0, this.tick};
                    this.runs.put(player.getEntityId(), run);
                }
                run[1] = this.tick;
                int quota = this.blocksPerRun.getValue() * (this.height.getModeString().equals("DOUBLE") ? 2 : 1);
                if (run[0] + this.inFlight(player.getEntityId()) >= quota) {
                    /* This run has had its blocks (in place, or on their way). */
                    continue;
                }
            }
            /* A held target counts as just above min-approach while it dips under it, and wins a tie: kept over a
               runner no faster, never over a faster one. */
            double score = held ? Math.max(approach, this.minApproach.getValue() + 1.0E-6) + 0.02 : approach;
            if (score > bestApproach) {
                bestApproach = score;
                best = player;
                bestOwn = approach;
            }
        }
        if (best != null) {
            this.lastTargetId = best.getEntityId();
            if (bestOwn > this.minApproach.getValue()) {
                this.lastQualifiedTick = this.tick;
            }
        }
        return best;
    }

    /** A player SpeedBlocker may follow: trackable, visible (unless target-invisible), in scan-range, on this floor. */
    private boolean inSight(EntityPlayer player) {
        if (player == mc.thePlayer || !CombatTargeting.isTrackablePlayer(player, this.ignoreTeammates.getValue())) {
            return false;
        }
        if (!this.targetInvisible.getValue() && player.isInvisible()) {
            return false;
        }
        double scan = this.scanRange.getValue();
        double dx = mc.thePlayer.posX - this.nowX(player);
        double dz = mc.thePlayer.posZ - this.nowZ(player);
        if (dx * dx + dz * dz > scan * scan) {
            return false;
        }
        return Math.abs(serverY(player) - mc.thePlayer.getEntityBoundingBox().minY) <= SAME_FLOOR;
    }

    /**
     * Press: the burst's target. Kept from the tick it was chosen while it is in sight, closing in or not, so a
     * runner that was stopped is still watched and blocked again if it goes round; within stop-distance the burst
     * ends. With none locked, the runner closing in fastest.
     */
    private EntityPlayer pressTarget() {
        int id = this.press.target();
        if (id >= 0) {
            net.minecraft.entity.Entity entity = mc.theWorld.getEntityByID(id);
            if (entity instanceof EntityPlayer && this.inSight((EntityPlayer) entity)) {
                EntityPlayer player = (EntityPlayer) entity;
                double stop = this.stopDistance.getValue();
                double dx = mc.thePlayer.posX - this.nowX(player);
                double dz = mc.thePlayer.posZ - this.nowZ(player);
                if (dx * dx + dz * dz < stop * stop) {
                    this.press.end();
                    this.trace.log(this.tick, "stopped", player.getName() + " is within stop-distance, burst over");
                    return null;
                }
                return player;
            }
            this.press.lock(-1);
        }
        EntityPlayer best = this.findTarget(true);
        if (best != null) {
            this.press.lock(best.getEntityId());
        }
        return best;
    }

    /**
     * The cells the target's feet enter next that are worth and able to take a block. Press: followed further
     * ahead, nothing while something already walls the run, and only the soonest cells in time.
     */
    private List<BlockPos> goals(EntityPlayer target, boolean press) {
        List<BlockPos> goals = new ArrayList<BlockPos>();
        double[] motion = this.velocities.get(target.getEntityId());
        if (motion == null) {
            return goals;
        }
        double sx = this.nowX(target);
        double sy = serverY(target);
        double sz = this.nowZ(target);
        int feetY = this.floorY(target, sx, sy, sz);
        boolean doubleHeight = this.height.getModeString().equals("DOUBLE");
        boolean sprinting = target.isSprinting() || SpeedblockPlan.speed(motion[0], motion[1]) >= 0.25;
        double[][] run = SpeedblockPlan.simulate(sx, sz, motion[0], motion[1], target.rotationYawHead,
                mc.thePlayer.posX, mc.thePlayer.posZ, target.onGround, sprinting,
                press ? Math.max(PRESS_TICKS, this.contactTicks.getValue()) : this.contactTicks.getValue());
        double[][] shown = new double[run.length][];
        for (int i = 0; i < run.length; i++) {
            shown[i] = new double[]{run[i][0], feetY, run[i][1]};
        }
        this.planPath = shown;
        List<int[]> cells = SpeedblockPlan.cellsAlong(sx, sz, feetY, run, press ? PRESS_CELLS : 6, doubleHeight);
        if (press) {
            int wall = SpeedblockPlan.firstWall(cells, doubleHeight, mc.thePlayer.posX, mc.thePlayer.posZ,
                    cell -> this.walls(cell, feetY));
            if (wall >= 0) {
                /* A block already stands in its way -- ours (landed, or placed here and waiting for the server's
                   answer) or the world's: watched, and blocked again only once the run is open. */
                int[] at = cells.get(wall);
                this.planCell = new BlockPos(at[0], at[1], at[2]);
                this.goalWhy = "already walled at " + at[0] + "," + at[1] + "," + at[2] + ", watching";
                return goals;
            }
        }
        /* Not before the block can be in the target's world. */
        int need = this.needTicks(target);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        /* Measured to the nearest point of the floor face under the cell -- the face that is clicked -- with the
           same reach PlaceUtil searches it with. The cell's centre (range + 0.87), then the face's centre
           (range + 0.25), let cells through that no point in range could place ("0 candidate(s)"). PlaceUtil tries
           points 0.2 apart, so the nearest one may be up to 0.15 beyond the nearest point measured here. */
        double reach = this.range.getValue() - 0.15;
        /* Where the server has the target, not where it is drawn. */
        AxisAlignedBB targetBox = target.getEntityBoundingBox().offset(sx - target.posX,
                sy - target.getEntityBoundingBox().minY, sz - target.posZ);
        boolean holding = PlaceUtil.usableBlock(mc.thePlayer.inventory.getCurrentItem());
        BlockPos keep = this.committed != null && this.committedTarget == target.getEntityId()
                && this.tick <= this.committedUntil ? this.committed : null;
        int late = 0;
        int taken = 0;
        int ours = 0;
        int bodies = 0;
        int far = 0;
        for (int[] cell : cells) {
            BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
            boolean kept = pos.equals(keep);
            /* The click comes once the look is there (and the block in hand): that wait counts too. */
            double cx = pos.getX() + 0.5 - eye.xCoord;
            double cy = pos.getY() + 0.5 - eye.yCoord;
            double cz = pos.getZ() + 0.5 - eye.zCoord;
            float cellYaw = (float) SpeedblockPlan.yawOf(cx, cz);
            float cellPitch = (float) -Math.toDegrees(Math.atan2(cy, Math.sqrt(cx * cx + cz * cz)));
            float turn = atlas.util.RotationEngine.angle(cellYaw, cellPitch, RotationHelper.serverYaw(),
                    RotationHelper.serverPitch());
            int ready = (int) Math.ceil(Math.max(0.0F, turn - 5.0F) / Math.max(1, this.speed.getValue()))
                    + (holding ? 0 : this.swapDelay.getValue());
            if (!kept && cell[3] < need + ready) {
                /* It would be there before the block is: too late for this one (the committed cell keeps its place
                   while the look turns to it -- it was timed when it was chosen). */
                late++;
                continue;
            }
            if (!BlockUtil.isReplaceable(pos) || this.refusedUntil.containsKey(pos)
                    || this.pending.containsKey(pos)) {
                taken++;
                continue;
            }
            if (this.inOwnWay(pos)) {
                /* This player is about to walk there: the block would stop us, not them. */
                ours++;
                continue;
            }
            /* A block in either body is refused or pushes the player: skip it
               and let the next cell along the path be the goal. */
            if (PlaceUtil.boxIntersects(mc.thePlayer.getEntityBoundingBox(), pos)
                    || PlaceUtil.boxIntersects(targetBox, pos)) {
                bodies++;
                continue;
            }
            double nearX = Math.max(pos.getX() + 0.1, Math.min(pos.getX() + 0.9, eye.xCoord));
            double nearZ = Math.max(pos.getZ() + 0.1, Math.min(pos.getZ() + 0.9, eye.zCoord));
            if (eye.squareDistanceTo(new Vec3(nearX, pos.getY(), nearZ)) > reach * reach) {
                far++;
                continue;
            }
            if (kept) {
                goals.add(0, pos);
            } else {
                goals.add(pos);
            }
        }
        this.goalWhy = cells.size() + " cell(s) on the run: " + late + " too late (need " + need + "t), " + taken
                + " taken, " + ours + " in our way, " + bodies + " in a body, " + far + " out of reach";
        if (press && goals.size() > PRESS_GOALS) {
            /* The soonest: the block meets it earliest, with time left for another if it goes round. */
            return new ArrayList<BlockPos>(goals.subList(0, PRESS_GOALS));
        }
        return goals;
    }

    /** The cell stops a runner: at the feet a block higher than a step, above them any block. */
    private boolean walls(int[] cell, int feetY) {
        BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
        net.minecraft.block.state.IBlockState state = mc.theWorld.getBlockState(pos);
        AxisAlignedBB box = state.getBlock().getCollisionBoundingBox(mc.theWorld, pos, state);
        return box != null && (cell[1] != feetY || box.maxY - pos.getY() > STEP_HEIGHT);
    }

    /**
     * The floor the runner's cells are on: its feet's level on the ground; in the air, the first solid block under
     * it (within four) plus one, so the cell has a floor to be clicked on and is where it lands.
     */
    private int floorY(EntityPlayer target, double x, double y, double z) {
        int feet = MathHelper.floor_double(y + 1.0E-3);
        if (target.onGround) {
            return feet;
        }
        BlockPos column = new BlockPos(x, feet, z);
        for (int down = 1; down <= 4; down++) {
            if (!BlockUtil.isReplaceable(column.down(down))) {
                return column.getY() - down + 1;
            }
        }
        return feet;
    }

    /** A click answered false: drop this face for a moment, count it against the cell. */
    private void invalidate(PlaceUtil.Aim aim, String why) {
        this.failedFaces.put(aim.key(), this.tick + FAILED_FACE_TICKS);
        this.locked = null;
        int failed = this.bump(aim.cell);
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
        if (this.bump(cell) >= this.maxRetries.getValue()) {
            this.refuse(cell);
        }
    }

    /** One more click on the cell; how many it has had. */
    private int bump(BlockPos cell) {
        int now = this.tick;
        int[] after = this.attempts.compute(cell, (key, old) -> old == null ? new int[]{1, now}
                : new int[]{old[0] + 1, now});
        return after[0];
    }

    /** Blocks sent for the target and not yet answered. */
    private int inFlight(int targetId) {
        int n = 0;
        for (int[] sent : this.pending.values()) {
            if (sent[1] == targetId) {
                n++;
            }
        }
        return n;
    }

    private void refuse(BlockPos cell) {
        this.refusedUntil.put(cell, System.currentTimeMillis() + this.retryCooldown.getValue());
        this.attempts.remove(cell);
        this.trace.log(this.tick, "refused", fmt(cell) + " for " + this.retryCooldown.getValue() + "ms");
    }

    /** Gives the held slot back restore-delay ticks after the block was last wanted or placed. */
    private void restoreDue() {
        if (this.lastSlot < 0) {
            return;
        }
        if (this.tick - Math.max(this.lastPlaceTick, this.wantedTick) < this.restoreDelay.getValue()) {
            return;
        }
        if (mc.thePlayer != null && this.lastSlot <= 8) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.lastSlot = -1;
    }

    /** The player's own key or scroll: the slot is theirs, nothing is given back, and the hand is left alone a while. */
    @EventTarget
    public void onSwap(atlas.events.SwapItemEvent event) {
        if (this.isEnabled() && this.lastSlot >= 0) {
            this.lastSlot = -1;
            this.locked = null;
            this.handsOffUntil = this.tick + HANDS_OFF;
        }
    }

    /** move-fix as the engine's movement yaw: NONE the camera's, SILENT the look's with the keys turned, STRICT and REAL the look's. */
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

    /** The tick a cell may be reached at the soonest: when a block placed now is in the target's world, plus lead. */
    private int needTicks(EntityPlayer target) {
        int base = this.leadPing.getValue() ? SpeedblockPlan.serverWallAfterTicks(atlas.util.Ping.own()) : 1;
        return base + this.lead.getValue();
    }

    /** The cell is where this player's box goes in the next three ticks at its own motion. */
    private boolean inOwnWay(BlockPos pos) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        for (int t = 1; t <= 3; t++) {
            if (PlaceUtil.boxIntersects(box.offset(mc.thePlayer.motionX * t, 0.0, mc.thePlayer.motionZ * t), pos)) {
                return true;
            }
        }
        return false;
    }

    /** show-plan: the predicted run of every player moving within scan-range (for the render thread). */
    private atlas.combat.RunRecorder recorder;
    private java.io.File recordFile;

    /** record-runs: one tick of every moving player in scan-range for the recorder. */
    private void record() {
        if (!this.recordRuns.getValue()) {
            this.endRecording();
            return;
        }
        if (this.recorder == null) {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            final java.io.File file = new java.io.File("./config/Atlas/", "speedblock-runs-" + stamp + ".csv");
            this.recordFile = file;
            this.recorder = new atlas.combat.RunRecorder(line -> atlas.util.AsyncLog.append(file, line));
            atlas.util.AsyncLog.append(file, atlas.combat.RunRecorder.HEADER);
        }
        this.recorder.prune(this.tick);
        double scan = this.scanRange.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            double x = serverX(player);
            double z = serverZ(player);
            double dx = x - mc.thePlayer.posX;
            double dz = z - mc.thePlayer.posZ;
            if (dx * dx + dz * dz > scan * scan) {
                continue;
            }
            double[] motion = this.velocities.get(player.getEntityId());
            boolean sprinting = player.isSprinting()
                    || motion != null && SpeedblockPlan.speed(motion[0], motion[1]) >= 0.25;
            double[] seen = this.lastSeen.get(player.getEntityId());
            boolean fresh = seen != null && (int) seen[2] == this.tick;
            /* From where the plan starts (the update moved on), checked against fresh server positions. */
            this.recorder.observe(this.tick, player.getEntityId(), player.getName(), this.nowX(player),
                    this.nowZ(player), x, z, fresh, motion == null ? Double.NaN : motion[0],
                    motion == null ? Double.NaN : motion[1], player.rotationYawHead, mc.thePlayer.posX,
                    mc.thePlayer.posZ, player.onGround, sprinting,
                    this.recorder.samples(this.tick)
                            ? atlas.sim.SimTargets.path(player, atlas.combat.RunRecorder.LENGTH) : null);
        }
    }

    /** The recording ends: its summary in chat and at the end of the file. */
    private void endRecording() {
        if (this.recorder == null) {
            return;
        }
        String summary = "SpeedBlocker prediction error, model vs straight line: " + this.recorder.summary();
        atlas.util.AsyncLog.append(this.recordFile, "# " + summary);
        if (this.recorder.settled() > 0) {
            atlas.util.ChatUtil.sendFormatted("&7[SpeedBlocker] " + summary + " &8(" + this.recordFile.getName() + ")&r");
        }
        this.recorder = null;
        this.recordFile = null;
    }

    private void predictEveryone() {
        if (!this.showPlan.getValue()) {
            if (!this.otherPaths.isEmpty()) {
                this.otherPaths = java.util.Collections.emptyList();
            }
            return;
        }
        List<double[][]> paths = new ArrayList<double[][]>();
        double scan = this.scanRange.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            double[] motion = this.velocities.get(player.getEntityId());
            if (motion == null || SpeedblockPlan.speed(motion[0], motion[1]) < 0.05) {
                continue;
            }
            double x = this.nowX(player);
            double z = this.nowZ(player);
            double dx = x - mc.thePlayer.posX;
            double dz = z - mc.thePlayer.posZ;
            if (dx * dx + dz * dz > scan * scan) {
                continue;
            }
            boolean sprinting = player.isSprinting() || SpeedblockPlan.speed(motion[0], motion[1]) >= 0.25;
            double[][] run = SpeedblockPlan.simulate(x, z, motion[0], motion[1], player.rotationYawHead,
                    mc.thePlayer.posX, mc.thePlayer.posZ, player.onGround, sprinting, this.contactTicks.getValue());
            double y = this.floorY(player, x, serverY(player), z);
            double[][] shown = new double[run.length][];
            for (int i = 0; i < run.length; i++) {
                shown[i] = new double[]{run[i][0], y, run[i][1]};
            }
            paths.add(shown);
        }
        this.otherPaths = paths;
    }

    /** show-plan: the predicted run as small marks at the feet, and the chosen cell. */
    @EventTarget
    public void onRender(atlas.events.Render3DEvent event) {
        if (!this.isEnabled() || !this.showPlan.getValue() || mc.getRenderManager() == null) {
            return;
        }
        double[][] path = this.planPath;
        BlockPos cell = this.planCell;
        List<double[][]> others = this.otherPaths;
        if (path == null && cell == null && others.isEmpty()) {
            return;
        }
        atlas.mixin.IAccessorRenderManager view = (atlas.mixin.IAccessorRenderManager) mc.getRenderManager();
        atlas.util.RenderUtil.enableRenderState();
        /* Everyone moving nearby, grey; the target's run on top, yellow. */
        for (double[][] other : others) {
            for (double[] p : other) {
                atlas.util.RenderUtil.drawFilledBox(new AxisAlignedBB(p[0] - 0.04, p[1], p[2] - 0.04,
                        p[0] + 0.04, p[1] + 0.08, p[2] + 0.04).offset(-view.getRenderPosX(), -view.getRenderPosY(),
                        -view.getRenderPosZ()), 200, 200, 200, 110);
            }
        }
        if (path != null) {
            for (double[] p : path) {
                atlas.util.RenderUtil.drawFilledBox(new AxisAlignedBB(p[0] - 0.06, p[1], p[2] - 0.06,
                        p[0] + 0.06, p[1] + 0.12, p[2] + 0.06).offset(-view.getRenderPosX(), -view.getRenderPosY(),
                        -view.getRenderPosZ()), 255, 200, 40, 160);
            }
        }
        if (cell != null) {
            atlas.util.RenderUtil.drawBlockBoundingBox(cell, 1.0, 60, 220, 255, 220, 2.0F);
        }
        atlas.util.RenderUtil.disableRenderState();
    }

    /* The server's position of another player (S14/S18, in 1/32 blocks): the drawn one trails it. */
    /** The server's position of the player now: its last update moved on by the velocity for the ticks since. */
    private double nowX(EntityPlayer player) {
        return serverX(player) + this.sinceUpdate(player, 0);
    }

    private double nowZ(EntityPlayer player) {
        return serverZ(player) + this.sinceUpdate(player, 1);
    }

    private double sinceUpdate(EntityPlayer player, int axis) {
        double[] last = this.lastSeen.get(player.getEntityId());
        double[] motion = this.velocities.get(player.getEntityId());
        if (last == null || motion == null) {
            return 0.0;
        }
        return motion[axis] * Math.max(0, Math.min(3, this.tick - (int) last[2]));
    }

    private static double serverX(EntityPlayer player) {
        return player.serverPosX / 32.0;
    }

    private static double serverY(EntityPlayer player) {
        return player.serverPosY / 32.0;
    }

    private static double serverZ(EntityPlayer player) {
        return player.serverPosZ / 32.0;
    }

    @Override
    public String[] getSuffix() {
        String trigger = this.trigger.getValue() == 1 ? "Press " + this.pressKey.getKeyName() : null;
        if (this.targetName == null || this.tick - this.lastPlaceTick > 20) {
            return trigger == null ? new String[0] : new String[]{trigger};
        }
        return trigger == null ? new String[]{this.targetName} : new String[]{trigger, this.targetName};
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
