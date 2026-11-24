package atlas.nickresolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextureDecoder {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    private static final Pattern HASH_IN_URL = Pattern.compile("/texture/([0-9a-fA-F]{16,128})/?$");

    private static final PublicKey MOJANG_KEY = loadMojangKey();

    private TextureDecoder() {
    }

    public static final class TextureInfo {
        public final UUID profileId;
        public final String profileName;
        public final String skinUrl;
        public final String skinHash;
        public final String capeUrl;
        public final long timestamp;

        public final boolean signed;

        public final boolean hasSignature;

        TextureInfo(UUID profileId, String profileName, String skinUrl, String skinHash, String capeUrl,
                    long timestamp, boolean signed) {
            this(profileId, profileName, skinUrl, skinHash, capeUrl, timestamp, signed, signed);
        }

        TextureInfo(UUID profileId, String profileName, String skinUrl, String skinHash, String capeUrl,
                    long timestamp, boolean signed, boolean hasSignature) {
            this.profileId = profileId;
            this.profileName = profileName;
            this.skinUrl = skinUrl;
            this.skinHash = skinHash;
            this.capeUrl = capeUrl;
            this.timestamp = timestamp;
            this.signed = signed;
            this.hasSignature = hasSignature;
        }

        public UUID trustedOwner() {
            return this.signed ? this.profileId : null;
        }
    }

    private static PublicKey loadMojangKey() {
        try (InputStream in = TextureDecoder.class.getResourceAsStream("/yggdrasil_session_pubkey.der")) {
            if (in == null) {
                LOG.warn("Mojang's session key was not found; skin signatures cannot be checked");
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                bytes.write(buf, 0, n);
            }
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes.toByteArray()));
        } catch (Exception e) {
            LOG.warn("Mojang's session key could not be read; skin signatures cannot be checked", e);
            return null;
        }
    }

    static boolean signatureValid(Property property) {
        if (property == null || !property.hasSignature() || property.getSignature().isEmpty()) {
            return false;
        }
        if (MOJANG_KEY == null) {
            return true;
        }
        try {
            return property.isSignatureValid(MOJANG_KEY);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static TextureInfo decode(GameProfile profile) {
        if (profile == null || profile.getProperties() == null) {
            return null;
        }
        Collection<Property> textures = profile.getProperties().get("textures");
        if (textures == null || textures.isEmpty()) {
            return null;
        }
        Property property = textures.iterator().next();
        String signature = property.getSignature();
        return decode(property.getValue(), signatureValid(property), signature != null && !signature.isEmpty());
    }

    public static TextureInfo decode(String base64, boolean signed) {
        return decode(base64, signed, signed);
    }

    private static TextureInfo decode(String base64, boolean signed, boolean hasSignature) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = decodeBase64(base64.trim());
            if (bytes == null) {
                return null;
            }
            JsonElement root = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject json = root.getAsJsonObject();
            UUID profileId = parseUuid(string(json, "profileId"));
            String profileName = string(json, "profileName");
            long timestamp = json.has("timestamp") && json.get("timestamp").isJsonPrimitive()
                    ? json.get("timestamp").getAsLong() : 0L;

            String skinUrl = null;
            String capeUrl = null;
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                JsonObject tex = json.getAsJsonObject("textures");
                skinUrl = url(tex, "SKIN");
                capeUrl = url(tex, "CAPE");
            }
            return new TextureInfo(profileId, profileName, skinUrl, hashFromUrl(skinUrl), capeUrl, timestamp, signed, hasSignature);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public static String hashFromUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = HASH_IN_URL.matcher(url);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    public static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        try {
            if (t.length() == 32) {
                t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
                        + t.substring(16, 20) + "-" + t.substring(20);
            }
            if (t.length() != 36) {
                return null;
            }
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String url(JsonObject textures, String key) {
        if (!textures.has(key) || !textures.get(key).isJsonObject()) {
            return null;
        }
        return string(textures.getAsJsonObject(key), "url");
    }

    private static String string(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static byte[] decodeBase64(String s) {
        try {
            return java.util.Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException standardFailed) {
            try {
                return java.util.Base64.getUrlDecoder().decode(s);
            } catch (IllegalArgumentException urlFailed) {
                return null;
            }
        }
    }
}
