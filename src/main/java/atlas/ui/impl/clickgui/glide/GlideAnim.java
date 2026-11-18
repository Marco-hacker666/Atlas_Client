package atlas.ui.impl.clickgui.glide;

import java.util.HashMap;
import java.util.Map;

final class GlideAnim {
    private final Map<String, Float> values = new HashMap<String, Float>();
    private long lastFrame;
    private float dt = 0.016F;

    void frame() {
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0L ? 0.016F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
    }

    void reset() {
        this.lastFrame = 0L;
    }

    float ease(String key, float target, float speed) {
        Float now = this.values.get(key);
        float v = now == null ? target : now;
        v += (target - v) * Math.min(1.0F, this.dt * speed);
        if (Math.abs(target - v) < 0.002F) {
            v = target;
        }
        this.values.put(key, v);
        return v;
    }

    void set(String key, float value) {
        this.values.put(key, value);
    }
}
