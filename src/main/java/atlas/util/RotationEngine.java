package atlas.util;

import atlas.module.modules.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

public final class RotationEngine {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private float lastStep;
    private float thisStep;
    private float speedFactor = 1.0F;
    private int tick = Integer.MIN_VALUE;

    public void reset() {
        this.lastStep = 0.0F;
        this.thisStep = 0.0F;
        this.speedFactor = 1.0F;
    }

    public float lastStep() {
        roll();
        return this.lastStep;
    }

    private void roll() {
        int now = mc.thePlayer == null ? 0 : mc.thePlayer.ticksExisted;
        if (now == this.tick) {
            return;
        }
        this.lastStep = now == this.tick + 1 ? this.thisStep : 0.0F;
        this.thisStep = 0.0F;
        this.tick = now;
    }

    public float[] step(float fromYaw, float fromPitch, float toYaw, float toPitch,
                        float maxStep, float accel, int randomPercent, boolean l1) {
        return step(fromYaw, fromPitch, toYaw, toPitch, maxStep, accel, randomPercent, l1,
                EnumSet.noneOf(Feature.class));
    }

    public enum Feature { NOISE, CURVE, EASE }

    private double bow;
    private final Random noise = new Random();

    public float[] step(float fromYaw, float fromPitch, float toYaw, float toPitch,
                        float maxStep, float accel, int randomPercent, boolean l1, Set<Feature> features) {
        roll();
        float deltaYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float deltaPitch = toPitch - fromPitch;
        float total = measure(deltaYaw, deltaPitch, l1);
        if (total < 0.05F) {
            return new float[]{fromYaw, fromPitch};
        }
        Rotations settings = features.isEmpty() ? null : Rotations.active();
        float step = maxStep;
        if (settings != null && features.contains(Feature.NOISE) && settings.speedNoise.getValue() > 0) {
            double sd = settings.speedNoise.getValue() / 100.0;
            float drawn = (float) Math.max(Math.max(0.2, 1.0 - 3.0 * sd),
                    Math.min(1.0, 1.0 - sd + this.noise.nextGaussian() * sd));
            this.speedFactor += (drawn - this.speedFactor) * 0.35F;
            step *= this.speedFactor;
        } else if (randomPercent > 0) {
            float drawn = 1.0F - (float) RandomUtil.nextDouble(0.0, randomPercent / 100.0);
            this.speedFactor += (drawn - this.speedFactor) * 0.35F;
            step *= this.speedFactor;
        }
        if (accel > 0.0F) {
            step = Math.min(step, this.lastStep + accel);
        }
        if (settings != null && features.contains(Feature.EASE) && settings.ease.getValue()) {
            float zone = settings.easeZone.getValue();
            if (total < zone) {
                step = Math.min(step, Math.max(settings.easeFloor.getValue(), step * total / zone));
            }
        }
        float scale = total <= step ? 1.0F : step / total;
        float nextYaw = fromYaw + deltaYaw * scale;
        float nextPitch = fromPitch + deltaPitch * scale;
        if (settings != null && features.contains(Feature.CURVE)) {
            double keep = settings.curveSmooth.getValue() / 100.0;
            this.bow = this.bow * keep + this.noise.nextGaussian() * Math.sqrt(1.0 - keep * keep);
            if (scale < 1.0F && settings.curve.getValue() > 0) {
                float moveYaw = deltaYaw * scale;
                float movePitch = deltaPitch * scale;
                float length = (float) Math.sqrt(moveYaw * moveYaw + movePitch * movePitch);
                if (length > 1.0F) {
                    float side = (float) (this.bow * settings.curve.getValue() / 100.0) * length;
                    nextYaw -= movePitch / length * side;
                    nextPitch += moveYaw / length * side;
                }
            }
        }
        float[] out = quantize(fromYaw, fromPitch, nextYaw, nextPitch);
        this.thisStep += measure(out[0] - fromYaw, out[1] - fromPitch, l1);
        return out;
    }

    private static float measure(float yaw, float pitch, boolean l1) {
        return l1 ? Math.abs(yaw) + Math.abs(pitch) : (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    public static float[] quantize(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        double unit = RotationUtil.gcd();
        float deltaYaw = MathHelper.wrapAngleTo180_float(toYaw - fromYaw);
        float deltaPitch = toPitch - fromPitch;
        float yaw = fromYaw + (float) (Math.round(deltaYaw / unit) * unit);
        float pitch = fromPitch + (float) (Math.round(deltaPitch / unit) * unit);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    public static float angle(float yawA, float pitchA, float yawB, float pitchB) {
        float yaw = MathHelper.wrapAngleTo180_float(yawA - yawB);
        float pitch = pitchA - pitchB;
        return (float) Math.sqrt(yaw * yaw + pitch * pitch);
    }

    public static Vec3 direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    public static float[] rotationsTo(Vec3 eyes, Vec3 point, float nearYaw) {
        double dx = point.xCoord - eyes.xCoord;
        double dy = point.yCoord - eyes.yCoord;
        double dz = point.zCoord - eyes.zCoord;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{nearYaw + MathHelper.wrapAngleTo180_float(yaw - nearYaw),
                MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    public static Vec3 nearestOnBox(AxisAlignedBB box, Vec3 eyes, float yaw, float pitch) {
        Vec3 look = direction(yaw, pitch);
        Vec3 point = new Vec3((box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0);
        for (int i = 0; i < 4; i++) {
            double t = Math.max(0.0, (point.xCoord - eyes.xCoord) * look.xCoord
                    + (point.yCoord - eyes.yCoord) * look.yCoord
                    + (point.zCoord - eyes.zCoord) * look.zCoord);
            point = RotationUtil.closestPointOnAabb(box, eyes.addVector(look.xCoord * t, look.yCoord * t, look.zCoord * t));
        }
        return point;
    }

    public static AxisAlignedBB aimBox(AxisAlignedBB box, double inset, double low, double high) {
        double w = (box.maxX - box.minX) * inset;
        double d = (box.maxZ - box.minZ) * inset;
        double h = box.maxY - box.minY;
        return new AxisAlignedBB(box.minX + w, box.minY + h * low, box.minZ + d,
                box.maxX - w, box.minY + h * high, box.maxZ - d);
    }
}
