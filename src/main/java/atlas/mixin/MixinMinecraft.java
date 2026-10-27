package atlas.mixin;

import atlas.Atlas;
import atlas.init.Initializer;
import atlas.event.EventManager;
import atlas.event.types.EventType;
import atlas.events.*;
import atlas.module.modules.NoHitDelay;
import atlas.ui.impl.mainmenu.AtlasMainMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(value = {Minecraft.class}, priority = 9999)
public abstract class MixinMinecraft {
    @Shadow
    private int leftClickCounter;
    @Shadow
    public PlayerControllerMP playerController;
    @Shadow
    public WorldClient theWorld;
    @Shadow
    public EntityPlayerSP thePlayer;
    @Shadow
    public GuiScreen currentScreen;
    @Shadow
    public net.minecraft.client.settings.GameSettings gameSettings;

    /* 2026-10-12: vanilla holds every screen without a world to 30 fps, which
       made the animated start screen stutter. Our menu and the setup screen
       run at the player's own frame limit (at least 60). */
    @Inject(method = {"getLimitFramerate"}, at = {@At("HEAD")}, cancellable = true)
    private void menuFramerate(CallbackInfoReturnable<Integer> callbackInfo) {
        if (this.theWorld == null && (this.currentScreen instanceof AtlasMainMenu
                || this.currentScreen instanceof atlas.setup.SetupScreen)) {
            callbackInfo.setReturnValue(Math.max(60, this.gameSettings.limitFramerate));
        }
    }

    @Inject(
            method = {"displayGuiScreen"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void replaceMainMenu(GuiScreen guiScreen, CallbackInfo callbackInfo) {
        if (isVanillaMainMenu(guiScreen)) {
            displayAtlasMainMenu();
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = {"startGame"},
            at = {@At("HEAD")}
    )
    private void startGame(CallbackInfo callbackInfo) {
        new Initializer();
    }

    @Inject(
            method = {"startGame"},
            at = {@At("RETURN")}
    )
    private void postStartGame(CallbackInfo callbackInfo) {
        new Atlas();
        Atlas.updateDisplayTitle();
    }

    @Inject(
            method = {"runTick"},
            at = {@At("HEAD")}
    )
    private void runTick(CallbackInfo callbackInfo) {
        if (this.theWorld != null && this.thePlayer != null) {
            EventManager.call(new TickEvent(EventType.PRE));
        }
    }

    @Inject(
            method = {"runTick"},
            at = {@At("RETURN")}
    )
    private void postRunTick(CallbackInfo callbackInfo) {
        if (this.theWorld != null && this.thePlayer != null) {
            EventManager.call(new TickEvent(EventType.POST));
        }
        if (isVanillaMainMenu(this.currentScreen)) {
            displayAtlasMainMenu();
        }
    }

    private boolean isVanillaMainMenu(GuiScreen guiScreen) {
        return guiScreen != null && "net.minecraft.client.gui.GuiMainMenu".equals(guiScreen.getClass().getName());
    }

    private void displayAtlasMainMenu() {
        ((Minecraft) (Object) this).displayGuiScreen(new AtlasMainMenu());
    }

    @Inject(
            method = {"loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V"},
            at = {@At("HEAD")}
    )
    private void loadWorld(WorldClient worldClient, String string, CallbackInfo callbackInfo) {
        EventManager.call(new LoadWorldEvent(worldClient));
    }

    @Inject(
            method = {"updateFramebufferSize"},
            at = {@At("RETURN")}
    )
    private void updateFramebufferSize(CallbackInfo callbackInfo) {
        EventManager.call(new ResizeEvent());
    }

    @Inject(
            method = {"clickMouse"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void clickMouse(CallbackInfo callbackInfo) {
        if (Atlas.moduleManager != null && atlas.module.ModuleBundle.of(atlas.module.modules.NoHitDelay.class).isEnabled()) {
            this.leftClickCounter = 0;
        }
        /* 2026-10-26 (VanillaInput): a click a module pressed this tick takes its crosshair, in order. */
        net.minecraft.util.MovingObjectPosition managed = atlas.util.VanillaInput.nextAttack();
        LeftClickMouseEvent event = new LeftClickMouseEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
            return;
        }
        atlas.util.VanillaInput.apply(managed);
    }

    @Inject(
            method = {"clickMouse"},
            at = {@At("RETURN")}
    )
    private void clickMouseReturn(CallbackInfo callbackInfo) {
        atlas.util.VanillaInput.restore();
    }

    @Inject(
            method = {"rightClickMouse"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void rightClickMouse(CallbackInfo callbackInfo) {
        RightClickMouseEvent event = new RightClickMouseEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
            return;
        }
        /* ItemUseFix: while a block is being broken, the game ignores right
           click; it is told it is not breaking for this one call. */
        atlas.module.modules.ItemUseFix.beforeRightClick();
        /* 2026-10-27 (V3): a sword's right click while an aura fights goes along its look (VanillaInput.applyUse). */
        atlas.util.VanillaInput.applyUse();
    }

    @Inject(
            method = {"rightClickMouse"},
            at = {@At("RETURN")}
    )
    private void rightClickMouseReturn(CallbackInfo callbackInfo) {
        atlas.util.VanillaInput.restoreUse();
        atlas.module.modules.ItemUseFix.afterRightClick();
    }

    @Inject(
            method = {"sendClickBlockToController"},
            at = {@At("HEAD")},
            cancellable = true
    )
    private void sendClickBlockToController(CallbackInfo callbackInfo) {
        if (atlas.util.VanillaInput.holdsBlockBreaking()) {
            /* 2026-10-27: an aura fights along its own look; the camera's crosshair digs nothing meanwhile. */
            callbackInfo.cancel();
            this.playerController.resetBlockRemoving();
            return;
        }
        HitBlockEvent event = new HitBlockEvent();
        EventManager.call(event);
        if (event.isCancelled()) {
            callbackInfo.cancel();
            this.playerController.resetBlockRemoving();
        }
    }

    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/settings/KeyBinding;setKeyBindState(IZ)V"
            )
    )
    private void setKeyBindState(int integer, boolean boolean2) {
        KeyBinding.setKeyBindState(integer, boolean2);
        if (boolean2 && this.currentScreen == null) {
            EventManager.call(new KeyEvent(integer));
        }
    }

    @Redirect(
            method = {"runTick"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/InventoryPlayer;changeCurrentItem(I)V"
            )
    )
    private void changeCurrentItem(InventoryPlayer inventoryPlayer, int slot) {
        SwapItemEvent event = new SwapItemEvent(-1, slot);
        EventManager.call(event);
        if (!event.isCancelled()) {
            inventoryPlayer.changeCurrentItem(slot);
        }
    }
}
