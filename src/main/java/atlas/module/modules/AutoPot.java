package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0): throws useful splash potions when needed,
// with an optional hotbar refill from the inventory.
import java.util.List;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.util.PacketUtil;
import atlas.util.TimerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

public class AutoPot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    private int prevSlot = -1;
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "JUMP", "HOP"});
    public final PercentProperty health = new PercentProperty("health", 75);
    public final IntProperty delay = new IntProperty("delay", 500, 100, 2000);
    public final BooleanProperty heal = new BooleanProperty("heal", true);
    public final BooleanProperty regen = new BooleanProperty("regen", true);
    public final BooleanProperty fireRes = new BooleanProperty("fire-res", true);
    public final BooleanProperty strength = new BooleanProperty("strength", true);
    public final BooleanProperty speed = new BooleanProperty("speed", true);
    public final BooleanProperty jump = new BooleanProperty("jump", true);
    public final BooleanProperty refill = new BooleanProperty("refill", true);
    public final FloatProperty hopHeight = new FloatProperty("hop-height", 0.42F, 0.0F, 0.6F, () -> mode.getValue() == 2);

    public AutoPot() {
        super("AutoPot", false, false, "Automatically throws useful splash potions when needed.");
    }

    @Override
    public void onEnabled() {
        this.prevSlot = -1;
        this.timer.reset();
    }

    @Override
    public void onDisabled() {
        this.restoreSlot();
        this.timer.reset();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.playerController == null) {
            return;
        }
        if (event.getType() == EventType.POST) {
            this.restoreSlot();
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (!this.timer.hasTimeElapsed(this.delay.getValue())) {
            return;
        }
        if (mc.thePlayer.capabilities.isFlying) {
            return;
        }
        int potionSlot = this.findPotion(0, 8);
        if (potionSlot == -1) {
            if (this.refill.getValue()) {
                int invSlot = this.findPotion(9, 35);
                /* 2026-10-31 (audit): only with the player's own inventory as the open container. With a chest or a
                   furnace open, the click was applied to that container's slot numbering here while the server
                   dropped it (window 0 is not the open one): the items shown were no longer where they were. */
                if (invSlot != -1 && this.hasSpaceInHotbar()
                        && mc.thePlayer.openContainer == mc.thePlayer.inventoryContainer) {
                    mc.playerController.windowClick(0, invSlot, 0, 1, mc.thePlayer);
                    this.timer.reset();
                }
            }
            return;
        }
        if (this.mode.getValue() != 0 && !mc.thePlayer.onGround) {
            return;
        }
        if (this.mode.getValue() == 1) {
            mc.thePlayer.jump();
        } else if (this.mode.getValue() == 2) {
            mc.thePlayer.motionY = this.hopHeight.getValue();
        }
        this.prevSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = potionSlot;
        mc.playerController.updateController();
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(potionSlot);
        if (stack != null) {
            mc.thePlayer.rotationPitch = 90.0F;
            PacketUtil.sendPacket(new C08PacketPlayerBlockPlacement(stack));
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
            this.timer.reset();
        } else {
            this.restoreSlot();
        }
    }

    private void restoreSlot() {
        if (this.prevSlot != -1 && mc.thePlayer != null && mc.playerController != null) {
            mc.thePlayer.inventory.currentItem = this.prevSlot;
            mc.playerController.updateController();
        }
        this.prevSlot = -1;
    }

    private int findPotion(int start, int end) {
        for (int i = start; i <= end; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!this.isSplashPotion(stack)) {
                continue;
            }
            if (this.matchesWantedEffect(stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSplashPotion(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemPotion && ItemPotion.isSplash(stack.getItemDamage());
    }

    private boolean matchesWantedEffect(ItemStack stack) {
        List<PotionEffect> effects = ((ItemPotion) stack.getItem()).getEffects(stack);
        if (effects == null) {
            return false;
        }
        float hp = mc.thePlayer.getHealth();
        for (PotionEffect effect : effects) {
            int id = effect.getPotionID();
            if (this.heal.getValue() && id == Potion.heal.id && hp <= (float) this.health.getValue() / 100.0F * mc.thePlayer.getMaxHealth()) {
                return true;
            }
            if (this.regen.getValue() && id == Potion.regeneration.id && hp <= (float) this.health.getValue() / 100.0F * mc.thePlayer.getMaxHealth()
                    && !mc.thePlayer.isPotionActive(Potion.regeneration)) {
                return true;
            }
            if (this.fireRes.getValue() && id == Potion.fireResistance.id && !mc.thePlayer.isPotionActive(Potion.fireResistance)) {
                return true;
            }
            if (this.speed.getValue() && id == Potion.moveSpeed.id && !mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                return true;
            }
            if (this.jump.getValue() && id == Potion.jump.id && !mc.thePlayer.isPotionActive(Potion.jump)) {
                return true;
            }
            if (this.strength.getValue() && id == Potion.damageBoost.id && !mc.thePlayer.isPotionActive(Potion.damageBoost)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSpaceInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.thePlayer.inventory.getStackInSlot(i) == null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
