package atlas.mixin;

import atlas.Atlas;
import atlas.module.modules.Chams;
import atlas.module.modules.ViewClip;
import atlas.module.modules.Xray;
import net.minecraft.client.renderer.chunk.SetVisibility;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(value = {VisGraph.class}, priority = 9999)
public abstract class MixinVisGraph {
    @Inject(
            method = {"func_178606_a"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void func_178606_a(CallbackInfo callbackInfo) {
        if (Atlas.moduleManager != null) {
            if (Atlas.moduleManager.modules.get(Chams.class).isEnabled()
                    || ((ViewClip) Atlas.moduleManager.modules.get(ViewClip.class)).isNoClipActive()
                    || Atlas.moduleManager.modules.get(Xray.class).isEnabled()) {
                callbackInfo.cancel();
            }
        }
    }

    @Inject(
            method = {"computeVisibility"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void computeVisibility(CallbackInfoReturnable<SetVisibility> callbackInfoReturnable) {
        if (Atlas.moduleManager != null) {
            if (Atlas.moduleManager.modules.get(Chams.class).isEnabled()
                    || ((ViewClip) Atlas.moduleManager.modules.get(ViewClip.class)).isNoClipActive()
                    || Atlas.moduleManager.modules.get(Xray.class).isEnabled()) {
                SetVisibility setVisibility = new SetVisibility();
                setVisibility.setAllVisible(true);
                callbackInfoReturnable.setReturnValue(setVisibility);
            }
        }
    }
}
