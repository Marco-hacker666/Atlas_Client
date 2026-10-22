package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.module.Module;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class ShowCommand extends Command {
    public ShowCommand() {
        super(new ArrayList<>(Arrays.asList("show", "s", "unhide")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted(
                    String.format("%sUsage: .%s <&omodule&r>&r", Atlas.clientName, args.get(0).toLowerCase(Locale.ROOT))
            );
        } else if (!args.get(1).equals("*")) {
            Module module = Atlas.moduleManager.getModule(args.get(1));
            if (module == null) {
                ChatUtil.sendFormatted(String.format("%sModule &o%s&r not found&r", Atlas.clientName, args.get(1)));
            } else if (!module.isHidden()) {
                ChatUtil.sendFormatted(String.format("%s&o%s&r is not hidden in HUD&r", Atlas.clientName, module.getName()));
            } else {
                module.setHidden(false);
                ChatUtil.sendFormatted(String.format("%s&o%s&r is no longer hidden in HUD&r", Atlas.clientName, module.getName()));
            }
        } else {
            for (Module module : Atlas.moduleManager.modules.values()) {
                module.setHidden(false);
            }
            ChatUtil.sendFormatted(String.format("%sAll modules are no longer hidden in HUD&r", Atlas.clientName));
        }
    }
}
