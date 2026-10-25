package atlas.lb.aiming;

/**
 * What a smoother may look at besides the two rotations (2026-10-24,
 * LiquidBounce NextGen port): what RotationManager and the rotation target
 * give LiquidBounce's angle smoothers. Null fields mean "not known".
 */
public final class SmoothContext {
    /** A real aiming step (LiquidBounce passes a RotationTarget); false for tick estimates. */
    public final boolean live;
    /** The rotation sent the tick before the current one. */
    public final Rotation previous;
    /** The target rotation asked for the tick before. */
    public final Rotation previousTarget;
    /** Box distance to the target entity, 0 when none. */
    public final double distance;
    /** The current rotation already lands on the target entity. */
    public final boolean onCrosshair;

    public SmoothContext(boolean live, Rotation previous, Rotation previousTarget, double distance, boolean onCrosshair) {
        this.live = live;
        this.previous = previous;
        this.previousTarget = previousTarget;
        this.distance = distance;
        this.onCrosshair = onCrosshair;
    }

    /** For estimating ticks: not live, nothing known but the previous rotation. */
    public static SmoothContext estimate(Rotation previous) {
        return new SmoothContext(false, previous, null, 0.0, false);
    }
}
