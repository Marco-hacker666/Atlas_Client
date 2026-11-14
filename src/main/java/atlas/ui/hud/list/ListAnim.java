package atlas.ui.hud.list;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The module list's rows coming, going and changing places (2026-11-14; the JELLO list's animation of 2026-10-08,
 * made a thing of its own). A row is known by its key from frame to frame: a new one appears, one that is gone
 * stays on its way out, and each eases to its place when the order changes.
 */
public final class ListAnim {

    private static final class State {
        float appear;
        float slot = Float.NaN;
        ListRow row;
    }

    private final Map<Object, State> states = new LinkedHashMap<Object, State>();

    /**
     * One frame.
     *
     * @param active the rows that are on, in the order they are listed
     * @param dt     seconds since the last frame
     * @return the rows to draw -- the active ones, then those still going -- with their appear and slot set
     */
    public List<ListRow> step(List<ListRow> active, ListStyle style, float dt) {
        if (style.anim == ListStyle.Anim.NONE || style.animTime <= 0.0F) {
            this.states.clear();
            int index = 0;
            for (ListRow row : active) {
                row.appear = 1.0F;
                row.slot = (float) index++;
            }
            return new ArrayList<ListRow>(active);
        }
        dt = Math.max(0.0F, Math.min(0.1F, dt));
        /* A row is in place after about its coming's time. */
        float follow = 1.0F - (float) Math.exp(-dt / (style.animTime / 4.0F));
        float rate = dt / style.animTime;
        List<ListRow> out = new ArrayList<ListRow>();
        Set<Object> shown = new HashSet<Object>();
        int index = 0;
        for (ListRow row : active) {
            State state = this.states.get(row.key);
            if (state == null) {
                state = new State();
                this.states.put(row.key, state);
            }
            if (Float.isNaN(state.slot)) {
                state.slot = (float) index;
            }
            state.slot += ((float) index - state.slot) * follow;
            state.appear = Math.min(1.0F, state.appear + rate);
            state.row = row;
            row.appear = state.appear;
            row.slot = state.slot;
            out.add(row);
            shown.add(row.key);
            index++;
        }
        for (Iterator<Map.Entry<Object, State>> it = this.states.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, State> entry = it.next();
            if (shown.contains(entry.getKey())) {
                continue;
            }
            State state = entry.getValue();
            state.appear -= rate;
            if (state.appear <= 0.0F || state.row == null) {
                it.remove();
                continue;
            }
            state.row.appear = state.appear;
            state.row.slot = state.slot;
            out.add(state.row);
        }
        return out;
    }

    /** Forgets every row: the next frame's rows are simply there. */
    public void clear() {
        this.states.clear();
    }

    /** 0 to 1 with a soft landing: how far along a row's coming is, from how long it has been coming. */
    public static float ease(float appear) {
        float left = 1.0F - Math.max(0.0F, Math.min(1.0F, appear));
        return 1.0F - left * left * left;
    }
}
