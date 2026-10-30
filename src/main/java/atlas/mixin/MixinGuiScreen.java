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

/**
 * Clicked chat and book text (2026-10-30, audit). A RUN_COMMAND click is sent
 * as chat, and Atlas takes its commands from outgoing chat, so a server's
 * clickable line could run any of them. One that would is dropped unless
 * this client made it (CommandManager.ownClick).
 */
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
}
