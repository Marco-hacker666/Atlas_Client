package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.mixin.IAccessorKeyBinding;
import atlas.util.CombatTargeting;
import atlas.util.ItemUtil;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;

import java.util.Random;

/**
 * AutoBlock's Crab mode (2026-11-09, owner: "port Crab's Block Hit, as close as you can"): rebuilt from what Crab
 * client's Block Hit does (its DLL, read with Ghidra), with its settings and defaults; no code of its is used.
 *
 * Each tick, while it may block (a sword in hand; left mouse held, with left-mouse; right mouse held exactly when
 * right-mouse is on; hurt at all, with damaged; and the player's hurt time no higher than max-hurt-time):
 * - with nobody's block running, the cooldown at 0 and the nearest enemy within range, it presses the use key with
 *   one click of its own (vanilla raises the sword), starts a 10-tick cooldown and holds;
 * - it holds for max-hold, or until it may no longer block, then lets the key up; lag-chance of the time every
 *   outgoing packet is then held for lag-max (AutoBlock's blink);
 * - a hit landing on the player (hurt time 10) ends the cooldown, so the next block comes as the hurt time falls
 *   back to max-hurt-time, just before the player can be hit again;
 * - prevent-delaying-attacks: an attack (seen at most once per 10 ticks, as the last-attacker time moving) or a
 *   change of target ends the lag; otherwise it runs out a tick at a time. block-again-immediately: a lag ending
 *   ends the cooldown too.
 * Times are ticks (the settings are in ms, 50 a tick) and a fraction is a chance of one more tick, as Crab's.
 *
 * Differences, from this client: an attack packet sent during a lag lets the held packets go before it with
 * prevent-delaying-attacks (Crab notices the attack a tick later, so its attack can wait that tick); hands off
 * while Silent Aura blocks by itself, as the other modes; the block animation options are drawn the way Slinky's
 * fake animation is (Crab's drawing code was not followed in detail).
 */
final class AutoBlockCrab {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Ticks between two blocks starting, unless a hit on the player or block-again ends it sooner. */
    static final int COOLDOWN_TICKS = 10;
    /** Ticks between two attacks being noticed. */
    static final int ATTACK_GAP_TICKS = 10;
    /** How far an enemy may be to count as the target at all (blocks); range decides the block. */
    static final double TARGET_SEARCH = 6.0;

    private final BlockHit module;
    private final Random random = new Random();
    private boolean active;
    private boolean holding;
    /** The use key is down because of this mode: given back when the mode stops. */
    private boolean keyOwned;
    private boolean lagging;
    private boolean animation;
    private int cooldown;
    private int holdTicks;
    private int lagTicks;
    private int sinceAttack;
    private int lastTargetId;
    private int lastAttackerTime = Integer.MIN_VALUE;

    AutoBlockCrab(BlockHit module) {
        this.module = module;
    }

    boolean active() {
        return this.active;
    }

    boolean lagging() {
        return this.lagging;
    }

    /** floor(x), plus one with the chance of x's fraction: Crab's way of turning a time into whole ticks. */
    static int ticks(float value, float roll) {
        if (Float.isNaN(value) || value <= 0.0F) {
            return 0;
        }
        int whole = (int) Math.min(value, Integer.MAX_VALUE - 1);
        return whole + (roll < value - whole ? 1 : 0);
    }

    private int ticksOfMs(int ms) {
        return ticks(ms / 50.0F, this.random.nextFloat());
    }

    void tick() {
        this.active = true;
        if (mc.thePlayer == null || mc.thePlayer.isDead || mc.currentScreen != null) {
            reset(true);
            return;
        }
        if (this.module.auraBlocksAlone()) {
            reset(false);
            return;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        int hurt = mc.thePlayer.hurtTime;
        EntityPlayer target = CombatTargeting.findTarget(TARGET_SEARCH * TARGET_SEARCH, true);
        float range = this.module.crabRange.getValue();
        boolean inRange = target != null && mc.thePlayer.getDistanceSqToEntity(target) < range * range;
        boolean may = mayBlock(hurt);
        if (hurt == 10) {
            this.cooldown = 0;
        }

        int lastAttacker = mc.thePlayer.getLastAttackerTime();
        boolean attacked = false;
        if (++this.sinceAttack >= ATTACK_GAP_TICKS && this.lastAttackerTime != Integer.MIN_VALUE
                && lastAttacker != this.lastAttackerTime) {
            this.sinceAttack = 0;
            attacked = true;
        }
        this.lastAttackerTime = lastAttacker;

        int targetId = target == null ? 0 : target.getEntityId();
        boolean lagEnded = false;
        boolean prevent = this.module.crabPreventDelay.getValue();
        if (targetId != this.lastTargetId) {
            this.lastTargetId = targetId;
            this.sinceAttack = 0;
            if (this.lagTicks != 0) {
                lagEnded = prevent || --this.lagTicks == 0;
                if (prevent) {
                    this.lagTicks = 0;
                }
            }
        } else if (this.lagTicks != 0) {
            if (prevent && attacked) {
                this.lagTicks = 0;
                lagEnded = true;
            } else {
                lagEnded = --this.lagTicks == 0;
            }
        }
        if (lagEnded) {
            releaseLag();
            if (this.module.crabBlockAgain.getValue()) {
                this.cooldown = 0;
            }
        }

        if (this.holding) {
            this.holdTicks++;
            if (may && this.holdTicks < ticksOfMs(this.module.crabMaxHold.getValue())) {
                animation(may, inRange);
                return;
            }
            setUseKey(false);
            int chance = this.module.crabLagChance.getValue();
            if (chance > 0 && this.random.nextFloat() * 100.0F < chance) {
                this.lagTicks = ticksOfMs(this.module.crabLagMax.getValue());
                if (this.lagTicks > 0) {
                    startLag();
                }
            }
            this.holding = false;
            animation(may, inRange);
            return;
        }
        if (target != null && this.cooldown == 0 && may && inRange) {
            KeyBinding use = mc.gameSettings.keyBindUseItem;
            setUseKey(true);
            ((IAccessorKeyBinding) use).setPressTime(((IAccessorKeyBinding) use).getPressTime() + 1);
            this.holding = true;
            this.keyOwned = true;
            this.cooldown = COOLDOWN_TICKS;
            this.holdTicks = 0;
            this.lagTicks = 0;
            releaseLag();
        }
        animation(may, inRange);
    }

    /** Crab's conditions for blocking this tick. */
    private boolean mayBlock(int hurt) {
        if (!ItemUtil.isHoldingSword()) {
            return false;
        }
        if (this.module.crabLeftMouse.getValue() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            return false;
        }
        /* Right mouse: held exactly when the setting is on. Off and held, the player is blocking by hand. */
        if (this.module.crabRightMouse.getValue() != KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode())) {
            return false;
        }
        if (hurt == 0 && this.module.crabDamaged.getValue()) {
            return false;
        }
        return ticksOfMs(this.module.crabMaxHurt.getValue()) >= hurt;
    }

    private static void setUseKey(boolean down) {
        ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(down);
    }

    /** prevent-delaying-attacks: an attack lets the held packets go before it. */
    void onSend(PacketEvent event) {
        if (!this.lagging || event.getType() != EventType.SEND || !this.module.crabPreventDelay.getValue()) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            this.lagTicks = 0;
            releaseLag();
            if (this.module.crabBlockAgain.getValue()) {
                this.cooldown = 0;
            }
        }
    }

    private void startLag() {
        if (!this.lagging && Atlas.blinkManager.setBlinkState(true, BlinkModules.AUTO_BLOCK_LAG)) {
            this.lagging = true;
        }
    }

    private void releaseLag() {
        if (this.lagging) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK_LAG);
        }
        this.lagging = false;
    }

    /**
     * crab-animation: the sword drawn up whenever it may block (only with the target in range, with
     * crab-animation-in-range); crab-force-animation: drawn up while blocking or lagging.
     */
    private void animation(boolean may, boolean inRange) {
        boolean show = ItemUtil.isHoldingSword()
                && (this.module.crabForceAnimation.getValue() && (this.holding || this.lagging)
                || this.module.crabAnimation.getValue() && may
                && (!this.module.crabAnimationInRange.getValue() || inRange));
        if (show) {
            ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(72000);
            this.animation = true;
        } else if (this.animation) {
            if (!mc.thePlayer.isUsingItem()) {
                ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(0);
            }
            this.animation = false;
        }
    }

    /** Held packets go, the sword down, and the use key back to what the player really holds. */
    void reset(boolean releaseKey) {
        releaseLag();
        this.lagTicks = 0;
        this.holding = false;
        this.cooldown = 0;
        this.holdTicks = 0;
        this.sinceAttack = 0;
        this.lastAttackerTime = Integer.MIN_VALUE;
        if (mc.gameSettings != null && this.keyOwned && releaseKey) {
            int key = mc.gameSettings.keyBindUseItem.getKeyCode();
            setUseKey(mc.currentScreen == null && KeyBindUtil.isKeyDown(key));
        }
        if (releaseKey) {
            this.keyOwned = false;
        }
        if (this.animation && mc.thePlayer != null) {
            if (!mc.thePlayer.isUsingItem()) {
                ((atlas.mixin.IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(0);
            }
            this.animation = false;
        }
        this.active = false;
    }
}
