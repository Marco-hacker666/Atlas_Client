package me.ksyz.accountmanager.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Account tokens at rest (2026-11-01, owner: "do not keep tokens as plain text").
 *
 * AES-256-GCM, a fresh 12-byte nonce per value, stored as "enc:v1:" + Base64(nonce + ciphertext). The key is 32
 * random bytes in ~/.atlas/account.key, outside the game folder, so atlas.accounts.json alone -- a shared or
 * uploaded instance, a copied config folder -- does not give the tokens away. It does not stop a program running as
 * the same user on this computer, which can read the key too.
 *
 * A value without the prefix is the old plain text and is returned as it is (AccountManager then saves it encrypted).
 * A value that cannot be decrypted (key gone) comes back empty: that account has to log in again.
 */
public final class TokenVault {
    private static final String PREFIX = "enc:v1:";
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static byte[] key;

    private TokenVault() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || isEncrypted(plain)) {
            return plain == null ? "" : plain;
        }
        try {
            byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE);
            System.arraycopy(sealed, 0, out, NONCE, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            /* Never fall back to writing the token in the clear. */
            System.err.println("[Atlas] Could not encrypt an account token: " + e);
            return "";
        }
    }

    public static String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored == null ? "" : stored;
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (in.length <= NONCE) {
                return "";
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(TAG_BITS, in, 0, NONCE));
            return new String(cipher.doFinal(in, NONCE, in.length - NONCE), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Atlas] Could not decrypt an account token (log that account in again): " + e);
            return "";
        }
    }

    private static synchronized byte[] key() throws Exception {
        if (key != null) {
            return key;
        }
        File dir = new File(System.getProperty("user.home"), ".atlas");
        File file = new File(dir, "account.key");
        if (file.isFile()) {
            byte[] stored = Files.readAllBytes(file.toPath());
            if (stored.length == 32) {
                key = stored;
                return key;
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        byte[] fresh = new byte[32];
        RANDOM.nextBytes(fresh);
        File temp = new File(dir, "account.key.tmp");
        Files.write(temp.toPath(), fresh);
        temp.setReadable(false, false);
        temp.setReadable(true, true);
        temp.setWritable(false, false);
        temp.setWritable(true, true);
        if (!temp.renameTo(file)) {
            Files.copy(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp.delete();
        }
        key = fresh;
        return key;
    }
}
