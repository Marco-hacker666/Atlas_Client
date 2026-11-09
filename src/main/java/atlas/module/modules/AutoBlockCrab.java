package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.types.EventType;
import atlas.events.AttackEvent;
import atlas.events.PacketEvent;
import atlas.mixin.IAccessorEntityPlayer;
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
 * AutoBlock's Crab mode (2026-11-09, owner: "port Crab's Block Hit, as close as you can"). Crab client's Block Hit,
 * read from its DLL with Ghidra, turned out to be Slinky's Auto Block step for step (same settings, defaults and
 * order); this follows that, checked against OpenSlinky's readable reconstruction (logic/combat/auto_block.cpp).
 * No code of either is used.
 *
 * Each tick:
 * - Whether the use key is ours: with a sword in hand, left mouse held (left-mouse), right mouse held exactly when
 *   right-mouse is on, hurt at all (damaged), and hurt time no higher than max-hurt-time. While it is, the key is
 *   held down exactly while blocking (one more click each tick of it); when it stops being ours it goes back to
 *   what the player holds (up, while right-mouse is on).
 * - A hit landing on the player (hurt time 10) ends the re-block cooldown.
 * - An attack (the last-attacker time moving, looked at once 10 ticks have passed) or a new target ends a lag with
 *   prevent-delaying-attacks; otherwise the lag counts down. block-again-immediately: a lag ending ends the cooldown.
 * - Blocking: held for max-hold (or until the key is not ours), then let go; lag-chance of the time every outgoing
 *   packet is then held for lag-max (AutoBlock's blink).
 * - Not blocking: the key ours, the cooldown over and the target (under the crosshair, else the nearest) within
 *   range: press, a 10-tick cooldown, hold.
 * - right-mouse: no attack goes out while not blocking.
 * - animation: the sword drawn blocking while the key may be ours (only with the target in range, with
 *   animation-in-range).
 * Times are ticks (the settings are in ms, 50 a tick) and a fraction is a chance of one more tick.
 *
 * Different here: an attack packet during a lag lets the held packets go before it (with prevent-delaying-attacks;
 * the original notices the attack a tick later), and hands off while Silent Aura blocks by itself.
 */
final class AutoBlockCrab {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Ticks between two blocks starting, unless a hit on the player or block-again ends it sooner. */
    static final int COOLDOWN_TICKS = 10;
    /** Ticks before an attack is looked for again. */
    static final int ATTACK_GAP_TICKS = 10;
    /** How far an enemy may be to count as the target at all (blocks); range decides the block. */
    static final double TARGET_SEARCH = 6.0;
    /** A sword's full use time: where the drawn-blocking count starts. */
    static final int USE_DURATION = 72000;

    private final BlockHit module;
    private final Random random = new Random();
    private boolean active;
    private boolean blocking;
    /** The use key is set by this mode, so it is given back when the mode stops. */
    private boolean keyOwned;
    private boolean rightRequired;
    private boolean lagging;
    private int cooldown;
    private int heldTicks;
    private int lagTicks;
    private int sinceAttack;
    private int lastTargetId;
    private int lastAttackerTime = Integer.MIN_VALUE;
    private boolean animationShown;
    private int animationCount = USE_DURATION;

    AutoBlockCrab(BlockHit module) {
        this.module = module;
    }

    boolean active() {
        return this.active;
    }

    boolean lagging() {
        return this.lagging;
    }

    /** floor(x), plus one with the chance of x's fraction. */
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
        endAnimation();
        if (this.cooldown != 0) {
            this.cooldown--;
        }
        if (mc.thePlayer == null || mc.thePlayer.isDead || mc.currentScreen != null) {
            if (this.keyOwned) {
                setUseKey(physicalUse());
                this.keyOwned = false;
            }
            this.rightRequired = false;
            stopLag();
            return;
        }
        if (this.module.auraBlocksAlone()) {
            reset(false);
            return;
        }

        int hurt = mc.thePlayer.hurtTime;
        boolean sword = ItemUtil.isHoldingSword();
        this.rightRequired = false;
        boolean conditionsOk = false;
        boolean keyOurs = false;
        boolean skipKey = false;
        boolean giveBack;
        if (!sword) {
            giveBack = this.keyOwned;
            skipKey = !giveBack;
        } else if (this.module.crabLeftMouse.getValue() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            giveBack = true;
        } else {
            boolean needRight = this.module.crabRightMouse.getValue();
            this.rightRequired = needRight;
            if (needRight != physicalUse()) {
                giveBack = true;
            } else {
                conditionsOk = true;
                giveBack = hurt == 0 && this.module.crabDamaged.getValue()
                        || hurt > ticksOfMs(this.module.crabMaxHurt.getValue());
            }
        }
        if (!skipKey) {
            if (giveBack) {
                setUseKey(!this.rightRequired && physicalUse());
            } else {
                setUseKey(this.blocking);
                keyOurs = true;
                if (this.blocking) {
                    click();
                }
            }
            this.keyOwned = keyOurs;
        }

        EntityPlayer target = CombatTargeting.findTarget(TARGET_SEARCH * TARGET_SEARCH, true);
        float range = this.module.crabRange.getValue();
        boolean inRange = target != null && mc.thePlayer.getDistanceSqToEntity(target) < range * range;
        boolean animation = conditionsOk && this.module.crabAnimation.getValue()
                && (!this.module.crabAnimationInRange.getValue() || inRange);
        if (hurt == 10) {
            this.cooldown = 0;
        }

        /* An attack, or a new target. */
        int lastAttacker = mc.thePlayer.getLastAttackerTime();
        boolean hit = false;
        if (++this.sinceAttack >= ATTACK_GAP_TICKS) {
            hit = this.lastAttackerTime != Integer.MIN_VALUE && lastAttacker != this.lastAttackerTime
                    && lastAttacker >= mc.thePlayer.ticksExisted - 1;
            if (hit) {
                this.sinceAttack = 0;
            }
        }
        this.lastAttackerTime = lastAttacker;
        int targetId = target == null ? 0 : target.getEntityId();
        if (targetId != this.lastTargetId) {
            this.lastTargetId = targetId;
            this.sinceAttack = 0;
            hit = true;
        }

        if (this.lagTicks != 0) {
            if (hit && this.module.crabPreventDelay.getValue()) {
                this.lagTicks = 0;
            } else {
                this.lagTicks--;
            }
            if (this.lagTicks == 0) {
                stopLag();
                if (this.module.crabBlockAgain.getValue()) {
                    this.cooldown = 0;
                }
            }
        }

        if (this.blocking) {
            this.heldTicks++;
            if (keyOurs && this.heldTicks < ticksOfMs(this.module.crabMaxHold.getValue())) {
                showAnimation(animation);
                return;
            }
            setUseKey(false);
            this.keyOwned = true;
            int chance = this.module.crabLagChance.getValue();
            if (chance > 0 && this.random.nextFloat() * 100.0F < chance) {
                this.lagTicks = ticksOfMs(this.module.crabLagMax.getValue());
                if (this.lagTicks > 0) {
                    startLag();
                }
            }
            this.blocking = false;
            showAnimation(animation);
            return;
        }

        if (keyOurs && this.cooldown == 0 && inRange) {
            setUseKey(true);
            click();
            this.keyOwned = true;
            this.blocking = true;
            this.cooldown = COOLDOWN_TICKS;
            this.heldTicks = 0;
            stopLag();
        }
        showAnimation(animation);
    }

    private static boolean physicalUse() {
        return KeyBindUtil.isKeyDown(mc.gameSettings.keyBindUseItem.getKeyCode());
    }

    private static void setUseKey(boolean down) {
        ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(down);
    }

    /** One more press of the use key for vanilla's click loop. */
    private static void click() {
        KeyBinding use = mc.gameSettings.keyBindUseItem;
        ((IAccessorKeyBinding) use).setPressTime(((IAccessorKeyBinding) use).getPressTime() + 1);
    }

    /** right-mouse: no attack while not blocking. Without a sword, any lag ends. */
    void onAttack(AttackEvent event) {
        if (!ItemUtil.isHoldingSword()) {
            stopLag();
        }
        if (this.rightRequired && !this.blocking) {
            event.setCancelled(true);
        }
    }

    /** prevent-delaying-attacks: an attack lets the held packets go before it. */
    void onSend(PacketEvent event) {
        if (!this.lagging || event.getType() != EventType.SEND || !this.module.crabPreventDelay.getValue()) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            stopLag();
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

    private void stopLag() {
        this.lagTicks = 0;
        if (this.lagging) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.AUTO_BLOCK_LAG);
        }
        this.lagging = false;
    }

    /** The drawn-blocking count runs down from a sword's use time while shown, as a real block's would. */
    private void showAnimation(boolean show) {
        if (!show) {
            this.animationCount = USE_DURATION;
            return;
        }
        this.animationShown = true;
        ((IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(--this.animationCount);
    }

    private void endAnimation() {
        if (this.animationShown) {
            if (mc.thePlayer != null && !mc.thePlayer.isUsingItem()) {
                ((IAccessorEntityPlayer) mc.thePlayer).setItemInUseCount(0);
            }
            this.animationShown = false;
        }
    }

    /** Held packets go, the sword down, and the use key back to what the player really holds. */
    void reset(boolean releaseKey) {
        stopLag();
        this.blocking = false;
        this.cooldown = 0;
        this.heldTicks = 0;
        this.sinceAttack = 0;
        this.rightRequired = false;
        this.lastAttackerTime = Integer.MIN_VALUE;
        if (releaseKey && this.keyOwned && mc.gameSettings != null) {
            setUseKey(mc.currentScreen == null && physicalUse());
            this.keyOwned = false;
        }
        endAnimation();
        this.animationCount = USE_DURATION;
        this.active = false;
    }
}
