import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Used by the release workflow:
 *
 *     UPDATE_SIGNING_KEY=... java tools/update/SignUpdate.java <version> <jar> <out/update.properties>
 *
 * Writes the signed manifest that clients read (see atlas.update.UpdateManifest;
 * the payload format here must stay identical to UpdateManifest.payload).
 */
public class SignUpdate {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: SignUpdate <version> <jar> <out>");
            System.exit(2);
        }
        String version = args[0].startsWith("v") ? args[0].substring(1) : args[0];
        File jar = new File(args[1]);
        String key = System.getenv("UPDATE_SIGNING_KEY");
        if (key == null || key.trim().isEmpty()) {
            System.err.println("UPDATE_SIGNING_KEY is not set");
            System.exit(1);
        }
        String file = jar.getName();
        if (!file.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar")) {
            System.err.println("jar name not allowed: " + file);
            System.exit(1);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(jar.toPath())) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
        }
        StringBuilder sha = new StringBuilder();
        for (byte b : md.digest()) sha.append(String.format("%02x", b));
        long size = jar.length();

        String payload = "myau-update/1\n"
                + "version=" + version + "\n"
                + "file=" + file + "\n"
                + "size=" + size + "\n"
                + "sha256=" + sha + "\n";
        PrivateKey pk = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key.trim())));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(pk);
        s.update(payload.getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getEncoder().encodeToString(s.sign());

        String out = "format=1\n"
                + "version=" + version + "\n"
                + "file=" + file + "\n"
                + "size=" + size + "\n"
                + "sha256=" + sha + "\n"
                + "signature=" + sig + "\n";
        Files.write(new File(args[2]).toPath(), out.getBytes(StandardCharsets.UTF_8));
        System.out.println(out);
    }
}
