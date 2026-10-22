package atlas.module.modules;

import atlas.Atlas;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.module.Module;
import net.minecraft.client.Minecraft;

/**
 * Use items while breaking a block (2026-10-19, Slinky's Item Use Fix
 * rebuilt).
 *
 * The game ignores right click while a block is being broken
 * (Minecraft.rightClickMouse checks PlayerControllerMP.isHittingBlock).
 * For the length of that one call the controller is told it is not
 * breaking, then it is told again that it is, so mining carries on.
 */
public class ItemUseFix extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean restoreBreaking;

    public ItemUseFix() {
        super("ItemUseFix", false);
    }

    /** MixinMinecraft, at the head of rightClickMouse once nothing has cancelled it. */
    public static void beforeRightClick() {
        restoreBreaking = false;
        if (Atlas.moduleManager == null || mc.playerController == null) {
            return;
        }
        Module self = Atlas.moduleManager.modules.get(ItemUseFix.class);
        if (self == null || !self.isEnabled()) {
            return;
        }
        IAccessorPlayerControllerMP controller = (IAccessorPlayerControllerMP) mc.playerController;
        if (controller.getIsHittingBlock()) {
            controller.setIsHittingBlock(false);
            restoreBreaking = true;
        }
    }

    /** MixinMinecraft, when rightClickMouse returns. */
    public static void afterRightClick() {
        if (restoreBreaking && mc.playerController != null) {
            ((IAccessorPlayerControllerMP) mc.playerController).setIsHittingBlock(true);
        }
        restoreBreaking = false;
    }

    @Override
    public void onDisabled() {
        afterRightClick();
    }
}
