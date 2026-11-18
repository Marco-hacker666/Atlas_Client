package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.util.BlockUtil;
import atlas.util.CombatTargeting;
import atlas.util.ItemUtil;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;

// Raven bS's Autoblock, ported from its source (keystrokesmod combat/Autoblock.java, GPL-3), with its settings and defaults.

final class AutoBlockRaven {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private final BlockHit module;
    private boolean blocking;
    private boolean manual;
    private boolean lagging;
    private boolean animation;
    private boolean active;
    private int blockStart = -1;
    private int lagStart = -1;
    private int tick;
    private int lastHurt;

    private static final int OUT_ONCE = 1;
    private static final int OUT_ALWAYS = 2;
    private static final int OUT_FAKE = 3;
    private static final int MAX_PENDING_ATTACKS = 3;

    private int extraHold;

    private long blockEndedAt = -1L;
    private boolean wasInRange;

    private boolean unblockedAfterLeaving;

    private boolean nobodyInRange;

    private boolean fakeOutOfRange;

    private int pendingAttacks;
    private boolean forceUnblocked;
    private boolean attackWasDown;

    private final RavenAutoPlan autoPlan = new RavenAutoPlan(new java.util.Random());

    AutoBlockRaven(BlockHit module) {
        this.module = module;
    }

    boolean active() {
        return this.active;
    }

    boolean lagging() {
        return this.lagging;
    }

    static int msToTicks(int ms) {
        return ms <= 0 ? 0 : (int) Math.ceil(ms / 50.0);
    }

    static int hurtTrigger(int maxHurtMs) {
        return Math.max(1, Math.min(10, (int) Math.round(maxHurtMs / 50.0)));
    }

    boolean cancelsUse() {
        return this.forceUnblocked || this.nobodyInRange && !lookingAtSomethingToUse();
    }

    private static boolean lookingAtSomethingToUse() {
        MovingObjectPosition over = mc.objectMouseOver;
        if (over == null) {
            return false;
        }
        if (over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos pos = over.getBlockPos();
            return pos != null && BlockUtil.isInteractable(pos);
        }
        return over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && over.entityHit != null && !(over.entityHit instanceof EntityPlayer);
    }

    void tick() {
        this.active = true;
        this.forceUnblocked = false;
        this.nobodyInRange = false;
        this.fakeOutOfRange = false;
        step();
        forceAttack();
    }

    private void forceAttack() {
        boolean on = (this.module.ravenForceAttack.getValue() || humanAuto()) && mc.thePlayer != null && !mc.thePlayer.isDead
                && mc.currentScreen == null && mc.inGameHasFocus && ItemUtil.isHoldingSword()
                && !atlas.util.Auras.anyEnabled() && !bedNukerDigging();
        int key = mc.gameSettings.keyBindAttack.getKeyCode();
        boolean down = KeyBindUtil.isKeyDown(key);
        boolean pressed = down && !this.attackWasDown;
        this.attackWasDown = down;
        if (!on) {
            this.pendingAttacks = 0;
            return;
        }
        if (this.pendingAttacks > 0 && !mc.thePlayer.isUsingItem()) {
            for (int i = 0; i < this.pendingAttacks; i++) {
                KeyBinding.onTick(key);
            }
            this.pendingAttacks = 0;
            return;
        }
        if (pressed && mc.thePlayer.isUsingItem()) {
            this.pendingAttacks = Math.min(MAX_PENDING_ATTACKS, this.pendingAttacks + 1);
            this.forceUnblocked = true;
            releaseLag();
            stop(true);
            this.manual = false;
        }
    }

    private Entity enemy(double range) {
        EntityPlayer player = CombatTargeting.findTarget(range * range, this.module.ravenIgnoreTeammates.getValue());
        if (player != null || !this.module.ravenMobs.getValue()) {
            return player;
        }
        Entity nearest = null;
        double best = range;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityMob && !entity.isDead && ((EntityMob) entity).getHealth() > 0.0F) {
                double distance = BlockHit.boxDistance(entity);
                if (distance <= best) {
                    best = distance;
                    nearest = entity;
                }
            }
        }
        return nearest;
    }

    private void step() {
        if (mc.thePlayer == null || mc.thePlayer.isDead || mc.currentScreen != null || bedNukerDigging()) {
            reset(true);
            return;
        }
        int hurt = mc.thePlayer.hurtTime;
        boolean hurtAgain = hurt > this.lastHurt;
        this.lastHurt = hurt;

        if (!ItemUtil.isHoldingSword() || this.module.auraBlocksAlone()) {
            reset(false);
            return;
        }
        int now = ++this.tick;
        if (this.pendingAttacks > 0) {
            this.forceUnblocked = true;
            releaseLag();
            stop(true);
            this.manual = false;
            animation();
            return;
        }
        double range = this.module.ravenRange.getValue();
        Entity target = enemy(range);

        boolean auraFighting = atlas.util.Auras.anyEnabled() && target != null;

        boolean auto = this.module.ravenAuto.getValue() && target != null;
        boolean right = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode()) || auto;
        boolean left = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode()) || auraFighting || auto;
        boolean hasTarget = target != null;
        boolean leftRange = this.wasInRange && !hasTarget;
        int outOfRange = this.module.ravenOutOfRange.getValue();
        boolean nobody = outOfRange >= OUT_ALWAYS && !hasTarget;
        if (!right) {
            int pending = this.pendingAttacks;
            reset(true);
            this.pendingAttacks = pending;
            this.wasInRange = hasTarget;

            this.nobodyInRange = nobody;
            return;
        }
        this.wasInRange = hasTarget;
        this.nobodyInRange = nobody;
        if (outOfRange != OUT_ONCE || hasTarget) {
            this.unblockedAfterLeaving = false;
        }
        if (nobody) {
            releaseLag();
            stop(true);
            this.manual = false;
            this.fakeOutOfRange = outOfRange == OUT_FAKE && !lookingAtSomethingToUse();
            animation();
            return;
        }
        if (outOfRange == OUT_ONCE && leftRange) {
            releaseLag();
            stop(true);
            this.manual = false;
            this.unblockedAfterLeaving = true;
            animation();
            return;
        }
        if (this.module.ravenReleaseOnHit.getValue() && hurtAgain) {
            releaseLag();
            stop(true);
            this.manual = false;
            animation();
            return;
        }
        if (!left) {
            if (this.lagging) {
                releaseLag();
            }
            if (this.unblockedAfterLeaving) {
                animation();
                return;
            }
            if (!this.blocking) {
                start(now);
                this.manual = true;
            }
            animation();
            return;
        }
        if (this.manual) {
            stop(true);
            this.manual = false;
        }
        boolean wanted = hasTarget && !(this.module.ravenNoChasing.getValue() && targetRunning(target));
        if (this.lagging) {
            int lagMax = msToTicks(this.module.ravenLagMax.getValue());
            boolean expired = lagMax > 0 && this.lagStart >= 0 && now - this.lagStart >= lagMax;
            if (expired || !wanted) {
                releaseLag();
                if (expired && this.module.ravenBlockAgain.getValue() && wanted) {
                    start(now);
                }
            }
        }
        if (!wanted) {
            stop(true);
            this.autoPlan.reset();
            animation();
            return;
        }
        if (auto && humanAuto() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())) {
            boolean up = this.autoPlan.wantUp(System.currentTimeMillis(), this.blocking, threatening(target), hurt,
                    hurtAgain, ping(), msToTicks(this.module.ravenMaxHold.getValue()),
                    msToTicks(this.module.ravenRandomHold.getValue()));
            if (up && !this.blocking && !this.lagging) {
                start(now);
            } else if (!up && this.blocking) {
                if (lagRoll()) {
                    startLag(now);
                }
                stop(true);
            }
            animation();
            return;
        }
        boolean damaged = this.module.ravenDamaged.getValue();
        if (!this.blocking && !this.lagging
                && (!damaged || hurt == hurtTrigger(this.module.ravenMaxHurt.getValue()))) {
            start(now);
        }
        if (this.blocking) {
            int maxHold = msToTicks(this.module.ravenMaxHold.getValue());
            boolean down = maxHold > 0 && this.blockStart >= 0 && now - this.blockStart >= maxHold + this.extraHold;
            if (damaged && hurtAgain) {
                down = true;
            }
            if (down) {
                if (lagRoll()) {
                    startLag(now);
                }
                stop(true);
            }
        }
        animation();
    }

    void onSend(PacketEvent event) {
        if (event.getType() != EventType.SEND) {
            return;
        }
        if (bedNukerDigging()) {
            releaseLag();
            return;
        }
        if (!this.lagging || !this.module.ravenNoDelayAttacks.getValue()) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            releaseLag();
            if (this.module.ravenBlockAgain.getValue() && ItemUtil.isHoldingSword()) {
                start(this.tick);
            }
        }
    }

    private boolean humanAuto() {
        return this.module.ravenAuto.getValue() && this.module.ravenAutoHuman.getValue();
    }

    private static boolean threatening(Entity target) {
        if (!(target instanceof EntityPlayer)) {
            return true;
        }
        return RavenAutoPlan.facingDeg(target.rotationYaw, mc.thePlayer.posX - target.posX,
                mc.thePlayer.posZ - target.posZ) <= RavenAutoPlan.FACING_DEG;
    }

    private static int ping() {
        if (mc.getNetHandler() == null || mc.thePlayer == null) {
            return 0;
        }
        net.minecraft.client.network.NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? 0 : Math.max(0, info.getResponseTime());
    }

    private static boolean targetRunning(Entity target) {
        return AutoBlockRules.movingAway(target.posX - target.lastTickPosX, target.posZ - target.lastTickPosZ,
                target.posX - mc.thePlayer.posX, target.posZ - mc.thePlayer.posZ);
    }

    private boolean lagRoll() {
        int chance = this.module.ravenLagChance.getValue();
        if (chance <= 0) {
            return false;
        }
        return chance >= 100 || Math.random() * 100.0 < chance;
    }

    private void start(int now) {
        long since = this.blockEndedAt < 0L ? -1L : System.currentTimeMillis() - this.blockEndedAt;
        if (!ItemUtil.isHoldingSword() || this.forceUnblocked
                || AutoBlockRules.coolingDown(this.module.ravenCooldown.getValue(), since)) {
            return;
        }
        this.extraHold = AutoBlockRules.extraHoldTicks(msToTicks(this.module.ravenRandomHold.getValue()),
                Math.random(), Math.random());
        int key = mc.gameSettings.keyBindUseItem.getKeyCode();
        KeyBindUtil.setKeyBindState(key, true);
        KeyBinding.onTick(key);
        this.blocking = true;
        this.blockStart = now;
    }

    private void stop(boolean force) {
        if (!this.blocking && !force) {
            return;
        }
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        if (this.blocking) {
            this.blockEndedAt = System.currentTimeMillis();
        }
        this.blocking = false;
        this.blockStart = -1;
    }

    private void startLag(int now) {
        if (this.lagging) {
            return;
        }
        int reference = this.blockStart >= 0 ? this.blockStart : now;
        int lagMax = msToTicks(this.module.ravenLagMax.getValue());
        if (lagMax > 0 && now - reference >= lagMax) {
            return;
        }
        if (Atlas.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK_LAG)) {
            this.lagging = true;
            this.lagStart = reference;
        }
    }

    private void releaseLag() {
        if (this.lagging) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK_LAG);
        }
        this.lagging = false;
        this.lagStart = -1;
    }

    private void animation() {
        if (!this.module.ravenAnimation.getValue() && !this.fakeOutOfRange || !ItemUtil.isHoldingSword()) {
            animationOff();
            return;
        }
        if (this.blocking || this.lagging || this.fakeOutOfRange) {
            ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(72000);
            this.animation = true;
        } else {
            animationOff();
        }
    }

    private void animationOff() {
        if (this.animation && mc.thePlayer != null) {
            if (!mc.thePlayer.isUsingItem()) {
                ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(0);
            }
            this.animation = false;
        }
    }

    void reset(boolean releaseKey) {
        releaseLag();
        this.autoPlan.reset();
        this.pendingAttacks = 0;
        this.unblockedAfterLeaving = false;
        this.nobodyInRange = false;
        this.fakeOutOfRange = false;
        this.wasInRange = false;
        if (mc.gameSettings == null) {
            this.blocking = false;
            this.manual = false;
            this.active = false;
            return;
        }
        stop(releaseKey);
        this.manual = false;
        animationOff();

        int key = mc.gameSettings.keyBindUseItem.getKeyCode();
        if (mc.currentScreen == null && KeyBindUtil.isKeyDown(key)) {
            KeyBindUtil.setKeyBindState(key, true);
        }
        this.lastHurt = 0;
        this.active = false;
    }

    private static boolean bedNukerDigging() {
        if (Atlas.moduleManager == null) {
            return false;
        }
        BedNuker nuker = (BedNuker) Atlas.moduleManager.modules.get(BedNuker.class);
        return nuker != null && nuker.isEnabled() && nuker.isBreaking();
    }
}
