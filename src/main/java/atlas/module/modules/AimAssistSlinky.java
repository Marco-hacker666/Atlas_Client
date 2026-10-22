package atlas.module.modules;

import atlas.util.ItemUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.Random;

/**
 * AimAssist's Regular, Linear and Lock On styles, after OpenSlinky's Aim
 * Assist (2026-10-20, plan item 6; owner: "follow Slinky's aim assist").
 *
 * The target is chosen once a tick, the view turned every frame by the time
 * the frame took, the way a hand moves a mouse:
 *   aim point   the target's eye height, pulled toward the point of its box
 *               nearest this player by hitbox-h / hitbox-v (less so up close),
 *               its position carried toward where the server last put it by
 *               predict, plus a slowly wandering offset (randomization) from a
 *               smooth noise, so the point never sits still;
 *   dead zone   nothing is turned for the last dead-zone degrees;
 *   Regular     each frame (speed/2)^2 x what is left x the frame's seconds:
 *               fast when far, easing in;
 *   Linear      at most 5 x (speed/2)^2 degrees a second: one steady speed;
 *   Lock On     all that is left, every frame (sideways; up and down only with
 *               lock-vertical) -- the view stays on the target (owner: "Lock On
 *               as Slinky does it; that is what the mode is");
 *   texture     the speeds drift +-10 % over a second and +-5 % over a minute;
 *               up and down is damped while the sideways error is large; a
 *               little up-and-down noise rides on sideways turns; after the
 *               button goes down, Regular and Linear wait one or two ticks.
 * Turns go on the mouse grid (sensitivity GCD) with the remainder carried to
 * the next frame, and are applied as mouse movement (setAngles), so the
 * camera's previous angle moves with them.
 */
final class AimAssistSlinky {
    private static final Minecraft mc = Minecraft.getMinecraft();
    static final int REGULAR = 0;
    static final int LINEAR = 1;
    static final int LOCK_ON = 2;

    private final AimAssist module;
    private final Random random = new Random();
    private final Drift pitchDamping = new Drift();
    private final Drift pitchNoise = new Drift();
    private final Drift horizontalFactor = new Drift();
    private final Drift verticalFactor = new Drift();
    private final Drift speedFactor = new Drift();
    private EntityPlayer target;
    private boolean targetActive;
    private Vec3 previousAim = new Vec3(0.0, 0.0, 0.0);
    private Vec3 currentAim = this.previousAim;
    private int graceTicks;
    private long lastFrame;
    private float carryYaw;
    private float carryPitch;
    private float lastYaw = Float.NaN;
    private float lastPitch = Float.NaN;

    AimAssistSlinky(AimAssist module) {
        this.module = module;
    }

    private int style() {
        return this.module.style.getValue() - 1;
    }

    void reset() {
        this.target = null;
        this.targetActive = false;
        this.carryYaw = 0.0F;
        this.carryPitch = 0.0F;
        this.lastYaw = Float.NaN;
        this.graceTicks = 0;
    }

    private static boolean focused() {
        return mc.thePlayer != null && mc.theWorld != null && mc.inGameHasFocus && mc.currentScreen == null;
    }

    private static boolean physicalAttack() {
        int code = mc.gameSettings.keyBindAttack.getKeyCode();
        return code < 0 ? Mouse.isButtonDown(code + 100) : code > 0 && Keyboard.isKeyDown(code);
    }

    // ---- target, once a tick ----

    private static final class Score {
        double distance;
        float angle;
        float health = Float.MAX_VALUE;
        int hurtTime = Integer.MAX_VALUE;

        Score(double distance, float angle) {
            this.distance = distance;
            this.angle = angle;
        }
    }

    void tick() {
        if (!focused()) {
            this.target = null;
            this.targetActive = false;
            return;
        }
        boolean attack = physicalAttack();
        if (this.module.requireClick.getValue()) {
            if (!attack) {
                this.graceTicks = (this.random.nextBoolean() ? 1 : 0) + 1;
                this.target = null;
                this.targetActive = false;
                return;
            }
            if (this.graceTicks != 0 && this.style() < LOCK_ON) {
                this.graceTicks--;
                this.target = null;
                this.targetActive = false;
                return;
            }
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (this.module.stopOnBlockBreak.getValue() && attack && hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            this.targetActive = false;
            return;
        }
        if (this.module.weaponOnly.getValue() && !ItemUtil.hasRawUnbreakingEnchant()
                && !(this.module.allowTools.getValue() && ItemUtil.isHoldingTool())) {
            this.target = null;
            this.targetActive = false;
            return;
        }
        Vec3 eye = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + 1.62, mc.thePlayer.posZ);
        float viewYaw = mc.thePlayer.rotationYaw;
        Score best = new Score(this.module.range.getValue(), (float) this.module.fov.getValue());
        boolean single = this.module.targetMode.getValue() == 0;
        Vec3[] picked = new Vec3[1];
        boolean[] enabled = new boolean[1];
        if (single && this.target != null) {
            if (this.consider(this.target, eye, viewYaw, true, best, picked, enabled)) {
                this.previousAim = this.currentAim;
                this.currentAim = picked[0];
                return;
            }
            this.target = null;
            this.targetActive = false;
        }
        EntityPlayer chosen = null;
        Vec3 chosenPoint = null;
        boolean chosenEnabled = false;
        for (EntityPlayer candidate : mc.theWorld.playerEntities) {
            if (candidate == mc.thePlayer) {
                continue;
            }
            if (this.consider(candidate, eye, viewYaw, false, best, picked, enabled)) {
                chosen = candidate;
                chosenPoint = picked[0];
                chosenEnabled = enabled[0];
            }
        }
        if (chosen == null) {
            this.target = null;
            this.targetActive = false;
            return;
        }
        this.previousAim = this.target == chosen ? this.currentAim : chosenPoint;
        this.currentAim = chosenPoint;
        this.targetActive = chosenEnabled;
        this.target = chosen;
    }

    private boolean consider(EntityPlayer candidate, Vec3 eye, float viewYaw, boolean locked, Score best,
                             Vec3[] pointOut, boolean[] enabledOut) {
        if (candidate.isDead || candidate.isPlayerSleeping() || candidate.deathTime > 0) {
            return false;
        }
        if (this.module.ignoreInvisible.getValue() && candidate.isInvisibleToPlayer(mc.thePlayer)) {
            return false;
        }
        if (TeamUtil.isFriend(candidate) || !TargetFilter.accepts(candidate)
                || this.module.team.getValue() && TeamUtil.isSameTeam(candidate)
                || this.module.botChecks.getValue() && TeamUtil.isBot(candidate)) {
            return false;
        }
        Vec3 position = new Vec3(candidate.posX, candidate.posY, candidate.posZ);
        float prediction = this.module.predict.getValue();
        if (prediction > 0.0F && candidate instanceof EntityOtherPlayerMP) {
            Vec3 server = new Vec3(candidate.serverPosX / 32.0, candidate.serverPosY / 32.0, candidate.serverPosZ / 32.0);
            position = lerp(position, server, prediction);
        }
        AxisAlignedBB box = candidate.getEntityBoundingBox().expand(0.1, 0.1, 0.1);
        Vec3 nearest = new Vec3(clamp(box.minX, box.maxX, eye.xCoord), clamp(box.minY, box.maxY, eye.yCoord),
                clamp(box.minZ, box.maxZ, eye.zCoord));
        double distance = eye.distanceTo(nearest);
        if (distance > best.distance) {
            return false;
        }
        boolean enabled = true;
        Vec3 point = aimPoint(eye, position, nearest, distance, this.module.hitboxH.getValue(),
                this.module.hitboxV.getValue(), this.module.randomization.getValue());
        float angle = Math.abs(MathHelper.wrapAngleTo180_float(angles(eye, point)[0] - viewYaw));
        if (angle > best.angle) {
            if (!locked) {
                return false;
            }
            enabled = this.module.exceedFov.getValue();
        }
        float health = candidate.getHealth();
        if (Float.isNaN(health)) {
            health = 20.0F;
        }
        if (health > best.health || candidate.hurtTime > best.hurtTime) {
            return false;
        }
        if (this.module.lineOfSight.getValue() && mc.theWorld.rayTraceBlocks(eye, point, false, true, true) != null) {
            return false;
        }
        if (this.module.targetMode.getValue() != 0) {
            switch (this.module.sortBy.getValue()) {
                case 0:
                    best.distance = distance;
                    break;
                case 1:
                    best.health = health;
                    break;
                case 2:
                    if (candidate.hurtTime == best.hurtTime && this.random.nextBoolean()) {
                        return false;
                    }
                    best.hurtTime = candidate.hurtTime;
                    break;
                default:
                    best.angle = angle;
                    break;
            }
        } else {
            best.angle = angle;
        }
        pointOut[0] = point;
        enabledOut[0] = enabled;
        if (locked) {
            this.targetActive = enabled;
        }
        return true;
    }

    /** The point to turn to: eye height, pulled toward the nearest point of the box, with a wandering offset. */
    static Vec3 aimPoint(Vec3 localEye, Vec3 predicted, Vec3 nearest, double distance, float horizontal, float vertical,
                         float randomization) {
        Vec3 targetEye = new Vec3(predicted.xCoord, predicted.yCoord + 1.62, predicted.zCoord);
        double x = targetEye.xCoord;
        double z = targetEye.zCoord;
        if (horizontal > 0.0F) {
            float fraction = distance < 3.5 ? (float) (horizontal * (distance / 3.5)) : horizontal;
            Vec3 point = lerp(targetEye, nearest, fraction);
            x = point.xCoord;
            z = point.zCoord;
        }
        double y = targetEye.yCoord + (nearest.yCoord - targetEye.yCoord) * vertical;
        float input = (float) ((localEye.xCoord + localEye.yCoord + localEye.zCoord + x + y + z) * 0.1);
        double scale = randomization;
        if (distance < 3.5) {
            scale = randomization * (distance / 3.5 * 0.9 + 0.1);
        }
        return new Vec3(x + scale * 0.5 * noise(input), y + scale * 0.5 * noise(10000.0F + input),
                z + scale * 0.5 * noise(20000.0F + input));
    }

    // ---- the turn, once a frame ----

    void frame(float partial) {
        long now = System.nanoTime();
        float seconds = this.lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        if (!focused()) {
            this.lastYaw = Float.NaN;
            return;
        }
        float currentYaw = mc.thePlayer.rotationYaw;
        float currentPitch = mc.thePlayer.rotationPitch;
        /* What the player's own hand did since the last frame (require-mouse-move). */
        float manualYaw = Float.isNaN(this.lastYaw) ? 0.0F : MathHelper.wrapAngleTo180_float(currentYaw - this.lastYaw);
        float manualPitch = Float.isNaN(this.lastYaw) ? 0.0F : currentPitch - this.lastPitch;
        if (!this.targetActive || this.target == null || seconds <= 0.0F) {
            this.lastYaw = currentYaw;
            this.lastPitch = currentPitch;
            return;
        }
        Vec3 eye = lerp(new Vec3(mc.thePlayer.prevPosX, mc.thePlayer.prevPosY, mc.thePlayer.prevPosZ),
                new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ), partial).addVector(0.0, 1.62, 0.0);
        float[] desired = angles(eye, lerp(this.previousAim, this.currentAim, partial));
        float yaw = MathHelper.wrapAngleTo180_float(desired[0] - currentYaw);
        float pitch = desired[1] - currentPitch;
        float[] step = this.aimStep(yaw, pitch, seconds, manualYaw, manualPitch);
        this.apply(step[0], step[1]);
        this.lastYaw = mc.thePlayer.rotationYaw;
        this.lastPitch = mc.thePlayer.rotationPitch;
    }

    float[] aimStep(float yaw, float pitch, float time, float manualYaw, float manualPitch) {
        double now = System.nanoTime() / 1.0E9;
        float deadZone = this.module.deadZone.getValue();
        yaw = yaw > 0.0F ? Math.max(yaw - deadZone, 0.0F) : Math.min(yaw + deadZone, 0.0F);
        pitch = pitch > 0.0F ? Math.max(pitch - deadZone, 0.0F) : Math.min(pitch + deadZone, 0.0F);
        int style = this.style();
        if (style != LOCK_ON) {
            float draw = this.pitchDamping.sample(40.0F, 120.0F, 5.0, now, this.random);
            pitch *= Math.max((float) (-1.0 / draw * Math.abs(yaw) + 1.0), 0.0F);
        }
        if (style == LOCK_ON) {
            if (!this.module.lockVertical.getValue()) {
                pitch = 0.0F;
            }
        } else {
            float hHalf = this.module.speedH.getValue() * 0.5F;
            float vHalf = this.module.speedV.getValue() * 0.5F;
            float yawFactor = this.horizontalFactor.sample(0.9F, 1.1F, 1.0, now, this.random) * hHalf * hHalf;
            float pitchFactor = this.verticalFactor.sample(0.9F, 1.1F, 1.0, now, this.random) * vHalf * vHalf;
            float global = this.speedFactor.sample(0.95F, 1.05F, 60.0, now, this.random);
            if (style == LINEAR) {
                float yawStep = yawFactor * 5.0F * global * time;
                yaw = Math.max(-yawStep, Math.min(yawStep, yaw));
                float pitchStep = pitchFactor * 5.0F * global * time;
                pitch = Math.max(-pitchStep, Math.min(pitchStep, pitch));
            } else {
                float rawYaw = yawFactor * yaw * global * time;
                float rawPitch = pitchFactor * pitch * global * time;
                if (Math.abs(rawYaw) <= Math.abs(yaw)) {
                    yaw = rawYaw;
                }
                if (Math.abs(rawPitch) <= Math.abs(pitch)) {
                    pitch = rawPitch;
                }
            }
            float bound = Math.abs(yaw) * 0.1F;
            pitch += this.pitchNoise.sample(-bound, bound, 1.0, now, this.random);
        }
        if (this.module.requireMouseMove.getValue()) {
            /* Never turn more than the hand did this frame. */
            if (Math.abs(manualYaw) < Math.abs(yaw) && yaw != 0.0F) {
                yaw *= Math.abs(manualYaw) / Math.abs(yaw);
            }
            if (Math.abs(manualPitch) < Math.abs(pitch) && pitch != 0.0F) {
                pitch *= Math.abs(manualPitch) / Math.abs(pitch);
            }
        }
        return new float[]{yaw, pitch};
    }

    /** As mouse movement, on the sensitivity grid, the remainder carried to the next frame. */
    private void apply(float yaw, float pitch) {
        float f = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float unit = f * f * f * 1.2F;
        this.carryYaw += yaw;
        this.carryPitch += pitch;
        float yawCounts = Math.round(this.carryYaw / unit);
        float pitchCounts = Math.round(this.carryPitch / unit);
        this.carryYaw -= yawCounts * unit;
        this.carryPitch -= pitchCounts * unit;
        if (yawCounts != 0.0F || pitchCounts != 0.0F) {
            /* setAngles turns by 0.15 per unit and subtracts pitch, as the mouse does. */
            mc.thePlayer.setAngles(yawCounts * unit / 0.15F, -pitchCounts * unit / 0.15F);
        }
    }

    // ---- helpers ----

    private static double clamp(double min, double max, double v) {
        return v < min ? min : (v > max ? max : v);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.xCoord + (b.xCoord - a.xCoord) * t, a.yCoord + (b.yCoord - a.yCoord) * t,
                a.zCoord + (b.zCoord - a.zCoord) * t);
    }

    static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.xCoord - from.xCoord;
        double dy = to.yCoord - from.yCoord;
        double dz = to.zCoord - from.zCoord;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    /** Smooth 1D gradient noise in about [-1, 1]: nearby inputs give nearby values. */
    static float noise(float x) {
        int i = MathHelper.floor_float(x);
        float t = x - i;
        float g0 = gradient(i);
        float g1 = gradient(i + 1);
        float fade = t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
        return 2.0F * (g0 * t + fade * (g1 * (t - 1.0F) - g0 * t));
    }

    private static float gradient(int i) {
        int h = i * 0x27d4eb2d;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 32767.5F - 1.0F;
    }

    /** A value wandering between low and high: a straight line from one random point to the next over duration. */
    static final class Drift {
        private double timestamp = -1.0E9;
        private float left;
        private float right;

        float sample(float low, float high, double duration, double now, Random random) {
            if (now > this.timestamp + duration) {
                this.timestamp = now;
                if (this.left == 0.0F && this.right == 0.0F) {
                    do {
                        this.left = random.nextFloat();
                        this.right = random.nextFloat();
                    } while (Math.abs(this.left - this.right) < 0.2F);
                } else {
                    this.left = this.right;
                    do {
                        this.right = random.nextFloat();
                    } while (Math.abs(this.left - this.right) < 0.2F);
                }
            }
            double phase = (now - this.timestamp) / duration;
            double curve = (this.right - this.left) * phase + this.left;
            return (float) ((high - low) * curve + low);
        }
    }
}
