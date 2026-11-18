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

public class ServerProfiles extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File CONFIG_DIR = new File("./config/Atlas/");

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

        String address = address();
        if (address == null) {
            return;
        }
        String target = match(address);
        if (target == null || target.equals(this.applied)) {
            return;
        }

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

    private String match(String address) {
        return match(parse(this.rules.getValue()), address);
    }

    public static String match(java.util.List<String[]> rules, String address) {
        for (String[] rule : rules) {
            if (address.contains(rule[0])) {
                return rule[1];
            }
        }
        return null;
    }

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
            if (this.chat.getValue()) {
                ChatUtil.sendFormatted("&7[&bServerProfiles&7] &cno config named &f"
                        + target + "&c, left alone");
            }
            this.applied = target;
            return;
        }
        this.applied = target;

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
