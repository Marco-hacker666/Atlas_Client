package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.UpdateEvent;
import atlas.management.RotationHelper;
import atlas.module.Module;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ItemUtil;
import atlas.util.PacketUtil;
import atlas.util.RotationEngine;
import atlas.util.RotationUtil;
import atlas.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

public class ThrowAura extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final IntProperty cooldown = new IntProperty("cooldown", 500, 0, 2000);
    public final FloatProperty maxRange = new FloatProperty("max-range", 20.0F, 3.0F, 64.0F);
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT", "REAL"});
    private final TimerUtil timer = new TimerUtil();

    private static final float ON_TARGET = 2.0F;

    public ThrowAura() {
        super("ThrowAura", false, true, "Throw ur ball to the enemy");
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) return;
        if (event.getType() != EventType.PRE) return;

        if (!atlas.util.Auras.anyEnabled()) return;

        EntityLivingBase target = atlas.util.Auras.target();
        if (target == null) return;

        double distance = mc.thePlayer.getDistanceToEntity(target);
        if (distance > atlas.util.Auras.attackRange(3.0F) && distance <= maxRange.getValue()) {
            int projectileCount = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
            if (projectileCount > 0 && timer.hasTimeElapsed(cooldown.getValue().longValue())) {
                int projectileSlot = findProjectileHotbarSlot();
                if (projectileSlot != -1) {
                    float[] want = RotationUtil.getRotationsToBox(
                            target.getEntityBoundingBox(),
                            RotationHelper.serverYaw(),
                            RotationHelper.serverPitch(),
                            180.0F,
                            0.0F
                    );

                    float[] look = RotationHelper.request(this, want[0], want[1], 1, this.moveMode(), false,
                            RotationHelper.NO_LIMIT);
                    if (look != null && moveFix.getModeString().equals("REAL")) {
                        Atlas.rotationManager.setRotation(look[0], look[1], 1, false);
                    }

                    float border = target.getCollisionBorderSize();
                    if (RotationEngine.angle(event.getYaw(), event.getPitch(), want[0], want[1]) > ON_TARGET
                            && RotationUtil.rayTrace(target.getEntityBoundingBox().expand(border, border, border),
                            event.getYaw(), event.getPitch(), maxRange.getValue() + 1.0) == null) {
                        return;
                    }
                    int originalSlot = mc.thePlayer.inventory.currentItem;
                    if (projectileSlot != originalSlot) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(projectileSlot));
                    }
                    PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(projectileSlot)));
                    if (projectileSlot != originalSlot) {
                        PacketUtil.sendPacket(new C09PacketHeldItemChange(originalSlot));
                    }

                    timer.reset();
                }
            }
        }
    }

    private int moveMode() {
        switch (moveFix.getValue()) {
            case 0:
                return RotationHelper.MOVE_CAMERA;
            case 1:
                return RotationHelper.MOVE_FIX;
            default:
                return RotationHelper.MOVE_LOOK;
        }
    }

    private int findProjectileHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (ItemUtil.isProjectile(stack)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String[] getSuffix() {
        int count = ItemUtil.findInventorySlot(ItemUtil.ItemType.Projectile);
        return new String[]{String.valueOf(count)};
    }
}
