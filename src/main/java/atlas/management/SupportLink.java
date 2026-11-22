package atlas.management;

import atlas.Atlas;
import atlas.command.CommandManager;
import atlas.config.Config;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.ChatUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SupportLink {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File CONSENT = new File("./config/Atlas/support-consent.txt");
    private static final long POLL_MS = 10_000L;
    private static final long FAST_POLL_MS = 2_500L;
    private static final long FAST_FOR_MS = 5L * 60_000L;
    private static final ExecutorService POLLER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(4), r -> {
        Thread t = new Thread(r, "Atlas support");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    private static volatile long lastPoll;
    private static volatile long activeUntil;
    private static final long POLL_STUCK_MS = 60_000L;
    private static volatile boolean polling;
    private static volatile long pollSince;
    private static volatile boolean asked;
    private static volatile String consentCache;

    private SupportLink() {
    }

    public static String consent() {
        String c = consentCache;
        if (c == null) {
            try {
                c = CONSENT.isFile() ? new String(Files.readAllBytes(CONSENT.toPath()), StandardCharsets.UTF_8).trim() : "";
            } catch (Exception e) {
                c = "";
            }
            consentCache = c;
        }
        return c;
    }

    public static void setConsent(boolean yes) {
        consentCache = yes ? "yes" : "no";
        try {
            CONSENT.getParentFile().mkdirs();
            Files.write(CONSENT.toPath(), consentCache.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        asked = false;
        activeUntil = System.currentTimeMillis() + FAST_FOR_MS;
        lastPoll = 0L;
    }

    public static void allowed() {
        sendSettings(UsageReporter.currentSid(), UsageReporter.currentPlayer());
    }

    static void tick(final String sid, final String player) {
        long now = System.currentTimeMillis();
        long every = now < activeUntil ? FAST_POLL_MS : POLL_MS;
        if (sid == null || now - lastPoll < every || polling && now - pollSince < POLL_STUCK_MS) {
            return;
        }
        lastPoll = now;
        polling = true;
        pollSince = now;
        final long started = now;
        POLLER.execute(() -> {
            try {
                poll(sid, player);
            } finally {
                if (pollSince == started) {
                    polling = false;
                }
            }
        });
    }

    private static void poll(String sid, String player) {
        JsonObject body = new JsonObject();
        body.addProperty("sid", sid);
        body.addProperty("player", player.isEmpty() ? "-" : player);
        body.addProperty("consent", consent());
        String text = UsageReporter.call("/usage/support/poll", body);
        if (text == null) {
            return;
        }
        JsonObject answer;
        try {
            answer = new JsonParser().parse(text).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        if (bool(answer, "ask") && !"no".equals(consent()) && !asked) {
            asked = true;
            mc.addScheduledTask(SupportLink::showAsk);
        }
        if (!"yes".equals(consent())) {
            return;
        }
        final List<String> messages = new ArrayList<String>();
        if (answer.has("messages") && answer.get("messages").isJsonArray()) {
            for (JsonElement m : answer.getAsJsonArray("messages")) {
                if (m.isJsonObject() && m.getAsJsonObject().has("text")) {
                    messages.add(m.getAsJsonObject().get("text").getAsString());
                }
            }
        }
        if (!messages.isEmpty()) {
            activeUntil = System.currentTimeMillis() + FAST_FOR_MS;
            mc.addScheduledTask(() -> {
                for (String m : messages) {
                    showMessage(m);
                }
            });
        }
        if (answer.has("changes") && answer.get("changes").isJsonArray()) {
            for (JsonElement c : answer.getAsJsonArray("changes")) {
                if (c.isJsonObject()) {
                    final JsonObject change = c.getAsJsonObject();
                    activeUntil = System.currentTimeMillis() + FAST_FOR_MS;
                    mc.addScheduledTask(() -> applyChange(sid, player, change));
                }
            }
        }
        if (bool(answer, "wantSettings")) {
            mc.addScheduledTask(() -> sendSettings(sid, player));
        }
    }

    private static boolean bool(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static void showAsk() {
        IChatComponent line = new ChatComponentText(EnumChatFormatting.AQUA + "[Atlas] " + EnumChatFormatting.GRAY
                + "The Atlas owner wants to help you: chat with you here and adjust your settings. ");
        line.appendSibling(button("[Allow]", EnumChatFormatting.GREEN, CommandManager.ownClick(".support allow"),
                "Allow chat and settings help"));
        line.appendSibling(new ChatComponentText(" "));
        line.appendSibling(button("[No]", EnumChatFormatting.RED, CommandManager.ownClick(".support deny"),
                "Do not allow"));
        ChatUtil.send(line);
    }

    private static void showMessage(String raw) {
        String text = raw.replace('§', ' ');
        IChatComponent line = new ChatComponentText(EnumChatFormatting.GOLD + "[Atlas owner] " + EnumChatFormatting.WHITE
                + text + " ");
        line.appendSibling(button("[Reply]", EnumChatFormatting.AQUA,
                new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, ".support "), "Type your answer"));
        ChatUtil.send(line);
    }

    private static IChatComponent button(String label, EnumChatFormatting colour, ClickEvent click, String hover) {
        IChatComponent b = new ChatComponentText(colour + label);
        b.setChatStyle(new ChatStyle().setChatClickEvent(click)
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hover))));
        return b;
    }

    public static boolean reply(String text) {
        final String sid = UsageReporter.currentSid();
        final String player = UsageReporter.currentPlayer();
        if (sid == null) {
            return false;
        }
        final JsonObject body = new JsonObject();
        body.addProperty("sid", sid);
        body.addProperty("player", player.isEmpty() ? "-" : player);
        body.addProperty("text", text.length() > 500 ? text.substring(0, 500) : text);
        activeUntil = System.currentTimeMillis() + FAST_FOR_MS;
        POLLER.execute(() -> {
            String ok = UsageReporter.call("/usage/support/reply", body);
            mc.addScheduledTask(() -> ChatUtil.sendFormatted(ok != null ? "&6[You → Atlas owner] &f" + text
                    : "&c[Atlas] Your message could not be sent."));
        });
        return true;
    }

    static boolean offLimits(Module m) {
        return m instanceof atlas.module.modules.MommyIsHere || m instanceof atlas.module.modules.AutoAuth
                || m instanceof atlas.script.ScriptHost;
    }

    static String kind(Property<?> p) {
        if (p instanceof BooleanProperty) {
            return "bool";
        }
        if (p instanceof IntProperty) {
            return "int";
        }
        if (p instanceof PercentProperty) {
            return "percent";
        }
        if (p instanceof FloatProperty) {
            return "float";
        }
        if (p instanceof ModeProperty) {
            return "mode";
        }
        if (p instanceof ColorProperty) {
            return "color";
        }
        return null;
    }

    static JsonArray snapshot() {
        JsonArray out = new JsonArray();
        for (Module m : Atlas.moduleManager.modules.values()) {
            if (offLimits(m)) {
                continue;
            }
            JsonObject mo = new JsonObject();
            mo.addProperty("m", m.getClass().getSimpleName());
            mo.addProperty("name", m.getName());
            mo.addProperty("on", m.isEnabled());
            JsonArray props = new JsonArray();
            List<Property<?>> list = Atlas.propertyManager.properties.get(m.getClass());
            if (list != null) {
                for (Property<?> p : list) {
                    String k = kind(p);
                    if (k == null) {
                        continue;
                    }
                    JsonObject po = new JsonObject();
                    po.addProperty("n", p.getName());
                    po.addProperty("t", k);
                    if (p instanceof ModeProperty) {
                        ModeProperty mp = (ModeProperty) p;
                        po.addProperty("v", mp.getModeString());
                        JsonArray opts = new JsonArray();
                        for (String s : mp.visibleModes()) {
                            opts.add(new com.google.gson.JsonPrimitive(s));
                        }
                        po.add("o", opts);
                    } else if (p instanceof ColorProperty) {
                        po.addProperty("v", String.format("#%06X", ((Integer) p.getValue()) & 0xFFFFFF));
                    } else {
                        po.addProperty("v", String.valueOf(p.getValue()));
                    }
                    if (p instanceof IntProperty) {
                        po.addProperty("min", ((IntProperty) p).getMinimum());
                        po.addProperty("max", ((IntProperty) p).getMaximum());
                    } else if (p instanceof PercentProperty) {
                        po.addProperty("min", ((PercentProperty) p).getMinimum());
                        po.addProperty("max", ((PercentProperty) p).getMaximum());
                    } else if (p instanceof FloatProperty) {
                        po.addProperty("min", ((FloatProperty) p).getMinimum());
                        po.addProperty("max", ((FloatProperty) p).getMaximum());
                    }
                    if (!p.isVisible()) {
                        po.addProperty("hid", true);
                    }
                    props.add(po);
                }
            }
            mo.add("p", props);
            out.add(mo);
        }
        return out;
    }

    static void sendSettings(String sid, String player) {
        if (!"yes".equals(consent()) || sid == null) {
            return;
        }
        final JsonObject body = new JsonObject();
        body.addProperty("sid", sid);
        body.addProperty("player", player.isEmpty() ? "-" : player);
        body.add("settings", snapshot());
        POLLER.execute(() -> UsageReporter.call("/usage/support/settings", body));
    }

    static void applyChange(String sid, String player, JsonObject change) {
        if (!"yes".equals(consent()) || !change.has("items") || !change.get("items").isJsonArray()) {
            return;
        }
        List<String> done = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (JsonElement e : change.getAsJsonArray("items")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject it = e.getAsJsonObject();
            String cls = str(it, "m");
            String prop = str(it, "p");
            String value = str(it, "v");
            String label = cls + "." + prop;
            if (cls == null || prop == null) {
                failed.add(label);
                continue;
            }
            Module m = null;
            for (Module x : Atlas.moduleManager.modules.values()) {
                if (x.getClass().getSimpleName().equals(cls)) {
                    m = x;
                }
            }
            if (m == null || offLimits(m) || value == null) {
                failed.add(label);
                continue;
            }
            label = m.getName() + (prop.equals("on") ? "" : " " + prop);
            if (prop.equals("on")) {
                m.setEnabled(Boolean.parseBoolean(value));
                done.add(label + " " + (m.isEnabled() ? "on" : "off"));
                continue;
            }
            Property<?> target = null;
            List<Property<?>> list = Atlas.propertyManager.properties.get(m.getClass());
            if (list != null) {
                for (Property<?> p : list) {
                    if (p.getName().equals(prop)) {
                        target = p;
                    }
                }
            }
            if (target == null || kind(target) == null) {
                failed.add(label);
                continue;
            }
            boolean ok;
            try {
                ok = target.parseString(value);
            } catch (Exception ex) {
                ok = false;
            }
            if (ok) {
                done.add(label + " " + value);
            } else {
                failed.add(label);
            }
        }
        if (!done.isEmpty()) {
            try {
                String name = Config.lastConfig == null || Config.lastConfig.isEmpty() ? "default" : Config.lastConfig;
                new Config(name, false).save();
            } catch (Exception ignored) {
            }
            String list = String.join(", ", done);
            ChatUtil.sendFormatted("&6[Atlas owner] &7changed your settings: &f"
                    + (list.length() > 200 ? list.substring(0, 200) + "…" : list) + "&r");
        }
        JsonObject body = new JsonObject();
        body.addProperty("sid", sid);
        body.addProperty("player", player.isEmpty() ? "-" : player);
        body.addProperty("id", changeId(change));
        body.addProperty("applied", done.size());
        JsonArray f = new JsonArray();
        for (String s : failed) {
            f.add(new com.google.gson.JsonPrimitive(s));
        }
        body.add("failed", f);
        body.add("settings", snapshot());
        POLLER.execute(() -> UsageReporter.call("/usage/support/result", body));
    }

    static long changeId(JsonObject change) {
        try {
            return change.has("id") && change.get("id").isJsonPrimitive() ? change.get("id").getAsLong() : -1L;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }
}
