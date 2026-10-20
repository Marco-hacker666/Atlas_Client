package myau.module.modules;

import com.google.common.base.CaseFormat;
import myau.Myau;
import myau.enums.ChatColors;
import myau.enums.DelayModules;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.*;
import myau.management.RotationState;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.PercentProperty;
import myau.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockBed.EnumPartType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

public class BedNuker extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final long WHITELIST_SCAN_DELAY_MS = 1000L;
    private final TimerUtil timer = new TimerUtil();
    private final ArrayList<BlockPos> bedWhitelist = new ArrayList<BlockPos>();
    private final Color colorRed = new Color(ChatColors.RED.toAwtColor());
    private final Color colorYellow = new Color(ChatColors.YELLOW.toAwtColor());
    private final Color colorGreen = new Color(ChatColors.GREEN.toAwtColor());
    private BlockPos targetBed = null;
    private int breakStage = 0;
    private int tickCounter = 0;
    private float breakProgress = 0.0F;
    private boolean isBed = false;
    private int savedSlot = -1;
    private boolean readyToBreak = false;
    private boolean breaking = false;
    private boolean waitingForStart = false;
    private long whitelistScanAt = -1L;
    private int breakCooldown = 0;
    private BlockPos ownBedHead = null;
    /* The dig log (2026-10-20): each START and STOP, and whether the block was really gone 0.6 s later, in
       config/Atlas/bednuker-*.txt, to line up with the server's flags. */
    private static final java.io.File LOG_DIR = new java.io.File("./config/Atlas/");
    private static final java.text.SimpleDateFormat FILE_STAMP = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final java.text.SimpleDateFormat LINE_STAMP = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
    private java.io.File logTarget;
    private int groundTicks;
    private BlockPos checkPos;
    private long checkAt;
    private String checkWhat = "";
    private BlockPos ownBedFoot = null;
    private boolean ownBedPending = false;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "SWAP"});
    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 6.0F);
    public final PercentProperty speed = new PercentProperty("speed", 0);
    public final BooleanProperty groundSpeed = new BooleanProperty("ground-spoof", false);
    public final ModeProperty ignoreVelocity = new ModeProperty("ignore-velocity", 0, new String[]{"NONE", "CANCEL", "DELAY"});
    public final BooleanProperty surroundings = new BooleanProperty("surroundings", true);
    public final BooleanProperty toolCheck = new BooleanProperty("tool-check", true);
    public final BooleanProperty whiteList = new BooleanProperty("whitelist", true);

    /* Never break your own bed.
       The whitelist above cannot do this job: it only fills after a Hypixel
       specific chat line, so on any other server it stays empty, and even
       when it does run it protects every bed within 25 blocks including the
       enemy's. Your bed is instead identified by being the one nearest to
       where you spawn, which holds on every Bedwars implementation and needs
       no server specific text. */
    public final BooleanProperty protectOwnBed = new BooleanProperty("protect-own-bed", true);
    public final IntProperty ownBedRadius = new IntProperty("own-bed-radius", 20, 5, 40);
    public final BooleanProperty swing = new BooleanProperty("swing", true);

    /* Vanilla sets destroyDelay = 5 ticks after every successful break in
       PlayerControllerMP, so chaining the next block immediately is faster
       than the game can go. LiquidBounce's PacketMine uses 6 for the same
       reason and calls out network variance as the motivation. This applied
       only to beds before -- surrounding blocks were broken back to back
       with no cooldown at all. */
    public final IntProperty postBreakDelay = new IntProperty("post-break-delay", 6, 0, 20);

    /* Extra progress required beyond the normal threshold. The server runs
       its own break timer; finishing before it agrees is what "breaking too
       fast" means. A margin trades a little speed for tolerance when the
       client and server disagree, which they do more the higher the ping. */
    public final FloatProperty breakMargin = new FloatProperty("break-margin", 0.0F, 0.0F, 1.0F);

    /* Abandoning a dig without telling the server leaves it believing the
       block is still being mined, so the next START on another block arrives
       while one is already open. */
    public final BooleanProperty abortOnDrop = new BooleanProperty("abort-on-drop", true);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "STRICT", "REAL"});
    public final ModeProperty showTarget = new ModeProperty("show-target", 1, new String[]{"NONE", "DEFAULT", "HUD"});
    public final ModeProperty showProgress = new ModeProperty("show-progress", 1, new String[]{"NONE", "DEFAULT", "HUD"});

    private void resetBreaking() {
        if (this.targetBed != null && mc.theWorld != null && mc.thePlayer != null) {
            /* Only when a dig was actually opened (stage 1) and not already
               closed by a STOP (stage 2); aborting a dig the server never
               heard about is itself an unexpected packet. */
            if (this.abortOnDrop.getValue() && this.breakStage == 1) {
                PacketUtil.sendPacket(new C07PacketPlayerDigging(
                        Action.ABORT_DESTROY_BLOCK, this.targetBed, this.getHitFacing(this.targetBed)));
            }
            mc.theWorld.sendBlockBreakProgress(mc.thePlayer.getEntityId(), this.targetBed, -1);
        }
        this.targetBed = null;
        this.breakStage = 0;
        this.tickCounter = 0;
        this.breakProgress = 0.0F;
        this.isBed = false;
        this.readyToBreak = false;
        this.breaking = false;
    }

    private void scheduleWhitelistScan() {
        this.whitelistScanAt = System.currentTimeMillis() + WHITELIST_SCAN_DELAY_MS;
    }

    private void runPendingWhitelistScan() {
        if (this.whitelistScanAt == -1L || System.currentTimeMillis() < this.whitelistScanAt) {
            return;
        }
        this.whitelistScanAt = -1L;
        this.bedWhitelist.clear();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        int sX = MathHelper.floor_double(mc.thePlayer.posX);
        int sY = MathHelper.floor_double(mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight());
        int sZ = MathHelper.floor_double(mc.thePlayer.posZ);
        for (int i = sX - 25; i <= sX + 25; i++) {
            for (int j = sY - 25; j <= sY + 25; j++) {
                for (int k = sZ - 25; k <= sZ + 25; k++) {
                    BlockPos blockPos = new BlockPos(i, j, k);
                    Block block = mc.theWorld.getBlockState(blockPos).getBlock();
                    if (block instanceof BlockBed) {
                        this.bedWhitelist.add(blockPos);
                    }
                }
            }
        }
    }

    /** Both halves of a bed: HEAD and FOOT sit in adjacent blocks. */
    private BlockPos otherBedHalf(BlockPos pos) {
        IBlockState state = mc.theWorld.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockBed)) {
            return null;
        }
        EnumFacing facing = (EnumFacing) state.getValue(BlockBed.FACING);
        return state.getValue(BlockBed.PART) == EnumPartType.HEAD
                ? pos.offset(facing.getOpposite())
                : pos.offset(facing);
    }

    /**
     * Marks the bed closest to the current position as ours.
     *
     * Called right after a spawn, where "closest bed" and "our bed" are the
     * same thing on every Bedwars map. Scanning a flat slab rather than a
     * cube keeps this to a few thousand lookups instead of the 130k the
     * whitelist scan costs.
     */
    private void findOwnBed() {
        this.ownBedPending = false;
        this.ownBedHead = null;
        this.ownBedFoot = null;
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        int radius = this.ownBedRadius.getValue();
        int px = MathHelper.floor_double(mc.thePlayer.posX);
        int py = MathHelper.floor_double(mc.thePlayer.posY);
        int pz = MathHelper.floor_double(mc.thePlayer.posZ);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int y = py - 6; y <= py + 6; y++) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!(mc.theWorld.getBlockState(pos).getBlock() instanceof BlockBed)) {
                        continue;
                    }
                    double distance = mc.thePlayer.getDistanceSq(x + 0.5, y + 0.5, z + 0.5);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        if (best != null) {
            this.ownBedHead = best;
            this.ownBedFoot = this.otherBedHalf(best);
        }
    }

    private boolean isOwnBed(BlockPos pos) {
        if (!this.protectOwnBed.getValue()) {
            return false;
        }
        return pos.equals(this.ownBedHead) || pos.equals(this.ownBedFoot);
    }

    private float calcProgress() {
        if (this.targetBed == null) {
            return 0.0F;
        } else {
            float progress = this.breakProgress;
            if (this.groundSpeed.getValue()) {
                int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, mc.theWorld.getBlockState(this.targetBed).getBlock());
                progress = (float) this.tickCounter * this.getBreakDelta(mc.theWorld.getBlockState(this.targetBed), this.targetBed, slot, true);
            }
            return Math.min(1.0F, progress / (1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F)));
        }
    }

    private void restoreSlot() {
        if (this.savedSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.savedSlot;
            this.syncHeldItem();
            this.savedSlot = -1;
        }
    }

    private void syncHeldItem() {
        int currentPlayerItem = ((IAccessorPlayerControllerMP) mc.playerController).getCurrentPlayerItem();
        if (mc.thePlayer.inventory.currentItem != currentPlayerItem) {
            mc.thePlayer.stopUsingItem();
        }
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    private boolean hasProperTool(Block block) {
        Material material = block.getMaterial();
        if (material != Material.iron && material != Material.anvil && material != Material.rock) {
            return true;
        } else {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
                if (stack != null) {
                    Item item = stack.getItem();
                    if (item instanceof ItemPickaxe) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /*
     * 2026-10-20 (owner: "BedNuker digs but gets flagged / the block will not
     * break"). The face used to come from a look ray toward the block, and
     * when that ray met another block first -- the bed's cover, a corner --
     * the face reported was that other block's. A dig whose face is not the
     * target's side toward the player is what Grim's PositionBreak and
     * WrongBreak refuse, and a refused dig is a block that does not break.
     * Now: the ray's face only if the ray lands on the target itself, else
     * the target's face pointing at the eye.
     */
    private EnumFacing getHitFacing(BlockPos blockPos) {
        net.minecraft.util.Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        net.minecraft.util.Vec3 centre = new net.minecraft.util.Vec3(blockPos.getX() + 0.5, blockPos.getY() + 0.5,
                blockPos.getZ() + 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, centre, false, true, false);
        if (mop != null && mop.typeOfHit == MovingObjectType.BLOCK && blockPos.equals(mop.getBlockPos())
                && mop.sideHit != null) {
            return mop.sideHit;
        }
        return facingToward(blockPos, eye);
    }

    /** The face of the block at pos whose side the point is on, by the largest offset from the centre. */
    static EnumFacing facingToward(BlockPos pos, net.minecraft.util.Vec3 point) {
        double dx = point.xCoord - (pos.getX() + 0.5);
        double dy = point.yCoord - (pos.getY() + 0.5);
        double dz = point.zCoord - (pos.getZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy >= 0.0 ? EnumFacing.UP : EnumFacing.DOWN;
        }
        if (ax >= az) {
            return dx >= 0.0 ? EnumFacing.EAST : EnumFacing.WEST;
        }
        return dz >= 0.0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    /** Whether the line from the eye to the block's centre meets this block first (no digging through walls). */
    private boolean visible(BlockPos pos) {
        net.minecraft.util.Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        net.minecraft.util.Vec3 centre = new net.minecraft.util.Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, centre, false, true, false);
        return mop == null || mop.typeOfHit != MovingObjectType.BLOCK || pos.equals(mop.getBlockPos());
    }

    private float getDigSpeed(IBlockState iBlockState, int slot, boolean boolean5) {
        ItemStack item = mc.thePlayer.inventory.getStackInSlot(slot);
        float digSpeed = item == null ? 1.0F : item.getItem().getDigSpeed(item, iBlockState);
        if (digSpeed > 1.0F) {
            int enchantmentLevel = EnchantmentHelper.getEnchantmentLevel(Enchantment.efficiency.effectId, item);
            if (enchantmentLevel > 0) {
                digSpeed += (float) (enchantmentLevel * enchantmentLevel + 1);
            }
        }
        if (mc.thePlayer.isPotionActive(Potion.digSpeed)) {
            digSpeed *= 1.0F + (float) (mc.thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier() + 1) * 0.2F;
        }
        if (mc.thePlayer.isPotionActive(Potion.digSlowdown)) {
            switch (mc.thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) {
                case 0:
                    digSpeed *= 0.3F;
                    break;
                case 1:
                    digSpeed *= 0.09F;
                    break;
                case 2:
                    digSpeed *= 0.0027F;
                    break;
                default:
                    digSpeed *= 8.1E-4F;
            }
        }
        if (mc.thePlayer.isInsideOfMaterial(Material.water) && !EnchantmentHelper.getAquaAffinityModifier(mc.thePlayer)) {
            digSpeed /= 5.0F;
        }
        if (!boolean5) {
            digSpeed /= 5.0F;
        }
        return digSpeed;
    }

    boolean canHarvest(Block block, int slot) {
        if (block.getMaterial().isToolNotRequired()) {
            return true;
        } else {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            return stack != null && stack.canHarvestBlock(block);
        }
    }

    private float getBreakDelta(IBlockState iBlockState, BlockPos blockPos, int slot, boolean boolean5) {
        Block block = iBlockState.getBlock();
        float hardness = block.getBlockHardness(mc.theWorld, blockPos);
        float boost = this.canHarvest(block, slot) ? 30.0F : 100.0F;
        return hardness < 0.0F ? 0.0F : this.getDigSpeed(iBlockState, slot, boolean5) / hardness / boost;
    }

    private float calcBlockStrength(BlockPos blockPos) {
        IBlockState blockState = mc.theWorld.getBlockState(blockPos);
        int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, blockState.getBlock());
        return this.getBreakDelta(blockState, blockPos, slot, mc.thePlayer.onGround);
    }

    private BlockPos validateBedPlacement(BlockPos bedPosition) {
        IBlockState blockState = mc.theWorld.getBlockState(bedPosition);
        if (blockState.getBlock() instanceof BlockBed) {
            ArrayList<BlockPos> pos = new ArrayList<>();
            EnumPartType partType = blockState.getValue(BlockBed.PART);
            EnumFacing facing = blockState.getValue(BlockBed.FACING);
            for (BlockPos blockPos : Arrays.asList(bedPosition, bedPosition.offset(partType == EnumPartType.HEAD ? facing.getOpposite() : facing))) {
                for (EnumFacing enumFacing : Arrays.asList(EnumFacing.UP, EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST)) {
                    Block block = mc.theWorld.getBlockState(blockPos.offset(enumFacing)).getBlock();
                    if (BlockUtil.isReplaceable(block)) {
                        return null;
                    }
                    if (!(block instanceof BlockBed)) {
                        pos.add(blockPos.offset(enumFacing));
                    }
                }
            }
            if (!pos.isEmpty()) {
                /* One in sight first (2026-10-20): the easiest cover block could be on the far side of the
                   bed, dug through it -- a break through a wall, which Grim refuses. */
                pos.sort(
                        (blockPos, blockPos2) -> {
                            int seen = Boolean.compare(this.visible(blockPos2), this.visible(blockPos));
                            if (seen != 0) {
                                return seen;
                            }
                            int o = Float.compare(this.calcBlockStrength(blockPos2), this.calcBlockStrength(blockPos));
                            return o != 0
                                    ? o
                                    : Double.compare(
                                    blockPos.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ),
                                    blockPos2.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ)
                            );
                        }
                );
                return pos.get(0);
            }
        }
        return null;
    }

    private BlockPos findNearestBed() {
        return this.findTargetBed(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
    }

    private BlockPos findTargetBed(double x, double y, double z) {
        ArrayList<BlockPos> targets = new ArrayList<>();
        int sX = MathHelper.floor_double(x);
        int sY = MathHelper.floor_double(y);
        int sZ = MathHelper.floor_double(z);
        for (int i = sX - 6; i <= sX + 6; i++) {
            for (int j = sY - 6; j <= sY + 6; j++) {
                for (int k = sZ - 6; k <= sZ + 6; k++) {
                    BlockPos newPos = new BlockPos(i, j, k);
                    if (this.isOwnBed(newPos)) {
                        continue;
                    }
                    if (!(Boolean) this.whiteList.getValue() || !this.bedWhitelist.contains(newPos)) {
                        Block block = mc.theWorld.getBlockState(newPos).getBlock();
                        if (block instanceof BlockBed
                                && PlayerUtil.isBlockWithinReach(newPos, x, y, z, this.range.getValue().doubleValue())) {
                            targets.add(newPos);
                        }
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return null;
        } else {
            targets.sort(
                    Comparator.comparingDouble(
                            blockPos -> blockPos.distanceSqToCenter(mc.thePlayer.posX, mc.thePlayer.posY + (double) mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ)
                    )
            );
            for (BlockPos blockPos : targets) {
                if (this.surroundings.getValue()) {
                    BlockPos pos = this.validateBedPlacement(blockPos);
                    if (pos != null) {
                        Block block = mc.theWorld.getBlockState(pos).getBlock();
                        if (this.toolCheck.getValue() && !this.hasProperTool(block)) {
                            continue;
                        }
                        return pos;
                    }
                }
                return blockPos;
            }
            return null;
        }
    }

    private void doSwing() {
        if (this.swing.getValue()) {
            mc.thePlayer.swingItem();
        } else {
            PacketUtil.sendPacket(new C0APacketAnimation());
        }
    }

    private Color getProgressColor(int mode) {
        switch (mode) {
            case 1:
                float progress = this.calcProgress();
                if (progress <= 0.5F) {
                    return ColorUtil.interpolate(progress / 0.5F, this.colorRed, this.colorYellow);
                }
                return ColorUtil.interpolate((progress - 0.5F) / 0.5F, this.colorYellow, this.colorGreen);
            case 2:
                return ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
            default:
                return new Color(-1);
        }
    }

    public BedNuker() {
        super("BedNuker", false);
    }

    private void log(String line) {
        if (this.logTarget == null) {
            this.logTarget = new java.io.File(LOG_DIR, "bednuker-" + FILE_STAMP.format(new java.util.Date()) + ".txt");
        }
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new java.util.Date()) + "  " + line);
    }

    public boolean isReady() {
        return this.targetBed != null && this.readyToBreak;
    }

    public boolean isBreaking() {
        return this.targetBed != null && this.breaking;
    }

    @EventTarget(value = Priority.HIGH, whenDisabled = true)
    public void onTick(TickEvent event) {
        if (event.getType() == EventType.PRE) {
            this.runPendingWhitelistScan();
            if (!this.isEnabled()) {
                return;
            }
            if (this.breakCooldown > 0) {
                this.breakCooldown--;
            }
            if (this.checkPos != null && System.currentTimeMillis() >= this.checkAt) {
                /* The client removes the block at STOP; a refusal puts it back. */
                boolean back = !mc.theWorld.isAirBlock(this.checkPos);
                log((back ? "REFUSED " : "BROKEN ") + this.checkWhat + " " + this.checkPos.getX() + ","
                        + this.checkPos.getY() + "," + this.checkPos.getZ() + (back ? " -- the server put it back" : ""));
                this.checkPos = null;
            }
            if (this.ownBedPending) {
                this.findOwnBed();
            }
            // Our bed being gone means it was broken; stop reserving the spot.
            if (this.ownBedHead != null
                    && !(mc.theWorld.getBlockState(this.ownBedHead).getBlock() instanceof BlockBed)) {
                this.ownBedHead = null;
                this.ownBedFoot = null;
            }
            AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
            if(autoBlockIn.isEnabled()) return;
            if (this.targetBed != null) {
                if (mc.theWorld.isAirBlock(this.targetBed) || !PlayerUtil.canReach(this.targetBed, this.range.getValue().doubleValue())) {
                    this.restoreSlot();
                    this.resetBreaking();
                } else if (!this.isBed) {
                    BlockPos nearestBed = this.findNearestBed();
                    if (nearestBed != null && mc.theWorld.getBlockState(nearestBed).getBlock() instanceof BlockBed) {
                        this.resetBreaking();
                    }
                }
            }
            if (this.targetBed != null) {
                int slot = ItemUtil.findInventorySlot(mc.thePlayer.inventory.currentItem, mc.theWorld.getBlockState(this.targetBed).getBlock());
                if (this.mode.getValue() == 0 && this.savedSlot == -1) {
                    this.savedSlot = mc.thePlayer.inventory.currentItem;
                    mc.thePlayer.inventory.currentItem = slot;
                    this.syncHeldItem();
                }
                switch (this.breakStage) {
                    case 0:
                        if (!mc.thePlayer.isUsingItem()) {
                            IBlockState startState = mc.theWorld.getBlockState(this.targetBed);
                            float perTick = this.getBreakDelta(startState, this.targetBed, slot, mc.thePlayer.onGround);
                            log("START " + startState.getBlock().getLocalizedName() + " " + this.targetBed.getX() + ","
                                    + this.targetBed.getY() + "," + this.targetBed.getZ() + " face "
                                    + this.getHitFacing(this.targetBed) + (this.visible(this.targetBed) ? "" : " NOT-IN-SIGHT")
                                    + String.format(" | %.1f blocks | slot %d | ground %s | %.0f ticks at this speed",
                                    Math.sqrt(this.targetBed.distanceSqToCenter(mc.thePlayer.posX,
                                            mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ)),
                                    slot, mc.thePlayer.onGround, perTick > 0.0F ? Math.ceil(1.0F / perTick) : -1.0));
                            this.groundTicks = 0;
                            this.doSwing();
                            PacketUtil.sendPacket(
                                    new C07PacketPlayerDigging(Action.START_DESTROY_BLOCK, this.targetBed, this.getHitFacing(this.targetBed))
                            );
                            this.doSwing();
                            mc.effectRenderer.addBlockHitEffects(this.targetBed, this.getHitFacing(this.targetBed));
                            this.breakStage = 1;
                        }
                        break;
                    case 1:
                        if (this.mode.getValue() == 1) {
                            this.readyToBreak = false;
                        }
                        this.breaking = true;
                        this.tickCounter++;
                        if (mc.thePlayer.onGround) {
                            this.groundTicks++;
                        }
                        this.breakProgress = this.breakProgress
                                + this.getBreakDelta(mc.theWorld.getBlockState(this.targetBed), this.targetBed, slot, mc.thePlayer.onGround);
                        float tick = (float) this.tickCounter;
                        IBlockState blockState = mc.theWorld.getBlockState(this.targetBed);
                        boolean canBreak = mc.thePlayer.onGround && this.groundSpeed.getValue();
                        BlockPos target = this.targetBed;
                        float delta = tick * this.getBreakDelta(blockState, target, slot, canBreak);
                        mc.effectRenderer.addBlockHitEffects(this.targetBed, this.getHitFacing(this.targetBed));
                        float required = 1.0F - 0.3F * ((float) this.speed.getValue().intValue() / 100.0F)
                                + this.breakMargin.getValue();
                        if (this.breakProgress >= required || delta >= required) {
                            if (this.mode.getValue() == 1) {
                                this.readyToBreak = true;
                                this.savedSlot = mc.thePlayer.inventory.currentItem;
                                mc.thePlayer.inventory.currentItem = slot;
                                this.syncHeldItem();
                                if (mc.thePlayer.isUsingItem()) {
                                    this.savedSlot = mc.thePlayer.inventory.currentItem;
                                    mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;
                                    this.syncHeldItem();
                                }
                            }
                            this.breaking = false;
                            log(String.format("STOP after %d ticks (%d on ground) | progress %.2f | face %s%s",
                                    this.tickCounter, this.groundTicks, this.breakProgress, this.getHitFacing(this.targetBed),
                                    this.visible(this.targetBed) ? "" : " NOT-IN-SIGHT"));
                            this.checkPos = this.targetBed;
                            this.checkAt = System.currentTimeMillis() + 600L;
                            this.checkWhat = mc.theWorld.getBlockState(this.targetBed).getBlock().getLocalizedName();
                            PacketUtil.sendPacket(
                                    new C07PacketPlayerDigging(Action.STOP_DESTROY_BLOCK, this.targetBed, this.getHitFacing(this.targetBed))
                            );
                            this.doSwing();
                            IBlockState blockState_ = mc.theWorld.getBlockState(this.targetBed);
                            Block block = blockState_.getBlock();
                            if (block.getMaterial() != Material.air) {
                                mc.theWorld.playAuxSFX(2001, this.targetBed, Block.getStateId(blockState_));
                                mc.theWorld.setBlockToAir(this.targetBed);
                            }
                            if (block instanceof BlockBed) {
                                this.timer.reset();
                            }
                            this.breakCooldown = this.postBreakDelay.getValue();
                            this.breakStage = 2;
                        }
                        break;
                    case 2:
                        this.restoreSlot();
                        this.resetBreaking();
                }
                if (this.targetBed != null) {
                    return;
                }
            }
            if (mc.thePlayer.capabilities.allowEdit && this.timer.hasTimeElapsed(500)
                    && this.breakCooldown <= 0) {
                this.targetBed = this.findNearestBed();
                this.breakStage = 0;
                this.tickCounter = 0;
                this.breakProgress = 0.0F;
                this.isBed = this.targetBed != null && mc.theWorld.getBlockState(this.targetBed).getBlock() instanceof BlockBed;
                this.restoreSlot();
                if (this.targetBed != null) {
                    this.readyToBreak = true;
                }
            }
            if (this.targetBed == null) {
                Myau.delayManager.setDelayState(false, DelayModules.BED_NUKER);
            }
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            AutoBlockIn autoBlockIn = (AutoBlockIn) Myau.moduleManager.modules.get(AutoBlockIn.class);
            if(autoBlockIn.isEnabled()) return;
            if (this.isReady()) {
                double x = (double) this.targetBed.getX() + 0.5 - mc.thePlayer.posX;
                double y = (double) this.targetBed.getY() + 0.5 - mc.thePlayer.posY - (double) mc.thePlayer.getEyeHeight();
                double z = (double) this.targetBed.getZ() + 0.5 - mc.thePlayer.posZ;
                float[] rotations = RotationUtil.getRotationsTo(x, y, z, event.getYaw(), event.getPitch());
                event.setRotation(rotations[0], rotations[1], 5);
                event.setPervRotation(this.moveFix.getValue() != 0 ? rotations[0] : mc.thePlayer.rotationYaw, 5);
                if (this.moveFix.getModeString().equals("REAL")) {
                    /* REAL: the camera turns as well, spread over this tick's frames by
                       RotationManager; the mouse still works (not forced). */
                    Myau.rotationManager.setRotation(rotations[0], rotations[1], 5, false);
                }
            }
        }
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (this.isEnabled()) {
            if (this.isBreaking()
                    && !Myau.playerStateManager.attacking
                    && !Myau.playerStateManager.digging
                    && !Myau.playerStateManager.placing
                    && !Myau.playerStateManager.swinging) {
                this.doSwing();
            }
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (this.isEnabled()) {
            if (this.moveFix.getValue() == 1
                    && RotationState.isActived()
                    && RotationState.getPriority() == 5.0F
                    && MoveUtil.isForwardPressed()) {
                MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onKnockback(KnockbackEvent event) {
        if (this.isEnabled() && !event.isCancelled() && !(event.getY() <= 0.0)) {
            if (this.ignoreVelocity.getValue() == 1 && this.targetBed != null) {
                event.setCancelled(true);
                event.setX(mc.thePlayer.motionX);
                event.setY(mc.thePlayer.motionY);
                event.setZ(mc.thePlayer.motionZ);
            }
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (this.isEnabled()) {
            if (this.targetBed != null && (!this.isBed || !this.surroundings.getValue())) {
                if (this.showProgress.getValue() != 0) {
                    HUD hud = (HUD) Myau.moduleManager.modules.get(HUD.class);
                    float scale = hud.scale.getValue();
                    String text = String.format("%d%%", (int) (this.calcProgress() * 100.0F));
                    GlStateManager.pushMatrix();
                    GlStateManager.scale(scale, scale, 0.0F);
                    GlStateManager.disableDepth();
                    GlStateManager.enableBlend();
                    GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    int width = mc.fontRendererObj.getStringWidth(text);
                    mc.fontRendererObj
                            .drawString(
                                    text,
                                    (float) new ScaledResolution(mc).getScaledWidth() / 2.0F / scale - (float) width / 2.0F,
                                    (float) new ScaledResolution(mc).getScaledHeight() / 5.0F * 2.0F / scale,
                                    this.getProgressColor(this.showProgress.getValue()).getRGB() & 16777215 | -1090519040,
                                    hud.shadow.getValue()
                            );
                    GlStateManager.disableBlend();
                    GlStateManager.enableDepth();
                    GlStateManager.popMatrix();
                }
            }
        }
    }

    @EventTarget(Priority.LOW)
    public void onRender3D(Render3DEvent event) {
        if (this.isEnabled() && this.targetBed != null && !mc.theWorld.isAirBlock(this.targetBed)) {
            mc.theWorld.sendBlockBreakProgress(mc.thePlayer.getEntityId(), this.targetBed, (int) (this.calcProgress() * 10.0F) - 1);
            if (this.showTarget.getValue() != 0) {
                BedESP bedESP = (BedESP) myau.module.ModuleBundle.of(myau.module.modules.BedESP.class);
                Color color = this.getProgressColor(this.showTarget.getValue());
                RenderUtil.enableRenderState();
                BlockPos target = this.targetBed;
                double newHeight = this.isBed ? bedESP.getHeight() : 1.0;
                int r = color.getRed();
                int g = color.getBlue();
                int b = color.getGreen();
                RenderUtil.drawBlockBox(target, newHeight, r, b, g);
                RenderUtil.disableRenderState();
            }
        }
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        this.waitingForStart = false;
        this.whitelistScanAt = -1L;
        this.bedWhitelist.clear();
        this.resetBreaking();
    }

    @EventTarget(whenDisabled = true)
    public void onPacket(PacketEvent event) {
        if (!event.isCancelled()) {
            if (event.getPacket() instanceof S02PacketChat) {
                String text = ((S02PacketChat) event.getPacket()).getChatComponent().getFormattedText();
                if (text.contains("§e§lProtect your bed and destroy the enemy bed") || text.contains("§e§lDestroy the enemy bed and then eliminate them")) {
                    this.waitingForStart = true;
                }
            }
            if (event.getPacket() instanceof S08PacketPlayerPosLook && this.waitingForStart) {
                this.waitingForStart = false;
                this.bedWhitelist.clear();
                this.scheduleWhitelistScan();
            }
            /* Any respawn or teleport is a chance to locate our bed, which is
               why this does not depend on the game-start message: that line is
               Hypixel's wording and never arrives on other servers. */
            if (event.getPacket() instanceof S07PacketRespawn
                    || event.getPacket() instanceof S08PacketPlayerPosLook) {
                this.ownBedPending = true;
            }
            if (this.isEnabled() && this.targetBed != null && this.ignoreVelocity.getValue() == 2 && Myau.delayManager.getDelayModule() != DelayModules.BED_NUKER) {
                if (event.getPacket() instanceof S12PacketEntityVelocity) {
                    S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
                    if (packet.getEntityID() == mc.thePlayer.getEntityId() && packet.getMotionY() > 0) {
                        Myau.delayManager.delay(DelayModules.BED_NUKER);
                        Myau.delayManager.delayedPacket.offer(packet);
                        event.setCancelled(true);
                    }
                }
                if (event.getPacket() instanceof S27PacketExplosion) {
                    S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
                    if (explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F || explosion.func_149147_e() != 0.0F) {
                        Myau.delayManager.delay(DelayModules.BED_NUKER);
                        Myau.delayManager.delayedPacket.offer(explosion);
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled()) {
            if (this.isReady() || this.targetBed != null && mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled()) {
            if (this.isReady()) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onHitBlock(HitBlockEvent event) {
        if (this.isEnabled()) {
            if (this.isReady() || this.targetBed != null && mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled()) {
            if (this.savedSlot != -1) {
                event.setCancelled(true);
            }
        }
    }

    @Override
    public void onDisabled() {
        this.resetBreaking();
        this.savedSlot = -1;
        this.waitingForStart = false;
        this.whitelistScanAt = -1L;
        this.bedWhitelist.clear();
        Myau.delayManager.setDelayState(false, DelayModules.BED_NUKER);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, this.mode.getModeString())};
    }
}
