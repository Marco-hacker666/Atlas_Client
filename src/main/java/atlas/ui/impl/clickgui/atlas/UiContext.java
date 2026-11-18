package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class UiContext {
    static final class Hit {
        final String kind;
        final Object payload;
        final float x;
        final float y;
        final float x2;
        final float y2;

        Hit(String kind, Object payload, float x, float y, float x2, float y2) {
            this.kind = kind;
            this.payload = payload;
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
        }

        boolean contains(float mouseX, float mouseY) {
            return mouseX >= this.x && mouseX <= this.x2
                    && mouseY >= this.y && mouseY <= this.y2;
        }
    }

    private final List<Hit> hits = new ArrayList<Hit>();
    private final Map<Object, Float> lit = new HashMap<Object, Float>();
    private final Map<String, Spring> springs = new HashMap<String, Spring>();
    private final Map<Object, Long> pulses = new HashMap<Object, Long>();

    float delta = 0.016F;

    boolean animations = true;
    boolean smoothScroll = true;
    float springSpeed = 1.0F;

    void beginFrame() {
        this.hits.clear();
    }

    void reset() {
        this.lit.clear();
        this.springs.clear();
        this.hits.clear();
    }

    void hit(String kind, Object payload, float x, float y, float x2, float y2) {
        this.hits.add(new Hit(kind, payload, x, y, x2, y2));
    }

    Hit hitAt(float mouseX, float mouseY) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit candidate = this.hits.get(i);
            if (candidate.contains(mouseX, mouseY)) {
                return candidate;
            }
        }
        return null;
    }

    float toward(Object key, float target, float rate) {
        Float held = this.lit.get(key);
        float value = held == null ? target : held;
        value += (target - value) * (1.0F - (float) Math.exp(-rate * this.delta));
        if (Math.abs(target - value) < 0.0015F) {
            value = target;
        }
        this.lit.put(key, value);
        return value;
    }

    float ease(Object key, boolean towards, float rate) {
        return toward(key, towards ? 1.0F : 0.0F, rate);
    }

    void set(Object key, float value) {
        this.lit.put(key, Float.valueOf(value));
    }

    Spring spring(String key, float target, float stiffness, float damping) {
        Spring s = this.springs.get(key);
        if (s == null) {
            s = new Spring(target, stiffness, damping);
            this.springs.put(key, s);
        }
        s.target = target;
        if (!this.animations) {
            s.snap(target);
        } else {
            s.step(this.delta * this.springSpeed);
        }
        return s;
    }

    void putSpring(String key, Spring spring) {
        this.springs.put(key, spring);
    }

    void removeSpring(String key) {
        this.springs.remove(key);
    }

    float scrollEase(Object key, float target) {
        if (!this.smoothScroll) {
            this.lit.put(key, target);
            return target;
        }
        return toward(key, target, 16.0F);
    }

    void pulse(Object key) {
        this.pulses.put(key, System.currentTimeMillis());
    }

    float pulseAt(Object key, long millis) {
        Long at = this.pulses.get(key);
        if (at == null) {
            return -1.0F;
        }
        float t = (System.currentTimeMillis() - at) / (float) millis;
        if (t >= 1.0F) {
            this.pulses.remove(key);
            return -1.0F;
        }
        return t;
    }
}
