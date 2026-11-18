package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.AttackEvent;
import atlas.events.PlayerUpdateEvent;
import atlas.module.Module;
import atlas.property.IntRange;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;

import java.util.Random;

public class KeepSprint extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty slowdown = new PercentProperty("slowdown", 0);
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false);
    public final BooleanProperty reachOnly = new BooleanProperty("reach-only", false);

    public final BooleanProperty hitControl = new BooleanProperty("hit-control", false);
    public final PercentProperty slowDownVelocity = new PercentProperty("Hit Slow Down During Velocity", 100, () -> this.hitControl.getValue());
    public final PercentProperty slowDownNormal = new PercentProperty("Hit Slow Down Normal", 100, () -> this.hitControl.getValue());
    public final PercentProperty bufferDecrease = new PercentProperty("Buffer Decrease", 100, () -> this.hitControl.getValue());
    public final PercentProperty maxBuffer = new PercentProperty("Max Buffer", 500, () -> this.hitControl.getValue());

    public final BooleanProperty sprintSlowDownVelocity = new BooleanProperty("Velocity Hit Sprint", true, () -> this.hitControl.getValue());
    public final BooleanProperty sprintSlowDownNormal = new BooleanProperty("Normal Hit Sprint", true, () -> this.hitControl.getValue());
    public final BooleanProperty bufferAbuse = new BooleanProperty("Buffer Abuse", false, () -> this.hitControl.getValue());
    public final BooleanProperty onlyInAir = new BooleanProperty("Only In Air", false, () -> this.hitControl.getValue());

    public final IntRange motion = new IntRange("motion", 100, 100, 0, 100);
    public final IntRange motionWhenHurt = new IntRange("motion-when-hurt", 100, 100, 0, 100);
    public final IntRange hurtTime = new IntRange("hurt-time", 1, 10, 1, 10);
    public final PercentProperty chance = new PercentProperty("chance", 100);

    public boolean hitFlag;
    public double bufferCount;

    private boolean attackedThisTick;

    private int lastSlowTick = -1;

    private boolean lastHitKept = true;

    private boolean axisPending;
    private double axisMotion = 1.0;
    private final Random random = new Random();

    public KeepSprint() {
        super("KeepSprint", false, false, "Keeps you sprinting through attacks and slowdown.");
    }

    @Override
    public void onDisabled() {
        this.hitFlag = false;
        this.bufferCount = 0.0;
        this.attackedThisTick = false;
        this.lastSlowTick = -1;
        this.lastHitKept = true;
        this.axisPending = false;
    }

    public double hitMotion() {
        if (mc.thePlayer == null) {
            this.lastHitKept = true;
            return 1.0;
        }
        if (this.chance.getValue() < 100 && this.random.nextInt(100) >= this.chance.getValue()) {
            this.lastHitKept = false;
            return 0.6;
        }
        this.lastHitKept = true;
        int tick = mc.thePlayer.ticksExisted;
        if (tick == this.lastSlowTick) {
            return 1.0;
        }
        this.lastSlowTick = tick;
        int hurt = mc.thePlayer.hurtTime;
        boolean hurtWindow = hurt >= this.hurtTime.low() && hurt <= this.hurtTime.high();
        double kept = (hurtWindow ? this.motionWhenHurt : this.motion).random() / 100.0;

        double legacy = 0.6 + 0.4 * (1.0 - this.slowdown.getValue().doubleValue() / 100.0);
        return kept * legacy;
    }

    public double hitMotionForAxis() {
        if (!this.axisPending) {
            this.axisMotion = this.hitMotion();
            this.axisPending = true;
        } else {
            this.axisPending = false;
        }
        return this.axisMotion;
    }

    public boolean shouldKeepThisHitSprint() {
        return this.lastHitKept;
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (this.isEnabled() && !event.isCancelled()) {
            this.attackedThisTick = true;
        }
    }

    @Override
    public boolean shouldKeepSprint() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }

        if (groundOnly.getValue() && !mc.thePlayer.onGround) {
            return false;
        }

        if (reachOnly.getValue()) {
            if (mc.objectMouseOver == null || mc.objectMouseOver.hitVec == null) {
                return false;
            }
            double distance = mc.objectMouseOver.hitVec.distanceTo(
                    mc.getRenderViewEntity().getPositionEyes(1.0F)
            );
            return distance > 3.0;
        }

        return true;
    }

    @EventTarget
    public void onUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        if (!this.attackedThisTick) {
            return;
        }
        this.attackedThisTick = false;
        if (!this.hitControl.getValue()) {
            return;
        }

        if (mc.thePlayer.onGround && onlyInAir.getValue()) {
            return;
        }

        if (bufferAbuse.getValue()) {
            if (bufferCount < maxBuffer.getValue() && !hitFlag) {
                bufferCount++;
            } else {
                if (bufferCount > 0) {
                    bufferCount = Math.max(0, bufferCount - bufferDecrease.getValue());
                    hitFlag = true;
                    return;
                }
                hitFlag = false;
            }
        } else {
            bufferCount = 0;
            hitFlag = false;
        }

        double velocityFactor = slowDownVelocity.getValue().doubleValue() / 100.0;
        double normalFactor = slowDownNormal.getValue().doubleValue() / 100.0;

        if (mc.thePlayer.hurtTime > 0) {
            mc.thePlayer.motionX *= velocityFactor;
            mc.thePlayer.motionZ *= velocityFactor;
            if (!sprintSlowDownVelocity.getValue()) {
                mc.thePlayer.setSprinting(false);
            }
        } else {
            mc.thePlayer.motionX *= normalFactor;
            mc.thePlayer.motionZ *= normalFactor;
            if (!sprintSlowDownNormal.getValue()) {
                mc.thePlayer.setSprinting(false);
            }
        }
    }

    public double getCurrentSlowdown() {
        if (mc.thePlayer == null) return slowdown.getValue().doubleValue();
        if (mc.thePlayer.hurtTime > 0) {
            return slowDownVelocity.getValue().doubleValue();
        } else {
            return slowDownNormal.getValue().doubleValue();
        }
    }
                }
