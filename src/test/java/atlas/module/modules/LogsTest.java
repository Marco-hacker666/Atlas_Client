package atlas.module.modules;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Logs switch: which files count as session logs and which are kept while it is off (2026-10-20). */
public class LogsTest {

    @Test
    public void sessionLogsAreRecognisedByTheirStamp() {
        assertEquals("clutch", Logs.sessionPrefix("clutch-20261020-191450.txt"));
        assertEquals("flags", Logs.sessionPrefix("flags-20261020-185144.txt"));
        assertEquals("auto-send-logs", Logs.sessionPrefix("auto-send-logs-20261020-185144.txt"));
        assertNull(Logs.sessionPrefix("default.json"));
        assertNull(Logs.sessionPrefix("hud-anchors.txt"));
        assertNull(Logs.sessionPrefix("server-profiles.csv"));
    }

    @Test
    public void offKeepsOnlyFlagsAmongTheLogs() {
        assertTrue(Logs.allows(new File("config/Atlas/flags-20261020-185144.txt")));
        assertFalse(Logs.allows(new File("config/Atlas/clutch-20261020-191450.txt")));
        assertFalse(Logs.allows(new File("config/Atlas/gui-perf.txt")));
        assertFalse(Logs.allows(new File("config/Atlas/servers.txt")));
        assertTrue(Logs.allows(new File("config/Atlas/hud-anchors.txt")));
        assertTrue(Logs.allows(new File("some-temp-file.log")));
    }
}
