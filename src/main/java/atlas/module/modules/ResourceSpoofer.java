package atlas.module.modules;

import io.netty.buffer.Unpooled;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.PacketEvent;
import atlas.mixin.IAccessorC17PacketCustomPayload;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.TextProperty;
import atlas.util.ChatUtil;
import atlas.util.PacketUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.client.C19PacketResourcePackStatus.Action;
import net.minecraft.network.play.server.S48PacketResourcePackSend;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Note this is not ported from LiquidBounce

public class ResourceSpoofer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String REGISTER = "REGISTER";
    private static final String UNREGISTER = "UNREGISTER";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static final String DEFAULT_ALLOWED =
            "MC|Brand,MC|BEdit,MC|BSign,MC|TrList,MC|AdvCdm,MC|PickItem,MC|ItemName,MC|Beacon,MC|Struct";

    public final ModeProperty packMode = new ModeProperty("resource-pack", 1,
            new String[]{"OFF", "FAKE_LOADED", "DECLINE", "FAIL"});
    public final BooleanProperty blockDownload = new BooleanProperty("block-download", true,
            () -> this.packMode.getValue() != 0);

    public final BooleanProperty filterChannels = new BooleanProperty("filter-channels", true);
    public final TextProperty allowedChannels = new TextProperty("allowed-channels", DEFAULT_ALLOWED,
            this.filterChannels::getValue);

    public final BooleanProperty blockPrivateUrls = new BooleanProperty("block-private-urls", true);

    public final BooleanProperty disableTelemetry = new BooleanProperty("disable-telemetry", true);

    public final BooleanProperty notify = new BooleanProperty("notify", true);

    private int packsSpoofed;
    private int packsBlocked;
    private int channelsStripped;

    public ResourceSpoofer() {
        super("ResourceSpoofer", false, false,
                "Answers resource pack requests without loading them and hides modded plugin channels");
    }

    @Override
    public void onEnabled() {
        this.packsSpoofed = 0;
        this.packsBlocked = 0;
        this.channelsStripped = 0;
    }

    private void say(String message) {
        if (this.notify.getValue()) {
            ChatUtil.sendFormatted("&7[&bResourceSpoofer&7] " + message);
        }
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        String rest = scheme < 0 ? url : url.substring(scheme + 3);
        int at = rest.indexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end).toLowerCase();
    }

    private static boolean isPrivateHost(String host) {
        if (host.isEmpty() || host.equals("localhost")
                || host.endsWith(".local") || host.endsWith(".lan")
                || host.endsWith(".internal") || host.endsWith(".home")) {
            return true;
        }
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (bare.indexOf(':') >= 0) {
            return bare.equals("::1") || bare.startsWith("fc") || bare.startsWith("fd")
                    || bare.startsWith("fe80");
        }
        String[] parts = bare.split("[.]");
        if (parts.length != 4) {
            return false;
        }
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                o[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (o[i] < 0 || o[i] > 255) {
                return false;
            }
        }
        return o[0] == 0 || o[0] == 10 || o[0] == 127
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254);
    }

    private Set<String> allowed() {
        Set<String> set = new HashSet<String>();
        String raw = this.allowedChannels.getValue();
        if (raw == null) {
            return set;
        }
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                set.add(name);
            }
        }
        return set;
    }

    @EventTarget
    public void onTick(atlas.events.TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (this.disableTelemetry.getValue() && mc.gameSettings != null
                && mc.gameSettings.snooperEnabled) {
            mc.gameSettings.snooperEnabled = false;
            say("&7Mojang snooper disabled");
        }
    }

    @EventTarget(Priority.HIGH)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            handleResourcePack(event);
        } else {
            handleChannels(event);
        }
    }

    private void handleResourcePack(PacketEvent event) {
        if (!(event.getPacket() instanceof S48PacketResourcePackSend)) {
            return;
        }
        S48PacketResourcePackSend packet = (S48PacketResourcePackSend) event.getPacket();
        String hash = packet.getHash();

        if (this.blockPrivateUrls.getValue() && isPrivateHost(hostOf(packet.getURL()))) {
            event.setCancelled(true);
            PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, Action.FAILED_DOWNLOAD));
            this.packsBlocked++;
            say("&cblocked&7 pack pointing at a private address: &f" + packet.getURL());
            return;
        }

        if (this.packMode.getValue() == 0) {
            return;
        }

        if (this.blockDownload.getValue()) {
            event.setCancelled(true);
        }

        switch (this.packMode.getValue()) {
            case 1:

                PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, Action.ACCEPTED));
                PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, Action.SUCCESSFULLY_LOADED));
                break;
            case 2:
                PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, Action.DECLINED));
                break;
            case 3:
                PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, Action.ACCEPTED));
                PacketUtil.sendPacket(new C19PacketResourcePackStatus(hash, Action.FAILED_DOWNLOAD));
                break;
            default:
                break;
        }
        this.packsSpoofed++;
        say("&f" + this.packMode.getModeString() + "&7 for pack from &f" + packet.getURL());
    }

    private void handleChannels(PacketEvent event) {
        if (!this.filterChannels.getValue() || !(event.getPacket() instanceof C17PacketCustomPayload)) {
            return;
        }
        C17PacketCustomPayload packet = (C17PacketCustomPayload) event.getPacket();
        String channel = packet.getChannelName();
        if (!REGISTER.equals(channel) && !UNREGISTER.equals(channel)) {
            return;
        }

        PacketBuffer data = packet.getBufferData();
        if (data == null) {
            return;
        }

        byte[] raw = new byte[data.readableBytes()];
        data.getBytes(data.readerIndex(), raw);
        String[] names = new String(raw, UTF_8).split("\0");

        Set<String> keep = allowed();
        List<String> kept = new ArrayList<String>();
        int removed = 0;
        for (String name : names) {
            if (name.isEmpty()) {
                continue;
            }
            if (keep.contains(name)) {
                kept.add(name);
            } else {
                removed++;
            }
        }
        if (removed == 0) {
            return;
        }
        this.channelsStripped += removed;

        if (kept.isEmpty()) {
            event.setCancelled(true);
            say("&7dropped &f" + removed + "&7 channel(s) on " + channel);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) {
                sb.append('\0');
            }
            sb.append(kept.get(i));
        }
        PacketBuffer replacement = new PacketBuffer(Unpooled.buffer());
        replacement.writeBytes(sb.toString().getBytes(UTF_8));
        ((IAccessorC17PacketCustomPayload) packet).setData(replacement);
        say("&7stripped &f" + removed + "&7 of " + names.length + " channels on " + channel);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.packMode.getModeString()};
    }
}
