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

    // ── Color palette (same as TargetESP) ────────────────────────────────────
    private static final int[] COLORS = {
            0xFF4FC3F7, // Sky Blue
            0xFF81C784, // Green
            0xFFFF8A65, // Orange
            0xFFBA68C8, // Purple
            0xFFFFD54F, // Yellow
            0xFFFF6B6B, // Red
            0xFF4DB6AC, // Teal
            0xFFFFFFFF, // White
    };
    private static final String[] COLOR_NAMES = {
            "Sky Blue", "Green", "Orange", "Purple", "Yellow", "Red", "Teal", "White"
    };

    public ModeProperty accentColor = new ModeProperty("Color", 0, COLOR_NAMES);
    /* Only Atlas and Normal (the backup) are left (2026-10-06): Raven B3/B4,
       Cheadle, Modern and RiseLB were removed. The style is saved by name, so
       a config naming one of the removed styles keeps this default, Atlas. */
    public ModeProperty style = new ModeProperty("Style", 1, new String[]{"Normal", "Atlas", "Panel", "Slinky", "Glide"});
    /** The Slinky style's accent: switches, sliders, bound keys. */
    public ColorProperty slinkyAccent = new ColorProperty("slinky-accent", 0xDD4747, () -> this.style.getValue() == 3);
    /** The Glide style's accent (2026-10-26): enabled modules, switches, sliders. The HUD keeps its own colours. */
    public ColorProperty glideAccent = new ColorProperty("glide-accent", 0x048668, () -> this.style.getValue() == 4);
    /** The Glide style blurs the world behind its panels; off saves frames on weak PCs. */
    public BooleanProperty glideBlur = new BooleanProperty("glide-blur", true, () -> this.style.getValue() == 4);
    public BooleanProperty saveGuiState = new BooleanProperty("Save GUI State", true);
    public BooleanProperty shadow = new BooleanProperty("Shadow", true);

    public IntProperty windowWidth = new IntProperty("Window Width", 600, 300, 1200);
    public IntProperty windowHeight = new IntProperty("Window Height", 400, 200, 800);
    public FloatProperty cornerRadius = new FloatProperty("Corner Radius", 8.0f, 0.0f, 20.0f);
    /* 2026-10-27 (owner: "can the mouse wheel scroll faster?"): rows (or 16 px) per wheel notch in the menus. */
    public IntProperty scrollSpeed = new IntProperty("Scroll Speed", 3, 1, 10);

    /** Rows a wheel event moves the menus (notches times Scroll Speed); 1 before the module exists. */
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

    /** Whether the screen is one of the click menus. */
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

    /**
     * The key with the menu already closed by Escape (2026-10-17): Atlas does
     * not switch this module off when it closes, so the key used to switch it
     * off and the legacy "ClickGui" opener on the same key opened it again --
     * a second open over the first whenever the menu was really closed (the
     * 110 ms per press). With that opener stood down, the first press after
     * Escape only switched this off. Now a press with no menu showing opens
     * it, whatever this module's state.
     */
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
