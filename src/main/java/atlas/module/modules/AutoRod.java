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

// AutoRod (ported from LiquidBounce legacy's AutoRod, GPL-3)

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
        this.rodInUse = false;
        this.switchBack = -1;
    }

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
                    this.giveSlotBack();
                } else if (mc.thePlayer.fishEntity != null && isRod(held)) {
                    mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
                }
                this.switchBack = -1;
                this.rodInUse = false;

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
            return true;
        }

        return !this.facingEnemy.getValue();
    }

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
