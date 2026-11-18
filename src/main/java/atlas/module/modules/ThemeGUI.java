package atlas.module.modules;

import atlas.module.Module;
import atlas.ui.impl.clickgui.atlas.AtlasClickGui;
import atlas.ui.impl.clickgui.atlas.ThemeStudio;
import net.minecraft.client.Minecraft;

public class ThemeGUI extends Module {
    public ThemeGUI() {
        super("ThemeGUI", false, true, "Opens the Theme window on its own key.");
    }

    @Override
    public void onEnabled() {
        this.setEnabled(false);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof ThemeStudio) {
            return;
        }
        mc.displayGuiScreen(new ThemeStudio(AtlasClickGui.getInstance(), mc.currentScreen));
    }
}
