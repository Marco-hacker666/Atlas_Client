package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.mommy.MommySetupScreen;
import atlas.mommy.PanicCommands;
import atlas.mommy.PanicHooks;
import atlas.mommy.PanicRemote;
import atlas.mommy.PanicSetup;
import atlas.module.modules.MommyIsHere;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public class MommyCommand extends Command {
    public MommyCommand() {
        super(new ArrayList<String>(Arrays.asList("mommy")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        MommyIsHere module = (MommyIsHere) Atlas.moduleManager.modules.get(MommyIsHere.class);
        String sub = args.size() > 1 ? args.get(1).toLowerCase() : "";
        if (sub.equals("setup")) {
            Minecraft mc = Minecraft.getMinecraft();

            mc.addScheduledTask(() -> mc.displayGuiScreen(new MommySetupScreen(module::plan,
                    on -> module.phone.setValue(on))));
            return;
        }
        if (sub.equals("edit")) {
            String which = args.size() > 2 ? args.get(2).toLowerCase() : "";
            int shell = which.startsWith("p") ? PanicCommands.SHELL_POWERSHELL : which.startsWith("s")
                    ? PanicCommands.SHELL_SH : which.startsWith("c") ? PanicCommands.SHELL_CMD : module.shell.getValue();
            File f = PanicHooks.editCustom(shell);
            module.shell.setValue(shell);
            ChatUtil.sendFormatted("&b[Atlas] &7Opened &f" + f.getName() + "&7 (" + PanicCommands.SHELLS[shell]
                    + "). Save it; action &fCustom&7 runs it.&r");
            return;
        }
        if (sub.equals("phone")) {
            if (args.size() > 2 && args.get(2).equalsIgnoreCase("reset")) {
                PanicSetup.get().newRemoteKey();
                ChatUtil.sendFormatted("&b[Atlas] &7New phone link made; the old one no longer works. Pair again.&r");
            }
            module.phone.setValue(true);
            if (!PanicRemote.start(module::plan)) {
                ChatUtil.sendFormatted("&c[Atlas] Could not open port " + PanicSetup.get().remotePort + ".&r");
                return;
            }
            PanicHooks.openPairing();
            ChatUtil.sendFormatted("&b[Atlas] &7The pairing page opened in your browser: scan it with the phone.&r");
            return;
        }
        ChatUtil.sendFormatted("&b[Atlas] &f.mommy setup&7: what it brings up and closes, phone pairing, a test. "
                + "&f.mommy edit [cmd|ps|sh]&7: the custom command. &f.mommy phone [reset]&7: pair the phone.&r");
    }
}
