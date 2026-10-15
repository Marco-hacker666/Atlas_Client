package myau.module.modules;

import myau.event.EventTarget;
import myau.events.AttackEvent;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.property.IntRange;
import myau.property.properties.BooleanProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;

import java.util.Random;

public class KeepSprint extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ========== Original fields (kept for compatibility; other modules use them) ==========
    public final PercentProperty slowdown = new PercentProperty("slowdown", 0);
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false);
    public final BooleanProperty reachOnly = new BooleanProperty("reach-only", false);

    // ========== Fields added from Card (every feature kept) ==========
    // PercentProperty takes an int: 60 means 60%, the original 0.6
    /* The Card hit handling below is opt-in (2026-10-16), and its defaults
       are now 100% with the sprint kept (were 60%, sprint dropped). It runs
       only on a tick with a hit, where the old values would put back exactly
       the slowdown KeepSprint exists to remove; saved configs still hold
       them, so behind this switch, off by default, they do nothing until
       someone turns it on. */
    public final BooleanProperty hitControl = new BooleanProperty("hit-control", false);
    public final PercentProperty slowDownVelocity = new PercentProperty("Hit Slow Down During Velocity", 100, () -> this.hitControl.getValue());
    public final PercentProperty slowDownNormal = new PercentProperty("Hit Slow Down Normal", 100, () -> this.hitControl.getValue());
    public final PercentProperty bufferDecrease = new PercentProperty("Buffer Decrease", 100, () -> this.hitControl.getValue());   // 1.0 → 100%
    public final PercentProperty maxBuffer = new PercentProperty("Max Buffer", 500, () -> this.hitControl.getValue());            // 5.0 → 500%

    public final BooleanProperty sprintSlowDownVelocity = new BooleanProperty("Velocity Hit Sprint", true, () -> this.hitControl.getValue());
    public final BooleanProperty sprintSlowDownNormal = new BooleanProperty("Normal Hit Sprint", true, () -> this.hitControl.getValue());
    public final BooleanProperty bufferAbuse = new BooleanProperty("Buffer Abuse", false, () -> this.hitControl.getValue());
    public final BooleanProperty onlyInAir = new BooleanProperty("Only In Air", false, () -> this.hitControl.getValue());

    // ========== LiquidBounce NextGen's ModuleKeepSprint (2026-10-16) ==========
    /* How much speed a hit leaves, in percent, drawn from the range on each
       hit: 100 keeps all of it, 60 is vanilla. Motion when not hurt,
       MotionWhenHurt while our hurtTime is inside HurtTime. Chance: how often
       KeepSprint acts at all; a hit it skips is a vanilla hit (slowed to 60%,
       sprint dropped). */
    public final IntRange motion = new IntRange("motion", 100, 100, 0, 100);
    public final IntRange motionWhenHurt = new IntRange("motion-when-hurt", 100, 100, 0, 100);
    public final IntRange hurtTime = new IntRange("hurt-time", 1, 10, 1, 10);
    public final PercentProperty chance = new PercentProperty("chance", 100);

    // ========== State variables from the original Card ==========
    public boolean hitFlag;      // was By
    public double bufferCount;   // was Bz

    /* A hit this tick (AttackEvent), for the Card handling below. */
    private boolean attackedThisTick;
    /* NextGen's "sprinting": one slowdown per tick. With the sprint kept,
       every hit in a tick would slow us again; vanilla only slows the first
       (it drops the sprint, and the next hit has no sprint knockback). */
    private int lastSlowTick = -1;
    /* This hit's decision, shared by the motion and the sprint hooks of one
       attackTargetEntityWithCurrentItem call. */
    private boolean lastHitKept = true;
    /* Vanilla's hit has the 0.6 twice (motionX, then motionZ); the mixin
       asks once per axis. The first ask decides the hit, the second gets the
       same answer. */
    private boolean axisPending;
    private double axisMotion = 1.0;
    private final Random random = new Random();

    public KeepSprint() {
        super("KeepSprint", false, false, "Keeps you sprinting through attacks and slowdown.");
    }

    /**
     * The buffer-abuse counter and the hit flag are module state, not player
     * state: they have to start clean next time. Leaving bufferCount where it
     * stopped meant a re-enabled KeepSprint began part-way up its buffer.
     */
    @Override
    public void onDisabled() {
        this.hitFlag = false;
        this.bufferCount = 0.0;
        this.attackedThisTick = false;
        this.lastSlowTick = -1;
        this.lastHitKept = true;
        this.axisPending = false;
    }

    /**
     * The speed this hit leaves, as a factor of the current speed (1.0 keeps
     * all, 0.6 is vanilla), for the 0.6 in a hit. Decides the hit: a hit the
     * chance skips is vanilla (0.6, and shouldKeepThisHitSprint says so).
     * Only the first hit of a tick slows; later ones in the same tick keep
     * their speed, as in vanilla.
     */
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
        /* The original "slowdown" setting (0 by default) still applies, so
           a config that set it keeps its effect. */
        double legacy = 0.6 + 0.4 * (1.0 - this.slowdown.getValue().doubleValue() / 100.0);
        return kept * legacy;
    }

    /** hitMotion() for the vanilla hit's two 0.6 constants: one decision for both axes. */
    public double hitMotionForAxis() {
        if (!this.axisPending) {
            this.axisMotion = this.hitMotion();
            this.axisPending = true;
        } else {
            this.axisPending = false;
        }
        return this.axisMotion;
    }

    /** Whether the hit hitMotion() just decided keeps the sprint. */
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

        // original groundOnly logic
        if (groundOnly.getValue() && !mc.thePlayer.onGround) {
            return false;
        }

        // original reachOnly logic
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

    /**
     * The full Card behaviour: runs every frame (call it from the client's main loop)
     */
    @EventTarget
    public void onUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled()) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        /* Only on a tick with a hit (2026-10-16). This ran on every tick:
           with its defaults (60%, sprint off) KeepSprint slowed the player
           to 60% and dropped the sprint all the time, fighting or not, and
           the server saw nearly every aura hit as a hit without sprint. */
        if (!this.attackedThisTick) {
            return;
        }
        this.attackedThisTick = false;
        if (!this.hitControl.getValue()) {
            return;
        }

        // -- on the ground with onlyInAir set: nothing to do --
        if (mc.thePlayer.onGround && onlyInAir.getValue()) {
            return;
        }

        // -- buffer (Buffer Abuse) logic --
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

        // -- slow-down and sprint control, hurt or not --
        // percent to a fraction (60 -> 0.6)
        double velocityFactor = slowDownVelocity.getValue().doubleValue() / 100.0;
        double normalFactor = slowDownNormal.getValue().doubleValue() / 100.0;

        /* The sprint options can only drop the sprint. Setting it from them
           also started a sprint that was not there (walking backwards, no
           sprint key), which is a sprint the server should never see. */
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

    /**
     * The slow-down to use now (for callers such as PlayerUtil)
     * Compatible with the original slowDown field; returns a percentage
     */
    public double getCurrentSlowdown() {
        if (mc.thePlayer == null) return slowdown.getValue().doubleValue();
        if (mc.thePlayer.hurtTime > 0) {
            return slowDownVelocity.getValue().doubleValue();
        } else {
            return slowDownNormal.getValue().doubleValue();
        }
    }
                }
