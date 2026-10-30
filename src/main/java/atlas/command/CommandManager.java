package atlas.command;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.PacketEvent;
import atlas.util.ChatUtil;
import net.minecraft.network.play.client.C01PacketChatMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CommandManager {
    public ArrayList<Command> commands;

    public CommandManager() {
        this.commands = new ArrayList<>();
    }

    public void handleCommand(String string) {
        List<String> params = Arrays.asList(string.substring(1).trim().split("\\s+"));
        ArrayList<String> arrayList = new ArrayList<>(params);
        if (params.get(0).isEmpty()) {
            ChatUtil.sendFormatted(String.format("%sUnknown command&r", Atlas.clientName).replace("&", "§"));
        } else {
            for (Command command : Atlas.commandManager.commands) {
                for (String name : command.names) {
                    if (params.get(0).equalsIgnoreCase(name)) {
                        command.runCommand(arrayList);
                        return;
                    }
                }
            }
            ChatUtil.sendFormatted(String.format("%sUnknown command (&o%s&r)&r", Atlas.clientName, params.get(0)).replace("&", "§"));
        }
    }

    /*
     * 2026-10-30 (audit): commands are taken from outgoing chat, and vanilla sends a clicked RUN_COMMAND (a chat
     * line, a book) as chat too -- a server's "[click here]" could run .onlineconfig <url> or .config save. A click
     * that would run one of ours goes through only when this client made that ClickEvent (MixinGuiScreen). Compared
     * by identity: a server's click is a new object even with the same text; GuiNewChat's line splitting copies the
     * style shallowly, so ours keep theirs.
     */
    private static final java.util.ArrayDeque<net.minecraft.event.ClickEvent> OWN_CLICKS =
            new java.util.ArrayDeque<net.minecraft.event.ClickEvent>();
    private static final int OWN_CLICKS_KEPT = 512;

    /** A RUN_COMMAND click this client offers itself (config lists and the like). */
    public static net.minecraft.event.ClickEvent ownClick(String command) {
        net.minecraft.event.ClickEvent click =
                new net.minecraft.event.ClickEvent(net.minecraft.event.ClickEvent.Action.RUN_COMMAND, command);
        synchronized (OWN_CLICKS) {
            OWN_CLICKS.addLast(click);
            while (OWN_CLICKS.size() > OWN_CLICKS_KEPT) {
                OWN_CLICKS.removeFirst();
            }
        }
        return click;
    }

    public static boolean isOwnClick(net.minecraft.event.ClickEvent click) {
        synchronized (OWN_CLICKS) {
            for (net.minecraft.event.ClickEvent own : OWN_CLICKS) {
                if (own == click) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isTypingCommand(String string) {
        if (string == null || string.length() < 2) {
            return false;
        } else {
            return string.charAt(0) == '.' && Character.isLetterOrDigit(string.charAt(1));
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.SEND && event.getPacket() instanceof C01PacketChatMessage) {
            String msg = ((C01PacketChatMessage) event.getPacket()).getMessage();
            if (this.isTypingCommand(msg)) {
                event.setCancelled(true);
                this.handleCommand(msg);
            }
        }
    }
}
