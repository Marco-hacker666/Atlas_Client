package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0): throws an ender pearl at the nearest surface
// when knocked into the void. One throw per fall, slot restored after.
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.UpdateEvent;
import atlas.management.RotationHelper;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.util.RotationEngine;
import atlas.util.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

/*
 * 2026-10-31, moved onto the shared engine (RotationHelper), and made to throw:
 * - the look turns to the surface as a hand does (it jumped there in one
 *   tick) and is handed back after the throw; with silent-aim off the camera
 *   follows the same look;
 * - the pearl is thrown only once the look the server already has -- the
 *   last packet's, which a throw goes along -- is on the surface;
 * - it is thrown as a pearl is (a use, as the vanilla right click sends it):
 *   a right click on a block, which this used, never throws one in 1.8, so
 *   no pearl ever left;
 * - the aim is from the eyes to the surface: the surface's world coordinates
 *   were taken for the offset to it, which pointed somewhere else entirely;
 * - the surface is a face open to the air (it took one against another block);
 * - the slot is switched only for the throw, not on every tick of the fall.
 */
public class PearlSaver extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Rotation priority: as before, below Scaffold (3) and Clutch (7). */
    private static final int ROT_PRIORITY = 2;
    /** The last sent look within this many degrees of the aim counts as on the surface (the aim's own dead zone is 1). */
    private static final float ON_TARGET = 2.0F;
    private boolean thrown = false;

    public final FloatProperty minFall = new FloatProperty("min-fall", 4.0F, 2.0F, 12.0F);
    public final FloatProperty reach = new FloatProperty("reach", 5.0F, 3.0F, 8.0F);
    public final BooleanProperty silentAim = new BooleanProperty("silent-aim", true);

    public PearlSaver() {
        super("PearlSaver", false, false, "Throws an ender pearl to save you from void falls.");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null || mc.playerController == null) {
            return;
        }
        if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
            this.thrown = false;
            return;
        }
        if (this.thrown || mc.thePlayer.fallDistance < this.minFall.getValue() || mc.thePlayer.motionY >= 0.0) {
            return;
        }
        if (this.groundBelow()) {
            return;
        }
        int pearlSlot = this.findPearlSlot();
        if (pearlSlot == -1) {
            return;
        }
        Placement placement = this.findSurface();
        if (placement == null) {
            return;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        float[] want = RotationUtil.getRotationsTo(placement.hitVec.xCoord - eyes.xCoord,
                placement.hitVec.yCoord - eyes.yCoord, placement.hitVec.zCoord - eyes.zCoord,
                RotationHelper.serverYaw(), RotationHelper.serverPitch());
        boolean silent = this.silentAim.getValue();
        float[] look = RotationHelper.request(this, want[0], want[1], ROT_PRIORITY,
                silent ? RotationHelper.MOVE_CAMERA : RotationHelper.MOVE_LOOK, false, RotationHelper.NO_LIMIT);
        if (look != null && !silent) {
            /* The camera follows the look the server gets, spread over this tick's frames. */
            Atlas.rotationManager.setRotation(look[0], look[1], ROT_PRIORITY, false);
        }
        if (RotationEngine.angle(event.getYaw(), event.getPitch(), want[0], want[1]) > ON_TARGET) {
            return;
        }
        /* The slot is only synced (updateController would also run the incoming packets mid-update);
           sendUseItem syncs the pearl's slot itself before its use. */
        int previous = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = pearlSlot;
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack != null && mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, stack)) {
            this.thrown = true;
        }
        mc.thePlayer.inventory.currentItem = previous;
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    private boolean groundBelow() {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        int scan = this.reach.getValue().intValue() + 4;
        for (int y = 1; y <= scan; y++) {
            if (!mc.theWorld.isAirBlock(feet.down(y))) {
                return true;
            }
        }
        return false;
    }

    private Placement findSurface() {
        BlockPos feet = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        int radius = this.reach.getValue().intValue();
        Placement best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= 1; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (pos.equals(feet) || mc.theWorld.isAirBlock(pos)) {
                        continue;
                    }
                    for (EnumFacing face : EnumFacing.values()) {
                        if (mc.theWorld.isAirBlock(pos.offset(face))) {
                            double dist = mc.thePlayer.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (dist < bestDist && dist <= mc.playerController.getBlockReachDistance() + 1.5F) {
                                bestDist = dist;
                                Vec3 hit = new Vec3(pos.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                                        pos.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                                        pos.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5);
                                best = new Placement(pos, face, hit);
                            }
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    private int findPearlSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemEnderPearl && stack.stackSize > 0) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onDisabled() {
        this.thrown = false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.thrown ? "spent" : "ready"};
    }

    private static class Placement {
        final BlockPos pos;
        final EnumFacing face;
        final Vec3 hitVec;

        Placement(BlockPos pos, EnumFacing face, Vec3 hitVec) {
            this.pos = pos;
            this.face = face;
            this.hitVec = hitVec;
        }
    }
}
