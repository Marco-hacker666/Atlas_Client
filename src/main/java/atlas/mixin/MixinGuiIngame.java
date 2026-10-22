package atlas.mixin;

import atlas.Atlas;
import atlas.module.modules.AutoBlockIn;
import atlas.module.modules.RenderFixes;
import atlas.module.modules.Scaffold;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = {GuiIngame.class}, priority = 9999)
public abstract class MixinGuiIngame {
    @Inject(method = {"renderScoreboard"}, at = @At("HEAD"), cancellable = true)
    private void atlas$renderModernScoreboard(ScoreObjective objective, ScaledResolution scaledRes, CallbackInfo callbackInfo) {
        if (RenderFixes.renderScoreboard(objective, scaledRes)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void atlas$renderTooltip(ScaledResolution sr, float partialTicks, CallbackInfo callbackInfo) {
        if (Atlas.moduleManager != null) {
            atlas.module.modules.Hotbar hotbar = (atlas.module.modules.Hotbar) Atlas.moduleManager.modules.get(atlas.module.modules.Hotbar.class);
            if (hotbar != null && hotbar.isEnabled()) {
                callbackInfo.cancel();
            }
        }
    }

    @Redirect(
            method = {"updateTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/InventoryPlayer;getCurrentItem()Lnet/minecraft/item/ItemStack;"
            )
    )
    private ItemStack updateTick(InventoryPlayer inventoryPlayer) {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled() && scaffold.itemSpoof.getValue()) {
            int slot = scaffold.getSlot();
            if (slot >= 0) {
                return inventoryPlayer.getStackInSlot(slot);
            }
        }
        AutoBlockIn autoBlockIn = (AutoBlockIn) Atlas.moduleManager.modules.get(AutoBlockIn.class);
        if(autoBlockIn.itemSpoof.getValue() && autoBlockIn.isEnabled()){
            int slot = autoBlockIn.getSlot();
            if (slot >= 0) {
                return inventoryPlayer.getStackInSlot(slot);
            }
        }
        atlas.module.modules.Clutch clutch = (atlas.module.modules.Clutch) Atlas.moduleManager.modules.get(atlas.module.modules.Clutch.class);
        int clutchSlot = clutch == null ? -1 : clutch.getSpoofSlot();
        if (clutchSlot >= 0) {
            return inventoryPlayer.getStackInSlot(clutchSlot);
        }
        return inventoryPlayer.getCurrentItem();
    }
}
