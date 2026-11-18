package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LeftClickMouseEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.ItemUtil;
import atlas.util.KeyBindUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityLargeFireball;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;

public class HitSelect extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"SECOND", "CRITICALS", "W_TAP", "ACTIVE"});

    public final PercentProperty chance = new PercentProperty("chance", 90, () -> this.mode.getValue() == 3);
    public final ModeProperty preference = new ModeProperty("preference", 0,
            new String[]{"KB_REDUCTION", "CRITICALS"}, () -> this.mode.getValue() == 3);

    public final ModeProperty timing = new ModeProperty("timing", 0,
            new String[]{"INVULN_TIMING", "HIT_TIMER"}, () -> this.mode.getValue() == 3);
    public final BooleanProperty burst = new BooleanProperty("burst", true, () -> this.mode.getValue() == 3);
    public final IntProperty burstClicks = new IntProperty("burst-clicks", 2, 2, 4,
            () -> this.mode.getValue() == 3 && this.burst.getValue());
    public final BooleanProperty reduce = new BooleanProperty("reduce", true, () -> this.mode.getValue() == 3);

    public final IntProperty onlyInCombo = new IntProperty("only-in-combo", 0, 0, 10);
    public final BooleanProperty firstHit = new BooleanProperty("first-hit", false);
    public final IntProperty waitFirst = new IntProperty("wait-first", 0, 0, 500);
    public final IntProperty lowHealth = new IntProperty("low-health", 0, 0, 20);
    public final IntProperty hitBack = new IntProperty("hit-back", 0, 0, 10);
    public final BooleanProperty onlyJumping = new BooleanProperty("only-jumping", false);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapon-only", false);
    public final BooleanProperty moreDamage = new BooleanProperty("more-damage", false);
    public final BooleanProperty fakeSwing = new BooleanProperty("fake-swing", false);

    private final HitSelectGate gate = new HitSelectGate();

    private volatile int hitBackLeft;

    private boolean auraRolled;
    private boolean auraSkips;

    private boolean auraHolding;

    private int auraPassedTick = -1;

    private int burstLeft;
    private int burstTarget = -1;

    private int waited;

    private boolean forcing;

    private boolean forcedThisTick;
    private boolean reducedThisKnockback;
    private long lastClickMs;

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

        if (event.getType() != EventType.POST && this.hitBackLeft > 0) {
            this.hitBackLeft--;
        }
        if (event.getType() == EventType.POST) {
            this.resetMotion();
        } else if (this.velocityTicks > 0) {
            this.sinceVelocity++;
            this.velocityTicks--;

            if (this.sinceVelocity > 1 && mc.thePlayer.onGround) {
                this.velocityTicks = 0;
            }
        }
    }

    @EventTarget(Priority.HIGH)
    public void onClick(LeftClickMouseEvent event) {
        if (this.forcing && event.isCancelled()) {
            this.forcing = false;
            return;
        }
        if (!this.isEnabled() || this.mode.getValue() != 3 || event.isCancelled()
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.forcing) {
            this.forcing = false;
            this.forcedThisTick = true;
            return;
        }
        if (this.forcedThisTick) {
            drop(event);
            return;
        }
        if (this.auraPassedTick == mc.thePlayer.ticksExisted) {
            return;
        }
        this.lastClickMs = System.currentTimeMillis();
        EntityLivingBase target = mc.objectMouseOver != null
                && mc.objectMouseOver.entityHit instanceof EntityLivingBase
                ? (EntityLivingBase) mc.objectMouseOver.entityHit : null;
        if (this.activeDrops(target, false)) {
            drop(event);
            if (target != null) {
                this.waited++;
            }
        } else if (target != null) {
            this.waited = 0;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE) {
            return;
        }

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

    private boolean enemy(EntityLivingBase target) {
        if (!(target instanceof EntityPlayer) || target.deathTime > 0) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) target;
        return !TeamUtil.isFriend(player) && !TeamUtil.isSameTeam(player) && !TeamUtil.isBot(player);
    }

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

    public boolean dropsAuraHit(EntityLivingBase target) {
        if (!this.isEnabled() || mc.thePlayer == null || target == null) {
            return false;
        }
        boolean holds = this.mode.getValue() == 3 ? this.activeDrops(target, true)
                : !this.letThrough(target) && (this.firstHitWaits(target) || !this.ruleLetsThrough(target));
        if (holds) {
            if (!this.auraHolding) {
                this.auraHolding = true;
                this.dropped++;
            }
            this.swingLocally();
            return true;
        }
        this.auraHolding = false;
        this.auraRolled = false;
        this.auraPassedTick = mc.thePlayer.ticksExisted;
        return false;
    }

    public void passForcedHit() {
        if (mc.thePlayer != null) {
            this.auraPassedTick = mc.thePlayer.ticksExisted;
        }
    }

    private boolean letThrough(EntityLivingBase target) {
        if (this.lowHealth.getValue() > 0 && !mc.thePlayer.capabilities.isCreativeMode
                && mc.thePlayer.getHealth() <= this.lowHealth.getValue()) {
            return true;
        }
        if (this.onlyJumping.getValue() && !mc.gameSettings.keyBindJump.isKeyDown()) {
            return true;
        }
        if (this.weaponOnly.getValue() && !ItemUtil.isHoldingSword() && !ItemUtil.isHoldingTool()) {
            return true;
        }
        if (this.hitBackLeft > 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        int id = target.getEntityId();
        if (this.onlyInCombo.getValue() > 0 && this.gate.combo(now) < this.onlyInCombo.getValue()) {
            return true;
        }
        if (this.firstHit.getValue() && this.gate.firstHit(id, now)) {
            return true;
        }
        return this.moreDamage.getValue() && target.hurtTime > 0 && this.gate.beats(id, hitDamage(target));
    }

    private boolean firstHitWaits(EntityLivingBase target) {
        return this.waitFirst.getValue() > 0 && !this.firstHit.getValue()
                && this.gate.waiting(target.getEntityId(), System.currentTimeMillis(), this.waitFirst.getValue());
    }

    private static double hitDamage(EntityLivingBase target) {
        EntityPlayerSP player = mc.thePlayer;
        double base = player.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue();
        double enchantment = EnchantmentHelper.getModifierForCreature(player.getHeldItem(), target.getCreatureAttribute());
        boolean critical = player.fallDistance > 0.0F && !player.onGround && !player.isOnLadder() && !player.isInWater()
                && !player.isPotionActive(Potion.blindness) && player.ridingEntity == null;
        return HitSelectGate.damage(base, enchantment, critical);
    }

    private boolean ruleLetsThrough(EntityLivingBase target) {
        switch (this.mode.getValue()) {
            case 0:
                return this.secondHitGoes(mc.thePlayer, target);
            case 1:
                return this.criticalHitGoes(mc.thePlayer);
            case 2:
                return this.wTapHitGoes(mc.thePlayer, this.sprintState);
            default:
                return true;
        }
    }

    private void swingLocally() {
        EntityPlayerSP player = mc.thePlayer;
        if (this.fakeSwing.getValue() && (!player.isSwingInProgress || player.swingProgressInt >= 3
                || player.swingProgressInt < 0)) {
            player.swingProgressInt = -1;
            player.isSwingInProgress = true;
        }
    }

    private boolean activeDrops(EntityLivingBase target, boolean aura) {
        if (target != null) {
            if (this.letThrough(target)) {
                return false;
            }
            if (this.firstHitWaits(target)) {
                return true;
            }
        }
        if (aura) {
            if (!this.auraRolled) {
                this.auraRolled = true;
                this.auraSkips = Math.random() * 100.0 >= this.chance.getValue();
            }
            if (this.auraSkips) {
                return false;
            }
        } else if (Math.random() * 100.0 >= this.chance.getValue()) {
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

        float until;
        InvulnTiming invuln = InvulnTiming.instance();
        if (this.timing.getValue() == 0 && invuln != null) {
            until = invuln.predict(target);
        } else {
            until = target.hurtTime - expected;
        }
        if (until <= 0.0F) {
            int spacing = Math.max(2, expected * 2);
            if (now - this.lastAllowedTick >= spacing || now < this.lastAllowedTick) {
                if (!aura) {
                    this.lastAllowedTick = now;
                }
                this.armBurst(target);
                return false;
            }
        } else if (until <= 1.0F) {
            this.armBurst(target);
            return false;
        }
        return true;
    }

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
        this.swingLocally();
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled()) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S19PacketEntityStatus && mc.theWorld != null) {
                S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
                Entity hurt = status.getOpCode() == 2 ? status.getEntity(mc.theWorld) : null;
                if (hurt != null) {
                    this.gate.hurt(hurt.getEntityId(), System.currentTimeMillis());
                }
                return;
            }
            if (event.getPacket() instanceof net.minecraft.network.play.server.S12PacketEntityVelocity
                    && mc.thePlayer != null
                    && ((net.minecraft.network.play.server.S12PacketEntityVelocity) event.getPacket()).getEntityID()
                    == mc.thePlayer.getEntityId()) {
                this.velocityTicks = VELOCITY_TICKS;
                this.sinceVelocity = 0;
                this.reducedThisKnockback = false;
                this.hitBackLeft = this.hitBack.getValue();
                this.gate.knockedBack(System.currentTimeMillis());
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

            boolean decided = this.mode.getValue() != 3
                    && (this.auraPassedTick == mc.thePlayer.ticksExisted || this.letThrough(living));
            if (!decided && this.mode.getValue() != 3 && this.firstHitWaits(living)) {
                decided = true;
                allow = false;
                this.fixMotion();
            }

            switch (decided ? 3 : this.mode.getValue()) {
                case 3:
                    break;
                case 0:
                    allow = this.prioritizeSecondHit(mc.thePlayer, living);
                    break;
                case 1:
                    allow = this.prioritizeCriticalHits(mc.thePlayer);
                    break;
                case 2:
                    allow = this.prioritizeWTapHits(mc.thePlayer, this.sprintState);
                    break;
            }

            if (!allow) {
                event.setCancelled(true);
                this.blockedHits++;
            } else {
                this.allowedHits++;
                this.lastAllowedTick = mc.thePlayer.ticksExisted;
                this.gate.attacked(living.getEntityId(), hitDamage(living), System.currentTimeMillis());
            }
        }
    }

    private boolean prioritizeSecondHit(EntityLivingBase player, EntityLivingBase target) {
        if (this.secondHitGoes(player, target)) {
            return true;
        }

        this.fixMotion();
        return false;
    }

    private boolean secondHitGoes(EntityLivingBase player, EntityLivingBase target) {
        if (target.hurtTime != 0) {
            return true;
        }

        if (player.hurtTime <= player.maxHurtTime - 1) {
            return true;
        }

        double dist = player.getDistanceToEntity(target);
        if (dist < 2.5) {
            return true;
        }

        if (!this.isMovingTowards(target, player, 60.0)) {
            return true;
        }

        return !this.isMovingTowards(player, target, 60.0);
    }

    private boolean prioritizeCriticalHits(EntityLivingBase player) {
        if (this.criticalHitGoes(player)) {
            return true;
        }

        this.fixMotion();
        return false;
    }

    private boolean criticalHitGoes(EntityLivingBase player) {
        if (player.onGround) {
            return true;
        }

        if (player.hurtTime != 0) {
            return true;
        }

        return player.fallDistance > 0.0f;
    }

    private boolean prioritizeWTapHits(EntityLivingBase player, boolean sprinting) {
        if (this.wTapHitGoes(player, sprinting)) {
            return true;
        }

        this.fixMotion();
        return false;
    }

    private boolean wTapHitGoes(EntityLivingBase player, boolean sprinting) {
        if (player.isCollidedHorizontally) {
            return true;
        }

        if (!mc.gameSettings.keyBindForward.isKeyDown()) {
            return true;
        }

        return sprinting;
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
            this.savedSlowdown = keepSprint.slowdown.getValue().doubleValue();
            this.keepSprintWasEnabled = keepSprint.isEnabled();

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
            keepSprint.slowdown.setValue((int) this.savedSlowdown);

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

        double mx = currentPos.xCoord - lastPos.xCoord;
        double mz = currentPos.zCoord - lastPos.zCoord;
        double movementLength = Math.sqrt(mx * mx + mz * mz);

        if (movementLength == 0.0) {
            return false;
        }

        mx /= movementLength;
        mz /= movementLength;

        double tx = targetPos.xCoord - currentPos.xCoord;
        double tz = targetPos.zCoord - currentPos.zCoord;
        double targetLength = Math.sqrt(tx * tx + tz * tz);

        if (targetLength == 0.0) {
            return false;
        }

        tx /= targetLength;
        tz /= targetLength;

        double dotProduct = mx * tx + mz * tz;

        return dotProduct >= Math.cos(Math.toRadians(maxAngle));
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.gate.clear();
        this.hitBackLeft = 0;
    }

    @Override
    public void onEnabled() {
        this.sprintState = mc.thePlayer != null && mc.thePlayer.isSprinting();
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
        this.hitBackLeft = 0;
        this.gate.clear();
        this.auraRolled = false;
        this.auraHolding = false;
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
