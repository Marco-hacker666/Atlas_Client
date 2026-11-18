package atlas.module.modules;

import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.ui.impl.clickgui.normal.ClickGuiScreen;
import atlas.ui.impl.clickgui.atlas.AtlasClickGui;
import atlas.ui.impl.clickgui.glide.GlideClickGui;
import atlas.ui.impl.clickgui.slinky.SlinkyClickGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.awt.*;

public class ClickGUIModule extends Module {
    private boolean switchingGuiStyle;

    private static final int[] COLORS = {
            0xFF4FC3F7,
            0xFF81C784,
            0xFFFF8A65,
            0xFFBA68C8,
            0xFFFFD54F,
            0xFFFF6B6B,
            0xFF4DB6AC,
            0xFFFFFFFF,
    };
    private static final String[] COLOR_NAMES = {
            "Sky Blue", "Green", "Orange", "Purple", "Yellow", "Red", "Teal", "White"
    };

    public ModeProperty accentColor = new ModeProperty("Color", 0, COLOR_NAMES);

    public ModeProperty style = new ModeProperty("Style", 1, new String[]{"Normal", "Atlas", "Panel", "Slinky", "Glide"});

    public ColorProperty slinkyAccent = new ColorProperty("slinky-accent", 0xDD4747, () -> this.style.getValue() == 3);

    public ColorProperty glideAccent = new ColorProperty("glide-accent", 0x048668, () -> this.style.getValue() == 4);

    public BooleanProperty glideBlur = new BooleanProperty("glide-blur", true, () -> this.style.getValue() == 4);
    public BooleanProperty saveGuiState = new BooleanProperty("Save GUI State", true);
    public BooleanProperty shadow = new BooleanProperty("Shadow", true);

    public IntProperty windowWidth = new IntProperty("Window Width", 600, 300, 1200);
    public IntProperty windowHeight = new IntProperty("Window Height", 400, 200, 800);
    public FloatProperty cornerRadius = new FloatProperty("Corner Radius", 8.0f, 0.0f, 20.0f);

    public IntProperty scrollSpeed = new IntProperty("Scroll Speed", 3, 1, 10);

    public static int wheelSteps(int wheel) {
        int notches = Math.max(1, Math.abs(wheel) / 120);
        ClickGUIModule gui = atlas.Atlas.moduleManager == null ? null
                : (ClickGUIModule) atlas.Atlas.moduleManager.modules.get(ClickGUIModule.class);
        return notches * (gui == null ? 1 : gui.scrollSpeed.getValue());
    }

    public Color getAccentColor() {
        int idx = accentColor.getValue();
        if (idx < 0 || idx >= COLORS.length) idx = 0;
        return new Color(COLORS[idx], true);
    }

    public ClickGUIModule() {
        super("ClickGUI", false);
        setKey(Keyboard.KEY_RSHIFT);
    }

    public void openSelectedGui() {
        Minecraft mc = Minecraft.getMinecraft();

        if (atlas.management.UsageReporter.consent() == null && !(mc.currentScreen instanceof atlas.ui.ConsentScreen)) {
            mc.displayGuiScreen(new atlas.ui.ConsentScreen(this::openSelectedGui));
            return;
        }
        GuiScreen screen = getSelectedGui();
        this.switchingGuiStyle = isClickGui(mc.currentScreen);
        try {
            mc.displayGuiScreen(screen);
        } finally {
            this.switchingGuiStyle = false;
        }
    }

    public boolean isSwitchingGuiStyle() {
        return switchingGuiStyle;
    }

    public GuiScreen getSelectedGui() {
        if (style.getValue() == 4) {
            return GlideClickGui.getInstance();
        }
        if (style.getValue() == 3) {
            return SlinkyClickGui.getInstance();
        }
        if (style.getValue() == 2) {
            return atlas.ui.impl.clickgui.panel.PanelClickGui.getInstance();
        }
        if (style.getValue() == 1) {
            return AtlasClickGui.getInstance();
        }
        return ClickGuiScreen.getInstance();
    }

    public static boolean isClickGui(GuiScreen screen) {
        return screen instanceof ClickGuiScreen || screen instanceof AtlasClickGui
                || screen instanceof atlas.ui.impl.clickgui.panel.PanelClickGui || screen instanceof SlinkyClickGui
                || screen instanceof GlideClickGui;
    }

    @Override
    public void verifyValue(String name) {
        if ("Style".equalsIgnoreCase(name)) {
            Minecraft mc = Minecraft.getMinecraft();
            if (isClickGui(mc.currentScreen)) {
                openSelectedGui();
            }
        }
    }

    @Override
    public boolean toggle() {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.isEnabled() && mc.theWorld != null && mc.currentScreen == null) {
            atlas.ui.GuiPerf.open();
            openSelectedGui();
            atlas.ui.GuiPerf.opened();
            return false;
        }
        return super.toggle();
    }

    @Override
    public void onEnabled() {
        super.onEnabled();
        if (Minecraft.getMinecraft().theWorld == null) {
            this.setEnabled(false);
            return;
        }
        atlas.ui.GuiPerf.open();
        openSelectedGui();
        atlas.ui.GuiPerf.opened();
    }

    @Override
    public void onDisabled() {
        super.onDisabled();
        Minecraft.getMinecraft().displayGuiScreen(null);
        if (Minecraft.getMinecraft().currentScreen == null) {
            Minecraft.getMinecraft().setIngameFocus();
        }
    }
}
