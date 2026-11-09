package atlas.module.modules;

import atlas.module.Module;
import atlas.ui.impl.clickgui.atlas.AtlasClickGui;
import atlas.ui.impl.clickgui.atlas.ThemeStudio;
import net.minecraft.client.Minecraft;

/**
 * Opens the Theme window on its own key (2026-11-09, owner: keep it out of
 * the click menu's layout). It is not a mode: a press opens the window over
 * the game and the module switches itself straight back off; Esc in the
 * window goes back to the game. Unbound until a key is chosen (.bind
 * ThemeGUI KEY, or the bind field in the menu).
 */
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
