package atlas.nickresolver;

import java.util.UUID;

public final class ResolvedIdentity {
    public enum Source {
        TEXTURE_SIGNED(true),

        MOJANG_UUID(true),

        OFFLINE_UUID(true),

        SERVER_PAYLOAD(false),

        CLOUD_REPORT(false),

        SKIN_HISTORY(false),

        NOT_NICKED(true),

        FAKE_UUID(false),

        UNKNOWN(false);

        public final boolean confident;

        Source(boolean confident) {
            this.confident = confident;
        }
    }

    public final UUID uuid;

    public volatile String displayName;

    public volatile String tabDisplayName;

    public volatile String originalName;
    public volatile Source source = Source.UNKNOWN;

    public volatile String skinHash;

    public volatile String skinOwnerName;
    public volatile UUID skinOwnerId;

    public volatile long firstSeen;
    public volatile long lastSeen;

    public volatile long mojangCheckedAt;

    public volatile long nextLookupAt;

    public volatile long nextCloudLookupAt;

    public ResolvedIdentity(UUID uuid) {
        this.uuid = uuid;
        long now = System.currentTimeMillis();
        this.firstSeen = now;
        this.lastSeen = now;
    }

    public boolean isNickedWithKnownName() {
        String real = this.originalName;
        String shown = this.displayName;
        return real != null && shown != null && this.source != Source.NOT_NICKED
                && !real.equalsIgnoreCase(shown);
    }

    public boolean isNickedUnknown() {
        return this.source == Source.FAKE_UUID;
    }

    @Override
    public String toString() {
        return "ResolvedIdentity{" + this.uuid + ", shown=" + this.displayName + ", real=" + this.originalName
                + ", source=" + this.source + ", skin=" + this.skinHash + "}";
    }
}
