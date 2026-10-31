package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.util.CombatTargeting;
import atlas.util.ItemUtil;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;

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

    /** Raven's onPrePlayerInteract: the start of the tick, before vanilla's clicks. */
    void tick() {
        this.active = true;
        if (mc.thePlayer == null || mc.thePlayer.isDead || mc.currentScreen != null || bedNukerDigging()) {
            reset(true);
            return;
        }
        int hurt = mc.thePlayer.hurtTime;
        boolean hurtAgain = hurt > this.lastHurt;
        this.lastHurt = hurt;
        if (!ItemUtil.isHoldingSword() || atlas.util.Auras.blocksByItself()) {
            reset(false);
            return;
        }
        int now = ++this.tick;
        double range = this.module.ravenRange.getValue();
        EntityPlayer target = CombatTargeting.findTarget(range * range, this.module.ravenIgnoreTeammates.getValue());
        /* Raven: KillAura on (and not waiting for the mouse) with an enemy in range counts as left click. */
        boolean auraFighting = atlas.util.Auras.anyEnabled() && target != null;
        boolean right = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode());
        boolean left = KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode()) || auraFighting;
        if (!right) {
            reset(true);
            return;
        }
        if (!left) {
            /* Right click alone: a plain block. */
            if (this.lagging) {
                releaseLag();
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
        boolean wanted = target != null;
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
            boolean down = maxHold > 0 && this.blockStart >= 0 && now - this.blockStart >= maxHold;
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

    private boolean lagRoll() {
        int chance = this.module.ravenLagChance.getValue();
        if (chance <= 0) {
            return false;
        }
        return chance >= 100 || Math.random() * 100.0 < chance;
    }

    private void start(int now) {
        if (!ItemUtil.isHoldingSword()) {
            return;
        }
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
        if (!this.module.ravenAnimation.getValue() || !ItemUtil.isHoldingSword()) {
            animationOff();
            return;
        }
        if (this.blocking || this.lagging) {
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
