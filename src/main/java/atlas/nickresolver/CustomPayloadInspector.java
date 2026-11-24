package atlas.nickresolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class CustomPayloadInspector {
    private static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_CHANNELS = 64;
    private static final String[] UUID_KEYS = {"uuid", "id", "playerId", "player_uuid", "uniqueId"};
    private static final String[] REAL_NAME_KEYS = {"realName", "real_name", "originalName", "original_name",
            "realname", "username", "accountName"};
    private static final String[] NICK_KEYS = {"nick", "nickname", "displayName", "display_name", "disguise"};
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");

    public static final class ChannelStats {
        public final String channel;
        public final AtomicLong packets = new AtomicLong();
        public final AtomicLong hints = new AtomicLong();
        public volatile int lastSize;
        public volatile boolean sawUuid;

        ChannelStats(String channel) {
            this.channel = channel;
        }
    }

    private final IdentityResolver resolver;
    private final Map<String, ChannelStats> channels = Collections.synchronizedMap(
            new LinkedHashMap<String, ChannelStats>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ChannelStats> eldest) {
                    return this.size() > MAX_CHANNELS;
                }
            });

    public CustomPayloadInspector(IdentityResolver resolver) {
        this.resolver = resolver;
    }

    public void inspect(String channel, ByteBuf buffer, boolean parse) {
        if (channel == null || buffer == null) {
            return;
        }
        ChannelStats stats;
        synchronized (this.channels) {
            stats = this.channels.get(channel);
            if (stats == null) {
                stats = new ChannelStats(channel);
                this.channels.put(channel, stats);
            }
        }
        stats.packets.incrementAndGet();
        int readable = buffer.readableBytes();
        stats.lastSize = readable;
        if (!parse || readable <= 2 || readable > MAX_BYTES || channel.startsWith("MC|")) {
            return;
        }

        byte[] bytes = new byte[readable];
        buffer.getBytes(buffer.readerIndex(), bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (UUID_TEXT.matcher(text).find()) {
            stats.sawUuid = true;
        }
        int hints = 0;
        int start = text.indexOf('{');
        int startArray = text.indexOf('[');
        if (startArray >= 0 && (start < 0 || startArray < start)) {
            start = startArray;
        }
        if (start >= 0) {
            try {
                JsonElement root = new JsonParser().parse(text.substring(start).trim());
                hints = this.walk(root, 0);
            } catch (RuntimeException notJson) {
                hints = 0;
            }
        }
        if (hints > 0) {
            stats.hints.addAndGet(hints);
        }
    }

    public List<ChannelStats> channels() {
        synchronized (this.channels) {
            return new ArrayList<ChannelStats>(this.channels.values());
        }
    }

    private int walk(JsonElement element, int depth) {
        if (element == null || depth > 8) {
            return 0;
        }
        int found = 0;
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            int limit = Math.min(array.size(), 512);
            for (int i = 0; i < limit; i++) {
                found += this.walk(array.get(i), depth + 1);
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            found += this.tryObject(object);
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                if (e.getValue().isJsonObject() || e.getValue().isJsonArray()) {
                    found += this.walk(e.getValue(), depth + 1);
                }
            }
        }
        return found;
    }

    private int tryObject(JsonObject object) {
        UUID uuid = null;
        for (String key : UUID_KEYS) {
            uuid = TextureDecoder.parseUuid(string(object, key));
            if (uuid != null) {
                break;
            }
        }
        if (uuid == null) {
            return 0;
        }
        String real = null;
        for (String key : REAL_NAME_KEYS) {
            real = string(object, key);
            if (real != null) {
                break;
            }
        }
        if (real == null) {
            String name = string(object, "name");
            String nick = null;
            for (String key : NICK_KEYS) {
                nick = string(object, key);
                if (nick != null) {
                    break;
                }
            }
            if (name != null && nick != null && !name.equalsIgnoreCase(IdentityResolver.strip(nick))) {
                real = name;
            }
        }
        if (real == null) {
            return 0;
        }
        this.resolver.onPayloadHint(uuid, IdentityResolver.strip(real));
        return 1;
    }

    private static String string(JsonObject object, String key) {
        JsonElement e = object.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        String s = e.getAsString();
        return s.isEmpty() ? null : s;
    }
}
