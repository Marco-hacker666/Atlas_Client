package atlas.nickresolver;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TextureDecoderTest {
    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private static final String VALUE = "ewogICJ0aW1lc3RhbXAiIDogMTc5NTUyNDU1MDczOCwKICAicHJvZmlsZUlkIiA6ICIwNjlhNzlmNDQ0ZTk0NzI2YTViZWZjYTkw"
            + "ZTM4YWFmNSIsCiAgInByb2ZpbGVOYW1lIiA6ICJOb3RjaCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1"
            + "cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVy"
            + "ZS8yOTIwMDlhNDkyNWI1OGYwMmM3N2RhZGMzZWNlZjA3ZWE0Yzc0NzJmNjRlMGZkYzMyY2U1NTIyNDg5MzYyNjgwIgogICAgfQog"
            + "IH0KfQ==";
    private static final String SIG = "sb4JYGgIKMa9ffuFdLgGlYvTywWxKtV15T4QxxUu8Eih9xeLFFyedo/MzB78RCx0EB1HMWDRa/+dgVs4DZ+cYaMO0PwG9/yIczj7"
            + "+1z8xla+8uViRRc+t5D7CgWIkArxirx329ERm2a0N71e2UwwM8RvoBsZYKiVBgykqEAwEHGVOXZAjGmg+ehkA4sM0PV++j9vSMVm"
            + "1JjDxnNEzygeh2RAyMaQP1bu01EXxGOn6EtzPUo8A9k1QMtFHgGns60gOycqGo4jK1z3mndcNVQmNGe7ZTydFRvTB2N6WCAbOkzB"
            + "MMaePEUaKK7fbIHaNpKBCdk0rJmR1xiERahE4goh0y3t1bSx1geWhgNMNJIjKBqPf3TS2hxa+F7fXn8t74ISporkO9TqPj5Bkhdb"
            + "lOzEf6gZe6lfoEjWpgWUypGnnMRAYSJKwyJ2DG2TE0+5lwoJdDy//jOeFdxoKze0w+dKPHc6fQxYOX5sRmxzU1jpStNCXV/7GVvt"
            + "Lh6WeIinKMAaKQ+5PRrqjcEZx7nyVpeg0IQsRUBIZaTXN99t0xMkgTUHRJ8BXngxspa4FA6b9LgvSmAlzdKrGtVaq3Pr4bGNuIQj"
            + "G8plg96l170GoJrE4Pb6PHrdiW4SUCCQeW76EZ2Ukgebw/f3FHNIX9YKS1bC1pFB+Ka/ybX7MEhHKHraDvg=";

    private static GameProfile profile(UUID id, String name, String value, String signature) {
        GameProfile p = new GameProfile(id, name);
        p.getProperties().put("textures", signature == null ? new Property("textures", value) : new Property("textures", value, signature));
        return p;
    }

    @Test
    public void mojangsSignatureIsAccepted() {
        TextureDecoder.TextureInfo t = TextureDecoder.decode(profile(NOTCH, "Notch", VALUE, SIG));
        assertNotNull(t);
        assertTrue(t.signed);
        assertTrue(t.hasSignature);
        assertEquals(NOTCH, t.trustedOwner());
        assertEquals("Notch", t.profileName);
    }

    @Test
    public void aForgedPayloadWithMojangsSignatureCopiedIsRejected() {
        String json = new String(Base64.getDecoder().decode(VALUE), StandardCharsets.UTF_8).replace("\"Notch\"", "\"SomeNick\"");
        String forged = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        TextureDecoder.TextureInfo t = TextureDecoder.decode(profile(NOTCH, "SomeNick", forged, SIG));
        assertNotNull(t);
        assertFalse(t.signed);
        assertTrue(t.hasSignature);
        assertNull(t.trustedOwner());
        assertEquals("SomeNick", t.profileName);
        assertNotNull(t.skinHash);
    }

    @Test
    public void noSignatureIsNotTrusted() {
        TextureDecoder.TextureInfo t = TextureDecoder.decode(profile(NOTCH, "Notch", VALUE, null));
        assertNotNull(t);
        assertFalse(t.signed);
        assertFalse(t.hasSignature);
        assertNull(t.trustedOwner());
    }
}
