package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.Render2DEvent;
import atlas.events.RightClickMouseEvent;
import atlas.events.SwapItemEvent;
import atlas.events.UpdateEvent;
import atlas.management.Arbiter;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.BlockUtil;
import atlas.util.ChatUtil;
import atlas.util.MoveUtil;
import atlas.util.PlaceUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AutoBlockIn extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int PRIORITY = 6;
    private static final EnumFacing[] HORIZONTAL = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};

    private static final int FAILED_FACE_TICKS = 10;

    private static final int ANSWER_TICKS = 30;

    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);

    public final IntProperty speed = new IntProperty("speed", 45, 5, 180);
    public final IntProperty placeInterval = new IntProperty("place-interval", 1, 1, 5);
    public final IntProperty swapDelay = new IntProperty("swap-delay", 1, 0, 3);
    public final IntProperty maxRetries = new IntProperty("max-retries", 3, 1, 10);

    public final IntProperty retryCooldown = new IntProperty("retry-cooldown", 2000, 250, 10000);
    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT", "REAL"});
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", true);
    public final BooleanProperty showProgress = new BooleanProperty("show-progress", true);
    public final BooleanProperty debug = new BooleanProperty("debug", false);

    public final BooleanProperty debugChat = new BooleanProperty("debug-chat", false, this.debug::getValue);

    private final Map<BlockPos, Integer> pending = new ConcurrentHashMap<BlockPos, Integer>();

    private final Map<BlockPos, Integer> attempts = new ConcurrentHashMap<BlockPos, Integer>();

    private final Map<BlockPos, Long> refusedUntil = new ConcurrentHashMap<BlockPos, Long>();

    private final Map<PlaceUtil.FaceKey, Integer> failedFaces = new ConcurrentHashMap<PlaceUtil.FaceKey, Integer>();

    private final PlaceUtil.Stats stats = new PlaceUtil.Stats();
    private final PlaceUtil.Trace trace = new PlaceUtil.Trace("[AB]", this.debug, this.debugChat);

    private volatile int tick;
    private volatile int pauseTicks;
    private PlaceUtil.Aim locked;
    private int lastPlaceTick = -100;
    private int swapWait;
    private int lastSlot = -1;
    private float progress;

    private float lastTurn;
    private float placedTurn = -1.0F;

    public AutoBlockIn() {
        super("AutoBlockIn", false);
    }

    @Override
    public void onEnabled() {
        this.resetState();
        this.trace.reset();
        this.lastSlot = mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : -1;
        this.trace.log(this.tick, "enabled", "");
    }

    @Override
    public void onDisabled() {
        if (mc.thePlayer != null && this.lastSlot >= 0 && this.lastSlot <= 8) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        this.lastSlot = -1;
        this.resetState();
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.resetState();
    }

    private void resetState() {
        this.locked = null;
        this.pending.clear();
        this.attempts.clear();
        this.refusedUntil.clear();
        this.failedFaces.clear();
        this.pauseTicks = 0;
        this.swapWait = 0;
        this.lastPlaceTick = -100;
        this.progress = 0.0F;
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
        final int now = ++this.tick;
        this.trace.log(now, "tick", "");
        this.pending.entrySet().removeIf(entry -> now - entry.getValue() > ANSWER_TICKS);
        this.failedFaces.entrySet().removeIf(entry -> now >= entry.getValue());
        final long ms = System.currentTimeMillis();
        this.refusedUntil.entrySet().removeIf(entry -> ms >= entry.getValue());

        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            this.locked = null;
            this.trace.log(now, "stopped", "server moved us, pausing");
            return;
        }
        if (Arbiter.catching()) {
            this.locked = null;
            this.trace.log(now, "stopped", "standing aside for " + Arbiter.catcher());
            return;
        }

        List<BlockPos> body = this.bodyCells();
        Set<BlockPos> bodySet = new HashSet<BlockPos>(body);
        List<BlockPos> sides = this.sideGoals(body, bodySet);
        List<BlockPos> roofs = this.roofGoals(body);
        this.updateProgress(body, bodySet);
        if (sides.isEmpty() && roofs.isEmpty()) {
            this.locked = null;
            if (this.pending.isEmpty()) {
                this.trace.log(now, "done", "no open goal, nothing pending");
                if (this.autoDisable.getValue()) {
                    ChatUtil.sendFormatted("&7[&aAutoBlockIn&7] &fblocked in, switching off");
                    this.setEnabled(false);
                }
            } else {
                this.trace.log(now, "waiting", this.pending.size() + " placement(s) unanswered");
            }
            return;
        }
        this.trace.log(now, "activation conditions passed", "sides " + sides.size() + ", roof " + roofs.size()
                + ", body " + body.size() + " cells");

        int slot = PlaceUtil.bestBlockSlot();
        if (slot < 0) {
            this.locked = null;
            this.trace.log(now, "stopped", "no usable blocks in hotbar");
            return;
        }
        if (mc.thePlayer.inventory.currentItem != slot) {
            mc.thePlayer.inventory.currentItem = slot;
            this.swapWait = this.swapDelay.getValue();
            this.trace.log(now, "swap", "slot " + slot + ", waiting " + this.swapWait);
        }
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();

        float serverYaw = event.getYaw();
        float serverPitch = event.getPitch();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double reach = this.range.getValue();

        PlaceUtil.Aim aim = this.plan(body, bodySet, sides, roofs, eye, reach, serverYaw, serverPitch, stack, now);
        this.locked = aim;
        if (aim == null) {
            if (this.swapWait > 0) {
                this.swapWait--;
            }
            return;
        }

        float[] rotation = aim.current ? new float[]{serverYaw, serverPitch}
                : PlaceUtil.step(serverYaw, serverPitch, aim.yaw, aim.pitch, this.speed.getValue());
        float turn = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw));

        if (turn > 2.0F && (Math.abs(turn - this.placedTurn) < 1.0E-3F
                || atlas.management.PlaceRotations.wouldDuplicate(rotation[0]))) {
            double gcd = atlas.util.RotationUtil.gcd();
            rotation[0] += Math.signum(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw))
                    * (float) (gcd > 0.0 ? gcd : 0.01);
            turn = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - serverYaw));
        }
        if (turn > 0.0F) {
            this.lastTurn = turn;
        }

        if (this.swapWait <= 0 && now - this.lastPlaceTick >= this.placeInterval.getValue()) {
            MovingObjectPosition hit = PlaceUtil.verify(eye, reach, rotation[0], rotation[1], aim.support, aim.face);
            if (hit != null) {
                PlaceUtil.Aim shot = aim.verified(hit.hitVec, true);
                boolean placed = PlaceUtil.click(shot);
                this.trace.log(now, "controller click result", placed + " " + shot);
                if (placed) {
                    this.lastPlaceTick = now;
                    this.pending.put(aim.cell, now);
                    this.locked = null;
                    this.placedTurn = this.lastTurn;
                    this.trace.log(now, "success", "placed at " + fmt(aim.cell));
                } else {
                    this.invalidate(shot, now, "click returned false");
                }
            }
        }
        if (this.swapWait > 0) {
            this.swapWait--;
        }
        this.trace.log(now, "rotation calculated", String.format("%.1f/%.1f -> %.1f/%.1f (target %.1f/%.1f)",
                serverYaw, serverPitch, rotation[0], rotation[1], aim.yaw, aim.pitch));
        event.setRotation(rotation[0], rotation[1], PRIORITY);
        event.setPervRotation(this.moveFix.getValue() != 0 ? rotation[0] : mc.thePlayer.rotationYaw, PRIORITY);
        if (this.moveFix.getModeString().equals("REAL")) {
            Atlas.rotationManager.setRotation(rotation[0], rotation[1], PRIORITY, false);
        }
    }

    private PlaceUtil.Aim plan(List<BlockPos> body, Set<BlockPos> bodySet, List<BlockPos> sides, List<BlockPos> roofs,
                               Vec3 eye, double reach, float yaw, float pitch, ItemStack stack, int now) {
        Set<PlaceUtil.FaceKey> excluded = this.failedFaces.keySet();
        String[] names = {"sides", "side-support", "roof", "roof-support"};
        for (int tier = 0; tier < 4; tier++) {
            List<BlockPos> goals;
            switch (tier) {
                case 0:
                    goals = sides;
                    break;
                case 1:
                    goals = this.supportGoals(sides, bodySet);
                    break;
                case 2:
                    goals = roofs;
                    break;
                default:
                    goals = this.supportGoals(roofs, bodySet);
                    break;
            }
            if (goals.isEmpty()) {
                continue;
            }
            this.stats.reset();
            PlaceUtil.Aim aim = PlaceUtil.bestAim(goals, eye, reach, yaw, pitch, this.locked, stack, excluded, this.stats);
            this.trace.log(now, "target " + names[tier], goals.size() + " goal(s), candidate count = "
                    + this.stats.candidates + ", rays " + this.stats.rays + ", result " + this.stats.source);
            if (aim != null) {
                this.trace.log(now, "best candidate selected", names[tier] + " " + aim);
                return aim;
            }
        }
        this.trace.log(now, "no candidate", "nothing reachable in any tier");
        return null;
    }

    private void invalidate(PlaceUtil.Aim aim, int now, String why) {
        this.failedFaces.put(aim.key(), now + FAILED_FACE_TICKS);
        this.locked = null;
        int failed = this.attempts.merge(aim.cell, 1, Integer::sum);
        if (failed >= this.maxRetries.getValue()) {
            this.refuse(aim.cell);
        }
        this.trace.log(now, "failure", why + " at " + fmt(aim.cell) + " (" + failed + "x), re-planning");
    }

    private void refuse(BlockPos cell) {
        this.refusedUntil.put(cell, System.currentTimeMillis() + this.retryCooldown.getValue());
        this.attempts.remove(cell);

        if (mc.isCallingFromMinecraftThread()) {
            this.trace.log(this.tick, "refused", fmt(cell) + " for " + this.retryCooldown.getValue() + "ms");
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
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

    public int getSlot() {
        return this.lastSlot;
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (this.isEnabled() && this.moveFix.getValue() == 1 && RotationState.isActived()
                && RotationState.getPriority() == PRIORITY && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.pauseTicks = 2;
            this.pending.clear();
            return;
        }
        if (!(event.getPacket() instanceof S23PacketBlockChange)) {
            return;
        }
        S23PacketBlockChange change = (S23PacketBlockChange) event.getPacket();
        BlockPos pos = change.getBlockPosition();
        if (pos == null || this.pending.remove(pos) == null) {
            return;
        }
        if (change.getBlockState() != null && BlockUtil.isReplaceable(change.getBlockState().getBlock())) {
            int failed = this.attempts.merge(pos, 1, Integer::sum);
            if (failed >= this.maxRetries.getValue()) {
                this.refuse(pos);
            }
        } else {
            this.attempts.remove(pos);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || !this.showProgress.getValue() || mc.currentScreen != null || mc.fontRendererObj == null) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        String text = String.format("Blocking: %.0f%%", this.progress * 100.0F);
        int color = this.progress <= 0.33F ? 0xFF5555 : this.progress <= 0.66F ? 0xFFFF55 : 0x55FF55;
        mc.fontRendererObj.drawString(text,
                sr.getScaledWidth() / 2.0F - mc.fontRendererObj.getStringWidth(text) / 2.0F,
                sr.getScaledHeight() / 5.0F * 2.0F, color | 0xBF000000, true);
    }

    private List<BlockPos> bodyCells() {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        int x0 = MathHelper.floor_double(box.minX);
        int x1 = MathHelper.floor_double(box.maxX - 1.0E-4);
        int y0 = MathHelper.floor_double(box.minY);
        int y1 = MathHelper.floor_double(box.maxY - 1.0E-4);
        int z0 = MathHelper.floor_double(box.minZ);
        int z1 = MathHelper.floor_double(box.maxZ - 1.0E-4);
        List<BlockPos> cells = new ArrayList<BlockPos>();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    private List<BlockPos> sideGoals(List<BlockPos> body, Set<BlockPos> bodySet) {
        Set<BlockPos> goals = new LinkedHashSet<BlockPos>();
        for (BlockPos cell : body) {
            for (EnumFacing facing : HORIZONTAL) {
                BlockPos side = cell.offset(facing);
                if (!bodySet.contains(side) && this.isOpenGoal(side)) {
                    goals.add(side);
                }
            }
        }
        return new ArrayList<BlockPos>(goals);
    }

    private List<BlockPos> roofGoals(List<BlockPos> body) {
        int top = Integer.MIN_VALUE;
        for (BlockPos cell : body) {
            top = Math.max(top, cell.getY());
        }
        List<BlockPos> roofs = new ArrayList<BlockPos>();
        for (BlockPos cell : body) {
            if (cell.getY() == top && this.isOpenGoal(cell.up())) {
                roofs.add(cell.up());
            }
        }
        return roofs;
    }

    private List<BlockPos> supportGoals(List<BlockPos> goals, Set<BlockPos> bodySet) {
        Set<BlockPos> goalSet = new HashSet<BlockPos>(goals);
        Set<BlockPos> supports = new LinkedHashSet<BlockPos>();
        for (BlockPos goal : goals) {
            for (EnumFacing facing : EnumFacing.values()) {
                BlockPos cell = goal.offset(facing);
                if (goalSet.contains(cell) || bodySet.contains(cell) || supports.contains(cell)
                        || !this.isOpenGoal(cell)) {
                    continue;
                }
                for (EnumFacing next : EnumFacing.values()) {
                    if (PlaceUtil.clickable(cell.offset(next))) {
                        supports.add(cell);
                        break;
                    }
                }
            }
        }
        return new ArrayList<BlockPos>(supports);
    }

    private boolean isOpenGoal(BlockPos pos) {
        return BlockUtil.isReplaceable(pos)
                && !PlaceUtil.boxIntersects(mc.thePlayer.getEntityBoundingBox(), pos)
                && !this.pending.containsKey(pos)
                && !this.refusedUntil.containsKey(pos);
    }

    private void updateProgress(List<BlockPos> body, Set<BlockPos> bodySet) {
        int total = 0;
        int filled = 0;
        Set<BlockPos> seen = new HashSet<BlockPos>();
        int top = Integer.MIN_VALUE;
        for (BlockPos cell : body) {
            top = Math.max(top, cell.getY());
        }
        for (BlockPos cell : body) {
            List<BlockPos> around = new ArrayList<BlockPos>();
            for (EnumFacing facing : HORIZONTAL) {
                around.add(cell.offset(facing));
            }
            if (cell.getY() == top) {
                around.add(cell.up());
            }
            for (BlockPos pos : around) {
                if (bodySet.contains(pos) || !seen.add(pos)) {
                    continue;
                }
                total++;
                if (!BlockUtil.isReplaceable(pos)) {
                    filled++;
                }
            }
        }
        this.progress = total == 0 ? 0.0F : (float) filled / total;
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
