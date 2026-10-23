package atlas.ui.impl.clickgui.atlas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-frame state every control of the Atlas menu shares (2026-10-23,
 * docs/GUI-AUDIT-2026-10-23.md section 6): the rectangles that can be clicked,
 * and the fades, springs and one-shot pulses that animate them. Moved out of
 * AtlasClickGui unchanged, so parts of the menu can live in classes of their
 * own and be tested.
 *
 * Easing and springs run on wall time (delta, set every frame), so the feel
 * does not change with frame rate or with the game paused.
 */
final class UiContext {

    /**
     * One clickable rectangle, recorded as it is drawn.
     *
     * The kind says what a click there means and the payload says what it
     * means it to. Everything the pointer can act on goes through here,
     * including the window's own frame, so there is exactly one description of
     * where each control is.
     */
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

    /** Seconds since the last frame, capped. */
    float delta = 0.016F;
    /* From the theme, every frame: Animations off snaps every spring, Smooth
       scrolling off jumps every scroll, and the spring speed scales time. */
    boolean animations = true;
    boolean smoothScroll = true;
    float springSpeed = 1.0F;

    /** A new frame: nothing is clickable until it is drawn again. */
    void beginFrame() {
        this.hits.clear();
    }

    /** Opening the menu: every fade and spring starts again at its target. */
    void reset() {
        this.lit.clear();
        this.springs.clear();
        this.hits.clear();
    }

    void hit(String kind, Object payload, float x, float y, float x2, float y2) {
        this.hits.add(new Hit(kind, payload, x, y, x2, y2));
    }

    /**
     * The topmost thing under the pointer, in layout coordinates.
     *
     * Searched newest first, because later drawing means nearer the viewer --
     * an open dropdown covers the rows it is drawn over, and the click has to
     * agree with the eye about that.
     */
    Hit hitAt(float mouseX, float mouseY) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit candidate = this.hits.get(i);
            if (candidate.contains(mouseX, mouseY)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Moves a stored value a proportion of the way to its target each frame.
     * Exponential approach: frame-rate independent by construction, fast at
     * first and easing in at the end. Used for fades; anything with a position
     * uses a spring instead.
     */
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

    /** Starts a fade from {@code value} on its next frame (an arrival from 0, a scroll from the top). */
    void set(Object key, float value) {
        this.lit.put(key, Float.valueOf(value));
    }

    /** A spring, created at its target the first time it is asked for. */
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

    /** A scroll offset, eased unless the theme turns smooth scrolling off. */
    float scrollEase(Object key, float target) {
        if (!this.smoothScroll) {
            this.lit.put(key, target);
            return target;
        }
        return toward(key, target, 16.0F);
    }

    /** Records the instant something was acted on, for one-shot animations. */
    void pulse(Object key) {
        this.pulses.put(key, System.currentTimeMillis());
    }

    /** 0 to 1 across the pulse's life, or -1 when it is over. */
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
