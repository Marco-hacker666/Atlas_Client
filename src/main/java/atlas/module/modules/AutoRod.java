package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.CombatTargeting;
import atlas.util.HealthUtil;
import atlas.util.RotationUtil;
import atlas.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * AutoRod (2026-10-31, ported from LiquidBounce legacy's AutoRod, GPL-3; it
 * replaces the earlier OpenSkid port).
 *
 * Casts the fishing rod at the enemy the crosshair is on -- or the one on the
 * look up to activation-distance -- when this player is healthy enough, few
 * enough enemies are near and the enemy is not nearly dead; casts anyway to
 * escape when low; with facing-enemy off, casts whenever it can. The rod goes
 * out through vanilla's own use (sendUseItem), is taken back after
 * pullback-delay, and the next cast waits push-delay after that. No aim of its
 * own: it throws where the player looks.
 *
 * Differences from the original, each a fix: the pull-back reels the line in
 * when the rod was already in hand (the original's stopUsingItem does nothing
 * for a rod, so the line stayed out); a cast with the rod in hand keeps that
 * rod (the original re-picked the first rod in the hotbar, a slot change with
 * nothing to switch back from); enemies behind a wall are not cast at; the
 * slot is given back when the module is switched off mid-cast.
 */
public class AutoRod extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty facingEnemy = new BooleanProperty("facing-enemy", true);
    public final BooleanProperty ignoreOnEnemyLowHealth = new BooleanProperty("ignore-on-enemy-low-health", true,
            this.facingEnemy::getValue);
    public final BooleanProperty healthFromScoreboard = new BooleanProperty("health-from-scoreboard", false,
            () -> this.facingEnemy.getValue() && this.ignoreOnEnemyLowHealth.getValue());
    public final BooleanProperty absorption = new BooleanProperty("absorption", false,
            () -> this.facingEnemy.getValue() && this.ignoreOnEnemyLowHealth.getValue());
    public final FloatProperty activationDistance = new FloatProperty("activation-distance", 8.0F, 1.0F, 20.0F);
    public final IntProperty enemiesNearby = new IntProperty("enemies-nearby", 1, 1, 5);
    public final IntProperty playerHealthThreshold = new IntProperty("player-health-threshold", 5, 1, 20);
    public final IntProperty enemyHealthThreshold = new IntProperty("enemy-health-threshold", 5, 1, 20,
            () -> this.facingEnemy.getValue() && this.ignoreOnEnemyLowHealth.getValue());
    public final IntProperty escapeHealthThreshold = new IntProperty("escape-health-threshold", 10, 1, 20);
    public final IntProperty pushDelay = new IntProperty("push-delay", 100, 50, 1000);
    public final IntProperty pullbackDelay = new IntProperty("pullback-delay", 500, 50, 1000);
    public final BooleanProperty onUsingItem = new BooleanProperty("on-using-item", false);

    private final TimerUtil pushTimer = new TimerUtil();
    private final TimerUtil rodPullTimer = new TimerUtil();
    private boolean rodInUse;
    private int switchBack = -1;

    public AutoRod() {
        super("AutoRod", false, false, "Casts your fishing rod at the enemy you face, or to escape when low.");
    }

    @Override
    public void onEnabled() {
        this.pushTimer.reset();
        this.rodPullTimer.reset();
        this.rodInUse = false;
        this.switchBack = -1;
    }

    @Override
    public void onDisabled() {
        if (this.rodInUse) {
            this.giveSlotBack();
        }
        this.rodInUse = false;
        this.switchBack = -1;
    }

    @EventTarget(whenDisabled = true)
    public void onWorld(LoadWorldEvent event) {
        /* A slot remembered in the last world means nothing in this one. */
        this.rodInUse = false;
        this.switchBack = -1;
    }

    /** TickEvent PRE (runTick head): where vanilla's own right click happens. */
    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.playerController == null || mc.currentScreen != null) {
            return;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        boolean usingRod = this.rodInUse || mc.thePlayer.isUsingItem() && isRod(held);
        if (usingRod) {
            if (this.rodPullTimer.hasTimeElapsed(this.pullbackDelay.getValue())) {
                if (this.switchBack != -1 && mc.thePlayer.inventory.currentItem != this.switchBack) {
                    /* Back to the previous item: the line is taken in with it. */
                    this.giveSlotBack();
                } else if (mc.thePlayer.fishEntity != null && isRod(held)) {
                    /* The rod was already in hand: a second use reels it in. */
                    mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
                }
                this.switchBack = -1;
                this.rodInUse = false;
                /* The next cast always waits push-delay after the pull-back. */
                this.pushTimer.reset();
            }
            return;
        }
        if (!this.shouldRod()) {
            return;
        }
        if (!this.pushTimer.hasTimeElapsed(this.pushDelay.getValue())) {
            return;
        }
        int slot;
        if (isRod(held)) {
            slot = mc.thePlayer.inventory.currentItem;
        } else {
            slot = findRod();
            if (slot == -1) {
                return;
            }
            this.switchBack = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
            mc.playerController.updateController();
        }
        ItemStack rod = mc.thePlayer.inventory.getStackInSlot(slot);
        if (!isRod(rod)) {
            return;
        }
        mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, rod);
        this.rodInUse = true;
        this.rodPullTimer.reset();
    }

    private boolean shouldRod() {
        float own = health(mc.thePlayer);
        if (this.facingEnemy.getValue() && own >= this.playerHealthThreshold.getValue()) {
            if (!this.onUsingItem.getValue() && !isRod(mc.thePlayer.getItemInUse())
                    && (mc.thePlayer.isUsingItem() || auraBlocking())) {
                return false;
            }
            EntityLivingBase facing = this.facingEnemy();
            if (facing == null || this.nearbyEnemies() > this.enemiesNearby.getValue()) {
                return false;
            }
            return !this.ignoreOnEnemyLowHealth.getValue() || health(facing) >= this.enemyHealthThreshold.getValue();
        }
        if (own <= this.escapeHealthThreshold.getValue()) {
            /* Low: a cast to keep them off. */
            return true;
        }
        /* Facing-enemy off: cast whenever it can. */
        return !this.facingEnemy.getValue();
    }

    /** The enemy under the crosshair, else the first one on the look within activation-distance. */
    private EntityLivingBase facingEnemy() {
        MovingObjectPosition over = mc.objectMouseOver;
        if (over != null && over.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && this.selected(over.entityHit)) {
            return (EntityLivingBase) over.entityHit;
        }
        double reach = this.activationDistance.getValue();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = mc.thePlayer.getLook(1.0F);
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        EntityLivingBase best = null;
        double bestDistance = reach;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!this.selected(player)) {
                continue;
            }
            float border = player.getCollisionBorderSize();
            AxisAlignedBB box = player.getEntityBoundingBox().expand(border, border, border);
            MovingObjectPosition hit = box.calculateIntercept(eyes, end);
            double distance = box.isVecInside(eyes) ? 0.0 : hit == null ? Double.MAX_VALUE : eyes.distanceTo(hit.hitVec);
            if (distance <= bestDistance && mc.thePlayer.canEntityBeSeen(player)) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private int nearbyEnemies() {
        int count = 0;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (this.selected(player) && RotationUtil.distanceToEntity(player) < this.activationDistance.getValue()) {
                count++;
            }
        }
        return count;
    }

    /** An enemy: another living player the client's targeting accepts (friends, team mates and bots never). */
    private boolean selected(Entity entity) {
        if (!(entity instanceof EntityPlayer) || entity == mc.thePlayer) {
            return false;
        }
        EntityPlayer player = (EntityPlayer) entity;
        return player.getHealth() > 0.0F && CombatTargeting.isTrackablePlayer(player, true) && TargetFilter.accepts(player);
    }

    private float health(EntityLivingBase entity) {
        float health = this.healthFromScoreboard.getValue() ? HealthUtil.resolve(entity) : entity.getHealth();
        return this.absorption.getValue() ? health + entity.getAbsorptionAmount() : health;
    }

    private static boolean auraBlocking() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(KillAura.class);
        return module instanceof KillAura && module.isEnabled() && ((KillAura) module).isBlocking();
    }

    private static boolean isRod(ItemStack stack) {
        return stack != null && stack.getItem() == Items.fishing_rod;
    }

    private static int findRod() {
        for (int i = 0; i < 9; i++) {
            if (isRod(mc.thePlayer.inventory.getStackInSlot(i))) {
                return i;
            }
        }
        return -1;
    }

    private void giveSlotBack() {
        if (this.switchBack >= 0 && this.switchBack < 9 && mc.thePlayer != null
                && mc.thePlayer.inventory.currentItem != this.switchBack) {
            mc.thePlayer.inventory.currentItem = this.switchBack;
            if (mc.playerController != null) {
                mc.playerController.updateController();
            }
        }
    }
}
