package atlas.command.commands;

import atlas.Atlas;
import atlas.command.Command;
import atlas.script.ScriptEntry;
import atlas.script.ScriptKind;
import atlas.script.ScriptManager;
import atlas.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * .script: the Scripts tab from chat (2026-11-03). list, reload [name], new [16|17], folder [16|17],
 * import [16|17] (Raven bS's scripts, as that version; 17 when not said), open name, on name, off name.
 */
public class ScriptCommand extends Command {
    public ScriptCommand() {
        super(new ArrayList<String>(Arrays.asList("script", "scripts")));
    }

    @Override
    public void runCommand(ArrayList<String> args) {
        ScriptManager manager = ScriptManager.get();
        String sub = args.size() < 2 ? "list" : args.get(1).toLowerCase(Locale.ROOT);
        String rest = args.size() < 3 ? "" : String.join(" ", args.subList(2, args.size()));
        ScriptKind version = rest.equals("16") || rest.equalsIgnoreCase("bs16") ? ScriptKind.RAVEN16
                : rest.equals("17") || rest.equalsIgnoreCase("bs17") || rest.equalsIgnoreCase("raven") ? ScriptKind.RAVEN17
                : ScriptKind.ATLAS;
        switch (sub) {
            case "list":
            case "l":
                this.list(manager);
                return;
            case "reload":
            case "r":
                if (rest.isEmpty()) {
                    manager.reloadAll();
                } else {
                    ScriptEntry entry = this.find(manager, rest);
                    if (entry != null) {
                        manager.reload(entry);
                    }
                }
                return;
            case "new":
                manager.newScript(version);
                return;
            case "folder":
                manager.openFolder(version);
                return;
            case "import":
                manager.importRaven(version == ScriptKind.RAVEN16 ? ScriptKind.RAVEN16 : ScriptKind.RAVEN17);
                return;
            case "open": {
                ScriptEntry entry = this.find(manager, rest);
                if (entry != null) {
                    manager.openFile(entry);
                }
                return;
            }
            case "on":
            case "off": {
                ScriptEntry entry = this.find(manager, rest);
                if (entry != null) {
                    entry.module().setEnabled(sub.equals("on"));
                }
                return;
            }
            default:
                ChatUtil.sendFormatted(String.format("%sUsage: .%s list | reload [name] | new [16|17] | folder [16|17]"
                        + " | import [16|17] | open <name> | on <name> | off <name>&r", Atlas.clientName, args.get(0)));
        }
    }

    private ScriptEntry find(ScriptManager manager, String name) {
        ScriptEntry entry = manager.byName(name);
        if (entry == null) {
            ChatUtil.sendFormatted(String.format("%sNo script named &o%s&r", Atlas.clientName, name));
        }
        return entry;
    }

    private void list(ScriptManager manager) {
        if (manager.entries().isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sNo scripts. Put .java files in config/Atlas/scripts (Atlas), raven16/ (Raven bS 16)"
                    + " or raven17/ (Raven bS 17), or .script new [16|17]&r", Atlas.clientName));
            return;
        }
        ChatUtil.sendFormatted(String.format("%sScripts (compiler: %s):&r", Atlas.clientName, manager.compilerStatus()));
        for (ScriptEntry entry : manager.entries()) {
            String state = entry.isCompiling() ? "&ecompiling"
                    : !entry.problems().isEmpty() ? entry.isLoaded() ? "&elast save did not compile" : "&cdid not load"
                    : entry.module().isEnabled() ? "&aon" : "&7off";
            ChatUtil.sendFormatted("  &f" + entry.name + " &8(" + entry.kind.label + ", " + entry.shownPath() + ") "
                    + state + (entry.problems().isEmpty() ? "" : "&7: " + entry.problems().get(0)) + "&r");
        }
    }
}
