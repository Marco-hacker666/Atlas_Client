package atlas.util;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** What the bug report must never carry (2026-10-07). */
public class RedactorTest {

    private final Redactor redactor = new Redactor("Steve_123", "0123456789abcdef0123456789abcdef",
            "eyJhbGciOiJIUzI1NiJ9tokenpartlongenough", Arrays.asList("Alex99", "Notch", "Steve_123"),
            "C:\\Users\\someone", "someone");

    @Test
    public void ownNameBecomesMe() {
        assertEquals("[CHAT] <me> joined", redactor.apply("[CHAT] Steve_123 joined"));
        assertEquals("[CHAT] <me> joined", redactor.apply("[CHAT] steve_123 joined"));
    }

    @Test
    public void otherPlayersBecomePlayer() {
        assertEquals("<player> hit <player>", redactor.apply("Alex99 hit Notch"));
    }

    @Test
    public void namesInsideLongerWordsStay() {
        assertEquals("NotchFan", redactor.apply("NotchFan"));
    }

    @Test
    public void tokenUuidEmailHomeAndAccountGo() {
        String out = redactor.apply("token eyJhbGciOiJIUzI1NiJ9tokenpartlongenough uuid 01234567-89ab-cdef-0123-456789abcdef"
                + " mail a.b@example.com path C:\\Users\\someone\\curseforge and C:/Users/someone/x user someone");
        assertFalse(out.contains("eyJhbGci"));
        assertFalse(out.contains("01234567"));
        assertFalse(out.contains("example.com"));
        assertFalse(out.toLowerCase().contains("someone"));
        assertTrue(out.contains("<home>"));
        assertTrue(out.contains("<my-uuid>"));
    }

    @Test
    public void jwtShapedTokensGoEvenWhenUnknown() {
        String out = redactor.apply("auth aaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbb.cccccccccccc end");
        assertEquals("auth <token> end", out);
    }

    @Test
    public void publicIpsAreMaskedLoopbackIsNot() {
        assertEquals("connect <ip>:25565 and 127.x.x.x", redactor.apply("connect 51.79.12.200:25565 and 127.0.0.1"));
    }

    @Test
    public void ordinaryLogTextIsUntouched() {
        String line = "22:15:20  LAGBACK x4 1.39 | speed 0.06/t | walk | air | Scaffold 42%";
        assertEquals(line, redactor.apply(line));
    }

    @Test
    public void ipv6IsMaskedButTimesAndMethodRefsStay() {
        assertEquals("server <ip> port", redactor.apply("server 2001:db8:85a3::8a2e:370:7334 port"));
        assertEquals("[<ip>]:25565", redactor.apply("[2a01:4f8:c0c:1234::1]:25565"));
        assertEquals("<lan-ip> and ::1", redactor.apply("fe80::1c2b:3cff:fe4d:5e6f and ::1"));
        assertEquals("19:23:01.544  HIT", redactor.apply("19:23:01.544  HIT"));
        assertEquals("Foo::bar a :: b", redactor.apply("Foo::bar a :: b"));
    }

    @Test
    public void namesInTheClientsOwnLogsGo() {
        assertEquals("HIT-DMG target=<player> dealt=2.00", redactor.apply("HIT-DMG target=Gone_Player dealt=2.00"));
        assertEquals("FIGHT vs <player> | 2.5s", redactor.apply("FIGHT vs Somebody22 | 2.5s"));
        assertEquals("HIT blocked | by <player> d=2.10", redactor.apply("HIT blocked | by Elysio31 d=2.10"));
        assertEquals("target=#73960 by unknown", redactor.apply("target=#73960 by unknown"));
    }
}
