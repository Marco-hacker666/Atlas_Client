package atlas.nickresolver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IdentityCache {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    private static final int FORMAT = 1;

    static final long RETENTION_MS = 14L * 24 * 60 * 60 * 1000;

    public static final class SkinOwner {
        public final UUID uuid;
        public final String name;
        public final long seenAt;

        SkinOwner(UUID uuid, String name, long seenAt) {
            this.uuid = uuid;
            this.name = name;
            this.seenAt = seenAt;
        }
    }

    private final Map<UUID, ResolvedIdentity> byUuid = new ConcurrentHashMap<UUID, ResolvedIdentity>();
    private final Map<String, UUID> byShownName = new ConcurrentHashMap<String, UUID>();
    private final Map<String, SkinOwner> skinIndex = new ConcurrentHashMap<String, SkinOwner>();
    private final File file;
    private volatile boolean dirty;

    public IdentityCache(File file) {
        this.file = file;
    }

    public ResolvedIdentity get(UUID uuid) {
        return uuid == null ? null : this.byUuid.get(uuid);
    }

    public ResolvedIdentity getOrCreate(UUID uuid) {
        ResolvedIdentity existing = this.byUuid.get(uuid);
        if (existing != null) {
            return existing;
        }
        ResolvedIdentity created = new ResolvedIdentity(uuid);
        ResolvedIdentity raced = ((ConcurrentHashMap<UUID, ResolvedIdentity>) this.byUuid).putIfAbsent(uuid, created);
        this.dirty = true;
        return raced != null ? raced : created;
    }

    public ResolvedIdentity byShownName(String name) {
        if (name == null) {
            return null;
        }
        UUID uuid = this.byShownName.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? null : this.byUuid.get(uuid);
    }

    public ResolvedIdentity find(String name) {
        ResolvedIdentity shown = this.byShownName(name);
        if (shown != null) {
            return shown;
        }
        for (ResolvedIdentity identity : this.byUuid.values()) {
            if (identity.originalName != null && identity.originalName.equalsIgnoreCase(name)) {
                return identity;
            }
        }
        return null;
    }

    public Collection<ResolvedIdentity> all() {
        return Collections.unmodifiableCollection(this.byUuid.values());
    }

    public SkinOwner skinOwner(String skinHash) {
        return skinHash == null ? null : this.skinIndex.get(skinHash);
    }

    public void updateShownName(ResolvedIdentity identity, String shown) {
        synchronized (identity) {
            String old = identity.displayName;
            if (old != null && !old.equalsIgnoreCase(shown)) {
                this.byShownName.remove(old.toLowerCase(Locale.ROOT), identity.uuid);
            }
            identity.displayName = shown;
            identity.lastSeen = System.currentTimeMillis();
        }
        if (shown != null) {
            this.byShownName.put(shown.toLowerCase(Locale.ROOT), identity.uuid);
        }
        this.dirty = true;
    }

    public boolean resolve(ResolvedIdentity identity, String originalName, ResolvedIdentity.Source source) {
        synchronized (identity) {
            if (identity.source != ResolvedIdentity.Source.UNKNOWN
                    && rank(source) > rank(identity.source)) {
                return false;
            }
            identity.originalName = originalName;
            identity.source = source;
        }
        this.dirty = true;
        return true;
    }

    public void reset(ResolvedIdentity identity) {
        synchronized (identity) {
            identity.originalName = null;
            identity.source = ResolvedIdentity.Source.UNKNOWN;
            identity.mojangCheckedAt = 0L;
        }
        this.dirty = true;
    }

    public void indexSkin(String skinHash, UUID owner, String name) {
        if (skinHash == null || owner == null || name == null) {
            return;
        }
        this.skinIndex.put(skinHash, new SkinOwner(owner, name, System.currentTimeMillis()));
        this.dirty = true;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clear() {
        this.byUuid.clear();
        this.byShownName.clear();
        this.skinIndex.clear();
        this.dirty = true;
    }

    private static int rank(ResolvedIdentity.Source source) {
        switch (source) {
            case TEXTURE_SIGNED:
            case MOJANG_UUID:
            case OFFLINE_UUID:
            case NOT_NICKED:
                return 0;
            case SERVER_PAYLOAD:
                return 1;
            case CLOUD_REPORT:
                return 2;
            case SKIN_HISTORY:
                return 3;
            case FAKE_UUID:
                return 4;
            default:
                return 5;
        }
    }

    public void load() {
        if (!this.file.isFile()) {
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(this.file), StandardCharsets.UTF_8)) {
            JsonElement rootElement = new JsonParser().parse(reader);
            if (!rootElement.isJsonObject()) {
                return;
            }
            JsonObject root = rootElement.getAsJsonObject();
            long now = System.currentTimeMillis();
            if (root.has("identities")) {
                for (JsonElement e : root.getAsJsonArray("identities")) {
                    ResolvedIdentity identity = readIdentity(e.getAsJsonObject());
                    if (identity == null || now - identity.lastSeen > RETENTION_MS
                            || !IdentityResolver.isPlayerUuid(identity.uuid)) {
                        continue;
                    }
                    this.byUuid.put(identity.uuid, identity);
                    if (identity.displayName != null) {
                        this.byShownName.put(identity.displayName.toLowerCase(Locale.ROOT), identity.uuid);
                    }
                }
            }
            if (root.has("skins")) {
                for (JsonElement e : root.getAsJsonArray("skins")) {
                    JsonObject o = e.getAsJsonObject();
                    UUID uuid = TextureDecoder.parseUuid(str(o, "uuid"));
                    String hash = str(o, "hash");
                    String name = str(o, "name");
                    long seenAt = o.has("seenAt") ? o.get("seenAt").getAsLong() : 0L;
                    if (uuid != null && hash != null && name != null && now - seenAt <= RETENTION_MS) {
                        this.skinIndex.put(hash, new SkinOwner(uuid, name, seenAt));
                    }
                }
            }
            LOG.info("Loaded {} identities and {} skins from {}", this.byUuid.size(), this.skinIndex.size(), this.file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting with an empty cache", this.file, e);
        }
    }

    public synchronized void saveIfDirty() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        long now = System.currentTimeMillis();
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("savedAt", now);

        JsonArray identities = new JsonArray();
        List<ResolvedIdentity> snapshot = new ArrayList<ResolvedIdentity>(this.byUuid.values());
        for (ResolvedIdentity identity : snapshot) {
            if (now - identity.lastSeen <= RETENTION_MS) {
                identities.add(writeIdentity(identity));
            }
        }
        root.add("identities", identities);

        JsonArray skins = new JsonArray();
        for (Map.Entry<String, SkinOwner> e : this.skinIndex.entrySet()) {
            SkinOwner owner = e.getValue();
            if (now - owner.seenAt > RETENTION_MS) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("hash", e.getKey());
            o.addProperty("uuid", owner.uuid.toString());
            o.addProperty("name", owner.name);
            o.addProperty("seenAt", owner.seenAt);
            skins.add(o);
        }
        root.add("skins", skins);

        File parent = this.file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            LOG.warn("Could not create {}", parent);
            this.dirty = true;
            return;
        }
        File tmp = new File(this.file.getPath() + ".tmp");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            gson.toJson(root, writer);
        } catch (IOException e) {
            LOG.warn("Could not write {}", tmp, e);
            this.dirty = true;
            return;
        }
        try {
            try {
                Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Could not replace {}", this.file, e);
            this.dirty = true;
        }
    }

    private static JsonObject writeIdentity(ResolvedIdentity identity) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", identity.uuid.toString());
        put(o, "displayName", identity.displayName);
        put(o, "tabDisplayName", identity.tabDisplayName);
        put(o, "originalName", identity.originalName);
        o.addProperty("source", identity.source.name());
        put(o, "skinHash", identity.skinHash);
        put(o, "skinOwnerName", identity.skinOwnerName);
        put(o, "skinOwnerId", identity.skinOwnerId == null ? null : identity.skinOwnerId.toString());
        o.addProperty("firstSeen", identity.firstSeen);
        o.addProperty("lastSeen", identity.lastSeen);
        o.addProperty("mojangCheckedAt", identity.mojangCheckedAt);
        return o;
    }

    private static ResolvedIdentity readIdentity(JsonObject o) {
        UUID uuid = TextureDecoder.parseUuid(str(o, "uuid"));
        if (uuid == null) {
            return null;
        }
        ResolvedIdentity identity = new ResolvedIdentity(uuid);
        identity.displayName = str(o, "displayName");
        identity.tabDisplayName = str(o, "tabDisplayName");
        identity.originalName = str(o, "originalName");
        try {
            identity.source = ResolvedIdentity.Source.valueOf(str(o, "source"));
        } catch (RuntimeException e) {
            identity.source = ResolvedIdentity.Source.UNKNOWN;
        }
        identity.skinHash = str(o, "skinHash");
        identity.skinOwnerName = str(o, "skinOwnerName");
        identity.skinOwnerId = TextureDecoder.parseUuid(str(o, "skinOwnerId"));
        identity.firstSeen = o.has("firstSeen") ? o.get("firstSeen").getAsLong() : 0L;
        identity.lastSeen = o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0L;
        identity.mojangCheckedAt = o.has("mojangCheckedAt") ? o.get("mojangCheckedAt").getAsLong() : 0L;
        return identity;
    }

    private static void put(JsonObject o, String key, String value) {
        if (value != null) {
            o.addProperty(key, value);
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
