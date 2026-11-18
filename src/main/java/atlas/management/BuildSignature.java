package atlas.management;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class BuildSignature {
    private static final byte[] MASK = "atlas-fx-mask-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY = load();
    private static volatile long offsetSeconds;
    private static long lastTime;

    private BuildSignature() {
    }

    public static boolean official() {
        return KEY != null;
    }

    public static void serverTime(long relaySeconds) {
        offsetSeconds = relaySeconds - System.currentTimeMillis() / 1000L;
    }

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
