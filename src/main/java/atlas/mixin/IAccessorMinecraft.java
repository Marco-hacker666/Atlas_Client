package atlas.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Timer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@SideOnly(Side.CLIENT)
@Mixin({Minecraft.class})
public interface IAccessorMinecraft {
    @Accessor
    Logger getLogger();

    @Accessor("timer")
    Timer getTimer();

    @Accessor("rightClickDelayTimer")
    int getRightClickDelayTimer();

    @Accessor("rightClickDelayTimer")
    void setRightClickDelayTimer(int integer);

    /* 2026-10-24: the LiquidBounce clicker's miss cooldown (ticks left after a swing at the air). */
    @Accessor("leftClickCounter")
    int getLeftClickCounter();

    /* The LiquidBounce aura's FailSwing: a swing at the air costs vanilla's miss delay. */
    @Accessor("leftClickCounter")
    void setLeftClickCounter(int ticks);

    /* 2026-10-24: KillAura's simulate-clicks (Yuri): vanilla's own left click. */
    @org.spongepowered.asm.mixin.gen.Invoker("clickMouse")
    void callClickMouse();

    /* 2026-10-11: where AtlasResources adds the jar's "atlas" resources. */
    @Accessor("defaultResourcePacks")
    java.util.List<net.minecraft.client.resources.IResourcePack> getDefaultResourcePacks();
}
