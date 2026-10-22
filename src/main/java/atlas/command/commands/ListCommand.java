package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.module.Module;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

public class ListCommand extends Command {
    public ListCommand() {
        super(new ArrayList<>(Arrays.asList("list", "l", "modules", "atlas")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (!Atlas.moduleManager.modules.isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sModules:&r", Atlas.clientName));
            for (Module module : Atlas.moduleManager.modules.values()) {
                ChatUtil.sendFormatted(String.format("%s»&r %s&r", module.isHidden() ? "&8" : "&7", module.formatModule()));
            }
        }
    }
}
