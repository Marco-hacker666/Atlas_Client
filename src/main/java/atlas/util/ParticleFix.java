package atlas.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.util.EnumParticleTypes;

public final class ParticleFix {
    private static final double MAX_DIST_SQ = 32.0 * 32.0;

    private ParticleFix() {
    }

    public static void spawn(WorldClient world, EnumParticleTypes type, double x, double y, double z,
                             double dx, double dy, double dz, int... params) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            EffectRenderer renderer = mc.effectRenderer;
            EntityPlayerSP player = mc.thePlayer;
            if (renderer != null && player != null) {
                double ox = player.posX - x;
                double oy = player.posY - y;
                double oz = player.posZ - z;
                if (ox * ox + oy * oy + oz * oz > MAX_DIST_SQ) {
                    return;
                }
                renderer.spawnEffectParticle(type.getParticleID(), x, y, z, dx, dy, dz, params);
                return;
            }
        } catch (Throwable ignored) {
        }
        world.spawnParticle(type, x, y, z, dx, dy, dz, params);
    }
}
