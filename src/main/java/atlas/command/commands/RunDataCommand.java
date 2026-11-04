package atlas.command.commands;

import atlas.command.Command;
import atlas.management.RunCollector;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

/** .rundata on|off|status: the anonymous SpeedBlocker run data (RunCollector), 2026-11-01. */
public class RunDataCommand extends Command {
    public RunDataCommand() {
        super(new ArrayList<String>(Arrays.asList("rundata")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        String arg = args.size() > 1 ? args.get(1).toLowerCase() : "status";
        if (arg.equals("on")) {
            RunCollector.setConsent(true);
            ChatUtil.sendFormatted("&b[Atlas] &7Thanks! Anonymous run data is on (no names, no server, no coordinates). "
                    + "&8.rundata off&7 (or Client Settings > Privacy) stops it.&r");
        } else if (arg.equals("off")) {
            RunCollector.setConsent(false);
            ChatUtil.sendFormatted("&b[Atlas] &7Run data is off: nothing is recorded or sent, and rows not yet sent are dropped.&r");
        } else {
            Boolean consent = RunCollector.consent();
            ChatUtil.sendFormatted("&b[Atlas] &7Run data: " + (consent == null ? "not chosen" : consent ? "&aon" : "&coff")
                    + "&7. &8.rundata on&7 / &8.rundata off&r");
        }
    }
}
