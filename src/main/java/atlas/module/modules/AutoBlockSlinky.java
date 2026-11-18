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

final class AutoBlockSlinky {
    private static final Minecraft mc = Minecraft.getMinecraft();

    static final int COOLDOWN_TICKS = 10;

    static final int ATTACK_GAP_TICKS = 10;

    static final double TARGET_SEARCH = 6.0;

    static final int USE_DURATION = 72000;

    private final BlockHit module;
    private final Random random = new Random();
    private boolean active;
    private boolean blocking;

    private boolean keyOwned;
    private boolean rightRequired;
    private boolean lagging;
    private int cooldown;
    private int heldTicks;
    private int lagTicks;
    private int sinceAttack;
    private int lastTargetId;
    private int lastAttackerTime = Integer.MIN_VALUE;

    private Object attackerOf;
    private boolean animationShown;
    private int animationCount = USE_DURATION;

    AutoBlockSlinky(BlockHit module) {
        this.module = module;
    }

    boolean active() {
        return this.active;
    }

    boolean lagging() {
        return this.lagging;
    }

    static int ticks(float value, float roll) {
        if (Float.isNaN(value) || value <= 0.0F) {
            return 0;
        }
        int whole = (int) Math.min(value, Integer.MAX_VALUE - 1);
        return whole + (roll < value - whole ? 1 : 0);
    }

    private int extraHold;
    private long blockEndedAt = -1L;

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
        } else if (this.module.sRequireLeft.getValue() && !KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            giveBack = true;
        } else {
            boolean needRight = this.module.sRequireRight.getValue();
            this.rightRequired = needRight;
            if (needRight != physicalUse()) {
                giveBack = true;
            } else {
                conditionsOk = true;
                giveBack = hurt == 0 && this.module.sRequireDamage.getValue()
                        || hurt > this.module.sHurt.getValue();
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
        float range = this.module.sRange.getValue();
        boolean inRange = target != null && mc.thePlayer.getDistanceSqToEntity(target) < range * range;
        boolean animation = conditionsOk && this.module.sFake.getValue()
                && (!this.module.sFakeInRange.getValue() || inRange);
        if (hurt == 10) {
            this.cooldown = 0;
        }

        if (this.attackerOf != mc.thePlayer) {
            this.attackerOf = mc.thePlayer;
            this.lastAttackerTime = Integer.MIN_VALUE;
        }
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
            if (hit && this.module.sLagFlush.getValue()) {
                this.lagTicks = 0;
            } else {
                this.lagTicks--;
            }
            if (this.lagTicks == 0) {
                stopLag();
                if (this.module.sLagReblock.getValue()) {
                    this.cooldown = 0;
                }
            }
        }

        if (this.blocking) {
            this.heldTicks++;
            if (keyOurs && this.heldTicks < this.module.sHold.getValue() + this.extraHold) {
                showAnimation(animation);
                return;
            }
            setUseKey(false);
            this.keyOwned = true;
            this.blockEndedAt = System.currentTimeMillis();
            int chance = this.module.sLagChance.getValue();
            if (chance > 0 && this.random.nextFloat() * 100.0F < chance) {
                this.lagTicks = this.module.sLagTicks.getValue();
                if (this.lagTicks > 0) {
                    startLag();
                }
            }
            this.blocking = false;
            showAnimation(animation);
            return;
        }

        long since = this.blockEndedAt < 0L ? -1L : System.currentTimeMillis() - this.blockEndedAt;
        if (keyOurs && this.cooldown == 0 && inRange
                && !AutoBlockRules.coolingDown(this.module.sCooldown.getValue(), since)) {
            this.extraHold = AutoBlockRules.extraHoldTicks(this.module.sRandomHold.getValue(), Math.random(), Math.random());
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

    private static void click() {
        KeyBinding use = mc.gameSettings.keyBindUseItem;
        ((IAccessorKeyBinding) use).setPressTime(((IAccessorKeyBinding) use).getPressTime() + 1);
    }

    void onAttack(AttackEvent event) {
        if (!ItemUtil.isHoldingSword()) {
            stopLag();
        }
        if (this.rightRequired && !this.blocking) {
            event.setCancelled(true);
        }
    }

    void onSend(PacketEvent event) {
        if (!this.lagging || event.getType() != EventType.SEND || !this.module.sLagFlush.getValue()) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            stopLag();
            if (this.module.sLagReblock.getValue()) {
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

    void reset(boolean releaseKey) {
        stopLag();
        this.blocking = false;
        this.cooldown = 0;
        this.heldTicks = 0;
        this.sinceAttack = 0;
        this.rightRequired = false;
        this.lastAttackerTime = Integer.MIN_VALUE;
        this.attackerOf = null;

        if (this.keyOwned && mc.gameSettings != null) {
            setUseKey(releaseKey && mc.currentScreen == null && physicalUse());
            this.keyOwned = false;
        }
        endAnimation();
        this.animationCount = USE_DURATION;
        this.active = false;
    }
}
