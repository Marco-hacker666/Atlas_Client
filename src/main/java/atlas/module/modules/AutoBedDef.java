package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.Render2DEvent;
import atlas.events.SwapItemEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.BlockFalling;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Covers your own bed with blocks: both sides, both ends, then the top.
 *
 * 2026-10-27 (report: "AutoBedDef does not work"), what was wrong:
 *  - any bed within 8 blocks was "ours" -- an enemy's too, so with BedNuker
 *    it walled up the bed being broken. Now the first bed found in a world is
 *    yours (you spawn next to it, the waiting lobby has none) and only that one
 *    is covered (own-bed-only; off: the nearest bed, as before).
 *  - near a bed the hand was forced onto wool every tick, and every scroll
 *    was cancelled while the module was on, covered bed or not: no sword in a
 *    fight at home. Now the block is taken only while there is a block to
 *    place, and the slot you had (or scrolled to meanwhile) comes back after.
 *  - only wool counted: no wool, nothing happened. Now any full solid block
 *    (wool first) unless wool-only.
 *  - the top went onto the bed with sneaking faked on the client only: the
 *    server, never told, used the bed instead ("you can only sleep at night")
 *    and the client showed a block that was not there. Now the sneak is real
 *    (held through the movement input, so the server gets it a tick before the
 *    click), and a block beside is clicked instead when there is one.
 *  - the top's aim picked the spot FARTHEST from the current look; now the
 *    nearest, as for the sides.
 *  - the click was checked against a look guessed from this module's own
 *    numbers; with a module of higher priority turning (Clutch, Scaffold) the
 *    server had another one. Now it is checked against the look the server
 *    last received (the movement packet's, UpdateEvent POST).
 *  - range 5 by default: past vanilla's 4.5 block reach. Default 4.5 now.
 */
public class AutoBedDef extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();

    public final FloatProperty range = new FloatProperty("range", 4.5f, 3.0f, 7.0f);
    public final IntProperty placeDelay = new IntProperty("place-delay", 80, 0, 300);
    public final IntProperty speed = new IntProperty("speed", 100, 30, 100);
    public final IntProperty rotTol = new IntProperty("rot-tolerance", 35, 5, 180);
    public final BooleanProperty swing = new BooleanProperty("swing", true);
    public final BooleanProperty showProgress = new BooleanProperty("show-progress", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "REAL"});
    public final BooleanProperty ownBedOnly = new BooleanProperty("own-bed-only", true);
    public final BooleanProperty woolOnly = new BooleanProperty("wool-only", false);

    private static final int ROT_PRIORITY = 6;
    private static final int BED_SEARCH_RADIUS = 12;

    /** The look the server last received (the movement packet's). */
    private float sentYaw;
    private float sentPitch;
    private float aimYaw;
    private float aimPitch;

    private BlockPos targetBlock;
    private EnumFacing targetFacing;
    private Vec3 targetHitVec;
    /** The click goes onto the bed's top: the server must know we sneak, or it uses the bed. */
    private boolean needSneak;

    private long lastPlaceTime = 0;
    /** The slot to give back once nothing is left to place; -1: the hand is the player's. */
    private int restoreSlot = -1;
    private float progress = 0f;

    private BlockPos lockedBedFoot = null;
    private EnumFacing lockedBedFacing = null;
    /** A bed was taken as ours in this world: once it is gone, no other bed is. */
    private boolean bedSeen;

    public AutoBedDef() {
        super("AutoBedDef", false);
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer != null) {
            sentYaw = mc.thePlayer.rotationYaw;
            sentPitch = mc.thePlayer.rotationPitch;
            aimYaw = sentYaw;
            aimPitch = sentPitch;
        }
        progress = 0f;
        resetTarget();
        restoreSlot = -1;
        lockedBedFoot = null;
        lockedBedFacing = null;
        bedSeen = false;
    }

    @Override
    public void onDisabled() {
        idle();
        progress = 0f;
        lockedBedFoot = null;
        lockedBedFacing = null;
    }

    /* Tick PRE runs before the update that would recompute these, so on the
       first tick of a new world the old world's target was still live. */
    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        resetTarget();
        needSneak = false;
        restoreSlot = -1;
        lockedBedFoot = null;
        lockedBedFacing = null;
        bedSeen = false;
    }

    private void resetTarget() {
        targetBlock = null;
        targetFacing = null;
        targetHitVec = null;
    }

    /** Nothing to place now: no target, no sneak, the player's slot back. */
    private void idle() {
        resetTarget();
        needSneak = false;
        if (restoreSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = restoreSlot;
        }
        restoreSlot = -1;
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() == EventType.POST) {
            sentYaw = event.getYaw();
            sentPitch = event.getPitch();
            return;
        }
        if (mc.currentScreen != null) return;

        if (!isValidBed(lockedBedFoot)) {
            lockedBedFoot = null;
            lockedBedFacing = null;
            if (!ownBedOnly.getValue() || !bedSeen) {
                findNearestBed();
            }
        }
        updateProgress();
        if (lockedBedFoot == null) {
            idle();
            return;
        }

        List<BlockPos> defensePositions = getDefensePositions();
        int nextIdx = findNextDefensePosIndex(defensePositions);
        if (nextIdx == -1) {
            idle();
            return;
        }
        int blockSlot = findBlockSlot();
        if (blockSlot == -1) {
            idle();
            return;
        }
        boolean isRoof = nextIdx >= 6;
        computePlacement(defensePositions.get(nextIdx), isRoof);
        if (targetBlock == null) {
            /* Not reachable from here: the hand stays the player's. */
            idle();
            return;
        }
        if (mc.thePlayer.inventory.currentItem != blockSlot) {
            if (restoreSlot == -1) {
                restoreSlot = mc.thePlayer.inventory.currentItem;
            }
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        float yawDiff = MathHelper.wrapAngleTo180_float(aimYaw - sentYaw);
        float pitchDiff = aimPitch - sentPitch;
        float maxTurn = (float) speed.getValue();

        float yaw = sentYaw + MathHelper.clamp_float(yawDiff, -maxTurn, maxTurn);
        float pitch = MathHelper.clamp_float(sentPitch + MathHelper.clamp_float(pitchDiff, -maxTurn, maxTurn), -90f, 90f);

        event.setRotation(yaw, pitch, ROT_PRIORITY);
        event.setPervRotation(moveFix.getValue() != 0 ? yaw : mc.thePlayer.rotationYaw, ROT_PRIORITY);
        if (moveFix.getModeString().equals("REAL")) {
            /* REAL: the camera turns as well, spread over this tick's frames by
               RotationManager; the mouse still works (not forced). */
            Atlas.rotationManager.setRotation(yaw, pitch, ROT_PRIORITY, false);
        }
    }

    /** Start of the tick, before vanilla's input: the click, along the look the server already has. */
    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (!isEnabled()) return;
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.currentScreen != null) return;
        if (targetBlock == null || targetFacing == null || targetHitVec == null) return;

        if (!withinRotationTolerance(sentYaw, sentPitch, aimYaw, aimPitch)) return;
        if (needSneak && !mc.thePlayer.isSneaking()) {
            /* The sneak goes to the server with this tick's movement; the click next tick. */
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPlaceTime < placeDelay.getValue()) return;

        MovingObjectPosition mop = rayTrace(sentYaw, sentPitch, range.getValue());
        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(targetBlock)
                || mop.sideHit != targetFacing) return;

        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        float savedYaw = mc.thePlayer.rotationYaw;
        float savedPitch = mc.thePlayer.rotationPitch;
        mc.thePlayer.rotationYaw = sentYaw;
        mc.thePlayer.rotationPitch = sentPitch;
        mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, targetBlock, targetFacing, mop.hitVec);
        mc.thePlayer.rotationYaw = savedYaw;
        mc.thePlayer.rotationPitch = savedPitch;

        if (swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            try {
                mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
            } catch (Exception ignored) {
            }
        }

        lastPlaceTime = now;
        resetTarget();
    }

    @EventTarget
    public void onMove(MoveInputEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (needSneak && !mc.thePlayer.movementInput.sneak) {
            /* A real sneak, slowed as vanilla slows it, so the server knows before the click on the bed. */
            mc.thePlayer.movementInput.sneak = true;
            mc.thePlayer.movementInput.moveStrafe *= 0.3F;
            mc.thePlayer.movementInput.moveForward *= 0.3F;
        }
        if (moveFix.getValue() == 1
                && RotationState.isActived()
                && RotationState.getPriority() == ROT_PRIORITY
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    /** While the hand holds our block, a scroll picks the slot that comes back after. */
    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (isEnabled() && restoreSlot != -1) {
            restoreSlot = event.setSlot(restoreSlot);
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!isEnabled() || mc.currentScreen != null) return;
        if (!showProgress.getValue() || lockedBedFoot == null) return;
        if (mc.fontRendererObj == null) return;

        String text = String.format("%.0f%%", progress * 100.0f);

        GL11.glPushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        ScaledResolution sr = new ScaledResolution(mc);
        int width = mc.fontRendererObj.getStringWidth(text);

        mc.fontRendererObj.drawString(
                text,
                (float) sr.getScaledWidth() / 2.0f - (float) width / 2.0f,
                (float) sr.getScaledHeight() / 5.0f * 2.0f,
                getProgressColor().getRGB() & 16777215 | -1090519040,
                true
        );

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GL11.glPopMatrix();
    }

    private void findNearestBed() {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        BlockPos origin = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ));
        int radius = ownBedOnly.getValue() ? BED_SEARCH_RADIUS : 8;

        double bestDist = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    if (!isBedFoot(p)) continue;

                    EnumFacing facing = getBedFacing(p);
                    if (facing == null) continue;

                    double dist = mc.thePlayer.getDistanceSq(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                    if (dist < bestDist) {
                        bestDist = dist;
                        lockedBedFoot = p;
                        lockedBedFacing = facing;
                    }
                }
            }
        }
        if (lockedBedFoot != null) {
            bedSeen = true;
        }
    }

    private boolean isBedFoot(BlockPos pos) {
        Block b = mc.theWorld.getBlockState(pos).getBlock();
        if (!(b instanceof BlockBed)) return false;
        int meta = b.getMetaFromState(mc.theWorld.getBlockState(pos));
        return (meta & 8) == 0;
    }

    private boolean isValidBed(BlockPos pos) {
        if (pos == null) return false;
        return mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed;
    }

    private EnumFacing getBedFacing(BlockPos pos) {
        int meta = mc.theWorld.getBlockState(pos).getBlock().getMetaFromState(mc.theWorld.getBlockState(pos));
        switch (meta & 3) {
            case 0:
                return EnumFacing.SOUTH;
            case 1:
                return EnumFacing.WEST;
            case 2:
                return EnumFacing.NORTH;
            case 3:
                return EnumFacing.EAST;
            default:
                return null;
        }
    }

    private List<BlockPos> getDefensePositions() {
        List<BlockPos> list = new ArrayList<>();
        if (lockedBedFoot == null || lockedBedFacing == null) return list;

        BlockPos foot = lockedBedFoot;
        BlockPos head = foot.offset(lockedBedFacing);
        EnumFacing fwd = lockedBedFacing;
        EnumFacing back = fwd.getOpposite();
        EnumFacing left = leftOf(fwd);
        EnumFacing right = left.getOpposite();

        list.add(foot.offset(left));
        list.add(foot.offset(right));
        list.add(head.offset(left));
        list.add(head.offset(right));
        list.add(foot.offset(back));
        list.add(head.offset(fwd));
        list.add(foot.up(1));
        list.add(head.up(1));

        return list;
    }

    private int findNextDefensePosIndex(List<BlockPos> positions) {
        for (int i = 0; i < positions.size(); i++) {
            if (isReplaceable(positions.get(i))) return i;
        }
        return -1;
    }

    /**
     * The click that puts a block at {@code wantPlace}: the face of a block
     * next to it, reachable along a look that lands there. The nearest look
     * to the current one wins; a bed is clicked only for the top and only
     * when nothing else is there (it needs the sneak).
     */
    private void computePlacement(BlockPos wantPlace, boolean isRoof) {
        resetTarget();
        needSneak = false;

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0f);
        double reach = range.getValue();
        double reachSq = reach * reach;

        float bestDiff = Float.MAX_VALUE;
        boolean bestOnBed = true;
        double[] offsets = {0.2, 0.4, 0.5, 0.6, 0.8};

        for (EnumFacing face : EnumFacing.values()) {
            BlockPos support = wantPlace.offset(face);

            if (isReplaceable(support)) continue;
            Block supportBlock = mc.theWorld.getBlockState(support).getBlock();
            boolean onBed = supportBlock instanceof BlockBed;
            if (onBed && !isRoof) continue;
            if (onBed && targetBlock != null && !bestOnBed) continue;

            double dx = support.getX() + 0.5 - eyes.xCoord;
            double dy = support.getY() + 0.5 - eyes.yCoord;
            double dz = support.getZ() + 0.5 - eyes.zCoord;
            if (dx * dx + dy * dy + dz * dz > (reach + 1.5) * (reach + 1.5)) continue;

            EnumFacing clickFace = face.getOpposite();

            for (double u : offsets) {
                for (double v : offsets) {
                    Vec3 hitPos = getFacePoint(support, clickFace, u, v);

                    double hdx = hitPos.xCoord - eyes.xCoord;
                    double hdy = hitPos.yCoord - eyes.yCoord;
                    double hdz = hitPos.zCoord - eyes.zCoord;
                    if (hdx * hdx + hdy * hdy + hdz * hdz > reachSq) continue;

                    float[] rot = computeRotations(hdx, hdy, hdz);

                    MovingObjectPosition mop = rayTrace(rot[0], rot[1], reach);
                    if (mop == null
                            || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                            || !mop.getBlockPos().equals(support)
                            || mop.sideHit != clickFace) continue;

                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - sentYaw)) + Math.abs(rot[1] - sentPitch);
                    boolean better = targetBlock == null || bestOnBed && !onBed || bestOnBed == onBed && diff < bestDiff;
                    if (better) {
                        bestDiff = diff;
                        bestOnBed = onBed;
                        aimYaw = rot[0];
                        aimPitch = rot[1];
                        targetBlock = support;
                        targetFacing = clickFace;
                        targetHitVec = mop.hitVec;
                    }
                }
            }
        }
        needSneak = targetBlock != null && bestOnBed;
    }

    private Vec3 getFacePoint(BlockPos block, EnumFacing face, double u, double v) {
        double x = block.getX();
        double y = block.getY();
        double z = block.getZ();
        switch (face) {
            case DOWN:
                return new Vec3(x + u, y, z + v);
            case UP:
                return new Vec3(x + u, y + 1.0, z + v);
            case NORTH:
                return new Vec3(x + u, y + v, z);
            case SOUTH:
                return new Vec3(x + u, y + v, z + 1.0);
            case WEST:
                return new Vec3(x, y + v, z + u);
            case EAST:
                return new Vec3(x + 1.0, y + v, z + u);
            default:
                return new Vec3(x + 0.5, y + 0.5, z + 0.5);
        }
    }

    private float[] computeRotations(double dx, double dy, double dz) {
        double hd = Math.sqrt(dx * dx + dz * dz);
        float yaw = MathHelper.wrapAngleTo180_float((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, hd)));
        return new float[]{yaw, pitch};
    }

    private MovingObjectPosition rayTrace(float yaw, float pitch, double distance) {
        float yr = (float) Math.toRadians(yaw);
        float pr = (float) Math.toRadians(pitch);
        double lx = -Math.sin(yr) * Math.cos(pr);
        double ly = -Math.sin(pr);
        double lz = Math.cos(yr) * Math.cos(pr);
        Vec3 start = mc.thePlayer.getPositionEyes(1.0f);
        Vec3 end = start.addVector(lx * distance, ly * distance, lz * distance);
        return mc.theWorld.rayTraceBlocks(start, end);
    }

    private boolean withinRotationTolerance(float useYaw, float usePitch, float targetYaw, float targetPitch) {
        float dy = Math.abs(MathHelper.wrapAngleTo180_float(useYaw - targetYaw));
        float dp = Math.abs(MathHelper.wrapAngleTo180_float(usePitch - targetPitch));
        return dy <= rotTol.getValue() && dp <= rotTol.getValue();
    }

    /** Wool first; else (unless wool-only) any full solid block that places plainly. */
    private int findBlockSlot() {
        int other = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || stack.stackSize == 0) continue;
            if (!(stack.getItem() instanceof ItemBlock)) continue;
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (block == Blocks.wool) return slot;
            if (other == -1 && !woolOnly.getValue() && defends(block)) other = slot;
        }
        return other;
    }

    private static boolean defends(Block block) {
        return block.isFullCube() && block.getMaterial().isSolid() && !(block instanceof BlockFalling)
                && !(block instanceof BlockContainer) && block != Blocks.tnt && block != Blocks.crafting_table
                && block != Blocks.ice && block != Blocks.slime_block;
    }

    private boolean isReplaceable(BlockPos pos) {
        Block b = mc.theWorld.getBlockState(pos).getBlock();
        return b == Blocks.air
                || b == Blocks.water
                || b == Blocks.flowing_water
                || b == Blocks.lava
                || b == Blocks.flowing_lava
                || b == Blocks.fire;
    }

    private EnumFacing leftOf(EnumFacing f) {
        switch (f) {
            case NORTH:
                return EnumFacing.WEST;
            case WEST:
                return EnumFacing.SOUTH;
            case SOUTH:
                return EnumFacing.EAST;
            case EAST:
                return EnumFacing.NORTH;
            default:
                return EnumFacing.NORTH;
        }
    }

    private void updateProgress() {
        if (lockedBedFoot == null || lockedBedFacing == null) {
            progress = 0f;
            return;
        }
        List<BlockPos> positions = getDefensePositions();
        int filled = 0;
        for (BlockPos pos : positions) {
            if (!isReplaceable(pos)) filled++;
        }
        progress = positions.isEmpty() ? 0f : (float) filled / (float) positions.size();
    }

    private Color getProgressColor() {
        if (progress <= 0.33f) return new Color(255, 85, 85);
        else if (progress <= 0.66f) return new Color(255, 255, 85);
        else return new Color(85, 255, 85);
    }

    /** The slot the player has: the one that comes back while our block is in hand. */
    public int getSlot() {
        return restoreSlot != -1 ? restoreSlot : mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : -1;
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final atlas.module.ModuleBundle defenderMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.BedDefender(), "defender", "Bed defender", false);
    public final atlas.module.ModuleBundle autoBedMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.AutoBed(), "auto-bed", "Bed protection", false);
}
