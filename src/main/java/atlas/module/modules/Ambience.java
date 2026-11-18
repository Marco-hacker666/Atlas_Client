package atlas.module.modules;

import java.awt.Color;
import atlas.event.EventTarget;
import atlas.events.PacketEvent;
import atlas.events.Render3DEvent;
import atlas.events.UpdateEvent;
import atlas.event.types.EventType;
import atlas.module.Module;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S03PacketTimeUpdate;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.util.BlockPos;
import net.minecraft.world.biome.BiomeGenBase;

public final class Ambience extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty time = new IntProperty("Time", 0, 0, 22999);
    public final IntProperty speed = new IntProperty("Time Speed", 0, 0, 20);

    public final ModeProperty weather =
            new ModeProperty(
                    "Weather",
                    0,
                    new String[] {
                            "Unchanged", "Clear", "Rain", "Heavy Snow", "Light Snow", "Nether Particles"
                    });

    private volatile boolean serverRaining;

    public Ambience() {
        super("Ambience", false);
    }

    @Override
    public void onEnabled() {
        this.serverRaining = mc.theWorld != null && mc.theWorld.getWorldInfo().isRaining();
    }

    @Override
    public void onDisabled() {
        if (mc.theWorld != null && !this.weather.getModeString().equals("Unchanged")) {
            mc.theWorld.getWorldInfo().setRaining(this.serverRaining);
            mc.theWorld.setRainStrength(this.serverRaining ? 1.0F : 0.0F);
            if (!this.serverRaining) {
                mc.theWorld.getWorldInfo().setThundering(false);
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (this.isEnabled() && mc.theWorld != null) {
            mc.theWorld.setWorldTime(
                    (long) (time.getValue() + (System.currentTimeMillis() * speed.getValue())));
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (mc.thePlayer != null && mc.theWorld != null && mc.thePlayer.ticksExisted % 20 == 0) {
                switch (this.weather.getModeString()) {
                    case "Clear":
                    {
                        mc.theWorld.setRainStrength(0);
                        mc.theWorld.getWorldInfo().setCleanWeatherTime(Integer.MAX_VALUE);
                        mc.theWorld.getWorldInfo().setRainTime(0);
                        mc.theWorld.getWorldInfo().setThunderTime(0);
                        mc.theWorld.getWorldInfo().setRaining(false);
                        mc.theWorld.getWorldInfo().setThundering(false);
                        break;
                    }
                    case "Nether Particles":
                    case "Light Snow":
                    case "Heavy Snow":
                    case "Rain":
                    {
                        mc.theWorld.setRainStrength(1);
                        mc.theWorld.getWorldInfo().setCleanWeatherTime(0);
                        mc.theWorld.getWorldInfo().setRainTime(Integer.MAX_VALUE);
                        mc.theWorld.getWorldInfo().setThunderTime(Integer.MAX_VALUE);
                        mc.theWorld.getWorldInfo().setRaining(true);
                        mc.theWorld.getWorldInfo().setThundering(false);
                        break;
                    }
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getPacket() instanceof S03PacketTimeUpdate) {
            event.setCancelled(true);
        } else if (event.getPacket() instanceof S2BPacketChangeGameState
                && !this.weather.getModeString().equals("Unchanged")) {
            S2BPacketChangeGameState s2b = (S2BPacketChangeGameState) event.getPacket();

            if (s2b.getGameState() == 1 || s2b.getGameState() == 2) {
                this.serverRaining = s2b.getGameState() == 1;
                event.setCancelled(true);
            }
        }
    }

    public float getFloatTemperature(BlockPos blockPos, BiomeGenBase biomeGenBase) {
        if (this.isEnabled()) {
            switch (this.weather.getModeString()) {
                case "Nether Particles":
                case "Light Snow":
                case "Heavy Snow":
                    return 0.1F;
                case "Rain":
                    return 0.2F;
            }
        }
        return biomeGenBase.getFloatTemperature(blockPos);
    }

    public boolean skipRainParticles() {
        final String name = this.weather.getModeString();
        return this.isEnabled()
                && (name.equals("Light Snow")
                || name.equals("Heavy Snow")
                || name.equals("Nether Particles"));
    }
}
