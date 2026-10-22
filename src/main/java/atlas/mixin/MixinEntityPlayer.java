package atlas.mixin;

import atlas.Atlas;
import atlas.module.modules.KeepSprint;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

@SideOnly(Side.CLIENT)
@Mixin(value = {EntityPlayer.class}, priority = 9999)
public abstract class MixinEntityPlayer extends MixinEntityLivingBase {
    @ModifyConstant(
            method = {"attackTargetEntityWithCurrentItem"},
            constant = {@Constant(
                    doubleValue = 0.6
            )}
    )
    private double attackTargetEntityWithCurrentItem(double speed) {
        if (Atlas.moduleManager == null) {
            return speed;
        } else {
            KeepSprint keepSprint = (KeepSprint) Atlas.moduleManager.modules.get(KeepSprint.class);
            return keepSprint.isEnabled() && keepSprint.shouldKeepSprint()
                    ? keepSprint.hitMotionForAxis()
                    : speed;
        }
    }

    @Redirect(
            method = {"attackTargetEntityWithCurrentItem"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayer;setSprinting(Z)V"
            )
    )
    private void setSprinnt(EntityPlayer entityPlayer, boolean boolean2) {
        if (Atlas.moduleManager != null) {
            KeepSprint keepSprint = (KeepSprint) Atlas.moduleManager.modules.get(KeepSprint.class);
            if (!keepSprint.isEnabled() || !keepSprint.shouldKeepSprint() || !keepSprint.shouldKeepThisHitSprint()) {
                entityPlayer.setSprinting(boolean2);
            }
        }
    }
}
