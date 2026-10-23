package atlas.ui.impl.clickgui.atlas;

import org.lwjgl.input.Keyboard;

import java.util.function.Supplier;

/**
 * The text of one field and its caret (2026-10-23, docs/GUI-AUDIT-2026-10-23.md
 * E2): the search box, a new profile's name, the bug report and a setting
 * being typed into each kept a string of their own, with their own backspace,
 * and none had a caret that could move. Only the report box could paste.
 *
 * Keys: characters insert at the caret; Backspace and Delete remove before and
 * after it; Left, Right, Home and End move it; Ctrl+A selects everything, so
 * the next character, Backspace or Delete replaces it; Ctrl+V pastes. Enter
 * and Escape are only reported, for the field's owner to act on.
 *
 * No drawing here, so the editing can be tested without a game. Where the text
 * was last drawn is kept ({@link #drawnAt}) so that a click can put the caret
 * where it lands.
 */
final class TextInput {

    enum Result {
        /** The key did nothing (a full field, Backspace at the start). */
        NONE,
        /** The text changed. */
        EDITED,
        /** Only the caret or the selection moved. */
        MOVED,
        /** Enter. */
        SUBMIT,
        /** Escape. */
        CANCEL,
        /** Not a key a field uses; the owner may want it. */
        IGNORED
    }

    /** The most characters typing or pasting may make; 0 for no limit. */
    private final int max;
    /** Whether pasted line breaks are kept (the report box) or made spaces. */
    private final boolean newlines;

    private String text = "";
    private int caret;
    private boolean all;

    private float drawnX;
    private int drawnStart;
    private TextLayout.Width drawnWidth;

    TextInput(int max, boolean newlines) {
        this.max = max;
        this.newlines = newlines;
    }

    String text() {
        return this.text;
    }

    int caret() {
        return this.caret;
    }

    boolean isEmpty() {
        return this.text.isEmpty();
    }

    /** Whether Ctrl+A has everything selected. */
    boolean allSelected() {
        return this.all && !this.text.isEmpty();
    }

    /**
     * Replaces the text and puts the caret at its end. Never cut to the limit:
     * a value longer than a field allows is still that value.
     */
    void set(String value) {
        this.text = value == null ? "" : value;
        this.caret = this.text.length();
        this.all = false;
    }

    void clear() {
        set("");
    }

    void toEnd() {
        this.caret = this.text.length();
        this.all = false;
    }

    /**
     * One key, as GuiScreen.keyTyped hands it over. {@code ctrl} is whether
     * Control is held; {@code clipboard} is asked only for Ctrl+V.
     */
    Result key(char typed, int key, boolean ctrl, Supplier<String> clipboard) {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            return Result.SUBMIT;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            return Result.CANCEL;
        }
        /* Any other Control key falls through: AltGr is Control+Alt, and on
           many layouts it is how '@' or '\' is typed. A Control+letter comes
           with a control character, which is not typed. */
        if (ctrl && key == Keyboard.KEY_V) {
            String paste = clipboard == null ? null : clipboard.get();
            return insert(paste == null ? "" : clean(paste)) ? Result.EDITED : Result.NONE;
        }
        if (ctrl && key == Keyboard.KEY_A) {
            this.all = !this.text.isEmpty();
            this.caret = this.text.length();
            return Result.MOVED;
        }
        switch (key) {
            case Keyboard.KEY_BACK:
                if (allSelected()) {
                    clear();
                    return Result.EDITED;
                }
                if (this.caret == 0) {
                    return Result.NONE;
                }
                this.text = this.text.substring(0, this.caret - 1) + this.text.substring(this.caret);
                this.caret--;
                return Result.EDITED;
            case Keyboard.KEY_DELETE:
                if (allSelected()) {
                    clear();
                    return Result.EDITED;
                }
                if (this.caret >= this.text.length()) {
                    return Result.NONE;
                }
                this.text = this.text.substring(0, this.caret) + this.text.substring(this.caret + 1);
                return Result.EDITED;
            case Keyboard.KEY_LEFT:
                this.caret = allSelected() ? 0 : Math.max(0, this.caret - 1);
                this.all = false;
                return Result.MOVED;
            case Keyboard.KEY_RIGHT:
                this.caret = allSelected() ? this.text.length() : Math.min(this.text.length(), this.caret + 1);
                this.all = false;
                return Result.MOVED;
            case Keyboard.KEY_HOME:
                this.caret = 0;
                this.all = false;
                return Result.MOVED;
            case Keyboard.KEY_END:
                toEnd();
                return Result.MOVED;
            default:
                break;
        }
        if (typed >= 32 && typed != 127) {
            return insert(String.valueOf(typed)) ? Result.EDITED : Result.NONE;
        }
        return Result.IGNORED;
    }

    /** Inserts at the caret, replacing a Ctrl+A selection, as much as the limit allows. */
    private boolean insert(String piece) {
        if (allSelected()) {
            this.text = "";
            this.caret = 0;
        }
        this.all = false;
        if (this.max > 0) {
            int room = this.max - this.text.length();
            if (room <= 0) {
                return false;
            }
            if (piece.length() > room) {
                piece = piece.substring(0, room);
            }
        }
        if (piece.isEmpty()) {
            return false;
        }
        this.text = this.text.substring(0, this.caret) + piece + this.text.substring(this.caret);
        this.caret += piece.length();
        return true;
    }

    /** Pasted text without carriage returns or control characters; tabs become spaces. */
    private String clean(String paste) {
        StringBuilder out = new StringBuilder(paste.length());
        for (int i = 0; i < paste.length(); i++) {
            char c = paste.charAt(i);
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                out.append(this.newlines ? '\n' : ' ');
            } else if (c == '\t') {
                out.append(' ');
            } else if (c >= 32 && c != 127) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Where the text was drawn this frame: text[start] at x, measured by width. */
    void drawnAt(float x, int start, TextLayout.Width width) {
        this.drawnX = x;
        this.drawnStart = start;
        this.drawnWidth = width;
    }

    /** Puts the caret nearest to a click at x, by where the text was last drawn. */
    void placeAt(float x) {
        this.all = false;
        if (this.drawnWidth == null) {
            toEnd();
            return;
        }
        this.caret = TextLayout.indexAt(this.text, this.drawnStart, this.drawnWidth, x - this.drawnX);
    }
}
