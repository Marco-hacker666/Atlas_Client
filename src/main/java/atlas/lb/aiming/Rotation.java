package atlas.lb.aiming;

// A yaw and pitch (LiquidBounce NextGen port, GPL-3.0)

public final class Rotation {
    public final float yaw;
    public final float pitch;

    public Rotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static float wrap(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    public static float angleDifference(float a, float b) {
        return wrap(a - b);
    }

    public static Rotation fromVector(double dx, double dy, double dz) {
        return new Rotation(
                wrap((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F),
                wrap((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
    }

    public float[] deltaTo(Rotation other) {
        return new float[]{angleDifference(other.yaw, this.yaw), angleDifference(other.pitch, this.pitch)};
    }

    public float deltaLengthTo(Rotation other) {
        float[] d = deltaTo(other);
        return (float) Math.hypot(d[0], d[1]);
    }

    public boolean isDeltaCloseTo(Rotation other, float tolerance) {
        return deltaLengthTo(other) <= tolerance;
    }

    public boolean isDeltaCloseTo(Rotation other) {
        return isDeltaCloseTo(other, 2.0F);
    }

    public Rotation towardsLinear(Rotation other, float horizontal, float vertical) {
        float[] d = deltaTo(other);
        float length = (float) Math.hypot(d[0], d[1]);
        if (length == 0.0F) {
            return this;
        }
        float maxYaw = Math.abs(d[0] / length) * horizontal;
        float maxPitch = Math.abs(d[1] / length) * vertical;
        return new Rotation(this.yaw + clamp(d[0], -maxYaw, maxYaw), this.pitch + clamp(d[1], -maxPitch, maxPitch));
    }

    public Rotation interpolateTo(Rotation other, float factor) {
        return new Rotation(this.yaw + factor * (other.yaw - this.yaw), this.pitch + factor * (other.pitch - this.pitch));
    }

    public Rotation normalize(Rotation from, double gcd) {
        if (gcd <= 0.0) {
            return new Rotation(this.yaw, clamp(this.pitch, -90.0F, 90.0F));
        }
        float[] d = from.deltaTo(this);
        double yaw = from.yaw + Math.round(d[0] / gcd) * gcd;
        double pitch = from.pitch + Math.round(d[1] / gcd) * gcd;
        return new Rotation((float) yaw, clamp((float) pitch, -90.0F, 90.0F));
    }

    static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", this.yaw, this.pitch);
    }
}
