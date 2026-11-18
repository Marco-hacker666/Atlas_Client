package atlas.module.modules;

import atlas.Atlas;
import atlas.module.Module;
import atlas.ui.impl.clickgui.normal.ClickGuiScreen;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

public class GuiModule extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public GuiModule() {
        super("ClickGui", false);
        setKey(Keyboard.KEY_RSHIFT);
    }

    @Override
    public void onEnabled() {
        setEnabled(false);
        ClickGUIModule clickGui = (ClickGUIModule) Atlas.moduleManager.getModule("ClickGUI");
        if (clickGui != null && clickGui.getKey() == this.getKey()) {
            return;
        }
        if (clickGui != null) {
            if (clickGui.isEnabled()) {
                clickGui.openSelectedGui();
            } else {
                clickGui.setEnabled(true);
            }
            return;
        }
        mc.displayGuiScreen(ClickGuiScreen.getInstance());
    }
}
