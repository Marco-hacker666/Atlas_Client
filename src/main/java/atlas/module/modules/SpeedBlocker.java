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

public class SpeedBlocker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int PRIORITY = 6;

    private static final int FAILED_FACE_TICKS = 10;

    private static final int ATTEMPT_TICKS = 100;

    private static final int ANSWER_TICKS = 30;

    private static final double SAME_FLOOR = 2.5;

    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);

    public final FloatProperty scanRange = new FloatProperty("scan-range", 10.0F, 3.0F, 16.0F);

    public final FloatProperty minApproach = new FloatProperty("min-approach", 0.1F, 0.01F, 0.3F);

    public final IntProperty lead = new IntProperty("lead", 0, 0, 5);

    public final BooleanProperty leadPing = new BooleanProperty("lead-ping", true);

    public final IntProperty contactTicks = new IntProperty("contact-ticks", 30, 2, 60);

    public final ModeProperty trigger = new ModeProperty("trigger", 0, new String[]{"Auto", "Press"});
    public final atlas.property.properties.KeyProperty pressKey = new atlas.property.properties.KeyProperty("press-key",
            org.lwjgl.input.Keyboard.KEY_R, () -> this.trigger.getValue() == 1);
    public final FloatProperty pressTime = new FloatProperty("press-time", 3.0F, 0.5F, 10.0F,
            () -> this.trigger.getValue() == 1);
    public final FloatProperty stopDistance = new FloatProperty("stop-distance", 2.0F, 1.0F, 4.0F,
            () -> this.trigger.getValue() == 1);
    public final ModeProperty height = new ModeProperty("height", 0, new String[]{"FEET", "DOUBLE"});

    public final IntProperty blocksPerRun = new IntProperty("blocks-per-run", 1, 1, 3);

    public final BooleanProperty showPlan = new BooleanProperty("show-plan", false);

    public final BooleanProperty recordRuns = new BooleanProperty("record-runs", false);

    public final IntProperty speed = new IntProperty("speed", 90, 5, 180);
    public final IntProperty placeInterval = new IntProperty("place-interval", 2, 1, 10);
    public final IntProperty swapDelay = new IntProperty("swap-delay", 0, 0, 3);

    public final IntProperty restoreDelay = new IntProperty("restore-delay", 3, 0, 60);
    public final IntProperty maxRetries = new IntProperty("max-retries", 3, 1, 10);

    public final IntProperty retryCooldown = new IntProperty("retry-cooldown", 2000, 250, 10000);
    public final BooleanProperty requireSprint = new BooleanProperty("require-sprint", false);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty targetInvisible = new BooleanProperty("target-invisible", false);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT", "REAL"});
    public final BooleanProperty debug = new BooleanProperty("debug", false);
    public final BooleanProperty debugChat = new BooleanProperty("debug-chat", false, this.debug::getValue);

    private final Map<Integer, double[]> lastSeen = new HashMap<Integer, double[]>();

    private final Map<Integer, double[]> velocities = new HashMap<Integer, double[]>();

    private final Map<BlockPos, int[]> attempts = new ConcurrentHashMap<BlockPos, int[]>();
    private final Map<BlockPos, Long> refusedUntil = new ConcurrentHashMap<BlockPos, Long>();

    private final Map<BlockPos, int[]> pending = new ConcurrentHashMap<BlockPos, int[]>();
    private final java.util.Queue<Integer> landed = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();

    private final Map<PlaceUtil.FaceKey, Integer> failedFaces = new ConcurrentHashMap<PlaceUtil.FaceKey, Integer>();

    private final PlaceUtil.Stats stats = new PlaceUtil.Stats();
    private final PlaceUtil.Trace trace = new PlaceUtil.Trace("[SB]", this.debug, this.debugChat);

    private volatile int tick;

    private volatile PlaceUtil.Aim locked;
    private int lastPlaceTick = -100;
    private int swapWait;
    private int lastSlot = -1;

    private int wantedTick = -100;

    private int handsOffUntil;
    private static final int HANDS_OFF = 20;
    private String targetName;

    private final Map<Integer, int[]> runs = new HashMap<Integer, int[]>();

    private static final int RUN_GAP = 10;

    private volatile double[][] planPath;

    private volatile List<double[][]> otherPaths = java.util.Collections.emptyList();
    private volatile BlockPos planCell;

    private int lastTargetId = -1;

    private int lastQualifiedTick = -100;
    private static final int TARGET_HOLD = 6;
    private BlockPos committed;
    private int committedTarget = -1;
    private int committedUntil;

    private String goalWhy = "";
    private final atlas.combat.PressBurst press = new atlas.combat.PressBurst();

    private static final int PRESS_TICKS = 40;
    private static final int PRESS_CELLS = 16;

    private static final int PRESS_GOALS = 4;

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

        boolean bursting = pressMode && this.press.update(this.tick, this.pressKey.isDown(),
                Math.round(this.pressTime.getValue() * 20.0F));
        if (Arbiter.catching()) {
            this.locked = null;
            this.trace.log(this.tick, "stopped", "standing aside for " + Arbiter.catcher());
            this.restoreDue();
            return;
        }
        if (pressMode && !bursting) {
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

        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);

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

        float[] rotation = RotationHelper.request(this, aim.current ? serverYaw : aim.yaw,
                aim.current ? serverPitch : aim.pitch, PRIORITY, this.moveMode(), false, this.speed.getValue());
        if (rotation == null) {
            this.trace.log(this.tick, "waiting", "another module has the look");
            this.restoreDue();
            return;
        }

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
            Atlas.rotationManager.setRotation(rotation[0], rotation[1], PRIORITY, false);
        }
        this.restoreDue();
    }

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

    private void track() {
        Set<Integer> alive = new HashSet<Integer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            int id = player.getEntityId();
            alive.add(id);

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
                    this.velocities.remove(id);
                }
                continue;
            }
            int dt = Math.max(1, this.tick - (int) last[2]);
            double vx = (sx - last[0]) / dt;
            double vz = (sz - last[1]) / dt;
            if (vx * vx + vz * vz > 1.5 * 1.5) {
                this.velocities.remove(id);
            } else {
                double[] previous = this.velocities.get(id);
                if (previous != null) {
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
                continue;
            }
            double[] motion = this.velocities.get(player.getEntityId());
            if (motion == null) {
                continue;
            }
            double approach = SpeedblockPlan.approach(motion[0], motion[1], dx, dz);

            boolean held = player.getEntityId() == this.lastTargetId && this.tick - this.lastQualifiedTick <= TARGET_HOLD
                    && approach > 0.0;

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
                    continue;
                }
            }

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
                int[] at = cells.get(wall);
                this.planCell = new BlockPos(at[0], at[1], at[2]);
                this.goalWhy = "already walled at " + at[0] + "," + at[1] + "," + at[2] + ", watching";
                return goals;
            }
        }

        int need = this.needTicks(target);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);

        double reach = this.range.getValue() - 0.15;

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
                late++;
                continue;
            }
            if (!BlockUtil.isReplaceable(pos) || this.refusedUntil.containsKey(pos)
                    || this.pending.containsKey(pos)) {
                taken++;
                continue;
            }
            if (this.inOwnWay(pos)) {
                ours++;
                continue;
            }

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
            return new ArrayList<BlockPos>(goals.subList(0, PRESS_GOALS));
        }
        return goals;
    }

    private boolean walls(int[] cell, int feetY) {
        BlockPos pos = new BlockPos(cell[0], cell[1], cell[2]);
        net.minecraft.block.state.IBlockState state = mc.theWorld.getBlockState(pos);
        AxisAlignedBB box = state.getBlock().getCollisionBoundingBox(mc.theWorld, pos, state);
        return box != null && (cell[1] != feetY || box.maxY - pos.getY() > STEP_HEIGHT);
    }

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

    private void invalidate(PlaceUtil.Aim aim, String why) {
        this.failedFaces.put(aim.key(), this.tick + FAILED_FACE_TICKS);
        this.locked = null;
        int failed = this.bump(aim.cell);
        if (failed >= this.maxRetries.getValue()) {
            this.refuse(aim.cell);
        }
        this.trace.log(this.tick, "failure", why + " at " + fmt(aim.cell) + " (" + failed + "x), re-planning");
    }

    private void count(BlockPos cell) {
        if (this.bump(cell) >= this.maxRetries.getValue()) {
            this.refuse(cell);
        }
    }

    private int bump(BlockPos cell) {
        int now = this.tick;
        int[] after = this.attempts.compute(cell, (key, old) -> old == null ? new int[]{1, now}
                : new int[]{old[0] + 1, now});
        return after[0];
    }

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

    @EventTarget
    public void onSwap(atlas.events.SwapItemEvent event) {
        if (this.isEnabled() && this.lastSlot >= 0) {
            this.lastSlot = -1;
            this.locked = null;
            this.handsOffUntil = this.tick + HANDS_OFF;
        }
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

    private int needTicks(EntityPlayer target) {
        int base = this.leadPing.getValue() ? SpeedblockPlan.serverWallAfterTicks(atlas.util.Ping.own()) : 1;
        return base + this.lead.getValue();
    }

    private boolean inOwnWay(BlockPos pos) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        for (int t = 1; t <= 3; t++) {
            if (PlaceUtil.boxIntersects(box.offset(mc.thePlayer.motionX * t, 0.0, mc.thePlayer.motionZ * t), pos)) {
                return true;
            }
        }
        return false;
    }

    private atlas.combat.RunRecorder recorder;
    private java.io.File recordFile;

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

            this.recorder.observe(this.tick, player.getEntityId(), player.getName(), this.nowX(player),
                    this.nowZ(player), x, z, fresh, motion == null ? Double.NaN : motion[0],
                    motion == null ? Double.NaN : motion[1], player.rotationYawHead, mc.thePlayer.posX,
                    mc.thePlayer.posZ, player.onGround, sprinting,
                    this.recorder.samples(this.tick)
                            ? atlas.sim.SimTargets.path(player, atlas.combat.RunRecorder.LENGTH) : null);
        }
    }

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
