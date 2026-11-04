package atlas.script.raven.model;

import com.mojang.authlib.properties.Property;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;

/** Raven script API: a player in the tab list (2026-11-02). */
public class NetworkPlayer {
    private static final Map<NetworkPlayerInfo, NetworkPlayer> CACHE = new WeakHashMap<NetworkPlayerInfo, NetworkPlayer>();
    private final NetworkPlayerInfo playerInfo;

    public NetworkPlayer(NetworkPlayerInfo playerInfo) {
        this.playerInfo = playerInfo;
    }

    public String getCape() {
        return this.playerInfo.getLocationCape() == null ? null : this.playerInfo.getLocationCape().getResourcePath();
    }

    /** As Raven: this player's own display name without colours; anyone else's name with their team's prefix and
     *  suffix. */
    public String getDisplayName() {
        net.minecraft.client.entity.EntityPlayerSP self = net.minecraft.client.Minecraft.getMinecraft().thePlayer;
        if (self != null && this.playerInfo.getGameProfile() == self.getGameProfile()) {
            return self.getDisplayName().getUnformattedText();
        }
        return net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(this.playerInfo.getPlayerTeam(), this.getName());
    }

    public String getName() {
        return this.playerInfo.getGameProfile().getName();
    }

    public int getPing() {
        return this.playerInfo.getResponseTime();
    }

    /** The skin's textures property (base64), or null. */
    /** The skin's texture description, decoded from its Base64 (a JSON text), as Raven gives it. */
    public String getSkinData() {
        Collection<Property> textures = this.playerInfo.getGameProfile().getProperties().get("textures");
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        return new String(java.util.Base64.getDecoder().decode(
                textures.iterator().next().getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public String getUUID() {
        return this.playerInfo.getGameProfile().getId() == null ? null : this.playerInfo.getGameProfile().getId().toString();
    }

    public static NetworkPlayer convert(NetworkPlayerInfo networkPlayerInfo) {
        if (networkPlayerInfo == null) {
            return null;
        }
        synchronized (CACHE) {
            NetworkPlayer player = CACHE.get(networkPlayerInfo);
            if (player == null) {
                player = new NetworkPlayer(networkPlayerInfo);
                CACHE.put(networkPlayerInfo, player);
            }
            return player;
        }
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
