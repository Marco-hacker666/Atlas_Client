package atlas.command;

import net.minecraft.event.ClickEvent;
import org.junit.Test;

import static org.junit.Assert.*;

/** Only a click this client made may run an Atlas command (2026-10-30, audit; MixinGuiScreen). */
public class OwnClickTest {

    @Test
    public void ourOwnClickIsRecognised() {
        ClickEvent own = CommandManager.ownClick(".config load default");
        assertTrue(CommandManager.isOwnClick(own));
    }

    @Test
    public void aServersClickWithTheSameTextIsNot() {
        CommandManager.ownClick(".config load default");
        ClickEvent server = new ClickEvent(ClickEvent.Action.RUN_COMMAND, ".config load default");
        assertFalse(CommandManager.isOwnClick(server));
    }

    @Test
    public void typedCommandsAreDetected() {
        CommandManager manager = new CommandManager();
        assertTrue(manager.isTypingCommand(".onlineconfig load https://x"));
        assertFalse(manager.isTypingCommand("/hub"));
        assertFalse(manager.isTypingCommand("..."));
    }
}
