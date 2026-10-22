package myau.util;

import org.junit.Test;

import java.awt.Font;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 2026-10-22 (plan item 14): Google-Sans cut to its default instance, NotoSansSC and tenacity to everything but
 * the ideographs (the Chinese interface was removed). Every font must still load and keep its symbols.
 */
public class FontCoverageTest {

    private static final String DIR = "/assets/myau/font/";

    private static Font load(String name) throws Exception {
        try (InputStream in = FontCoverageTest.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                fail("missing " + name);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        }
    }

    private static boolean cjk(int c) {
        return c >= 0x4E00 && c <= 0x9FFF || c >= 0x3400 && c <= 0x4DBF || c >= 0xF900 && c <= 0xFAFF;
    }

    private static void collect(File file, Set<Integer> into) throws Exception {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    collect(child, into);
                }
            }
            return;
        }
        String name = file.getName();
        if (!(name.endsWith(".java") || name.endsWith(".json") || name.endsWith(".txt"))) {
            return;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            if (cjk(c)) {
                into.add(c);
            }
            i += Character.charCount(c);
        }
    }

    @Test
    public void everyFontLoads() throws Exception {
        File dir = new File("src/main/resources/assets/myau/font");
        File[] fonts = dir.listFiles((d, n) -> n.endsWith(".ttf") || n.endsWith(".otf"));
        assertTrue("fonts found", fonts != null && fonts.length > 20);
        for (File font : fonts) {
            Font f = load(font.getName());
            assertTrue(font.getName(), f.canDisplay('A') || font.getName().startsWith("icon") || font.getName().startsWith("noti"));
        }
    }

    @Test
    public void theTrimmedFontsKeepEverythingButChinese() throws Exception {
        /* 2026-10-22: the Chinese interface was removed; NotoSansSC and tenacity keep every glyph but the
           ideographs (LiquidFont still draws symbols beyond Latin-1 with NotoSansSC). */
        String symbols = "Aé·×°±→←↑↓…—•★ΩЖ€";
        for (String name : new String[]{"NotoSansSC-Regular.ttf", "tenacity.ttf"}) {
            Font font = load(name);
            assertTrue(name + " symbols", font.canDisplayUpTo(symbols) == -1);
            assertTrue(name + " has no ideographs any more", !font.canDisplay('中'));
        }
    }
}
