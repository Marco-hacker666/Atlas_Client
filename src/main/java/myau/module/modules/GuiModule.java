package myau.module.modules;

import myau.Myau;
import myau.module.Module;
import myau.ui.impl.clickgui.normal.ClickGuiScreen;
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
        ClickGUIModule clickGui = (ClickGUIModule) Myau.moduleManager.getModule("ClickGUI");
        if (clickGui != null && clickGui.getKey() == this.getKey()) {
            /* 2026-10-17: both ship bound to Right Shift, and ClickGUI comes
               first in the key loop, so one press opened the menu and this
               then opened it again over itself -- a full close (two file
               writes) and a second initGui, ~110 ms a press in perf logs.
               The press is ClickGUI's; nothing to do here. */
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
