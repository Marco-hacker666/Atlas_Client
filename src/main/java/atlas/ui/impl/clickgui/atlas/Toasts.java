package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.List;

final class Toasts {
    enum Kind { INFO, OK, WARN, ERROR }

    static final class Toast {
        final String text;
        final Kind kind;
        final long at;
        final long life;

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
