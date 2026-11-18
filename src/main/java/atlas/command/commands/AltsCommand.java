package atlas.command.commands;

import me.ksyz.accountmanager.gui.GuiAccountManager;
import atlas.command.Command;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;

// Ported from OpenSkid (GPL-3.0)

public class AltsCommand extends Command {
    public AltsCommand() {
        super(new ArrayList<String>(Arrays.asList("alts", "alt", "altmanager")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAccountManager(null));
    }
}
