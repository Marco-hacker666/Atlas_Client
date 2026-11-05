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

/**
 * AutoBlock's Raven mode (2026-10-31, owner: "make me Raven's auto block"): Raven bS's Autoblock, ported from its
 * source (keystrokesmod combat/Autoblock.java, GPL-3), with its settings and defaults.
 *
 * Only while right click is held and a sword is in hand:
 * - left click held too (or an aura switched on with an enemy in range), and an enemy within range: the use key
 *   goes down for at most max-hold, then up -- the tick the next hit lands, since 1.8 drops attack clicks while the
 *   sword is up -- then down again on the next tick;
 * - on lowering, lag-chance of the time every outgoing packet is held (Raven's outbound lag; here AutoBlock's
 *   blink) until max-lag after the block went up, the sword still drawn up on screen; an attack lets the held
 *   packets go first (prevent-delaying-attacks) and puts the sword straight back up (block-again-immediately), as
 *   does the lag running out;
 * - damaged: up only on the tick your hurt time reaches max-hurt-time (just before you can be hit again), down
 *   when you are hit again;
 * - right click alone: a plain block.
 * Let go of right click and everything stops. Raven's require-left/right settings are left out: its own order of
 * checks (no right click: stop; no left click: plain block) leaves them nothing to decide.
 *
 * Differences, from this client: hands off while Silent Aura blocks by itself (as Slinky); BedNuker digging stands
 * for Raven's BedAura mining; the raw right-click mouse event is not cancelled (vanilla's press raises the sword
 * as the plain block does, and the use key is this mode's from then on); the block animation is drawn per tick,
 * as Slinky's fake animation, not per frame.
 */
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
    /* 2026-11-05 (owner: "build what the others have"), all off by default; behaviour from OpenB4 and Raven bS. */
    private static final int OUT_ONCE = 1;
    private static final int OUT_ALWAYS = 2;
    private static final int OUT_FAKE = 3;
    private static final int MAX_PENDING_ATTACKS = 3;
    /** Ticks this hold runs past max-hold. */
    private int extraHold;
    /** When the last block ended; -1 before the first. */
    private long blockEndedAt = -1L;
    private boolean wasInRange;
    /** Once: left range with right click held; no block until it is let go or someone comes back. */
    private boolean unblockedAfterLeaving;
    /** Always, Fake: nobody in range, so right click raises nothing. */
    private boolean nobodyInRange;
    /** Fake: the sword drawn up with nobody in range and right click held. */
    private boolean fakeOutOfRange;
    /** Force attack: clicks vanilla dropped because the sword was up, to be made once it is down. */
    private int pendingAttacks;
    private boolean forceUnblocked;
    private boolean attackWasDown;

    AutoBlockRaven(BlockHit module) {
        this.module = module;
    }

    /** Ran this tick, so a change of mode lets go of what it held. */
    boolean active() {
        return this.active;
    }

    boolean lagging() {
        return this.lagging;
    }

    /** Raven: milliseconds to ticks, rounded up. */
    static int msToTicks(int ms) {
        return ms <= 0 ? 0 : (int) Math.ceil(ms / 50.0);
    }

    /** Raven: the hurt time the damaged block goes up at, max-hurt-time in ticks, 1 to 10. */
    static int hurtTrigger(int maxHurtMs) {
        return Math.max(1, Math.min(10, (int) Math.round(maxHurtMs / 50.0)));
    }

    /** A right click must not raise the sword this tick. A chest or a door under the crosshair still opens. */
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

    /** Raven's onPrePlayerInteract: the start of the tick, before vanilla's clicks. */
    void tick() {
        this.active = true;
        this.forceUnblocked = false;
        this.nobodyInRange = false;
        this.fakeOutOfRange = false;
        step();
        forceAttack();
    }

    /**
     * Force attack: vanilla drops a click while the sword is up. This tick comes before the mouse is read, so a press
     * is seen a tick after vanilla dropped it: the sword goes down, and the click is made the tick the item is no
     * longer in use. The player's own hand only: an aura plans its own clicks around the block.
     */
    private void forceAttack() {
        boolean on = this.module.ravenForceAttack.getValue() && mc.thePlayer != null && !mc.thePlayer.isDead
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

    /** The enemy to block against: a player, else a monster when raven-mobs is on. */
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
        /* Driven by the aura (BlockMode Raven), the aura's auto-block is this mode: standing aside would leave no block. */
        if (!ItemUtil.isHoldingSword() || this.module.auraBlocksAlone()) {
            reset(false);
            return;
        }
        int now = ++this.tick;
        if (this.pendingAttacks > 0) {
            /* Force attack: the sword stays down until the click has gone. */
            this.forceUnblocked = true;
            releaseLag();
            stop(true);
            this.manual = false;
            animation();
            return;
        }
        double range = this.module.ravenRange.getValue();
        Entity target = enemy(range);
        /* Raven: KillAura on (and not waiting for the mouse) with an enemy in range counts as left click. */
        boolean auraFighting = atlas.util.Auras.anyEnabled() && target != null;
        /* raven-auto: with an enemy in range it runs as if both buttons were held, and with nobody it does nothing. */
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
            /* Set here too: the press comes after this tick's start, and has to find it. */
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
            /* OpenB4: the hit has landed, so there is nothing left to block for now: a tick down, for the reply. */
            releaseLag();
            stop(true);
            this.manual = false;
            animation();
            return;
        }
        if (!left) {
            /* Right click alone: a plain block. */
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

    /** Raven's onSendPacket: an attack lets the held packets go first, and the sword goes straight back up. */
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

    /** OpenB4's disable while chasing: a target running from this player cannot hit it. */
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

    /** Raven: the lag runs until max-lag after the block went up; none when the hold already took that long. */
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

    /** Raven's force-block-animation: the sword drawn up while blocking or lagging. */
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

    /** Raven's resetState: held packets go, the sword down, the use key back to the player. */
    void reset(boolean releaseKey) {
        releaseLag();
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
        /* Raven: a right click still held keeps the use key down; never forced up here (an aura blocking by
           itself has the key while this stands aside). */
        int key = mc.gameSettings.keyBindUseItem.getKeyCode();
        if (mc.currentScreen == null && KeyBindUtil.isKeyDown(key)) {
            KeyBindUtil.setKeyBindState(key, true);
        }
        this.lastHurt = 0;
        this.active = false;
    }

    /** Raven stands aside while its BedAura mines; here BedNuker. */
    private static boolean bedNukerDigging() {
        if (Atlas.moduleManager == null) {
            return false;
        }
        BedNuker nuker = (BedNuker) Atlas.moduleManager.modules.get(BedNuker.class);
        return nuker != null && nuker.isEnabled() && nuker.isBreaking();
    }
}
