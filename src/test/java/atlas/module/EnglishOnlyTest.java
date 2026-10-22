package atlas.module;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 2026-10-22: the menu is English only; no heading it can show is Chinese. */
public class EnglishOnlyTest {

    private static boolean chinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x2E80) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void alwaysEnglish() {
        ModuleDocs.setEnglish(false);
        assertTrue(ModuleDocs.isEnglish());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void everyHeadingHasEnglish() throws Exception {
        Field groups = ModuleDocs.class.getDeclaredField("GROUPS");
        groups.setAccessible(true);
        Field auto = ModuleDocs.class.getDeclaredField("AUTO");
        auto.setAccessible(true);
        StringBuilder missing = new StringBuilder();
        for (String[][] spec : ((Map<String, String[][]>) groups.get(null)).values()) {
            for (String[] row : spec) {
                String shown = ModuleDocs.heading(row[0]);
                if (chinese(shown) || "Settings".equals(shown) && chinese(row[0])) {
                    missing.append(row[0]).append(" | ");
                }
            }
        }
        for (String[] row : (String[][]) auto.get(null)) {
            if (chinese(ModuleDocs.heading(row[0]))) {
                missing.append(row[0]).append(" | ");
            }
        }
        assertEquals("headings without English: " + missing, 0, missing.length());
        assertTrue(!chinese(ModuleDocs.heading("\u4e00\u822c")));
    }
}
