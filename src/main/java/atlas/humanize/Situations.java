package atlas.humanize;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

/**
 * The situation of a click on {@code target}, as Accuracy reads it
 * (2026-10-26: was KillAuraLB.situation, shared now that KillAura2, the Atlas
 * engine and AutoClicker use humanization too).
 */
public final class Situations {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private Situations() {
    }

    public static Accuracy.Situation of(Entity entity) {
        Accuracy.Situation s = new Accuracy.Situation();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = entity.getEntityBoundingBox();
        double dx = Math.max(0.0, Math.max(box.minX - eyes.xCoord, eyes.xCoord - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - eyes.yCoord, eyes.yCoord - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - eyes.zCoord, eyes.zCoord - box.maxZ));
        s.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double lx = entity.posX - mc.thePlayer.posX;
        double lz = entity.posZ - mc.thePlayer.posZ;
        double length = Math.max(1.0E-6, Math.hypot(lx, lz));
        double vx = entity.posX - entity.lastTickPosX;
        double vz = entity.posZ - entity.lastTickPosZ;
        s.targetLateralSpeed = Math.abs(vx * -lz + vz * lx) / length;
        s.targetAirborne = !entity.onGround;
        s.selfAirborne = !mc.thePlayer.onGround;
        s.ticksSinceHurt = mc.thePlayer.hurtTime > 0 ? Math.max(0, mc.thePlayer.maxHurtTime - mc.thePlayer.hurtTime) : -1;
        s.targetHealth = entity instanceof EntityLivingBase ? ((EntityLivingBase) entity).getHealth() : 20.0F;
        return s;
    }
}
