package atlas.ui.impl.clickgui.glide;

import java.util.HashMap;
import java.util.Map;

/**
 * The Glide style's eased values (hover tints, on/off fades, folding),
 * frame-time based so they run at the same speed at any frame rate. A key
 * seen for the first time starts at its target, so nothing animates in from
 * zero when the menu opens.
 */
final class GlideAnim {
    private final Map<String, Float> values = new HashMap<String, Float>();
    private long lastFrame;
    private float dt = 0.016F;

    /** Call once at the start of every frame. */
    void frame() {
        long now = System.nanoTime();
        this.dt = this.lastFrame == 0L ? 0.016F : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
    }

    /** Forget the last frame's time, so the first frame after reopening is not one long step. */
    void reset() {
        this.lastFrame = 0L;
    }

    /** Moves {@code key} toward {@code target}; {@code speed} is per second (about 1/time constant). */
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

    /** Sets {@code key} without easing. */
    void set(String key, float value) {
        this.values.put(key, value);
    }
}
