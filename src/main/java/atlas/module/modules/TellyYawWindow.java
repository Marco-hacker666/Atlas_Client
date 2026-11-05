package atlas.module.modules;

import net.minecraft.util.MathHelper;

import java.util.ArrayDeque;

/**
 * The yaw Speed Telly has sent over the last seconds, and the looks it should not send (2026-11-05). Written from what
 * Intave's PlacementAnalysis is documented to do: the yaw of look packets is summed over 5 seconds, steps near a half
 * turn are counted, and a look landing on the 45 degree grid after a big turn is noted. Pure, no world.
 */
final class TellyYawWindow {

    static final long WINDOW_MS = 5000L;
    /** A yaw step this big counts as a half turn; the check starts at 170. */
    static final float HALF_TURN = 168.0F;
    /** Degrees from a multiple of 45 that count as on the grid; the check uses 0.08. */
    static final float GRID_MARGIN = 0.12F;
    private static final float GRID = 45.0F;

    private static final class Step {
        final float yaw;
        final long at;

        Step(float yaw, long at) {
            this.yaw = yaw;
            this.at = at;
        }
    }

    private final ArrayDeque<Step> steps = new ArrayDeque<Step>();
    private float lastYaw = Float.NaN;
    private double sum;

    /** A look went out. */
    void look(float yaw, long now) {
        expire(now);
        if (!Float.isNaN(this.lastYaw)) {
            float step = Math.abs(MathHelper.wrapAngleTo180_float(yaw - this.lastYaw));
            this.steps.addLast(new Step(step, now));
            this.sum += step;
        }
        this.lastYaw = yaw;
    }

    /** Degrees of yaw sent in the window ending now. */
    double sum(long now) {
        expire(now);
        return this.sum;
    }

    void clear() {
        this.steps.clear();
        this.lastYaw = Float.NaN;
        this.sum = 0.0;
    }

    private void expire(long now) {
        while (!this.steps.isEmpty() && this.steps.peekFirst().at < now - WINDOW_MS) {
            this.sum -= this.steps.removeFirst().yaw;
        }
        if (this.steps.isEmpty()) {
            this.sum = 0.0;
        }
    }

    /** The yaw to step to on the way from one look to another: only this far round when the whole step is a half turn. */
    static float awayFromHalfTurn(float from, float to, float room) {
        float delta = MathHelper.wrapAngleTo180_float(to - from);
        return Math.abs(delta) < HALF_TURN ? to : from + Math.signum(delta) * room;
    }

    static boolean onGrid(float yaw) {
        float left = (yaw % GRID + GRID) % GRID;
        return Math.min(left, GRID - left) < GRID_MARGIN;
    }

    /** This yaw, or a little short of it on the side of {@code from} when it is on the grid. */
    static float offGrid(float yaw, float from, float amount) {
        if (!onGrid(yaw)) {
            return yaw;
        }
        return yaw + (MathHelper.wrapAngleTo180_float(from - yaw) < 0.0F ? -amount : amount);
    }
}
