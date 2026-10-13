package myau.module.modules;

import myau.event.EventTarget;
import myau.events.PlayerUpdateEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;

public class KeepSprint extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // ========== Original fields (kept for compatibility; other modules use them) ==========
    public final PercentProperty slowdown = new PercentProperty("slowdown", 0);
    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false);
    public final BooleanProperty reachOnly = new BooleanProperty("reach-only", false);

    // ========== Fields added from Card (every feature kept) ==========
    // PercentProperty takes an int: 60 means 60%, the original 0.6
    public final PercentProperty slowDownVelocity = new PercentProperty("Hit Slow Down During Velocity", 60);
    public final PercentProperty slowDownNormal = new PercentProperty("Hit Slow Down Normal", 60);
    public final PercentProperty bufferDecrease = new PercentProperty("Buffer Decrease", 100);   // 1.0 → 100%
    public final PercentProperty maxBuffer = new PercentProperty("Max Buffer", 500);            // 5.0 → 500%

    public final BooleanProperty sprintSlowDownVelocity = new BooleanProperty("Velocity Hit Sprint", false);
    public final BooleanProperty sprintSlowDownNormal = new BooleanProperty("Normal Hit Sprint", false);
    public final BooleanProperty bufferAbuse = new BooleanProperty("Buffer Abuse", false);
    public final BooleanProperty onlyInAir = new BooleanProperty("Only In Air", false);

    // ========== State variables from the original Card ==========
    public boolean hitFlag;      // was By
    public double bufferCount;   // was Bz

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

        if (mc.thePlayer.hurtTime > 0) {
            mc.thePlayer.motionX *= velocityFactor;
            mc.thePlayer.motionZ *= velocityFactor;
            mc.thePlayer.setSprinting(sprintSlowDownVelocity.getValue());
        } else {
            mc.thePlayer.motionX *= normalFactor;
            mc.thePlayer.motionZ *= normalFactor;
            mc.thePlayer.setSprinting(sprintSlowDownNormal.getValue());
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
