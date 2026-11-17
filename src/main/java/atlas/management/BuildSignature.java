package atlas.management;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Signs what the client sends to the relay (2026-11-17, owner: "the code is public: make sure it is my published
 * client, not a fork, and that nobody can change the packets and send fake data").
 *
 * Official release jars carry a key that is not in the public source: the build adds it from the owner's computer
 * (build.gradle.kts, ~/.atlas/build-key.txt) as atlas/data/fx.bin, masked. Every message is signed with it:
 * HMAC-SHA256 over "path\ntime\nSHA-256(body)" in X-Atlas-Sig, the time in X-Atlas-Time. The relay checks it
 * (report-relay/src/verify.js); a build without the key signs nothing and is shown as unofficial. The time is the
 * relay's (the offset learnt from its answers), so a wrong clock on the player's computer does not break it, and
 * it never repeats, so an old message cannot be sent again.
 */
public final class BuildSignature {
    private static final byte[] MASK = "atlas-fx-mask-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY = load();
    private static volatile long offsetSeconds;
    private static long lastTime;

    private BuildSignature() {
    }

    /** Whether this jar is an official, signing build. */
    public static boolean official() {
        return KEY != null;
    }

    /** The relay's clock, from one of its answers: seconds. */
    public static void serverTime(long relaySeconds) {
        offsetSeconds = relaySeconds - System.currentTimeMillis() / 1000L;
    }

    /** Adds the signature headers for this exact body (nothing when this is not an official build). */
    public static void sign(HttpURLConnection c, String path, byte[] body) {
        if (KEY == null) {
            return;
        }
        try {
            long t;
            synchronized (BuildSignature.class) {
                t = Math.max(lastTime + 1L, System.currentTimeMillis() / 1000L + offsetSeconds);
                lastTime = t;
            }
            String message = path + "\n" + t + "\n" + hex(MessageDigest.getInstance("SHA-256").digest(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            c.setRequestProperty("X-Atlas-Time", Long.toString(t));
            c.setRequestProperty("X-Atlas-Sig", hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception ignored) {
            // Unsigned: the relay counts it as unofficial.
        }
    }

    private static byte[] load() {
        try (InputStream in = BuildSignature.class.getResourceAsStream("/atlas/data/fx.bin")) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer)) > 0 && out.size() < 4096) {
                out.write(buffer, 0, n);
            }
            byte[] key = out.toByteArray();
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (key[i] ^ MASK[i % MASK.length]);
            }
            return key.length >= 16 ? key : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }
}
