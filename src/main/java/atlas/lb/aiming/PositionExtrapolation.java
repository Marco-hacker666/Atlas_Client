package atlas.lb.aiming;

/**
 * Where something will be in n ticks (2026-10-24, LiquidBounce NextGen port;
 * utils/entity/PositionExtrapolation.kt).
 *
 * LiquidBounce simulates other players' movement (PlayerSimulationCache);
 * that simulator is not part of this port, so players use the same linear
 * extrapolation as every other entity: this tick's step, repeated.
 */
public final class PositionExtrapolation {
    private PositionExtrapolation() {
    }

    /** {x, y, z} + ticks * ({x, y, z} - last). */
    public static double[] linear(double x, double y, double z, double lastX, double lastY, double lastZ, double ticks) {
        return new double[]{x + ticks * (x - lastX), y + ticks * (y - lastY), z + ticks * (z - lastZ)};
    }
}
