package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.TickEvent;
import atlas.util.KeyBindUtil;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.PercentProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemFood;
import atlas.mixin.IAccessorKeyBinding;

public class AutoGapple extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final PercentProperty healthPercentage = new PercentProperty("Health-Percent", 50);
    public final BooleanProperty eatInCombat = new BooleanProperty("Eat-In-Combat", false);

    private boolean isEating = false;
    private int eatTickCount = 0;
    private static final int EAT_DURATION_TICKS = 32;
    private int originalHotbarSlot = -1;

    public AutoGapple() {
        super("Gapple", false, false);
    }

    @Override
    public void onEnabled() {
        isEating = false;
        eatTickCount = 0;
        originalHotbarSlot = -1;
        super.onEnabled();
    }

    @Override
    public void onDisabled() {
        if (isEating) {
            releaseUseKey();
        }

        if (originalHotbarSlot >= 0 && originalHotbarSlot < 9 && mc.thePlayer != null && mc.thePlayer.inventory.currentItem != originalHotbarSlot) {
            mc.thePlayer.inventory.currentItem = originalHotbarSlot;
        }
        isEating = false;
        eatTickCount = 0;
        originalHotbarSlot = -1;
        super.onDisabled();
    }

    private static void releaseUseKey() {
        KeyBindUtil.updateKeyState(mc.gameSettings.keyBindUseItem.getKeyCode());
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        if (isEating) {
            releaseUseKey();
        }
        isEating = false;
        eatTickCount = 0;
        originalHotbarSlot = -1;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled()) {
            return;
        }

        if (event.getType() != EventType.PRE) {
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (isEating) {
            ItemStack heldItem = mc.thePlayer.getHeldItem();
            if (heldItem == null || heldItem.getItem() != Items.golden_apple) {
                releaseUseKey();
                isEating = false;

                if (originalHotbarSlot != -1 && mc.thePlayer.inventory.currentItem != originalHotbarSlot) {
                    mc.thePlayer.inventory.currentItem = originalHotbarSlot;
                    mc.playerController.updateController();
                }
                originalHotbarSlot = -1;
                eatTickCount = 0;
                return;
            }

            eatTickCount++;

            if (eatTickCount >= EAT_DURATION_TICKS) {
                releaseUseKey();
                isEating = false;

                if (originalHotbarSlot != -1 && mc.thePlayer.inventory.currentItem != originalHotbarSlot) {
                    mc.thePlayer.inventory.currentItem = originalHotbarSlot;
                    mc.playerController.updateController();
                }
                originalHotbarSlot = -1;
                eatTickCount = 0;
            }
            return;
        }

        if (!isEating) {
            if (!eatInCombat.getValue() && Atlas.playerStateManager.isInCombat()) {
                return;
            }

            float currentHealth = mc.thePlayer.getHealth();
            float maxHealth = mc.thePlayer.getMaxHealth();
            float healthPercent = (currentHealth / maxHealth) * 100.0F;

            if (healthPercent <= healthPercentage.getValue().floatValue()) {
                int gappleSlot = findGappleInHotbar();

                if (gappleSlot != -1) {
                    originalHotbarSlot = mc.thePlayer.inventory.currentItem;
                    mc.thePlayer.inventory.currentItem = gappleSlot;
                    mc.playerController.updateController();

                    ((IAccessorKeyBinding) mc.gameSettings.keyBindUseItem).setPressed(true);
                    isEating = true;
                    eatTickCount = 0;
                } else {
                }
            } else {
            }
        }
    }

    private int findGappleInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemFood) {
                if (stack.getItem() == Items.golden_apple) {
                    return i;
                }
            }
        }
        return -1;
    }
}
