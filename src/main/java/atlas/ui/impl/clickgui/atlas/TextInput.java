package atlas.ui.impl.clickgui.atlas;

import org.lwjgl.input.Keyboard;

import java.util.function.Supplier;

public final class TextInput {
    public enum Result {
        NONE,

        EDITED,

        MOVED,

        SUBMIT,

        CANCEL,

        IGNORED
    }

    private final int max;

    private final boolean newlines;

    private String text = "";
    private int caret;
    private boolean all;

    private float drawnX;
    private int drawnStart;
    private TextLayout.Width drawnWidth;

    public TextInput(int max, boolean newlines) {
        this.max = max;
        this.newlines = newlines;
    }

    public String text() {
        return this.text;
    }

    public int caret() {
        return this.caret;
    }

    boolean isEmpty() {
        return this.text.isEmpty();
    }

    public boolean allSelected() {
        return this.all && !this.text.isEmpty();
    }

    public void set(String value) {
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

    public Result key(char typed, int key, boolean ctrl, Supplier<String> clipboard) {
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            return Result.SUBMIT;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            return Result.CANCEL;
        }

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

    void drawnAt(float x, int start, TextLayout.Width width) {
        this.drawnX = x;
        this.drawnStart = start;
        this.drawnWidth = width;
    }

    void placeAt(float x) {
        this.all = false;
        if (this.drawnWidth == null) {
            toEnd();
            return;
        }
        this.caret = TextLayout.indexAt(this.text, this.drawnStart, this.drawnWidth, x - this.drawnX);
    }
}
