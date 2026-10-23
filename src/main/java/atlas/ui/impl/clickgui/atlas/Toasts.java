package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.List;

/**
 * Short messages shown inside the menu (2026-10-23, docs/GUI-AUDIT-2026-10-23.md
 * U7): what a click did, or why it could not. They used to go to chat, which
 * sits half hidden behind the menu and its blur.
 *
 * At most three at once; the oldest goes when a fourth arrives. The same text
 * again restarts its toast instead of stacking a copy. Problems stay longer
 * than results.
 */
final class Toasts {

    enum Kind { INFO, OK, WARN, ERROR }

    static final class Toast {
        final String text;
        final Kind kind;
        final long at;
        final long life;
        /** What a click on it does besides dismissing it (an Undo); null for nothing. */
        final Runnable action;

        Toast(String text, Kind kind, long at, Runnable action) {
            this.text = text;
            this.kind = kind;
            this.at = at;
            this.life = action == null ? lifeOf(kind) : Math.max(lifeOf(kind), 6000L);
            this.action = action;
        }
    }

    static final int MAX = 3;

    private final List<Toast> shown = new ArrayList<Toast>();

    static long lifeOf(Kind kind) {
        return kind == Kind.ERROR ? 4500L : kind == Kind.WARN ? 4000L : 2500L;
    }

    void add(String text, Kind kind, long now) {
        add(text, kind, now, null);
    }

    /** A toast a click acts on: it stays at least six seconds, to give the click time. */
    void add(String text, Kind kind, long now, Runnable action) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = this.shown.size() - 1; i >= 0; i--) {
            if (this.shown.get(i).text.equals(text)) {
                this.shown.remove(i);
            }
        }
        this.shown.add(new Toast(text, kind, now, action));
        while (this.shown.size() > MAX) {
            this.shown.remove(0);
        }
    }

    /** The toasts still showing, oldest first. */
    List<Toast> visible(long now) {
        for (int i = this.shown.size() - 1; i >= 0; i--) {
            Toast toast = this.shown.get(i);
            if (now - toast.at >= toast.life) {
                this.shown.remove(i);
            }
        }
        return this.shown;
    }

    void dismiss(Toast toast) {
        this.shown.remove(toast);
    }

    void clear() {
        this.shown.clear();
    }
}
