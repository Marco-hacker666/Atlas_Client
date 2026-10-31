package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.*;
import atlas.management.RotationHelper;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.RotationEngine;
import net.minecraft.block.BlockChest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.network.play.server.S24PacketBlockAction;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// Upstream author's note (translated): this ChestAura was taken from another client (Moshui), not from Eternity;
// the ChestAura in Eternity was contributed by the same author.
public class ChestAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final DecimalFormat df = new DecimalFormat("0.0");

    public final FloatProperty range = new FloatProperty("Range", 4.0f, 1.0f, 6.0f);
    public final BooleanProperty throughWalls = new BooleanProperty("Through Walls", true);
    public final ModeProperty moveFix = new ModeProperty("Move Fix", 1, new String[]{"None", "Silent", "Strict"});

    private final List<BlockPos> openedChests = new ArrayList<>();
    private TileEntityChest targetChest;
    private float[] rotations;
    private boolean isRotating;
    private boolean scaffoldWasEnabled = false;

    public ChestAura() {
        super("ChestAura", false);
    }

    @Override
    public void onEnabled() {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            scaffoldWasEnabled = true;
            scaffold.setEnabled(false);
        }
        openedChests.clear();
    }

    @Override
    public void onDisabled() {
        if (scaffoldWasEnabled) {
            Scaffold scaffold = (Scaffold) Atlas.moduleManager.getModule(Scaffold.class);
            if (scaffold != null) {
                scaffold.setEnabled(true);
            }
            scaffoldWasEnabled = false;
        }
        targetChest = null;
        isRotating = false;
    }

    @EventTarget(whenDisabled = true)
    public void onWorldLoad(LoadWorldEvent event) {
        openedChests.clear();
        scaffoldWasEnabled = false;
    }

    private void addOpenedChest(BlockPos pos) {
        if (!openedChests.contains(pos)) {
            openedChests.add(pos);
        }
        net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
        if (block instanceof BlockChest) {
            for (EnumFacing facing : EnumFacing.HORIZONTALS) {
                BlockPos neighbor = pos.offset(facing);
                if (mc.theWorld.getBlockState(neighbor).getBlock() == block) {
                    if (!openedChests.contains(neighbor)) {
                        openedChests.add(neighbor);
                    }
                }
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{df.format(range.getValue())};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled()) return;
        if (event.getPacket() instanceof S24PacketBlockAction) {
            S24PacketBlockAction packet = (S24PacketBlockAction) event.getPacket();
            if (packet.getData2() == 1) {
                addOpenedChest(packet.getBlockPosition());
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!isEnabled()) return;
        if (event.getType() != EventType.PRE) return;

        if (atlas.util.Auras.target() != null) {
            targetChest = null;
            isRotating = false;
            return;
        }

        if (mc.currentScreen instanceof GuiContainer || mc.currentScreen instanceof GuiInventory) {
            targetChest = null;
            isRotating = false;
            return;
        }

        for (TileEntity tileEntity : mc.theWorld.loadedTileEntityList) {
            if (tileEntity instanceof TileEntityChest) {
                TileEntityChest chest = (TileEntityChest) tileEntity;
                if (chest.numPlayersUsing > 0) {
                    addOpenedChest(chest.getPos());
                }
            }
        }

        targetChest = getClosestChest();
        isRotating = false;

        if (targetChest != null) {
            BlockPos pos = targetChest.getPos();
            double x = pos.getX() + 0.5 - mc.thePlayer.posX;
            double y = pos.getY() + 0.5 - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
            double z = pos.getZ() + 0.5 - mc.thePlayer.posZ;
            double dist = Math.sqrt(x * x + z * z);

            float yaw = (float) (Math.atan2(z, x) * 180.0 / Math.PI) - 90.0f;
            float pitch = (float) -(Math.atan2(y, dist) * 180.0 / Math.PI);

            /* 2026-10-31: the look goes through the shared engine (RotationHelper), which turns it as a hand
               does -- it used to jump onto the chest in one tick -- turns the keys for Silent and hands the
               look back when this stops asking. The chest is opened only along the look the engine returns,
               the one this tick's packet carries, at the point and face that look meets; before, it was
               clicked at once, on its top face at a corner, whatever the look. */
            float[] look = RotationHelper.request(this, yaw, pitch, 1, this.moveMode(), false, RotationHelper.NO_LIMIT);
            if (look == null) {
                return;
            }
            rotations = look;
            mc.thePlayer.rotationYawHead = look[0];
            mc.thePlayer.renderYawOffset = look[0];
            isRotating = true;

            MovingObjectPosition hit = this.lookOnChest(look, pos);
            if (hit != null && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                    mc.thePlayer.inventory.getCurrentItem(), pos, hit.sideHit, hit.hitVec)) {
                mc.thePlayer.swingItem();
                addOpenedChest(pos);
            }
        }
    }

    /** Move Fix as the engine's movement yaw: None the camera's, Silent the look's with the keys turned, Strict the look's. */
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

    /** Where the look's line meets the chest within range (through other blocks with Through Walls), or null. */
    private MovingObjectPosition lookOnChest(float[] look, BlockPos pos) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 dir = RotationEngine.direction(look[0], look[1]);
        double reach = this.range.getValue() + 1.0;
        Vec3 end = eye.addVector(dir.xCoord * reach, dir.yCoord * reach, dir.zCoord * reach);
        if (!this.throughWalls.getValue()) {
            MovingObjectPosition first = mc.theWorld.rayTraceBlocks(eye, end, false, true, false);
            return first != null && first.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && pos.equals(first.getBlockPos()) ? first : null;
        }
        return mc.theWorld.getBlockState(pos).getBlock().collisionRayTrace(mc.theWorld, pos, eye, end);
    }

    private TileEntityChest getClosestChest() {
        List<TileEntityChest> chests = mc.theWorld.loadedTileEntityList.stream()
                .filter(e -> e instanceof TileEntityChest)
                .map(e -> (TileEntityChest) e)
                .filter(e -> !openedChests.contains(e.getPos()))
                .filter(e -> mc.thePlayer.getDistanceSq(e.getPos()) <= range.getValue() * range.getValue())
                .filter(e -> throughWalls.getValue() || mc.thePlayer.canEntityBeSeen(
                        new net.minecraft.entity.item.EntityItem(mc.theWorld, e.getPos().getX(), e.getPos().getY(), e.getPos().getZ())))
                .sorted(Comparator.comparingDouble(e -> mc.thePlayer.getDistanceSq(e.getPos())))
                .collect(Collectors.toList());

        return chests.isEmpty() ? null : chests.get(0);
    }
}