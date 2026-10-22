package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.util.BugReport;
import atlas.util.ChatUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * .report [what happened] -- sends the bug report to the Discord channel
 * through the relay (falls back to the clipboard); .report copy -- only
 * copies it (2026-10-07).
 */
public class ReportCommand extends Command {

    public ReportCommand() {
        super(new ArrayList<>(Arrays.asList("report", "bugreport")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() >= 2 && "copy".equalsIgnoreCase(args.get(1))) {
            File file = BugReport.createAndCopy();
            ChatUtil.sendFormatted(Atlas.clientName + "&aBug report copied&r &7(names, tokens and IPs removed)."
                    + (file == null ? "" : " Saved as config/Atlas/reports/" + file.getName()));
            return;
        }
        String description = args.size() >= 2 ? String.join(" ", args.subList(1, args.size())) : null;
        ChatUtil.sendFormatted(Atlas.clientName + "&7Sending the bug report...");
        BugReport.send(description, ReportCommand::announce);
    }

    public static void announce(boolean sent, String message, File saved) {
        ChatUtil.sendFormatted(Atlas.clientName + (sent ? "&aBug report " : "&eBug report: ") + message
                + (saved == null ? "" : " &8(saved as config/Atlas/reports/" + saved.getName() + ")"));
        if (Atlas.notificationManager != null) {
            Atlas.notificationManager.add(sent ? "Bug report sent to Discord" : "Bug report copied (not sent)", 4000L);
        }
    }
}
