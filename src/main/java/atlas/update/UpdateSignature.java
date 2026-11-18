package atlas.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

public final class UpdateSignature {
    public static final String KEY_RESOURCE = "/atlas-update-key.pub";
    private static final String ALGORITHM = "SHA256withECDSA";

    private UpdateSignature() {
    }

    public static PublicKey bundledKey() {
        InputStream in = UpdateSignature.class.getResourceAsStream(KEY_RESOURCE);
        if (in == null) {
            return null;
        }
        try {
            return parsePublicKey(new String(readAll(in, 16 * 1024), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static PublicKey parsePublicKey(String text) throws GeneralSecurityException {
        StringBuilder b64 = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("-----") || t.startsWith("#")) {
                continue;
            }
            b64.append(t);
        }
        if (b64.length() == 0) {
            throw new GeneralSecurityException("Empty public key");
        }
        byte[] der = java.util.Base64.getDecoder().decode(b64.toString());
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    public static boolean verify(UpdateManifest manifest, PublicKey key) {
        if (manifest == null || key == null) {
            return false;
        }
        try {
            byte[] sig = java.util.Base64.getDecoder().decode(manifest.signature.trim());
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(manifest.payloadBytes());
            return s.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    public static String sha256Hex(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
        } finally {
            in.close();
        }
        return hex(md.digest());
    }

    public static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[bytes[i] & 0xF];
        }
        return new String(out);
    }

    public static boolean matches(UpdateManifest manifest, File jar) {
        try {
            return jar.isFile() && jar.length() == manifest.size && manifest.sha256.equals(sha256Hex(jar));
        } catch (IOException e) {
            return false;
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int r;
        while ((r = in.read(buf)) != -1) {
            total += r;
            if (total > max) {
                throw new IOException("Too large");
            }
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
