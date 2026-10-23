package atlas.mixin;

import atlas.Atlas;
import atlas.module.modules.ServerProfiles;
import de.florianmichael.viamcp.gui.AsyncVersionSlider;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMultiplayer.class)
public class MixinGuiMultiplayer extends GuiScreen {
    /* 2026-10-23 (owner, GUI redesign D3): ServerProfiles on or off from the
       server list, where the choice is made. An id far from vanilla's 0-8. */
    @Unique
    private static final int ATLAS_AUTO_CONFIG = 7310;

    @Inject(method = "createButtons", at = @At("TAIL"))
    private void addViaVersionSlider(CallbackInfo ci) {
        this.buttonList.add(new AsyncVersionSlider(-1, this.width - 110, 30, 100, 20));
        /* Under the version slider, the same width: the account manager's
           "Accounts" button has the top right (width - 106, 6), and its
           username line the top left (seen overlapping, 2026-10-23). */
        this.buttonList.add(new GuiButton(ATLAS_AUTO_CONFIG, this.width - 110, 54, 100, 20, atlasAutoConfigLabel()));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void atlasAutoConfig(GuiButton button, CallbackInfo ci) {
        if (button == null || button.id != ATLAS_AUTO_CONFIG) {
            return;
        }
        ci.cancel();
        ServerProfiles profiles = atlasServerProfiles();
        if (profiles != null) {
            profiles.setEnabled(!profiles.isEnabled());
        }
        button.displayString = atlasAutoConfigLabel();
    }

    @Unique
    private static ServerProfiles atlasServerProfiles() {
        return Atlas.moduleManager == null ? null
                : (ServerProfiles) Atlas.moduleManager.modules.get(ServerProfiles.class);
    }

    @Unique
    private static String atlasAutoConfigLabel() {
        ServerProfiles profiles = atlasServerProfiles();
        return "Auto config: " + (profiles != null && profiles.isEnabled() ? "ON" : "OFF");
    }
}
