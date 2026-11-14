package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.module.Module;
import atlas.ui.impl.clickgui.atlas.ThemeSwitch;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * .theme [list | next | prev | save NAME | load NAME | NAME]: the Theme window's saved looks without opening it
 * (2026-11-14). "load" takes any name, even one that is also a word of this command or a Theme setting (review,
 * PR #42).
 * The Theme module's next-theme-key and previous-theme-key do next and prev from a key.
 *
 * ".theme" was the Theme module's own command (ModuleCommand, which takes every module's name): ".theme mode
 * RAINBOW" set a setting. It still does -- a first word that is one of the module's settings goes to that command.
 */
public class ThemeCommand extends Command {
    public ThemeCommand() {
        super(new ArrayList<String>(Arrays.asList("theme", "themes")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        if (args.size() > 1 && toModuleCommand(args)) {
            return;
        }
        String first = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "list";
        if (first.equals("list")) {
            list();
        } else if (first.equals("next") || first.equals("prev") || first.equals("previous")) {
            String name = ThemeSwitch.step(first.equals("next") ? 1 : -1);
            say(name != null ? "&7Theme: &f" + name
                    : ThemeSwitch.names().isEmpty() ? "&7No saved themes yet. &8.theme save NAME&7 saves the look you have now."
                    : "&cNone of the saved themes could be read.");
        } else if (first.equals("save")) {
            String wanted = join(args, 2);
            if (wanted.isEmpty()) {
                say("&7Give it a name: &8.theme save NAME");
                return;
            }
            String saved = ThemeSwitch.save(wanted);
            say(saved == null ? "&cCould not save " + wanted + "&7 (letters, digits, spaces, - and _)."
                    : "&7Saved the look as &f" + saved);
        } else {
            load(join(args, first.equals("load") ? 2 : 1));
        }
    }

    private static void load(String wanted) {
        if (wanted.isEmpty()) {
            say("&7Which one? &8.theme load NAME&7; &8.theme list&7 shows them.");
            return;
        }
        String loaded = ThemeSwitch.load(wanted);
        if (loaded != null) {
            say("&7Theme: &f" + loaded);
        } else if (ThemeSwitch.find(wanted) != null) {
            say("&cCould not read the saved theme " + wanted + "&7.");
        } else {
            say("&cNo saved theme called " + wanted + "&7. &8.theme list&7 shows them.");
        }
    }

    private static void list() {
        List<String> names = ThemeSwitch.names();
        if (names.isEmpty()) {
            say("&7No saved themes yet: save one in the Theme window, or with &8.theme save NAME&7.");
            return;
        }
        String current = ThemeSwitch.current();
        StringBuilder line = new StringBuilder("&7Saved themes: ");
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            line.append(i > 0 ? "&7, " : "").append(name.equalsIgnoreCase(current == null ? "" : current)
                    ? "&a" + name + " &7(on)" : "&f" + name);
        }
        say(line.toString());
        say("&8.theme NAME&7 (or &8.theme load NAME&7) puts one on, &8.theme next&7 / &8prev&7 go through them, "
                + "&8.theme save NAME&7 saves. &8.theme SETTING VALUE&7 still sets the Theme module.");
    }

    /** A setting of the Theme module: handed to the module command, as before this command existed. */
    private static boolean toModuleCommand(ArrayList<String> args) {
        Module theme = Atlas.moduleManager.getModule("Theme");
        if (theme == null || Atlas.propertyManager.getProperty(theme, args.get(1)) == null) {
            return false;
        }
        for (Command command : Atlas.commandManager.commands) {
            if (command instanceof ModuleCommand) {
                ArrayList<String> forwarded = new ArrayList<String>(args);
                forwarded.set(0, theme.getName());
                command.runCommand(forwarded);
                return true;
            }
        }
        return false;
    }

    private static String join(ArrayList<String> args, int from) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < args.size(); i++) {
            out.append(out.length() > 0 ? " " : "").append(args.get(i));
        }
        return out.toString().trim();
    }

    private static void say(String text) {
        ChatUtil.sendFormatted("&b[Atlas] " + text + "&r");
    }
}
