package atlas.ui.hud.list;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ListAnim {
    private static final class State {
        float appear;
        float slot = Float.NaN;
        ListRow row;
    }

    private final Map<Object, State> states = new LinkedHashMap<Object, State>();

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

    public void clear() {
        this.states.clear();
    }

    public static float ease(float appear) {
        float left = 1.0F - Math.max(0.0F, Math.min(1.0F, appear));
        return 1.0F - left * left * left;
    }
}
