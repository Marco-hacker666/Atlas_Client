package atlas.mixin;

import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@SideOnly(Side.CLIENT)
@Mixin({C03PacketPlayer.class})
public interface IAccessorC03PacketPlayer {
    @Accessor("onGround")
    void setOnGround(boolean boolean1);

    /* 2026-11-02: a script's onPreMotion may move the position the packet reports (Raven's PlayerState). */
    @Accessor("x")
    void setX(double x);

    @Accessor("y")
    void setY(double y);

    @Accessor("z")
    void setZ(double z);
}
