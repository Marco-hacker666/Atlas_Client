package atlas.nickresolver;

public final class IdentityLabels {
    private IdentityLabels() {
    }

    public static String tag(ResolvedIdentity identity, NickSettings settings) {
        if (identity == null || !settings.enabled) {
            return null;
        }
        String body = null;
        ResolvedIdentity.Source source = identity.source;
        if (identity.isNickedWithKnownName()) {
            if (source.confident) {
                body = "§a" + identity.originalName;
            } else if (settings.showProbable) {
                body = "§e" + identity.originalName + "?";
            }
        } else if (identity.isNickedUnknown() && settings.showUnresolved) {
            body = "§cNICK";
        }
        if (settings.showSkinOwner && identity.skinOwnerName != null
                && !identity.skinOwnerName.equalsIgnoreCase(identity.displayName)
                && !identity.skinOwnerName.equalsIgnoreCase(identity.originalName)) {
            String owner = "§7skin: " + identity.skinOwnerName;
            body = body == null ? owner : body + " " + owner;
        }
        return body == null ? null : "§8[" + body + "§8]";
    }

    public static String nametag(ResolvedIdentity identity, NickSettings settings) {
        String tag = tag(identity, settings);
        if (tag == null && identity != null && settings.enabled && settings.showReal
                && identity.source == ResolvedIdentity.Source.NOT_NICKED) {
            return "§8[§2REAL§8]";
        }
        return tag;
    }

    public static String source(ResolvedIdentity.Source source) {
        switch (source) {
            case TEXTURE_SIGNED:
                return "皮膚簽章";
            case MOJANG_UUID:
                return "Mojang 查詢";
            case OFFLINE_UUID:
                return "離線 UUID 比對";
            case SERVER_PAYLOAD:
                return "伺服器封包（推測）";
            case CLOUD_REPORT:
                return "雲端資料庫（推測）";
            case SKIN_HISTORY:
                return "皮膚紀錄（推測）";
            case NOT_NICKED:
                return "非暱稱";
            case FAKE_UUID:
                return "暱稱（查無真名）";
            default:
                return "未知";
        }
    }

    public static String details(ResolvedIdentity identity) {
        StringBuilder sb = new StringBuilder();
        sb.append("§7顯示名稱：§f").append(nz(identity.displayName));
        sb.append("\n§7真名：§f").append(nz(identity.originalName));
        sb.append("\n§7來源：§f").append(source(identity.source));
        sb.append("\n§7UUID：§f").append(identity.uuid).append(" §8(v").append(identity.uuid.version()).append(')');
        if (identity.skinHash != null) {
            sb.append("\n§7皮膚：§f").append(identity.skinHash, 0, Math.min(16, identity.skinHash.length())).append('…');
        }
        if (identity.skinOwnerName != null) {
            sb.append("\n§7皮膚原主：§f").append(identity.skinOwnerName);
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }
}
