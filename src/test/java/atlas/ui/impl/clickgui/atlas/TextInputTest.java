package atlas.ui.impl.clickgui.atlas;

import org.junit.Test;
import org.lwjgl.input.Keyboard;

import static org.junit.Assert.*;

/** The menu's shared text field (2026-10-23, GUI redesign M1). */
public class TextInputTest {

    /** Every character four units wide. */
    private static final TextLayout.Width MONO = s -> s.length() * 4.0F;

    private static TextInput.Result type(TextInput input, String text) {
        TextInput.Result last = TextInput.Result.NONE;
        for (char c : text.toCharArray()) {
            last = input.key(c, 0, false, null);
        }
        return last;
    }

    private static TextInput.Result press(TextInput input, int key) {
        return input.key('\0', key, false, null);
    }

    @Test
    public void typesAtTheCaret() {
        TextInput input = new TextInput(0, false);
        assertEquals(TextInput.Result.EDITED, type(input, "abd"));
        assertEquals(TextInput.Result.MOVED, press(input, Keyboard.KEY_LEFT));
        type(input, "c");
        assertEquals("abcd", input.text());
        assertEquals(3, input.caret());
    }

    @Test
    public void backspaceAndDeleteWorkAroundTheCaret() {
        TextInput input = new TextInput(0, false);
        input.set("abcd");
        press(input, Keyboard.KEY_LEFT);
        press(input, Keyboard.KEY_LEFT);
        assertEquals(TextInput.Result.EDITED, press(input, Keyboard.KEY_BACK));
        assertEquals("acd", input.text());
        assertEquals(TextInput.Result.EDITED, press(input, Keyboard.KEY_DELETE));
        assertEquals("ad", input.text());
        press(input, Keyboard.KEY_HOME);
        assertEquals(TextInput.Result.NONE, press(input, Keyboard.KEY_BACK));
        press(input, Keyboard.KEY_END);
        assertEquals(TextInput.Result.NONE, press(input, Keyboard.KEY_DELETE));
        assertEquals("ad", input.text());
    }

    @Test
    public void theCaretStaysInsideTheText() {
        TextInput input = new TextInput(0, false);
        input.set("ab");
        press(input, Keyboard.KEY_RIGHT);
        assertEquals(2, input.caret());
        press(input, Keyboard.KEY_HOME);
        press(input, Keyboard.KEY_LEFT);
        assertEquals(0, input.caret());
    }

    @Test
    public void ctrlASelectsEverythingAndTheNextKeyReplacesIt() {
        TextInput input = new TextInput(0, false);
        input.set("3.05");
        assertEquals(TextInput.Result.MOVED, input.key('\0', Keyboard.KEY_A, true, null));
        assertTrue(input.allSelected());
        type(input, "7");
        assertEquals("7", input.text());
        assertFalse(input.allSelected());

        input.set("abc");
        input.key('\0', Keyboard.KEY_A, true, null);
        assertEquals(TextInput.Result.EDITED, press(input, Keyboard.KEY_BACK));
        assertEquals("", input.text());

        input.set("abc");
        input.key('\0', Keyboard.KEY_A, true, null);
        press(input, Keyboard.KEY_LEFT);
        assertFalse(input.allSelected());
        assertEquals(0, input.caret());
        assertEquals("abc", input.text());
    }

    @Test
    public void pasteInsertsAtTheCaretWithoutControlCharacters() {
        TextInput input = new TextInput(0, false);
        input.set("ad");
        press(input, Keyboard.KEY_LEFT);
        assertEquals(TextInput.Result.EDITED, input.key('\0', Keyboard.KEY_V, true, () -> "b\r\nc\t"));
        assertEquals("ab c d", input.text());
        assertEquals(5, input.caret());
    }

    @Test
    public void theReportBoxKeepsPastedLineBreaks() {
        TextInput input = new TextInput(0, true);
        input.key('\0', Keyboard.KEY_V, true, () -> "one\r\ntwo");
        assertEquals("one\ntwo", input.text());
    }

    @Test
    public void typingAndPastingStopAtTheLimit() {
        TextInput input = new TextInput(5, false);
        type(input, "abcd");
        input.key('\0', Keyboard.KEY_V, true, () -> "xyz");
        assertEquals("abcdx", input.text());
        assertEquals(TextInput.Result.NONE, type(input, "q"));
        assertEquals("abcdx", input.text());
    }

    @Test
    public void aValueLongerThanTheLimitIsNeverCut() {
        TextInput input = new TextInput(3, false);
        input.set("longer");
        assertEquals("longer", input.text());
        assertEquals(6, input.caret());
        press(input, Keyboard.KEY_BACK);
        assertEquals("longe", input.text());
    }

    @Test
    public void enterAndEscapeAreLeftToTheOwner() {
        TextInput input = new TextInput(0, false);
        input.set("x");
        assertEquals(TextInput.Result.SUBMIT, press(input, Keyboard.KEY_RETURN));
        assertEquals(TextInput.Result.SUBMIT, press(input, Keyboard.KEY_NUMPADENTER));
        assertEquals(TextInput.Result.CANCEL, press(input, Keyboard.KEY_ESCAPE));
        assertEquals(TextInput.Result.IGNORED, press(input, Keyboard.KEY_UP));
        assertEquals(TextInput.Result.IGNORED, input.key('\0', Keyboard.KEY_L, true, null));
        assertEquals("x", input.text());
    }

    @Test
    public void altGrCharactersAreTyped() {
        /* AltGr reaches the screen as Control+Alt; '@' is AltGr+Q on a German keyboard. */
        TextInput input = new TextInput(0, false);
        assertEquals(TextInput.Result.EDITED, input.key('@', Keyboard.KEY_Q, true, null));
        assertEquals("@", input.text());
        assertEquals(TextInput.Result.IGNORED, input.key('\u000C', Keyboard.KEY_L, true, null));
        assertEquals("@", input.text());
    }

    @Test
    public void aClickPutsTheCaretNearestToIt() {
        TextInput input = new TextInput(0, false);
        input.set("abcdef");
        input.drawnAt(100.0F, 2, MONO);
        input.placeAt(100.0F + 4.0F * 2 + 1.0F);
        assertEquals(4, input.caret());
        input.placeAt(100.0F + 4.0F * 2 + 3.0F);
        assertEquals(5, input.caret());
        input.placeAt(50.0F);
        assertEquals(2, input.caret());
        input.placeAt(500.0F);
        assertEquals(6, input.caret());
    }

    @Test
    public void withoutADrawingAClickGoesToTheEnd() {
        TextInput input = new TextInput(0, false);
        input.set("abc");
        press(input, Keyboard.KEY_HOME);
        input.placeAt(10.0F);
        assertEquals(3, input.caret());
    }
}
