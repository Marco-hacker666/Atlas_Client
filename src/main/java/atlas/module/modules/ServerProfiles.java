package atlas.module.modules;

import atlas.config.Config;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.TextProperty;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.play.server.S01PacketJoinGame;

import java.io.File;

/**
 * Loads a different saved config depending on which server was joined.
 *
 * What is safe on one server is not on another: the same backtrack window and
 * reach that pass unnoticed on a practice server are what a stricter anticheat
 * corrects, and the settings that suit it are a different set rather than a
 * softer version of the same one. Keeping those as separate saved configs is
 * already the answer -- the part that keeps going wrong is remembering to
 * switch before joining rather than three deaths afterwards.
 *
 * The rules are matched as plain substrings of the server address, in the order
 * written, so the specific can be put before the general. Only configs that
 * already exist are loaded: a missing file makes the client write the current
 * state out under that name, which would turn a typo into a new profile nobody
 * asked for, so a rule pointing at nothing is reported and skipped.
 *
 * Nothing is ever saved here. Switching profiles does change where the client's
 * own save-on-exit will go, which is what makes in-game adjustments stick to
 * the profile they were made under -- worth knowing before editing settings on
 * a server whose profile you did not intend to change.
 */
public class ServerProfiles extends Module {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File CONFIG_DIR = new File("./config/Atlas/");

    /** {@code address fragment = config name}, comma separated, first match wins. */
    public final TextProperty rules = new TextProperty("profiles", "");
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    private String applied;

    public ServerProfiles() {
        super("ServerProfiles", false, false,
                "Loads a saved config that matches the server you joined");
    }

    @Override
    public void onEnabled() {
        this.applied = null;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE
                || !(event.getPacket() instanceof S01PacketJoinGame)) {
            return;
        }
        /* Proxy networks send this again for every child server, so the work is
           guarded by which profile is already applied rather than by the packet
           being rare. */
        String address = address();
        if (address == null) {
            return;
        }
        String target = match(address);
        if (target == null || target.equals(this.applied)) {
            return;
        }
        /* Not applied here. This handler runs on the network thread, before
           vanilla has even created the new world and player, and a config load
           switches modules on and off -- their onEnabled/onDisabled run whatever
           they run. The next tick is on the client thread with a world and a
           player in place. */
        this.pendingTarget = target;
        this.pendingAddress = address;
    }

    private volatile String pendingTarget;
    private volatile String pendingAddress;

    @EventTarget
    public void onTick(atlas.events.TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        String target = this.pendingTarget;
        String address = this.pendingAddress;
        if (target == null) {
            return;
        }
        this.pendingTarget = null;
        this.pendingAddress = null;
        if (!target.equals(this.applied)) {
            apply(target, address);
        }
    }

    private String address() {
        ServerData data = mc.getCurrentServerData();
        if (data == null || data.serverIP == null) {
            return null;
        }
        return data.serverIP.toLowerCase();
    }

    /** First rule whose fragment appears in the address. */
    private String match(String address) {
        return match(parse(this.rules.getValue()), address);
    }

    /** The first rule of {@code rules} whose fragment is in {@code address}, its profile; null for none. */
    public static String match(java.util.List<String[]> rules, String address) {
        for (String[] rule : rules) {
            if (address.contains(rule[0])) {
                return rule[1];
            }
        }
        return null;
    }

    /**
     * The rules in a "fragment=profile, fragment=profile" string, as {fragment,
     * profile} in order; entries without both halves are left out. Fragments
     * are lower case, as they are matched (2026-10-23: shared with the menu's
     * Servers page, which writes the same string back).
     */
    public static java.util.List<String[]> parse(String raw) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String rule : raw.split(",")) {
            int equals = rule.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String fragment = rule.substring(0, equals).trim().toLowerCase();
            String config = rule.substring(equals + 1).trim();
            if (!fragment.isEmpty() && !config.isEmpty()) {
                out.add(new String[]{fragment, config});
            }
        }
        return out;
    }

    /** Rules back into the string parse() reads. */
    public static String format(java.util.List<String[]> rules) {
        StringBuilder out = new StringBuilder();
        for (String[] rule : rules) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(rule[0]).append('=').append(rule[1]);
        }
        return out.toString();
    }

    /** The address of the server played on now, lower case; null in single player. */
    public static String currentAddress() {
        ServerData data = mc.getCurrentServerData();
        if (data == null || data.serverIP == null) {
            return null;
        }
        return data.serverIP.toLowerCase();
    }

    private void apply(String target, String address) {
        File file = new File(CONFIG_DIR, target + ".json");
        if (!file.exists()) {
            /* Loading a name that does not exist makes the client save the
               current state under it. Refusing keeps a mistyped rule from
               quietly becoming a profile. */
            if (this.chat.getValue()) {
                ChatUtil.sendFormatted("&7[&bServerProfiles&7] &cno config named &f"
                        + target + "&c, left alone");
            }
            this.applied = target;
            return;
        }
        this.applied = target;
        /* Owner, 2026-10-23 (D3): what was changed under the profile in use is
           saved to it first. Loading used to drop it; quitting would have
           saved it there anyway. */
        String from = Config.lastConfig == null || Config.lastConfig.isEmpty() || Config.lastConfig.equals("!")
                ? "default" : Config.lastConfig;
        boolean saved = !from.equalsIgnoreCase(target);
        if (saved) {
            new Config(from, false).save();
        }
        new Config(target, false).load();
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bServerProfiles&7] loaded &f" + target
                    + "&7 for &f" + address + (saved ? "&7 (your changes to &f" + from + "&7 were saved)" : ""));
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.applied == null ? "idle" : this.applied};
    }
}
