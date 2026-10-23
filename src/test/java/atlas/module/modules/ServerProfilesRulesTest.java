package atlas.module.modules;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** ServerProfiles' rule string, shared with the menu's Servers page (2026-10-23, GUI redesign M6). */
public class ServerProfilesRulesTest {

    /** ServerProfiles.match before M6, verbatim but static. */
    private static String oldMatch(String raw, String address) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        for (String rule : raw.split(",")) {
            int equals = rule.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String fragment = rule.substring(0, equals).trim().toLowerCase();
            String config = rule.substring(equals + 1).trim();
            if (!fragment.isEmpty() && !config.isEmpty() && address.contains(fragment)) {
                return config;
            }
        }
        return null;
    }

    @Test
    public void matchesExactlyAsBefore() {
        Random random = new Random(6L);
        String[] parts = {"pika", "hypixel", "mc.", "net", "=", ",", " ", "default", "Pika", "test", "x"};
        String[] addresses = {"play.pika-network.net", "mc.hypixel.net", "test.server", "localhost", ""};
        for (int round = 0; round < 5000; round++) {
            StringBuilder raw = new StringBuilder();
            int length = random.nextInt(12);
            for (int i = 0; i < length; i++) {
                raw.append(parts[random.nextInt(parts.length)]);
            }
            for (String address : addresses) {
                assertEquals(raw + " @ " + address, oldMatch(raw.toString(), address),
                        ServerProfiles.match(ServerProfiles.parse(raw.toString()), address));
            }
        }
    }

    @Test
    public void formatIsReadBackTheSame() {
        List<String[]> rules = ServerProfiles.parse(" Pika = pika ,hypixel=Hyp, =x, broken, test.=default");
        assertEquals(3, rules.size());
        assertArrayEquals(new String[]{"pika", "pika"}, rules.get(0));
        assertArrayEquals(new String[]{"hypixel", "Hyp"}, rules.get(1));
        String text = ServerProfiles.format(rules);
        assertEquals("pika=pika, hypixel=Hyp, test.=default", text);
        List<String[]> again = ServerProfiles.parse(text);
        assertEquals(rules.size(), again.size());
        for (int i = 0; i < rules.size(); i++) {
            assertEquals(Arrays.asList(rules.get(i)), Arrays.asList(again.get(i)));
        }
        assertEquals("", ServerProfiles.format(ServerProfiles.parse("")));
    }
}
