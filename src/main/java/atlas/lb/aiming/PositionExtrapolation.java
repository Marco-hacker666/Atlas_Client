package atlas.lb.aiming;

public final class PositionExtrapolation {
    private PositionExtrapolation() {
    }

    public static double[] linear(double x, double y, double z, double lastX, double lastY, double lastZ, double ticks) {
        return new double[]{x + ticks * (x - lastX), y + ticks * (y - lastY), z + ticks * (z - lastZ)};
    }
}
