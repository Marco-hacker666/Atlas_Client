package atlas.management;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagReporterTest {
    @Test
    public void theGameLogKeepsProblemsAndAnticheatAlertsOnly() {
        String log = String.join("\n",
                "[19:15:57] [Client thread/INFO]: [CHAT] [Intave] someone failed PlacementAnalysis vl=10.0(+10.0)",
                "[19:15:58] [Client thread/INFO]: [CHAT] <friend> meet me at mid",
                "[19:15:59] [Client thread/INFO]: [CHAT] <friend> is intave on this server?",
                "[19:16:00] [Client thread/WARN]: Something odd",
                "[19:16:01] [Client thread/INFO]: Loaded 12 sounds",
                "java.lang.NullPointerException: x",
                "\tat atlas.module.Foo.bar(Foo.java:1)",
                "[19:16:02] [Client thread/INFO]: [Atlas] Scaffold threw");
        String kept = DiagReporter.filterGameLog(log);
        assertTrue(kept.contains("PlacementAnalysis"));
        assertFalse("other chat", kept.contains("meet me"));
        assertFalse("chat naming an anticheat is still chat", kept.contains("is intave on"));
        assertTrue(kept.contains("Something odd"));
        assertFalse(kept.contains("Loaded 12 sounds"));
        assertTrue(kept.contains("NullPointerException"));
        assertTrue(kept.contains("Foo.java:1"));
        assertTrue(kept.contains("[Atlas] Scaffold threw"));
    }

    @Test
    public void theUserFolderAndTokensAreBlanked() {
        String s = DiagReporter.scrub("at C:\\Users\\marco\\curseforge\\x and C:/Users/Someone/AppData"
                + " --accessToken abcdefghijklmnopqrstuvwxyz0123 ok eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop");
        assertFalse(s, s.contains("marco"));
        assertFalse(s, s.contains("Someone"));
        assertTrue(s, s.contains("C:\\Users\\~\\curseforge"));
        assertFalse(s, s.contains("abcdefghijklmnopqrstuvwxyz0123"));
        assertFalse(s, s.contains("eyJhbGci"));
        assertTrue(s, s.contains("ok"));
    }

    @Test
    public void aLongPartKeepsItsEnd() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("line ").append(i).append('\n');
        }
        String t = DiagReporter.tail(sb.toString(), 100);
        assertTrue(t.endsWith("line 999\n"));
        assertTrue(t.startsWith("["));
        assertEquals("short", DiagReporter.tail("short", 100));
    }
}
