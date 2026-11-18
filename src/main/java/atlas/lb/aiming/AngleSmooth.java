package atlas.lb.aiming;

import java.util.Random;

// How far one tick turns from the current rotation toward the target (LiquidBounce NextGen port, GPL-3.0)

public abstract class AngleSmooth {
    protected static final Random RANDOM = new Random();

    public abstract Rotation process(SmoothContext context, Rotation current, Rotation target);

    public abstract int calculateTicks(SmoothContext context, Rotation current, Rotation target);

    protected static float between(float min, float max) {
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        return low + RANDOM.nextFloat() * (high - low);
    }

    protected static int between(int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return low + RANDOM.nextInt(high - low + 1);
    }

    public abstract static class Factor extends AngleSmooth {
        protected abstract float[] factors(SmoothContext context, Rotation current, Rotation target);

        @Override
        public Rotation process(SmoothContext context, Rotation current, Rotation target) {
            float[] f = factors(context, current, target);
            return current.towardsLinear(target, f[0], f[1]);
        }

        @Override
        public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
            SmoothContext estimate = SmoothContext.estimate(context == null ? null : context.previous);
            Rotation rotation = current;
            int ticks = -1;
            do {
                float[] f = factors(estimate, rotation, target);
                rotation = rotation.towardsLinear(target, f[0], f[1]);
                ticks++;
            } while (!rotation.isDeltaCloseTo(target) && ticks < 80);
            return ticks;
        }
    }

    public static final class None extends AngleSmooth {
        @Override
        public Rotation process(SmoothContext context, Rotation current, Rotation target) {
            return current;
        }

        @Override
        public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
            return 0;
        }
    }

    public static final class Linear extends Factor {
        public float horizontalMin = 180.0F;
        public float horizontalMax = 180.0F;
        public float verticalMin = 180.0F;
        public float verticalMax = 180.0F;

        @Override
        protected float[] factors(SmoothContext context, Rotation current, Rotation target) {
            if (context != null && context.live) {
                return new float[]{between(this.horizontalMin, this.horizontalMax), between(this.verticalMin, this.verticalMax)};
            }
            return new float[]{Math.min(this.horizontalMin, this.horizontalMax), Math.min(this.verticalMin, this.verticalMax)};
        }
    }

    public static final class Sigmoid extends Factor {
        public float horizontalMin = 180.0F;
        public float horizontalMax = 180.0F;
        public float verticalMin = 180.0F;
        public float verticalMax = 180.0F;
        public float steepness = 10.0F;
        public float midpoint = 0.3F;

        @Override
        protected float[] factors(SmoothContext context, Rotation current, Rotation target) {
            float difference = Math.min(current.deltaLengthTo(target), 180.0F);
            boolean live = context != null && context.live;
            float h = live ? between(this.horizontalMin, this.horizontalMax) : Math.min(this.horizontalMin, this.horizontalMax);
            float v = live ? between(this.verticalMin, this.verticalMax) : Math.min(this.verticalMin, this.verticalMax);
            return new float[]{factor(difference, h), factor(difference, v)};
        }

        private float factor(float difference, float speed) {
            float scaled = difference / 120.0F;
            double sigmoid = 1.0 / (1.0 + Math.exp(-this.steepness * (scaled - this.midpoint)));
            return Rotation.clamp((float) (sigmoid * speed), 0.0F, 180.0F);
        }
    }

    public static final class Interpolation extends Factor {
        public int horizontalMin = 80;
        public int horizontalMax = 85;
        public int verticalMin = 20;
        public int verticalMax = 25;
        public int directionChangeMin = 95;
        public int directionChangeMax = 100;
        public float midpoint = 0.35F;

        static float normalizeDirectionChange(float angle) {
            return Rotation.clamp(angle / 180.0F, 0.0F, 1.0F);
        }

        private static float sigmoid(float t) {
            return (float) (1.0 / (1.0 + Math.exp(-0.5 * (t - 0.3))));
        }

        private static float bezier(float start, float end, float t) {
            return (1.0F - t) * (1.0F - t) * start + 2.0F * (1.0F - t) * t + t * t * end;
        }

        @Override
        protected float[] factors(SmoothContext context, Rotation current, Rotation target) {
            float[] d = current.deltaTo(target);
            boolean live = context != null && context.live;
            float directionChange = live && context.previousTarget != null
                    ? normalizeDirectionChange(context.previousTarget.deltaLengthTo(target))
                    * (between(this.directionChangeMin, this.directionChangeMax) / 100.0F)
                    : 0.0F;
            float h = (live ? between(this.horizontalMin, this.horizontalMax) : Math.min(this.horizontalMin, this.horizontalMax)) / 100.0F;
            float v = (live ? between(this.verticalMin, this.verticalMax) : Math.min(this.verticalMin, this.verticalMax)) / 100.0F;
            float hf = factor(Math.abs(d[0]), Rotation.clamp(h, 0.0F, 1.0F), directionChange);
            float vf = factor(Math.abs(d[1]), Rotation.clamp(v, 0.0F, 1.0F), directionChange);

            return new float[]{hf * Math.abs(d[0]), vf * Math.abs(d[1])};
        }

        private float factor(float difference, float speed, float directionChange) {
            float t = normalizeDirectionChange(difference);
            if (t > this.midpoint) {
                return bezier(0.05F, 1.0F, 1.0F - t) * speed;
            }
            return sigmoid(t) * Rotation.clamp(speed + directionChange, 0.0F, 1.0F);
        }
    }

    public static final class AimAssist extends AngleSmooth {
        public float horizontalSpeed = 3.0F;
        public float verticalSpeed = 0.0F;

        public float smoothing = 0.5F;
        public boolean strafeIncrease = true;

        public int strafeSide;
        public float deadZone = 1.0F;

        private float shrink(float delta, boolean random) {
            if (Math.abs(delta) <= this.deadZone) {
                return 0.0F;
            }
            float jitter = random ? between(-0.1F, 0.1F) : 0.0F;
            float share = Rotation.clamp(this.smoothing + jitter, 0.0F, 1.0F);
            return delta * (0.5F + 0.5F * (1.0F - share));
        }

        private static float quantize(float angle) {
            return (float) ((double) angle - (double) angle % 0.0096F);
        }

        private Rotation step(Rotation current, Rotation target, boolean random) {
            float[] d = current.deltaTo(target);
            float yawTarget = quantize(current.yaw + shrink(Rotation.clamp(d[0], -180.0F, 180.0F), random));
            float pitchTarget = quantize(current.pitch + shrink(Rotation.clamp(d[1], -180.0F, 180.0F), random));
            float yawStep = 0.1F * Math.min(Math.abs(this.horizontalSpeed), 10.0F);
            if (this.strafeIncrease && this.strafeSide != 0 && Math.signum(d[0]) == this.strafeSide) {
                yawStep = Math.min(1.0F, yawStep * 1.6F);
            }
            float pitchStep = 0.1F * Math.min(Math.abs(this.verticalSpeed), 10.0F);
            return new Rotation(current.yaw + Rotation.angleDifference(yawTarget, current.yaw) * yawStep,
                    current.pitch + (pitchTarget - current.pitch) * pitchStep);
        }

        @Override
        public Rotation process(SmoothContext context, Rotation current, Rotation target) {
            return step(current, target, true);
        }

        @Override
        public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
            Rotation rotation = current;
            int ticks = 0;
            while (!rotation.isDeltaCloseTo(target, Math.max(2.0F, this.deadZone * 1.5F)) && ticks < 80) {
                Rotation next = step(rotation, target, false);
                if (next.deltaLengthTo(rotation) < 1.0E-4F) {
                    break;
                }
                rotation = next;
                ticks++;
            }
            return ticks;
        }
    }

    public static final class Tracking extends AngleSmooth {
        public float yawP = 0.35F;
        public float pitchP = 0.25F;
        public float integral = 0.04F;
        public float leak = 0.8F;
        public float feedForward = 0.9F;

        public float gainVariance = 0.2F;
        public float onTargetZone = 1.5F;
        public float maxYawMin = 30.0F;
        public float maxYawMax = 45.0F;
        public float maxPitchMin = 15.0F;
        public float maxPitchMax = 25.0F;

        private static final float JUMP = 30.0F;
        private static final float INTEGRAL_LIMIT = 60.0F;
        private final float[] ff = new float[2];
        private final float[] sum = new float[2];
        private final float[] gain = {1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F};
        private boolean started;

        public void reseed() {
            this.started = false;
        }

        private void begin(boolean random) {
            this.ff[0] = this.ff[1] = 0.0F;
            this.sum[0] = this.sum[1] = 0.0F;
            for (int i = 0; i < this.gain.length; i++) {
                this.gain[i] = random ? 1.0F + between(-this.gainVariance, this.gainVariance) : 1.0F;
            }
            this.started = true;
        }

        private float axis(int axis, float error, float targetSpeed, double distance, float maxTurn) {
            this.ff[axis] = this.ff[axis] * 0.6F + targetSpeed * 0.4F;
            this.sum[axis] = Rotation.clamp(this.sum[axis] * this.leak + error, -INTEGRAL_LIMIT, INTEGRAL_LIMIT);
            float kp = (axis == 0 ? this.yawP : this.pitchP) * this.gain[axis * 3];
            if (Math.abs(error) <= this.onTargetZone) {
                kp *= 0.5F;
            }
            float carried = this.feedForward * this.gain[axis * 3 + 2] * this.ff[axis];
            float step = kp * error + this.integral * this.gain[axis * 3 + 1] * this.sum[axis] + carried;
            if (distance > 0.0 && distance < 0.8) {
                step *= (float) (distance / 0.8);
            }

            float limit = Math.abs(error) + Math.abs(carried);
            step = Rotation.clamp(step, -limit, limit);
            return Rotation.clamp(step, -maxTurn, maxTurn);
        }

        private Rotation step(SmoothContext context, Rotation current, Rotation target, boolean random) {
            float[] speed = context != null && context.previousTarget != null
                    ? context.previousTarget.deltaTo(target) : new float[]{0.0F, 0.0F};
            if (!this.started || Math.abs(speed[0]) > JUMP || Math.abs(speed[1]) > JUMP) {
                begin(random);
                speed[0] = speed[1] = 0.0F;
            }
            float[] e = current.deltaTo(target);
            if (Math.abs(e[0]) > 120.0F) {
                this.sum[0] = 0.0F;
            }
            double distance = context == null ? 0.0 : context.distance;
            float maxYaw = random ? between(this.maxYawMin, this.maxYawMax) : Math.min(this.maxYawMin, this.maxYawMax);
            float maxPitch = random ? between(this.maxPitchMin, this.maxPitchMax)
                    : Math.min(this.maxPitchMin, this.maxPitchMax);
            float yaw = axis(0, e[0], speed[0], distance, maxYaw);
            float pitch = axis(1, e[1], speed[1], distance, maxPitch);
            return new Rotation(current.yaw + yaw, Rotation.clamp(current.pitch + pitch, -90.0F, 90.0F));
        }

        @Override
        public Rotation process(SmoothContext context, Rotation current, Rotation target) {
            return step(context, current, target, true);
        }

        @Override
        public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
            Tracking estimate = new Tracking();
            estimate.yawP = this.yawP;
            estimate.pitchP = this.pitchP;
            estimate.integral = this.integral;
            estimate.leak = this.leak;
            estimate.feedForward = this.feedForward;
            estimate.gainVariance = 0.0F;
            estimate.onTargetZone = this.onTargetZone;
            estimate.maxYawMin = this.maxYawMin;
            estimate.maxYawMax = this.maxYawMax;
            estimate.maxPitchMin = this.maxPitchMin;
            estimate.maxPitchMax = this.maxPitchMax;
            Rotation rotation = current;
            int ticks = 0;
            while (!rotation.isDeltaCloseTo(target) && ticks < 80) {
                rotation = estimate.step(null, rotation, target, false);
                ticks++;
            }
            return ticks;
        }
    }

    public static final class Acceleration extends AngleSmooth {
        public float yawAccelMin = 20.0F;
        public float yawAccelMax = 25.0F;
        public float pitchAccelMin = 20.0F;
        public float pitchAccelMax = 25.0F;
        public boolean dynamicAccel;
        public float coefDistance = -1.393F;
        public float yawCrosshairMin = 17.0F;
        public float yawCrosshairMax = 20.0F;
        public float pitchCrosshairMin = 17.0F;
        public float pitchCrosshairMax = 20.0F;
        public boolean accelerationError = true;
        public float yawAccelError = 0.1F;
        public float pitchAccelError = 0.1F;
        public boolean constantError = true;
        public float yawConstantError = 0.1F;
        public float pitchConstantError = 0.1F;
        public boolean sigmoidDeceleration;
        public float steepness = 10.0F;
        public float midpoint = 0.3F;

        @Override
        public Rotation process(SmoothContext context, Rotation current, Rotation target) {
            Rotation previous = context != null && context.previous != null ? context.previous : current;
            float[] turn = turnSpeed(previous.deltaTo(current), current.deltaTo(target),
                    context != null && context.onCrosshair, context == null ? 0.0 : context.distance);
            return new Rotation(current.yaw + turn[0], current.pitch + turn[1]);
        }

        @Override
        public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
            Rotation previous = context != null && context.previous != null ? context.previous : current;
            float[] prevDiff = previous.deltaTo(current);
            float[] diff = current.deltaTo(target);
            if (diff[0] == 0.0F && diff[1] == 0.0F) {
                return 0;
            }
            float[] turn = turnSpeed(prevDiff, diff, false, 0.0);
            if (turn[0] == 0.0F && turn[1] == 0.0F
                    || Math.abs(diff[0]) < Math.abs(turn[0]) && Math.abs(diff[1]) < Math.abs(turn[1])) {
                return 0;
            }
            double ticksH = Math.floor(Math.abs(diff[0]) / Math.abs(turn[0]));
            double ticksV = Math.floor(Math.abs(diff[1]) / Math.abs(turn[1]));
            if (Double.isNaN(ticksH) || Double.isNaN(ticksV)) {
                return 0;
            }
            return (int) Math.max(ticksH, ticksV);
        }

        private float decelerationFactor(float difference) {
            if (!this.sigmoidDeceleration) {
                return 1.0F;
            }
            float scaled = difference / 120.0F;
            double sigmoid = 1.0 / (1.0 + Math.exp(-this.steepness * (scaled - this.midpoint)));
            return Rotation.clamp((float) sigmoid, 0.0F, 180.0F);
        }

        private float error(float acceleration, boolean yaw) {
            float accelError = this.accelerationError ? (yaw ? this.yawAccelError : this.pitchAccelError) : 0.0F;
            float constError = this.constantError ? (yaw ? this.yawConstantError : this.pitchConstantError) : 0.0F;
            return acceleration * between(-accelError, accelError) + between(-constError, constError);
        }

        float[] turnSpeed(float[] prevDiff, float[] diff, boolean crosshair, double distance) {
            float deceleration = decelerationFactor((float) Math.hypot(diff[0], diff[1]));
            boolean crosshairCheck = this.dynamicAccel && crosshair;
            float distanceFactor = (float) (this.coefDistance * distance);
            float aYaw = crosshairCheck ? between(this.yawCrosshairMin, this.yawCrosshairMax) : between(this.yawAccelMin, this.yawAccelMax);
            float aYaw2 = crosshairCheck ? between(this.yawCrosshairMin, this.yawCrosshairMax) : between(this.yawAccelMin, this.yawAccelMax);
            float aPitch = crosshairCheck ? between(this.pitchCrosshairMin, this.pitchCrosshairMax) : between(this.pitchAccelMin, this.pitchAccelMax);
            float aPitch2 = crosshairCheck ? between(this.pitchCrosshairMin, this.pitchCrosshairMax) : between(this.pitchAccelMin, this.pitchAccelMax);
            float yawAccel = acceleration(diff[0], prevDiff[0], -aYaw + distanceFactor, aYaw2 + distanceFactor, deceleration);
            float pitchAccel = acceleration(diff[1], prevDiff[1], -aPitch + distanceFactor, aPitch2 + distanceFactor, deceleration);
            return new float[]{
                    prevDiff[0] + yawAccel + error(yawAccel, true),
                    prevDiff[1] + pitchAccel + error(pitchAccel, false)};
        }

        private static float acceleration(float diff, float prevDiff, float min, float max, float deceleration) {
            float change = Rotation.angleDifference(diff, prevDiff);

            float low = Math.min(min, max);
            float high = Math.max(min, max);
            return Rotation.clamp(change, low, high) * deceleration;
        }
    }
}
