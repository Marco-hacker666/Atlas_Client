package atlas.command.commands;

import atlas.command.Command;
import atlas.management.SupportLink;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;

public class SupportCommand extends Command {
    public SupportCommand() {
        super(new ArrayList<String>(Arrays.asList("support")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() < 2) {
            ChatUtil.sendFormatted("&b[Atlas] &f.support <message>&7 talks to the Atlas owner; &f.support off&7 stops "
                    + "remote help (now: " + state() + "&7).&r");
            return;
        }
        String first = args.get(1).toLowerCase();
        if (args.size() == 2 && (first.equals("allow") || first.equals("yes"))) {
            SupportLink.setConsent(true);
            SupportLink.allowed();
            ChatUtil.sendFormatted("&b[Atlas] &7Remote help allowed: the Atlas owner can chat with you here and adjust "
                    + "your settings. &f.support off&7 stops it.&r");
            return;
        }
        if (args.size() == 2 && (first.equals("deny") || first.equals("no") || first.equals("off"))) {
            SupportLink.setConsent(false);
            ChatUtil.sendFormatted("&b[Atlas] &7Remote help is off: no messages, no setting changes. "
                    + "&f.support allow&7 turns it on.&r");
            return;
        }
        if (!"yes".equals(SupportLink.consent())) {
            ChatUtil.sendFormatted("&c[Atlas] Remote help is off. &f.support allow&c turns it on.&r");
            return;
        }
        String text = String.join(" ", args.subList(1, args.size())).trim();
        if (text.isEmpty() || !SupportLink.reply(text)) {
            ChatUtil.sendFormatted("&c[Atlas] Not sent: usage sharing must be on and you must be in a world.&r");
        }
    }

    private static String state() {
        String c = SupportLink.consent();
        return c.equals("yes") ? "&aallowed" : c.equals("no") ? "&coff" : "&7not asked yet";
    }
}
