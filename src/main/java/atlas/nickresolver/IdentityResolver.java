package atlas.nickresolver;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class IdentityResolver {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final int MAX_KNOWN_NAMES = 8192;
    private static final long RETRY_AFTER_ERROR_MS = 60_000L;

    private final IdentityCache cache;
    private final MojangApiClient mojang;
    private final NickSettings settings;
    private final CloudClient cloud;

    private final Set<UUID> online = ConcurrentHashMap.newKeySet();

    private final Map<Integer, UUID> entityToUuid = new ConcurrentHashMap<Integer, UUID>();

    private final Set<String> knownNames = ConcurrentHashMap.newKeySet();

    private volatile UUID selfUuid;
    private volatile SessionLog log;

    public IdentityResolver(IdentityCache cache, MojangApiClient mojang, NickSettings settings, CloudClient cloud) {
        this.cache = cache;
        this.mojang = mojang;
        this.settings = settings;
        this.cloud = cloud;
        for (ResolvedIdentity identity : cache.all()) {
            this.learnName(identity.displayName, false);
            this.learnName(identity.originalName, false);
        }
    }

    public void setLog(SessionLog log) {
        this.log = log;
    }

    public void onTabAdd(GameProfile profile, IChatComponent tabDisplay) {
        if (profile == null || profile.getId() == null) {
            return;
        }
        UUID uuid = profile.getId();
        String shown = profile.getName();
        if (!isPlayerUuid(uuid) || shown == null || shown.isEmpty() || uuid.equals(this.self())) {
            return;
        }
        this.online.add(uuid);

        ResolvedIdentity identity = this.cache.getOrCreate(uuid);
        String previous = identity.displayName;
        this.cache.updateShownName(identity, shown);
        if (previous != null && !previous.equalsIgnoreCase(shown)) {
            this.cache.reset(identity);
        }
        if (tabDisplay != null) {
            identity.tabDisplayName = strip(tabDisplay.getUnformattedText());
        }

        TextureDecoder.TextureInfo textures = TextureDecoder.decode(profile);
        identity.skinHash = textures == null ? null : textures.skinHash;
        identity.skinOwnerName = null;
        identity.skinOwnerId = null;

        this.learnName(shown, true);
        this.decide(identity, textures);
        this.cloud.record(identity);
        SessionLog log = this.log;
        if (log != null) {
            log.tab(identity, textures);
        }
    }

    public void onTabDisplayName(UUID uuid, IChatComponent tabDisplay) {
        ResolvedIdentity identity = this.cache.get(uuid);
        if (identity != null) {
            identity.tabDisplayName = tabDisplay == null ? null : strip(tabDisplay.getUnformattedText());
            identity.lastSeen = System.currentTimeMillis();
        }
    }

    public void onTabRemove(UUID uuid) {
        if (uuid != null) {
            this.online.remove(uuid);
            ResolvedIdentity identity = this.cache.get(uuid);
            if (identity != null) {
                identity.lastSeen = System.currentTimeMillis();
                this.cache.markDirty();
            }
        }
    }

    public void onSpawnPlayer(int entityId, UUID uuid) {
        if (uuid != null) {
            this.entityToUuid.put(entityId, uuid);
        }
    }

    public void onDestroyEntities(int[] ids) {
        for (int id : ids) {
            this.entityToUuid.remove(id);
        }
    }

    public void onWorldChange() {
        this.entityToUuid.clear();
    }

    public void onDisconnect() {
        this.entityToUuid.clear();
        this.online.clear();
        this.selfUuid = null;
    }

    public void onPayloadHint(UUID uuid, String name) {
        if (uuid == null || name == null || !VALID_NAME.matcher(name).matches()) {
            return;
        }
        this.learnName(name, true);
        ResolvedIdentity identity = this.cache.get(uuid);
        if (identity == null || identity.displayName == null || name.equalsIgnoreCase(identity.displayName)) {
            return;
        }
        this.cache.resolve(identity, name, ResolvedIdentity.Source.SERVER_PAYLOAD);
        this.cloud.record(identity);
    }

    public ResolvedIdentity forEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        ResolvedIdentity identity = this.cache.get(entity.getUniqueID());
        if (identity == null) {
            UUID mapped = this.entityToUuid.get(entity.getEntityId());
            identity = this.cache.get(mapped);
        }
        return identity;
    }

    public ResolvedIdentity forShownName(String name) {
        return this.cache.byShownName(name);
    }

    public Collection<UUID> online() {
        return Collections.unmodifiableSet(this.online);
    }

    public List<ResolvedIdentity> onlineIdentities() {
        List<ResolvedIdentity> list = new ArrayList<ResolvedIdentity>();
        for (UUID uuid : this.online) {
            ResolvedIdentity identity = this.cache.get(uuid);
            if (identity != null) {
                list.add(identity);
            }
        }
        return list;
    }

    public void refreshOnline() {
        if (!this.settings.enabled || !this.settings.useMojang) {
            return;
        }
        for (UUID uuid : this.online) {
            ResolvedIdentity identity = this.cache.get(uuid);
            if (identity != null && this.needsMojang(identity)) {
                this.queueMojang(identity);
            }
        }
    }

    public boolean forceLookup(ResolvedIdentity identity) {
        identity.nextLookupAt = 0L;
        identity.mojangCheckedAt = 0L;
        return identity.uuid.version() == 4 && this.queueMojang(identity);
    }

    private void decide(ResolvedIdentity identity, TextureDecoder.TextureInfo textures) {
        UUID uuid = identity.uuid;
        String shown = identity.displayName;

        UUID owner = textures == null ? null : textures.trustedOwner();
        if (owner != null) {
            if (owner.equals(uuid) && textures.profileName != null) {
                if (textures.profileName.equalsIgnoreCase(shown)) {
                    this.cache.resolve(identity, shown, ResolvedIdentity.Source.NOT_NICKED);
                    this.cache.indexSkin(textures.skinHash, uuid, shown);
                } else {
                    this.learnName(textures.profileName, false);
                    this.cache.resolve(identity, textures.profileName, ResolvedIdentity.Source.TEXTURE_SIGNED);
                }
                return;
            }
            if (!owner.equals(uuid)) {
                identity.skinOwnerId = owner;
                identity.skinOwnerName = textures.profileName;
            }
        }

        if (uuid.version() == 3) {
            if (offlineUuid(shown).equals(uuid)) {
                this.cache.resolve(identity, shown, ResolvedIdentity.Source.NOT_NICKED);
                if (textures != null && owner == null) {
                    this.cache.indexSkin(textures.skinHash, uuid, shown);
                }
                return;
            }
            String real = this.matchOfflineUuid(uuid);
            if (real != null) {
                this.cache.resolve(identity, real, ResolvedIdentity.Source.OFFLINE_UUID);
                return;
            }
            this.cache.resolve(identity, null, ResolvedIdentity.Source.FAKE_UUID);
        }

        if (textures != null && owner == null && textures.skinHash != null) {
            IdentityCache.SkinOwner seen = this.cache.skinOwner(textures.skinHash);
            if (seen != null && !seen.uuid.equals(uuid) && !seen.name.equalsIgnoreCase(shown)) {
                this.cache.resolve(identity, seen.name, ResolvedIdentity.Source.SKIN_HISTORY);
            }
        }

        if (this.settings.useMojang && this.needsMojang(identity)) {
            this.queueMojang(identity);
        } else {
            this.maybeAskCloud(identity);
        }
    }

    private void maybeAskCloud(ResolvedIdentity identity) {
        ResolvedIdentity.Source source = identity.source;
        boolean worthAsking = source == ResolvedIdentity.Source.FAKE_UUID
                || source == ResolvedIdentity.Source.SKIN_HISTORY
                || (source == ResolvedIdentity.Source.UNKNOWN && identity.skinOwnerId != null);
        long now = System.currentTimeMillis();
        if (!worthAsking || now < identity.nextCloudLookupAt) {
            return;
        }
        identity.nextCloudLookupAt = now + this.settings.cacheTtlMillis();
        this.cloud.lookup(identity, new CloudClient.LookupCallback() {
            @Override
            public void onResult(ResolvedIdentity target, CloudClient.Suggestion suggestion) {
                if (suggestion == null || target.source.confident) {
                    return;
                }
                IdentityResolver.this.learnName(suggestion.name, true);
                IdentityResolver.this.cache.resolve(target, suggestion.name, ResolvedIdentity.Source.CLOUD_REPORT);
            }
        });
    }

    private boolean needsMojang(ResolvedIdentity identity) {
        if (identity.uuid.version() != 4) {
            return false;
        }
        ResolvedIdentity.Source source = identity.source;
        if (source == ResolvedIdentity.Source.TEXTURE_SIGNED || source == ResolvedIdentity.Source.OFFLINE_UUID) {
            return false;
        }
        if (source == ResolvedIdentity.Source.NOT_NICKED && identity.mojangCheckedAt == 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < identity.nextLookupAt || this.mojang.isPending(identity.uuid)) {
            return false;
        }
        return identity.mojangCheckedAt == 0L || now - identity.mojangCheckedAt > this.settings.cacheTtlMillis();
    }

    private boolean queueMojang(final ResolvedIdentity identity) {
        identity.nextLookupAt = System.currentTimeMillis() + RETRY_AFTER_ERROR_MS;
        return this.mojang.lookup(identity.uuid, new MojangApiClient.Callback() {
            @Override
            public void onResult(UUID uuid, MojangApiClient.Result result) {
                IdentityResolver.this.onMojangResult(identity, result);
            }
        });
    }

    private void onMojangResult(ResolvedIdentity identity, MojangApiClient.Result result) {
        long now = System.currentTimeMillis();
        switch (result.kind) {
            case FOUND: {
                identity.mojangCheckedAt = now;
                this.learnName(result.name, false);

                this.cache.indexSkin(result.skinHash, identity.uuid, result.name);
                String shown = identity.displayName;
                if (shown != null && result.name.equalsIgnoreCase(shown)) {
                    this.forceResolve(identity, shown, ResolvedIdentity.Source.NOT_NICKED);
                } else {
                    this.forceResolve(identity, result.name, ResolvedIdentity.Source.MOJANG_UUID);
                }
                this.cloud.record(identity);
                break;
            }
            case NOT_FOUND:
                identity.mojangCheckedAt = now;
                this.cache.resolve(identity, identity.originalName, ResolvedIdentity.Source.FAKE_UUID);

                this.cloud.record(identity);
                this.maybeAskCloud(identity);
                break;
            default:
                identity.nextLookupAt = now + RETRY_AFTER_ERROR_MS;
                break;
        }
    }

    private void forceResolve(ResolvedIdentity identity, String name, ResolvedIdentity.Source source) {
        if (identity.source != ResolvedIdentity.Source.TEXTURE_SIGNED) {
            this.cache.resolve(identity, name, source);
        }
    }

    static boolean isPlayerUuid(UUID uuid) {
        int version = uuid.version();
        return version == 3 || version == 4;
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private String matchOfflineUuid(UUID uuid) {
        for (String name : this.knownNames) {
            if (offlineUuid(name).equals(uuid)) {
                return name;
            }
        }
        return null;
    }

    private void learnName(String name, boolean checkOffline) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            return;
        }
        if (this.knownNames.size() < MAX_KNOWN_NAMES) {
            this.knownNames.add(name);
        }
        if (!checkOffline) {
            return;
        }
        ResolvedIdentity target = this.cache.get(offlineUuid(name));
        if (target != null && target.displayName != null && !name.equalsIgnoreCase(target.displayName)) {
            this.cache.resolve(target, name, ResolvedIdentity.Source.OFFLINE_UUID);
            this.cloud.record(target);
        }
    }

    public ResolvedIdentity tryName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            return null;
        }
        this.learnName(name, true);
        ResolvedIdentity target = this.cache.get(offlineUuid(name));
        return target != null && target.source == ResolvedIdentity.Source.OFFLINE_UUID
                && name.equalsIgnoreCase(target.originalName) ? target : null;
    }

    private UUID self() {
        UUID self = this.selfUuid;
        if (self == null) {
            try {
                GameProfile profile = Minecraft.getMinecraft().getSession().getProfile();
                self = profile == null ? null : profile.getId();
            } catch (RuntimeException ignored) {
                self = null;
            }
            this.selfUuid = self;
        }
        return self;
    }

    static String strip(String text) {
        if (text == null) {
            return null;
        }
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(text);
        return stripped == null ? null : stripped.trim();
    }
}
