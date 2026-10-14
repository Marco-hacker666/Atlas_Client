import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Makes the update signing key. Run ONCE, from the project folder:
 *
 *     java tools/update/GenUpdateKey.java
 *
 * It writes:
 *   src/main/resources/myau-update-key.pub   public key  -> commit this
 *   update-signing-key.txt                   PRIVATE key -> paste into the GitHub
 *                                            secret UPDATE_SIGNING_KEY, keep a copy
 *                                            somewhere safe, then delete this file.
 *
 * Refuses to overwrite an existing public key: a new key would make every
 * jar already out there reject your future updates.
 */
public class GenUpdateKey {
    public static void main(String[] args) throws Exception {
        File pub = new File("src/main/resources/myau-update-key.pub");
        File priv = new File("update-signing-key.txt");
        if (pub.exists() && !(args.length > 0 && args[0].equals("--force"))) {
            System.err.println(pub + " already exists. Players' clients trust that key;");
            System.err.println("replacing it means older clients can no longer auto-update.");
            System.err.println("Run with --force only if you really lost the private key.");
            System.exit(1);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        Base64.Encoder b64 = Base64.getEncoder();
        Files.write(pub.toPath(), ("# Atlas update public key (ECDSA P-256). Safe to publish.\n"
                + b64.encodeToString(kp.getPublic().getEncoded()) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(priv.toPath(), b64.encodeToString(kp.getPrivate().getEncoded()).getBytes(StandardCharsets.UTF_8));
        System.out.println("Public key written to  " + pub + "  (commit it)");
        System.out.println("PRIVATE key written to " + priv);
        System.out.println("  1. GitHub repo -> Settings -> Secrets and variables -> Actions -> New repository secret");
        System.out.println("     Name: UPDATE_SIGNING_KEY   Value: the whole content of " + priv);
        System.out.println("  2. Keep a backup copy somewhere private (password manager).");
        System.out.println("  3. Delete " + priv + ". Never commit it.");
    }
}
