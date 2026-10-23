package atlas.ui.impl.clickgui.atlas;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Where a field's text goes (2026-10-23, GUI redesign M1). */
public class TextLayoutTest {

    private static final TextLayout.Width MONO = s -> s.length() * 4.0F;

    /** The bug-report box's wrapping before M1 (AtlasClickGui.wrapReport), without its line limit. */
    private static List<String> oldWrap(String text, float width) {
        List<String> lines = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
            if (MONO.of(current.toString()) > width) {
                String full = current.toString();
                int cut = full.lastIndexOf(' ');
                if (cut <= 0) {
                    cut = full.length() - 1;
                }
                lines.add(full.substring(0, cut));
                current.setLength(0);
                current.append(full.substring(cut).replaceFirst("^ ", ""));
            }
        }
        lines.add(current.toString());
        return lines;
    }

    private static List<String> newWrap(String text, float width) {
        List<String> out = new ArrayList<String>();
        for (int[] line : TextLayout.wrap(text, MONO, width)) {
            out.add(text.substring(line[0], line[1]));
        }
        return out;
    }

    @Test
    public void wrapsExactlyAsTheReportBoxDid() {
        Random random = new Random(20261023L);
        String alphabet = "abcdefgh      \n";
        for (int round = 0; round < 3000; round++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(120);
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            float width = 4.0F * (3 + random.nextInt(20));
            assertEquals("\"" + text + "\" at " + width, oldWrap(text.toString(), width), newWrap(text.toString(), width));
        }
    }

    @Test
    public void theCaretIsFoundInTheWrappedLines() {
        String text = "hello world foo";
        List<int[]> lines = TextLayout.wrap(text, MONO, 4.0F * 11);
        assertEquals("hello world", text.substring(lines.get(0)[0], lines.get(0)[1]));
        assertEquals("foo", text.substring(lines.get(1)[0], lines.get(1)[1]));
        assertEquals(0, TextLayout.lineOf(lines, 11));
        assertEquals(1, TextLayout.lineOf(lines, 12));
        assertEquals(1, TextLayout.lineOf(lines, text.length()));
        List<int[]> broken = TextLayout.wrap("a\n", MONO, 100.0F);
        assertEquals(2, broken.size());
        assertEquals(1, TextLayout.lineOf(broken, 2));
    }

    @Test
    public void aShortTextIsShownWhole() {
        assertArrayEquals(new int[]{0, 5}, TextLayout.window("abcde", 2, MONO, 20.0F, false));
    }

    @Test
    public void aLongTextShowsThePartWithTheCaret() {
        String text = "abcdefghij";
        float room = 4.0F * 4;
        assertArrayEquals(new int[]{0, 4}, TextLayout.window(text, 3, MONO, room, false));
        assertArrayEquals(new int[]{4, 8}, TextLayout.window(text, 8, MONO, room, false));
        assertArrayEquals(new int[]{6, 10}, TextLayout.window(text, 10, MONO, room, true));
        assertArrayEquals(new int[]{2, 6}, TextLayout.window(text, 2, MONO, room, true));
        Random random = new Random(7L);
        for (int round = 0; round < 500; round++) {
            int caret = random.nextInt(text.length() + 1);
            float max = 4.0F * (1 + random.nextInt(12));
            int[] window = TextLayout.window(text, caret, MONO, max, random.nextBoolean());
            assertTrue(window[0] <= caret && caret <= window[1]);
            assertTrue(MONO.of(text.substring(window[0], window[1])) <= max);
        }
    }

    @Test
    public void indexAtRoundsToTheNearerGap() {
        assertEquals(0, TextLayout.indexAt("abc", 0, MONO, 1.9F));
        assertEquals(1, TextLayout.indexAt("abc", 0, MONO, 2.1F));
        assertEquals(3, TextLayout.indexAt("abc", 0, MONO, 99.0F));
        assertEquals(1, TextLayout.indexAt("abc", 1, MONO, -5.0F));
    }
}
