package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LeftClickMouseEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.KeyBindUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP", "ACTIVE"});

    /* ACTIVE, after Vape's HitSelect "Active" (studied 2026-09-25), written
       here from what it does rather than how.

       Closing in on someone, most clicks cannot do anything: the target is
       inside the ten ticks after a hit and the server refuses the damage.
       They still cost -- every attack while sprinting cuts this player's own
       speed to 60% and ends the sprint on the client -- so the chase is
       slower for them. ACTIVE lets through the click that can land (the
       target's hurt time within the round trip HitTimer measures, or one
       tick short of it) and drops the rest before they happen: no swing, no
       attack, no slowdown. It cancels the click itself rather than the
       packet after it, which is what the other three modes do and why they
       have to patch the slowdown up with KeepSprint afterwards.

       Only while moving toward the target; backing off or strafing, every
       click goes through. After being knocked back, the preference decides:
       KB_REDUCTION lets every click through until landing (a sprint hit
       takes 40% off this player's own motion, knockback included);
       CRITICALS holds clicks while still rising and lets them go on the way
       down. */
    public final PercentProperty chance = new PercentProperty("chance", 90, () -> this.mode.getValue() == 3);
    public final ModeProperty preference = new ModeProperty("preference", 0,
            new String[]{"KB_REDUCTION", "CRITICALS"}, () -> this.mode.getValue() == 3);

    /* 2026-10-12 (owner, from a player's suggestions), ACTIVE only:

       timing: when the target can be hurt. INVULN_TIMING asks InvulnTiming's
       estimate (the target's hurt time minus the round trip, plus its safety
       margin -- its own settings, whether that module is on or not), so the
       hand and KillAura follow one clock; HIT_TIMER is the rule ACTIVE had.

       burst: the opening is one tick wide and the estimate can be a tick
       off. After clicks were dropped waiting for it, the opening click is
       followed by up to burst-clicks - 1 more, one a tick (never two in a
       tick: the server would refuse the second, and two attacks in one tick
       is a pattern of its own), while the target stays under the crosshair.

       reduce: a sprint hit takes 40% off this player's own motion. Knocked
       back away from where the player is walking (the knockback against the
       movement keys), one hit is made even without a click, once per
       knockback, so less of it carries the player off course. Only on an
       enemy under the crosshair, and only while fighting (a click in the
       last second). Neither burst nor reduce runs while KillAura is on: the
       aura makes its own hits. */
    public final ModeProperty timing = new ModeProperty("timing", 0,
            new String[]{"INVULN_TIMING", "HIT_TIMER"}, () -> this.mode.getValue() == 3);
    public final BooleanProperty burst = new BooleanProperty("burst", true, () -> this.mode.getValue() == 3);
    public final IntProperty burstClicks = new IntProperty("burst-clicks", 2, 2, 4,
            () -> this.mode.getValue() == 3 && this.burst.getValue());
    public final BooleanProperty reduce = new BooleanProperty("reduce", true, () -> this.mode.getValue() == 3);

    /** Burst clicks still to make, one a tick, and on whom. */
    private int burstLeft;
    private int burstTarget = -1;
    /** Clicks dropped since the last one let through: a burst only follows waiting. */
    private int waited;
    /** A click this module pressed this tick: the next click through clickMouse is it. */
    private boolean forcing;
    /** A forced click went through this tick: any further click this tick is dropped. */
    private boolean forcedThisTick;
    private boolean reducedThisKnockback;
    private long lastClickMs;
    /** Ticks after our own knockback still treated as part of it. */
    private static final int VELOCITY_TICKS = 7;
    private volatile int velocityTicks = 0;
    private int sinceVelocity = 0;
    private int lastAllowedTick = -1000;
    private int dropped = 0;

    private boolean sprintState = false;
    private boolean set = false;
    private boolean keepSprintWasEnabled = false;
    private double savedSlowdown = 0.0;

    private int blockedHits = 0;
    private int allowedHits = 0;

    public HitSelect() {
        super("HitSelect", false);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        if (event.getType() == EventType.POST) {
            this.resetMotion();
        } else if (this.velocityTicks > 0) {
            this.sinceVelocity++;
            this.velocityTicks--;
            /* Landed: the knockback is over. Not on the tick it arrived,
               when the player has not yet left the ground. */
            if (this.sinceVelocity > 1 && mc.thePlayer.onGround) {
                this.velocityTicks = 0;
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onClick(LeftClickMouseEvent event) {
        if (this.forcing && event.isCancelled()) {
            /* Something else stopped the click this module pressed. */
            this.forcing = false;
            return;
        }
        if (!this.isEnabled() || this.mode.getValue() != 3 || event.isCancelled()
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.forcing) {
            /* The click this module pressed: it has already been decided. */
            this.forcing = false;
            this.forcedThisTick = true;
            return;
        }
        if (this.forcedThisTick) {
            /* One attack a tick, and this tick already has one. */
            drop(event);
            return;
        }
        this.lastClickMs = System.currentTimeMillis();
        EntityLivingBase target = mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                ? (EntityLivingBase) mc.objectMouseOver.entityHit : null;
        if (this.activeDrops(target)) {
            drop(event);
            if (target != null) {
                this.waited++;
            }
        } else if (target != null) {
            this.waited = 0;
        }
    }

    /** Burst and reduce: at most one click a tick, pressed before vanilla reads the key. */
    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }
        /* A press vanilla never turned into a click (an item in use, a screen
           open) must not mark someone else's click as forced. */
        this.forcing = false;
        this.forcedThisTick = false;
        if (!this.isEnabled() || this.mode.getValue() != 3 || mc.thePlayer == null || mc.theWorld == null
                || mc.currentScreen != null || mc.thePlayer.isUsingItem() || this.auraOn()) {
            this.burstLeft = 0;
            return;
        }
        EntityLivingBase target = mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                ? (EntityLivingBase) mc.objectMouseOver.entityHit : null;
        boolean fighting = System.currentTimeMillis() - this.lastClickMs < 1000L;

        if (this.reduce.getValue() && fighting && this.velocityTicks > 0 && !this.reducedThisKnockback
                && this.sinceVelocity <= 3 && mc.thePlayer.isSprinting() && this.enemy(target)
                && this.knockedOffCourse()) {
            this.reducedThisKnockback = true;
            this.press();
            return;
        }
        if (this.burstLeft > 0) {
            if (this.burst.getValue() && fighting && target != null && target.getEntityId() == this.burstTarget) {
                this.burstLeft--;
                this.press();
            } else {
                this.burstLeft = 0;
            }
        }
    }

    private void press() {
        this.forcing = true;
        KeyBindUtil.pressKeyOnce(mc.gameSettings.keyBindAttack.getKeyCode());
    }

    private boolean auraOn() {
        return atlas.util.Auras.anyEnabled();
    }

    /** A player worth a forced hit: not a teammate, a friend or a bot. */
    private boolean enemy(EntityLivingBase target) {
        if (!(target instanceof EntityPlayer) || target.deathTime > 0) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) target;
        return !TeamUtil.isFriend(player) && !TeamUtil.isSameTeam(player) && !TeamUtil.isBot(player);
    }

    /** The motion carries the player against the way the movement keys point. */
    private boolean knockedOffCourse() {
        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) {
            return false;
        }
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        double ix = -Math.sin(yaw) * forward + Math.cos(yaw) * strafe;
        double iz = Math.cos(yaw) * forward + Math.sin(yaw) * strafe;
        double mx = mc.thePlayer.motionX;
        double mz = mc.thePlayer.motionZ;
        double speed = Math.sqrt(mx * mx + mz * mz);
        if (speed < 0.05) {
            return false;
        }
        return mx * ix + mz * iz < 0.0;
    }

    /**
     * K7 (2026-10-08): KillAura asks before an aura hit. True when ACTIVE
     * would drop this click on {@code target} -- the same decision as for a
     * hand click, so the aura and the mouse never follow two rules. The aura
     * counts a dropped click as a click (its CPS rhythm goes on), as a hand
     * click dropped here is still a click.
     */
    public boolean dropsAuraHit(EntityLivingBase target) {
        if (!this.isEnabled() || this.mode.getValue() != 3 || mc.thePlayer == null || target == null) {
            return false;
        }
        if (this.activeDrops(target)) {
            this.dropped++;
            return true;
        }
        return false;
    }

    /** ACTIVE's rule for one click; target may be null (nothing under the crosshair). */
    private boolean activeDrops(EntityLivingBase target) {
        if (Math.random() * 100.0 >= this.chance.getValue()) {
            return false;
        }
        if (this.velocityTicks > 0) {
            if (this.preference.getValue() == 0) {
                return false;
            }
            if (mc.thePlayer.motionY > 0.0) {
                return true;
            }
            if (mc.thePlayer.onGround) {
                return false;
            }
        }
        if (target == null) {
            return false;
        }
        if (!this.isMovingTowards(mc.thePlayer, target, 90.0)) {
            return false;
        }
        int expected = atlas.management.HitTimer.ticks();
        int now = mc.thePlayer.ticksExisted;
        /* How far from the opening, in ticks: 0 or less is open, (0, 1] is
           the tick before it, which also goes. */
        float until;
        InvulnTiming invuln = InvulnTiming.instance();
        if (this.timing.getValue() == 0 && invuln != null) {
            until = invuln.predict(target);
        } else {
            until = target.hurtTime - expected;
        }
        if (until <= 0.0F) {
            /* The opening: the first click in it goes, and the next only
               once two round trips have passed without it being answered. */
            int spacing = Math.max(2, expected * 2);
            if (now - this.lastAllowedTick >= spacing || now < this.lastAllowedTick) {
                this.lastAllowedTick = now;
                this.armBurst(target);
                return false;
            }
        } else if (until <= 1.0F) {
            this.armBurst(target);
            return false;
        }
        return true;
    }

    /** After waiting, the click that goes is followed by the rest of a burst. */
    private void armBurst(EntityLivingBase target) {
        if (this.burst.getValue() && this.waited > 0 && this.burstLeft == 0) {
            this.burstLeft = this.burstClicks.getValue() - 1;
            this.burstTarget = target.getEntityId();
        }
        this.waited = 0;
    }

    private void drop(LeftClickMouseEvent event) {
        event.setCancelled(true);
        this.dropped++;
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof net.minecraft.network.play.server.S12PacketEntityVelocity
                    && mc.thePlayer != null
                    && ((net.minecraft.network.play.server.S12PacketEntityVelocity) event.getPacket()).getEntityID()
                    == mc.thePlayer.getEntityId()) {
                this.velocityTicks = VELOCITY_TICKS;
                this.sinceVelocity = 0;
                this.reducedThisKnockback = false;
            }
            return;
        }
        if (event.getType() != EventType.SEND) {
            return;
        }

        if (event.getPacket() instanceof C0BPacketEntityAction) {
            C0BPacketEntityAction packet = (C0BPacketEntityAction) event.getPacket();
            switch (packet.getAction()) {
                case START_SPRINTING:
                    this.sprintState = true;
                    break;
                case STOP_SPRINTING:
                    this.sprintState = false;
                    break;
            }
            return;
        }

        if (event.getPacket() instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) event.getPacket();

            if (use.getAction() != C02PacketUseEntity.Action.ATTACK) {
                return;
            }

            Entity target = use.getEntityFromWorld(mc.theWorld);
            if (target == null || target instanceof EntityLargeFireball) {
                return;
            }

            if (!(target instanceof EntityLivingBase)) {
                return;
            }

            EntityLivingBase living = (EntityLivingBase) target;
            boolean allow = true;

            switch (this.mode.getValue()) {
                case 3: // ACTIVE decides at the click, above
                    break;
                case 0: // SECOND
                    allow = this.prioritizeSecondHit(mc.thePlayer, living);
                    break;
                case 1: // CRITICALS
                    allow = this.prioritizeCriticalHits(mc.thePlayer);
                    break;
                case 2: // WTAP
                    allow = this.prioritizeWTapHits(mc.thePlayer, this.sprintState);
                    break;
            }

            if (!allow) {
                event.setCancelled(true);
                this.blockedHits++;
            } else {
                this.allowedHits++;
            }
        }
    }

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        // If target is already hurt, allow the hit
        if (target.hurtTime != 0) {
            return true;
        }

        // If player hasn't recovered from hurt time, allow the hit
        if (player.hurtTime <= player.maxHurtTime - 1) {
            return true;
        }

        // If too close, allow the hit
        double dist = player.getDistanceToEntity(target);
        if (dist < 2.5) {
            return true;
        }

        // If not moving towards each other, allow the hit
        if (!this.isMovingTowards(target, player, 60.0)) {
            return true;
        }

        if (!this.isMovingTowards(player, target, 60.0)) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        // If on ground, allow the hit
        if (player.onGround) {
            return true;
        }

        // If hurt, allow the hit
        if (player.hurtTime != 0) {
            return true;
        }

        // If falling, allow the hit (for crits)
        if (player.fallDistance > 0.0f) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        // If against wall, allow the hit
        if (player.isCollidedHorizontally) {
            return true;
        }

        // If not moving forward, allow the hit
        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return true;
        }

        // If already sprinting, allow the hit
        if (sprinting) {
            return true;
        }

        // Block the hit and fix motion
        this.fixMotion();
        return false;
    }

    private void fixMotion() {
        if (this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Atlas.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Save the current slowdown value
            this.savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            this.keepSprintWasEnabled = keepSprint.isEnabled();

            // Temporarily enable KeepSprint silently so this internal motion fix does not spam toggles.
            if (!this.keepSprintWasEnabled) {
                keepSprint.setEnabled(true);
            }
            keepSprint.slowdown.setValue(0);

            this.set = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetMotion() {
        if (!this.set) {
            return;
        }

        KeepSprint keepSprint = (KeepSprint) Atlas.moduleManager.modules.get(KeepSprint.class);
        if (keepSprint == null) {
            return;
        }

        try {
            // Restore the original slowdown value
            keepSprint.slowdown.setValue((int) this.savedSlowdown);

            // Only restore the enabled state if HitSelect changed it.
            if (!this.keepSprintWasEnabled && keepSprint.isEnabled()) {
                keepSprint.setEnabled(false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        this.set = false;
        this.keepSprintWasEnabled = false;
        this.savedSlowdown = 0.0;
    }

    private boolean isMovingTowards(EntityLivingBase source, EntityLivingBase target, double maxAngle) {
        Vec3 currentPos = source.getPositionVector();
        Vec3 lastPos = new Vec3(source.lastTickPosX, source.lastTickPosY, source.lastTickPosZ);
        Vec3 targetPos = target.getPositionVector();

        // Calculate movement vector
        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        // If not moving, return false
        if (movementLength == 0.0) {
            return false;
        }

        // Normalize movement vector
        mx /= movementLength;
        mz /= movementLength;

        // Calculate vector to target
        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        // If target is at same position, return false
        if (targetLength == 0.0) {
            return false;
        }

        // Normalize target vector
        tx /= targetLength;
        tz /= targetLength;

        // Calculate dot product (cosine of angle between vectors)
        double dotProduct = mx * tx + mz * tz;

        // Check if angle is within threshold
        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @Override
    public void onDisabled() {
        this.resetMotion();
        this.sprintState = false;
        this.set = false;
        this.savedSlowdown = 0.0;
        this.blockedHits = 0;
        this.allowedHits = 0;
        this.velocityTicks = 0;
        this.dropped = 0;
        this.burstLeft = 0;
        this.waited = 0;
        this.forcing = false;
        this.forcedThisTick = false;
        this.reducedThisKnockback = false;
    }

    @Override
    public String[] getSuffix() {
        if (this.mode.getValue() == 3) {
            return new String[]{this.mode.getModeString(), this.dropped + " dropped"};
        }
        return new String[]{this.mode.getModeString()};
    }
}
