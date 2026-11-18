package atlas.module.modules;

import atlas.Atlas;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.module.Module;
import net.minecraft.client.Minecraft;

public class ItemUseFix extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static boolean restoreBreaking;

    public ItemUseFix() {
        super("ItemUseFix", false);
    }

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
