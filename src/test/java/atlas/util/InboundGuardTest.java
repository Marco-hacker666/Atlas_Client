package atlas.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InboundGuardTest {

    private static String nested(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        sb.append("\"x\"");
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        return sb.toString();
    }

    @Test
    public void ordinaryChatPasses() {
        String chat = "{\"text\":\"\",\"extra\":[{\"text\":\"[VIP] \",\"color\":\"gold\"},{\"text\":\"Steve\","
                + "\"hoverEvent\":{\"action\":\"show_text\",\"value\":{\"text\":\"hi\"}}},{\"text\":\": gg\"}]}";
        assertFalse(InboundGuard.jsonTooComplex(chat, InboundGuard.MAX_JSON_DEPTH, InboundGuard.MAX_JSON_OPENS));
    }

    @Test
    public void deepNestingIsCaught() {
        assertFalse(InboundGuard.jsonTooComplex(nested(64), 64, 4096));
        assertTrue(InboundGuard.jsonTooComplex(nested(65), 64, 4096));
        assertTrue(InboundGuard.jsonTooComplex(nested(5000), 64, 4096));
    }

    @Test
    public void wideTextIsCaught() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5000; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"text\":\"a\"}");
        }
        sb.append(']');
        assertTrue(InboundGuard.jsonTooComplex(sb.toString(), 64, 4096));
    }

    @Test
    public void bracketsInsideStringsDoNotCount() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            text.append("[{");
        }
        String json = "{\"text\":\"" + text + "\\\"[[[\"}";
        assertFalse(InboundGuard.jsonTooComplex(json, 64, 4096));
    }

    @Test
    public void saneRejectsNaNInfinityAndHugeValues() {
        assertTrue(InboundGuard.sane(3.2, 100));
        assertFalse(InboundGuard.sane(Double.NaN, 100));
        assertFalse(InboundGuard.sane(Double.POSITIVE_INFINITY, 100));
        assertFalse(InboundGuard.sane(-1.0E9, 100));
        assertFalse(InboundGuard.finite(1.0, Float.NaN));
    }

    @Test
    public void packUrlsAreSorted() {
        assertEquals(InboundGuard.Pack.WEB, InboundGuard.classifyPack("https://cdn.example.net/pack.zip"));
        assertEquals(InboundGuard.Pack.WEB, InboundGuard.classifyPack(" HTTP://x/y.zip"));
        assertEquals(InboundGuard.Pack.LOCAL_PROBE, InboundGuard.classifyPack("level://../../.minecraft/mods/x.jar"));
        assertEquals(InboundGuard.Pack.OTHER, InboundGuard.classifyPack("file:///C:/Users/x/secret.txt"));
        assertEquals(InboundGuard.Pack.OTHER, InboundGuard.classifyPack(null));
    }

    @Test
    public void aLocalPackIsOnlyForTheOwnWorld() {
        assertFalse(InboundGuard.localPackAllowed("level://World/resources.zip", false));
        assertTrue(InboundGuard.localPackAllowed("level://World/resources.zip", true));
        assertFalse(InboundGuard.localPackAllowed("level://../mods/x.jar", true));
        assertFalse(InboundGuard.localPackAllowed("level://World\\..\\..\\x", true));
        assertFalse(InboundGuard.localPackAllowed("level:///etc/passwd", true));
        assertFalse(InboundGuard.localPackAllowed("level://C:/x.zip", true));
    }
}
