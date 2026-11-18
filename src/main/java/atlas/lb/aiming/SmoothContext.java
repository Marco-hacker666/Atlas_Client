package atlas.lb.aiming;

public final class SmoothContext {
    public final boolean live;

    public final Rotation previous;

    public final Rotation previousTarget;

    public final double distance;

    public final boolean onCrosshair;

    public SmoothContext(boolean live, Rotation previous, Rotation previousTarget, double distance, boolean onCrosshair) {
        this.live = live;
        this.previous = previous;
        this.previousTarget = previousTarget;
        this.distance = distance;
        this.onCrosshair = onCrosshair;
    }

    public static SmoothContext estimate(Rotation previous) {
        return new SmoothContext(false, previous, null, 0.0, false);
    }
}
