package atlas.mixin;

import atlas.Atlas;
import atlas.command.CommandManager;
import atlas.util.ChatUtil;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(value = {GuiScreen.class}, priority = 9999)
public abstract class MixinGuiScreen {
    @Inject(method = {"handleComponentClick"}, at = {@At("HEAD")}, cancellable = true)
    private void atlas$guardCommandClick(IChatComponent component, CallbackInfoReturnable<Boolean> cir) {
        if (component == null || component.getChatStyle() == null || Atlas.commandManager == null) {
            return;
        }
        ClickEvent click = component.getChatStyle().getChatClickEvent();
        if (click == null || click.getAction() != ClickEvent.Action.RUN_COMMAND
                || !Atlas.commandManager.isTypingCommand(click.getValue()) || CommandManager.isOwnClick(click)) {
            return;
        }
        ChatUtil.sendFormatted(String.format("%s&cBlocked a click from the server that would run an Atlas command (&o%s&c).&r",
                Atlas.clientName, click.getValue()));
        cir.setReturnValue(true);
    }

    @Inject(method = {"handleKeyboardInput"}, at = {@At("HEAD")}, cancellable = true)
    private void atlas$scriptKeyPress(org.spongepowered.asm.mixin.injection.callback.CallbackInfo callbackInfo) {
        if (atlas.script.ScriptDispatcher.INSTANCE.onScreenKey(org.lwjgl.input.Keyboard.getEventCharacter(),
                org.lwjgl.input.Keyboard.getEventKey())) {
            callbackInfo.cancel();
        }
    }
}
