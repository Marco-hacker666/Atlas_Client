package atlas.util;

import me.ksyz.accountmanager.utils.TokenVault;
import org.junit.Test;

import static org.junit.Assert.*;

/** Account tokens are kept encrypted (2026-11-01). */
public class TokenVaultTest {

    @Test
    public void aTokenComesBackAsItWas() {
        String token = "M.C123_BAY.-CRt0kEn!*";
        String sealed = TokenVault.encrypt(token);
        assertTrue(TokenVault.isEncrypted(sealed));
        assertFalse(sealed.contains(token));
        assertEquals(token, TokenVault.decrypt(sealed));
    }

    @Test
    public void everySealIsDifferent() {
        assertNotEquals(TokenVault.encrypt("same"), TokenVault.encrypt("same"));
    }

    @Test
    public void plainTextAndEmptyPassThrough() {
        assertEquals("old-plain", TokenVault.decrypt("old-plain"));
        assertEquals("", TokenVault.encrypt(""));
        assertEquals("", TokenVault.decrypt(""));
    }

    @Test
    public void aDamagedValueIsEmptyNotAnError() {
        assertEquals("", TokenVault.decrypt("enc:v1:AAAA"));
        String sealed = TokenVault.encrypt("token");
        char last = sealed.charAt(sealed.length() - 2);
        String broken = sealed.substring(0, sealed.length() - 2) + (last == 'A' ? 'B' : 'A') + sealed.charAt(sealed.length() - 1);
        assertEquals("", TokenVault.decrypt(broken));
    }
}
