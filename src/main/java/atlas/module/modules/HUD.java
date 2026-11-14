package atlas.module.modules;

import atlas.ui.impl.clickgui.atlas.HudPanel;
import atlas.ui.UiMode;
import atlas.ui.hud.HudLayout;
import atlas.ui.hud.ListGlPainter;
import atlas.ui.hud.list.ListAnim;
import atlas.ui.hud.list.ListColors;
import atlas.ui.hud.list.ListFrame;
import atlas.ui.hud.list.ListLayout;
import atlas.ui.hud.list.ListPresets;
import atlas.ui.hud.list.ListRow;
import atlas.ui.hud.list.ListStyle;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.enums.ChatColors;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.Render2DEvent;
import atlas.events.TickEvent;
import atlas.font.CFontRenderer;
import atlas.font.FontProcess;
import atlas.mixin.IAccessorGuiChat;
import atlas.module.Module;
import atlas.util.ColorUtil;
import atlas.util.RenderUtil;
import atlas.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class HUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public static int targetHUDX = 100;
    public static int targetHUDY = 100;

    public static void setTargetHUDPosition(int x, int y) {
        targetHUDX = x;
        targetHUDY = y;
    }

    public static void resetTargetHUDPosition() {
        targetHUDX = 100;
        targetHUDY = 100;
    }

    private static final Set<Class<?>> RENDER_MODULES = new HashSet<>(Arrays.<Class<?>>asList(
            ESP.class, Chams.class, FullBright.class, Tracers.class, NameTags.class, Xray.class,
            TargetESP.class, TargetHUD.class, Indicators.class, BedESP.class, ItemESP.class,
            ViewClip.class, NoHurtCam.class, HUD.class, GuiModule.class,
            ChestESP.class, Trajectories.class, Radar.class, RenderFixes.class, FPScounter.class,
            WaterMark.class, HitParticleEffects.class, DynamicIsland.class,
            ArmorHUD.class, EffectsHUD.class, LegitHUD.class,
            TeamHealthDisplay.class, Statistics.class, Animations.class, Hotbar.class
    ));
    private static final Set<Class<?>> PLAYER_MODULES = new HashSet<>(Arrays.<Class<?>>asList(
            AutoHeal.class, FakeLag.class, AutoTool.class, ChestStealer.class, InvManager.class,
            InvWalk.class, Scaffold.class, AutoBlockIn.class, AutoSwap.class, SpeedMine.class,
            FastPlace.class, GhostHand.class, MCF.class, AntiDebuff.class, FlagDetector.class,
            AutoGapple.class, ThrowAura.class, InventoryClicker.class
    ));
    private static final Set<Class<?>> MISC_MODULES = new HashSet<>(Arrays.<Class<?>>asList(
            BedNuker.class, BedTracker.class, LightningTracker.class, NoRotate.class,
            NickHider.class, AntiObfuscate.class, AutoAnduril.class,
            Disabler.class, ClientSpoofer.class, AutoHypixel.class
    ));
    private List<Module> activeModules = new ArrayList<>();
    /*
     * JELLO module-list animation (2026-10-08; ArrayList.svelte's
     * transition:fly x 50px / 200ms and animate:flip 200ms): a row slides in
     * from the screen edge and fades in when its module turns on, slides back
     * out and fades when it turns off, and every row eases to its new place.
     */
    private static final class RowAnim {
        float appear;
        float y = Float.NaN;
    }

    private final java.util.Map<Module, RowAnim> rowAnims = new java.util.LinkedHashMap<>();
    private long lastRowFrame;
    /* Each listed module's name, suffix and width, worked out once a tick with
       the sort that needs them anyway. The render used to redo all three for
       every module up to three times a frame (glow, blur, text) -- suffix
       strings built, lower-cased and measured some two thousand times a
       second at 700 fps -- for values that only change when the tick does. */
    private java.util.Map<Module, String> lineName = new java.util.HashMap<>();
    private java.util.Map<Module, String[]> lineSuffix = new java.util.HashMap<>();
    private java.util.Map<Module, Float> lineWidth = new java.util.HashMap<>();
    public final ModeProperty colorMode = new ModeProperty(
            "color", 3, new String[]{"RAINBOW", "CHROMA", "ASTOLFO", "CUSTOM1", "CUSTOM12", "CUSTOM123"}
    );
    public final FloatProperty colorSpeed = new FloatProperty("color-speed", 1.0F, 0.5F, 1.5F);
    public final PercentProperty colorSaturation = new PercentProperty("color-saturation", 50);
    public final PercentProperty colorBrightness = new PercentProperty("color-brightness", 100);
    public final ColorProperty custom1 = new ColorProperty("custom-color-1", Color.WHITE.getRGB(), () -> this.colorMode.getValue() == 3 || this.colorMode.getValue() == 4 || this.colorMode.getValue() == 5);
    public final ColorProperty custom2 = new ColorProperty("custom-color-2", Color.WHITE.getRGB(), () -> this.colorMode.getValue() == 4 || this.colorMode.getValue() == 5);
    public final ColorProperty custom3 = new ColorProperty("custom-color-3", Color.WHITE.getRGB(), () -> this.colorMode.getValue() == 5);
    public final ModeProperty posX = new ModeProperty("position-x", 0, new String[]{"LEFT", "RIGHT"});
    public final ModeProperty posY = new ModeProperty("position-y", 0, new String[]{"TOP", "BOTTOM"});
    public final IntProperty offsetX = new IntProperty("offset-x", 2, 0, 255);
    public final IntProperty offsetY = new IntProperty("offset-y", 2, 0, 255);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 1.5F);
    /* 2026-11-14: CUSTOM draws the module list from the list-* settings below (atlas.ui.hud.list). */
    public final ModeProperty interfaceMode = new ModeProperty("interface", 0, new String[]{"ATLAS", "CREIDA", "CUSTOM"}).alias("atlas", "ATLAS");
    /**
     * 2026-10-08: how every HUD element draws its background. JELLO
     * (default): LiquidBounce's JelloBounce theme -- black at 45%, a soft
     * dark halo, rounded cards, flush module-list rows (HudPanel). CLASSIC:
     * each HUD's own older drawing.
     */
    public final ModeProperty hudTheme = new ModeProperty("hud-theme", 0, new String[]{"JELLO", "CLASSIC"});
    public final PercentProperty background = new PercentProperty("background", 25);
    public final IntProperty bgAlpha = new IntProperty("bg-alpha", 120, 0, 255);
    public final BooleanProperty blur = new BooleanProperty("blur", true);
    public final FloatProperty blurRadius = new FloatProperty("blur-radius", 10.0F, 1.0F, 30.0F, this.blur::getValue);
    public final BooleanProperty glow = new BooleanProperty("glow", true);
    public final ModeProperty glowColorMode = new ModeProperty("glow-color", 0, new String[]{"SYNC", "CUSTOM"}, this.glow::getValue);
    public final ColorProperty glowCustomColor = new ColorProperty("glow-custom-color", Color.WHITE.getRGB() & 0xFFFFFF, () -> this.glow.getValue() && this.glowColorMode.getValue() == 1);
    public final IntProperty glowAlpha = new IntProperty("glow-alpha", 100, 0, 255, this.glow::getValue);
    public final FloatProperty glowRadius = new FloatProperty("glow-radius", 3.0F, 1.0F, 15.0F, this.glow::getValue);
    public final BooleanProperty showBar = new BooleanProperty("bar", true, () -> !this.customList());
    public final ModeProperty sidebarMode = new ModeProperty("sidebar-mode", 0, new String[]{"RIGHT", "LEFT", "TOP", "OUTLINE", "NONE"}, () -> this.showBar.getValue() && !this.customList());
    public final BooleanProperty shadow = new BooleanProperty("shadow", true);
    public final BooleanProperty shaders = new BooleanProperty("Shaders", false);
    public final FloatProperty colorDistance = new FloatProperty("color-dist", 50F, 10F, 100F);
    public final BooleanProperty suffixes = new BooleanProperty("suffixes", true);
    public final ModeProperty separatorMode = new ModeProperty("separator-mode", 0, new String[]{"SPACE", "-", "!", "[]", "{}", "()", "\"\""}, this.suffixes::getValue);
    public final BooleanProperty lowerCase = new BooleanProperty("lower-case", false);
    public final BooleanProperty hideRender = new BooleanProperty("hide-render", false);
    public final BooleanProperty hidePlayer = new BooleanProperty("hide-player", false);
    public final BooleanProperty hideMisc = new BooleanProperty("hide-misc", false);
    public final BooleanProperty chatOutline = new BooleanProperty("chat-outline", true);
    public final BooleanProperty blinkTimer = new BooleanProperty("blink-timer", true);
    public final BooleanProperty notifications = new BooleanProperty("notifications", true);
    public final BooleanProperty toggleSound = new BooleanProperty("toggle-sounds", true);
    public final BooleanProperty toggleAlerts = new BooleanProperty("toggle-alerts", false);
    public final ModeProperty fontMode = new ModeProperty("font-mode", 6, new String[]{"SANS", "MINECRAFT", "NUNITO", "TENACITY", "TENACITY_BOLD", "SF_PRO", "GOOGLE_SANS"});
    public final BooleanProperty creidaFont = new BooleanProperty("creida-font", true, () -> this.interfaceMode.getValue() == 1);
    public final BooleanProperty creidaWatermark = new BooleanProperty("creida-watermark", true, () -> this.interfaceMode.getValue() == 1);
    public final BooleanProperty clientInfo = new BooleanProperty("client-info", true);
    public final BooleanProperty rounded = new BooleanProperty("rounded", true, () -> !this.customList());
    public final FloatProperty cornerRadius = new FloatProperty("corner-radius", 4.0F, 1.0F, 8.0F, () -> rounded.getValue() && !this.customList());
    public final FloatProperty padding = new FloatProperty("padding", 2.0F, 0.0F, 6.0F, () -> !this.customList());

    /*
     * 2026-11-14 (owner: "different module lists, as customisable as it gets, the best experience"): the module
     * list of interface CUSTOM. list-style picks one of ten ready-made shapes (ListPresets) and writes the shape
     * settings below it; change one of those by hand and list-style shows CUSTOM, or the preset it now equals.
     * Colours, order, animation and the glow are never touched by a preset. The layout is atlas.ui.hud.list,
     * which has no game in it and is tested and drawn to pictures (ListPreview) without one.
     */
    public final ModeProperty listStyle = new ModeProperty("list-style", 2, ListPresets.NAMES, this::customList);
    public final ModeProperty listColor = new ModeProperty("list-color", 0, ListStyle.names(ListStyle.Paint.values()), this::customList);
    public final ColorProperty listColor1 = new ColorProperty("list-color-1", 0x55CDFC, () -> this.listPaints("STATIC", "GRADIENT", "TRIPLE", "PALETTE", "PULSE", "FADE"));
    public final ColorProperty listColor2 = new ColorProperty("list-color-2", 0xF7A8B8, () -> this.listPaints("GRADIENT", "TRIPLE", "PALETTE", "PULSE"));
    public final ColorProperty listColor3 = new ColorProperty("list-color-3", 0xFFFFFF, () -> this.listPaints("TRIPLE", "PALETTE"));
    public final ColorProperty listColor4 = new ColorProperty("list-color-4", 0x9B6BFF, () -> this.listPaints("PALETTE"));
    public final FloatProperty listSpeed = new FloatProperty("list-speed", 1.0F, -3.0F, 3.0F, () -> this.listPaints("GRADIENT", "TRIPLE", "PALETTE", "RAINBOW", "ASTOLFO", "PULSE", "FADE"));
    public final PercentProperty listSpread = new PercentProperty("list-spread", 50, () -> this.listPaints("GRADIENT", "TRIPLE", "PALETTE", "RAINBOW", "ASTOLFO", "FADE"));
    public final PercentProperty listSaturation = new PercentProperty("list-saturation", 100, () -> this.customList() && !this.listPaints("HUD", "THEME"));
    public final PercentProperty listBrightness = new PercentProperty("list-brightness", 100, () -> this.customList() && !this.listPaints("HUD", "THEME"));
    public final ModeProperty listText = new ModeProperty("list-text", 0, ListStyle.names(ListStyle.Text.values()), this::customList);
    public final ModeProperty listSuffix = new ModeProperty("list-suffix", 0, ListStyle.names(ListStyle.Suffix.values()), () -> this.customList() && this.suffixes.getValue());
    public final BooleanProperty listTextShadow = new BooleanProperty("list-text-shadow", true, this::customList);
    public final ModeProperty listBackground = new ModeProperty("list-background", 1, ListStyle.names(ListStyle.Background.values()), this::customList);
    public final ColorProperty listBgColor = new ColorProperty("list-bg-color", 0x0F0F0F, this::listHasBackground);
    public final PercentProperty listBgOpacity = new PercentProperty("list-bg-opacity", 55, this::listHasBackground);
    public final PercentProperty listBgTint = new PercentProperty("list-bg-tint", 0, this::listHasBackground);
    public final ModeProperty listCorners = new ModeProperty("list-corners", 0, ListStyle.names(ListStyle.Corners.values()), this::customList);
    public final FloatProperty listRadius = new FloatProperty("list-radius", 3.0F, 0.0F, 8.0F, () -> this.customList() && this.listCorners.getValue() != 0);
    public final ModeProperty listBar = new ModeProperty("list-bar", 1, ListStyle.names(ListStyle.Bar.values()), this::customList);
    public final FloatProperty listBarWidth = new FloatProperty("list-bar-width", 1.5F, 0.5F, 4.0F, () -> this.customList() && this.listBar.getValue() != 0);
    public final FloatProperty listPadX = new FloatProperty("list-pad-x", 3.0F, 0.0F, 10.0F, this::customList);
    public final FloatProperty listPadY = new FloatProperty("list-pad-y", 2.0F, 0.0F, 6.0F, this::customList);
    public final FloatProperty listGap = new FloatProperty("list-gap", 0.0F, 0.0F, 8.0F, this::customList);
    public final ModeProperty listSort = new ModeProperty("list-sort", 0, ListStyle.names(ListStyle.Sort.values()), this::customList);
    public final ModeProperty listAnimation = new ModeProperty("list-animation", 3, ListStyle.names(ListStyle.Anim.values()), this::customList);
    public final FloatProperty listAnimTime = new FloatProperty("list-anim-time", 0.2F, 0.05F, 1.0F, () -> this.customList() && this.listAnimation.getValue() != 0);
    public final BooleanProperty listBlur = new BooleanProperty("list-blur", false, this::listHasBackground);
    public final FloatProperty listBlurSize = new FloatProperty("list-blur-size", 10.0F, 1.0F, 30.0F, () -> this.listHasBackground() && this.listBlur.getValue());
    public final BooleanProperty listGlow = new BooleanProperty("list-glow", false, this::customList);
    public final PercentProperty listGlowStrength = new PercentProperty("list-glow-strength", 60, () -> this.customList() && this.listGlow.getValue());
    public final FloatProperty listGlowSize = new FloatProperty("list-glow-size", 4.0F, 1.0F, 15.0F, () -> this.customList() && this.listGlow.getValue());

    /** The custom list's settings, in the order above; every one of them is part of a saved look (ThemeFiles). */
    public static final String[] LIST_SETTINGS = {"list-style", "list-color", "list-color-1", "list-color-2",
            "list-color-3", "list-color-4", "list-speed", "list-spread", "list-saturation", "list-brightness",
            "list-text", "list-suffix", "list-text-shadow", "list-background", "list-bg-color", "list-bg-opacity",
            "list-bg-tint", "list-corners", "list-radius", "list-bar", "list-bar-width", "list-pad-x", "list-pad-y",
            "list-gap", "list-sort", "list-animation", "list-anim-time", "list-blur", "list-blur-size", "list-glow",
            "list-glow-strength", "list-glow-size"};
    /** The settings a list-style preset writes. */
    private static final Set<String> LIST_SHAPE = new HashSet<>(Arrays.asList("list-text", "list-suffix",
            "list-text-shadow", "list-background", "list-bg-color", "list-bg-opacity", "list-bg-tint",
            "list-corners", "list-radius", "list-bar", "list-bar-width", "list-pad-x", "list-pad-y", "list-gap",
            "list-blur"));

    private final ListAnim listAnim = new ListAnim();
    /** Filled from the settings every frame. */
    private final ListStyle listLook = new ListStyle();
    /** This tick's rows of the custom list, measured; built with the tick's other caches. */
    private List<ListRow> listRows = new ArrayList<>();
    private long lastListFrame;
    private boolean writingListShape;

    /** Whether the module list is the custom one (interface CUSTOM). */
    public boolean customList() {
        return this.interfaceMode.getValue() == 2;
    }

    private boolean listHasBackground() {
        return this.customList() && this.listBackground.getValue() != 0;
    }

    private boolean listPaints(String... paints) {
        if (!this.customList()) {
            return false;
        }
        String now = this.listColor.getModeString();
        for (String paint : paints) {
            if (paint.equals(now)) {
                return true;
            }
        }
        return false;
    }

    /** The custom list's look as the settings have it now, into {@code s}. */
    private ListStyle readListStyle(ListStyle s) {
        s.right = this.posX.getValue() == 1;
        s.bottom = this.posY.getValue() == 1;
        s.text = ListStyle.pick(ListStyle.Text.values(), this.listText.getValue());
        s.suffix = ListStyle.pick(ListStyle.Suffix.values(), this.listSuffix.getValue());
        s.textShadow = this.listTextShadow.getValue();
        s.padX = this.listPadX.getValue();
        s.padY = this.listPadY.getValue();
        s.gap = this.listGap.getValue();
        s.background = ListStyle.pick(ListStyle.Background.values(), this.listBackground.getValue());
        s.backgroundColor = this.listBgColor.getValue() & 0xFFFFFF;
        s.backgroundOpacity = this.listBgOpacity.getValue();
        s.backgroundTint = this.listBgTint.getValue();
        s.corners = ListStyle.pick(ListStyle.Corners.values(), this.listCorners.getValue());
        s.radius = this.listRadius.getValue();
        s.bar = ListStyle.pick(ListStyle.Bar.values(), this.listBar.getValue());
        s.barWidth = this.listBarWidth.getValue();
        s.paint = ListStyle.pick(ListStyle.Paint.values(), this.listColor.getValue());
        s.colors[0] = this.listColor1.getValue() & 0xFFFFFF;
        s.colors[1] = this.listColor2.getValue() & 0xFFFFFF;
        s.colors[2] = this.listColor3.getValue() & 0xFFFFFF;
        s.colors[3] = this.listColor4.getValue() & 0xFFFFFF;
        s.speed = this.listSpeed.getValue();
        s.spread = this.listSpread.getValue();
        s.saturation = this.listSaturation.getValue();
        s.brightness = this.listBrightness.getValue();
        s.sort = ListStyle.pick(ListStyle.Sort.values(), this.listSort.getValue());
        s.anim = ListStyle.pick(ListStyle.Anim.values(), this.listAnimation.getValue());
        s.animTime = this.listAnimTime.getValue();
        s.blur = this.listBlur.getValue();
        s.glow = this.listGlow.getValue();
        return s;
    }

    /** Writes the shape settings a preset sets (LIST_SHAPE). */
    private void writeListShape(ListStyle s) {
        this.listText.setValue(s.text.ordinal());
        this.listSuffix.setValue(s.suffix.ordinal());
        this.listTextShadow.setValue(s.textShadow);
        this.listPadX.setValue(s.padX);
        this.listPadY.setValue(s.padY);
        this.listGap.setValue(s.gap);
        this.listBackground.setValue(s.background.ordinal());
        this.listBgColor.setValue(s.backgroundColor);
        this.listBgOpacity.setValue(s.backgroundOpacity);
        this.listBgTint.setValue(s.backgroundTint);
        this.listCorners.setValue(s.corners.ordinal());
        this.listRadius.setValue(s.radius);
        this.listBar.setValue(s.bar.ordinal());
        this.listBarWidth.setValue(s.barWidth);
        this.listBlur.setValue(s.blur);
    }

    /**
     * list-style picked: its shape is written. A shape setting changed: list-style shows the preset the shape now
     * equals, or CUSTOM. Not while a config or a saved look is read (Config.loading): the values read are the ones
     * that count, and the list-style read with them was worked out from them.
     */
    @Override
    public void verifyValue(String name) {
        super.verifyValue(name);
        if (this.writingListShape || atlas.config.Config.loading || name == null || !name.startsWith("list-")) {
            return;
        }
        this.writingListShape = true;
        try {
            if ("list-style".equals(name)) {
                ListStyle shape = this.readListStyle(new ListStyle());
                if (ListPresets.apply(this.listStyle.getModeString(), shape)) {
                    this.writeListShape(shape);
                }
            } else if (LIST_SHAPE.contains(name)) {
                String shown = ListPresets.match(this.readListStyle(new ListStyle()));
                if (!shown.equals(this.listStyle.getModeString())) {
                    this.listStyle.setValue(Arrays.asList(ListPresets.NAMES).indexOf(shown));
                }
            }
        } finally {
            this.writingListShape = false;
        }
    }

    private String getModuleName(Module module) {
        String moduleName = module.getName();
        if (this.lowerCase.getValue()) {
            moduleName = moduleName.toLowerCase(Locale.ROOT);
        }
        return moduleName;
    }

    CFontRenderer fontRenderer;
    private CFontRenderer nunitoFontRenderer;
    private final net.minecraft.client.gui.FontRenderer mcFont = mc.fontRendererObj;
    private int lastFontMode = -1; // Cache to prevent unnecessary updates

    private String[] getModuleSuffix(Module module) {
        String[] moduleSuffix = module.getSuffix();
        if (this.lowerCase.getValue()) {
            for (int i = 0; i < moduleSuffix.length; i++) {
                moduleSuffix[i] = moduleSuffix[i].toLowerCase();
            }
        }
        return moduleSuffix;
    }


    private int getModuleWidth(Module module) {
        if (this.interfaceMode.getValue() == 1) {
            return Math.round(this.getCreidaModuleWidth(module));
        }
        return this.calculateStringWidth(
                this.getModuleName(module), this.getModuleSuffix(module)
        );
    }

    private int calculateStringWidth(String string, String[] arr) {
        int width;
        switch (fontMode.getValue()) {
            case 0: // SANS
                width = fontRenderer.getStringWidth(string);
                break;
            case 1: // MINECRAFT
                width = mcFont.getStringWidth(string);
                break;
            default:
                width = fontRenderer.getStringWidth(string);
                break;
        }
        if (this.suffixes.getValue()) {
            for (String str : arr) {
                width += getStringWidth(this.formatSuffix(str));
            }
        }
        return width;
    }

    private int getStringWidth(String string) {
        switch (fontMode.getValue()) {
            case 1:
                return mcFont.getStringWidth(string);
            default:
                return fontRenderer.getStringWidth(string);
        }
    }

    private String formatSuffix(String suffix) {
        switch (this.separatorMode.getValue()) {
            case 1:
                return " - " + suffix;
            case 2:
                return " ! " + suffix;
            case 3:
                return " [" + suffix + "]";
            case 4:
                return " {" + suffix + "}";
            case 5:
                return " (" + suffix + ")";
            case 6:
                return " \"" + suffix + "\"";
            default:
                return " " + suffix;
        }
    }

    private float getColorCycle(long long3, long long4) {
        long speed = (long) (3000.0 / Math.pow(Math.min(Math.max(0.5F, this.colorSpeed.getValue()), 1.5F), 3.0));
        return 1.0F - (float) (Math.abs(long3 - long4 * 300L) % speed) / (float) speed;
    }

    public HUD() {
        super("HUD", true, true, "Wdym It HUD u never know it :?");
        updateFontRenderer();
    }

    private void updateFontRenderer() {
        // Only update if font mode actually changed
        if (lastFontMode == fontMode.getValue()) {
            return;
        }

        CFontRenderer selectedFont;
        switch (fontMode.getValue()) {
            case 0: // SANS
                selectedFont = FontProcess.getFont("sans");
                break;
            case 1: // MINECRAFT - handled separately
                selectedFont = FontProcess.getFont("sans");
                break;
            case 2: // NUNITO
                selectedFont = getNunitoFontRenderer();
                break;
            case 3: // TENACITY
                selectedFont = FontProcess.getFont("tenacity");
                break;
            case 4: // TENACITY_BOLD
                selectedFont = FontProcess.getFont("tenacity-bold");
                break;
            case 5: // SF_PRO
                selectedFont = FontProcess.getFont("sf-pro");
                break;
            case 6: // GOOGLE_SANS
                selectedFont = FontProcess.getFont("google-sans");
                break;
            default:
                selectedFont = FontProcess.getFont("sans");
                break;
        }

        if (selectedFont == null) {
            System.err.println("[Atlas] Failed to resolve HUD font mode: " + fontMode.getModeString());
            fontRenderer = FontProcess.getFont("sans");
            lastFontMode = -1;
            return;
        }

        fontRenderer = selectedFont;
        lastFontMode = fontMode.getValue();
    }

    private CFontRenderer getNunitoFontRenderer() {
        if (nunitoFontRenderer == null) {
            nunitoFontRenderer = new CFontRenderer("nunito", 18, Font.PLAIN, true, false);
            System.out.println("[Atlas] HUD Nunito loaded as: " + nunitoFontRenderer.getFont().getFontName());
        }
        return nunitoFontRenderer;
    }


    public Color getColor(long time) {
        return this.getColor(time, 0L);
    }

    /**
     * The HUD's colour for this line, themed by the Interface group -- which
     * also reaches every render module set to "HUD" colour.
     */
    public Color getColor(long time, long offset) {
        int accent = atlas.ui.impl.clickgui.atlas.AtlasClickGui.sharedAccent();
        /* 2026-11-12: Appearance > Colors > accent everywhere (on by default) puts the menu accent here. */
        Color own = accent != 0 ? new Color(accent) : this.getOwnColor(time, offset);
        ThemeStyle style = ThemeStyle.active(InterfaceColors.class);
        return style == null ? own : style.color(own, null, (int) Math.min(offset, 1000L));
    }

    private Color getOwnColor(long time, long offset) {
        Color color = Color.white;
        switch (this.colorMode.getValue()) {
            case 0:
                color = ColorUtil.fromHSB(this.getColorCycle(time, offset), 1.0F, 1.0F);
                break;
            case 1:
                color = ColorUtil.fromHSB(this.getColorCycle(time / 3L, 0L), 1.0F, 1.0F);
                break;
            case 2:
                float cycle = this.getColorCycle(time, offset);
                if (cycle % 1.0F < 0.5F) {
                    cycle = 1.0F - cycle % 1.0F;
                }
                color = ColorUtil.fromHSB(cycle, 1.0F, 1.0F);
                break;
            case 3:
                color = new Color(this.custom1.getValue());
                break;
            case 4:
                double cycle1 = this.getColorCycle(time, offset);
                color = ColorUtil.interpolate(
                        (float) (2.0 * Math.abs(cycle1 - Math.floor(cycle1 + 0.5))),
                        new Color(this.custom1.getValue()),
                        new Color(this.custom2.getValue())
                );
                break;
            case 5:
                double cycle2 = this.getColorCycle(time, offset);
                float floor = (float) (2.0 * Math.abs(cycle2 - Math.floor(cycle2 + 0.5)));
                if (floor <= 0.5F) {
                    color = ColorUtil.interpolate(floor * 2.0F, new Color(this.custom1.getValue()), new Color(this.custom2.getValue()));
                } else {
                    color = ColorUtil.interpolate((floor - 0.5F) * 2.0F, new Color(this.custom2.getValue()), new Color(this.custom3.getValue()));
                }
        }
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return Color.getHSBColor(
                hsb[0],
                hsb[1] * (this.colorSaturation.getValue().floatValue() / 100.0F),
                hsb[2] * (this.colorBrightness.getValue().floatValue() / 100.0F)
        );
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST) {
            this.activeModules = Atlas.moduleManager.modules.values().stream().filter(this::shouldShowInArraylist).sorted(Comparator.comparingInt(this::getModuleWidth).reversed()).collect(Collectors.<Module>toList());
            java.util.Map<Module, String> names = new java.util.HashMap<>();
            java.util.Map<Module, String[]> suffixes = new java.util.HashMap<>();
            java.util.Map<Module, Float> widths = new java.util.HashMap<>();
            for (Module module : this.activeModules) {
                String name = this.getModuleName(module);
                String[] suffix = this.getModuleSuffix(module);
                names.put(module, name);
                suffixes.put(module, suffix);
                widths.put(module, (float) this.calculateStringWidth(name, suffix));
            }
            this.lineName = names;
            this.lineSuffix = suffixes;
            this.lineWidth = widths;
            if (this.customList()) {
                this.listRows = this.buildListRows(names, suffixes);
            } else if (!this.listRows.isEmpty()) {
                this.listRows = new ArrayList<>();
                this.listAnim.clear();
            }
        }
    }

    /** The custom list's rows: each listed module's name and tag as shown, measured in the HUD's font. */
    private List<ListRow> buildListRows(java.util.Map<Module, String> names, java.util.Map<Module, String[]> suffixes) {
        List<ListRow> rows = new ArrayList<>();
        for (Module module : this.activeModules) {
            String name = names.get(module);
            String suffix = "";
            if (this.suffixes.getValue()) {
                StringBuilder joined = new StringBuilder();
                for (String part : suffixes.get(module)) {
                    joined.append(this.formatSuffix(part));
                }
                suffix = joined.toString();
            }
            atlas.module.Category category = atlas.module.ModuleCategories.of(module.getClass());
            rows.add(new ListRow(module, name, suffix, category == null ? 0 : category.ordinal(),
                    this.getStringWidth(name), suffix.isEmpty() ? 0.0F : this.getStringWidth(suffix)));
        }
        return rows;
    }

    private float listTextHeight() {
        return this.fontMode.getValue() == 1 ? (float) mcFont.FONT_HEIGHT - 1.0F : (float) fontRenderer.FONT_HEIGHT - 1.0F;
    }

    private ListGlPainter listPainter() {
        return new ListGlPainter(this.fontMode.getValue() == 1 ? null : this.fontRenderer, mcFont);
    }

    /** Where a row's colour comes from when the list's colour is HUD or THEME: only the game knows those. */
    private ListColors.Source listSource(ListStyle style, final long time) {
        if (style.paint == ListStyle.Paint.HUD) {
            return index -> this.getColor(time, index).getRGB();
        }
        if (style.paint == ListStyle.Paint.THEME) {
            final Theme theme = Theme.instance();
            return index -> {
                Color own = this.getColor(time, index);
                return theme == null ? own.getRGB() : theme.color(own, null, index).getRGB();
            };
        }
        return null;
    }

    /** The custom module list (interface CUSTOM): laid out by atlas.ui.hud.list, drawn here. */
    private void renderCustomList() {
        ListStyle style = this.readListStyle(this.listLook);
        long now = System.nanoTime();
        float dt = this.lastListFrame == 0L ? 0.016F : (now - this.lastListFrame) / 1.0E9F;
        this.lastListFrame = now;
        List<ListRow> rows = this.listAnim.step(ListRow.sorted(this.listRows, style.sort), style, dt);
        long time = System.currentTimeMillis();
        float s = this.scale.getValue();
        if (!rows.isEmpty()) {
            ListFrame frame = ListLayout.frame(style, rows, this.listTextHeight(), time, this.listSource(style, time));
            ScaledResolution sr = new ScaledResolution(mc);
            /* The corner the list hangs from, offset-x and offset-y in from the screen's. */
            float ax = style.right ? (float) sr.getScaledWidth() - this.offsetX.getValue() : (float) this.offsetX.getValue();
            float ay = style.bottom ? (float) sr.getScaledHeight() - this.offsetY.getValue() : (float) this.offsetY.getValue();
            HudLayout.report("HUD.list", "Module list", ax + frame.minX * s, ay + frame.minY * s,
                    (frame.maxX - frame.minX) * s, (frame.maxY - frame.minY) * s,
                    HudLayout.ints(this.offsetX, style.right ? -1 : 1, this.offsetY, style.bottom ? -1 : 1),
                    HudLayout.scale(this.scale));
            GlStateManager.pushMatrix();
            GlStateManager.translate(ax, ay, 0.0F);
            GlStateManager.scale(s, s, 1.0F);
            ListGlPainter painter = this.listPainter();
            /* The passes draw their shapes into a buffer of their own and need the texture back on before they
               end (finish). Not in mobile-safe mode, as the list's other passes. */
            if (!atlas.util.render.MobileSafe.active()) {
                if (style.glow) {
                    atlas.util.shader.BlurUtils.prepareBloom();
                    frame.paintGlow(painter, this.listGlowStrength.getValue() / 100.0F);
                    painter.finish();
                    atlas.util.shader.BlurUtils.bloomEnd(3, this.listGlowSize.getValue());
                }
                if (style.blur && frame.hasBacking()) {
                    atlas.util.shader.BlurUtils.prepareBlur();
                    frame.paintBacking(painter, 0xFFFFFFFF);
                    painter.finish();
                    atlas.util.shader.BlurUtils.blurEnd(2, this.listBlurSize.getValue());
                }
            }
            frame.paint(painter);
            painter.finish();
            GlStateManager.popMatrix();
        }
        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0F);
        this.renderBlinkTimer(time, 0L);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    /**
     * A few rows of the custom list as it is drawn now, for the Theme window's preview: hanging from (x, y), the
     * top right corner for a list on the right of the screen and the top left for one on the left, at a scale of
     * 1. Returns how far down it reaches.
     */
    public float drawListPreview(float x, float y, long time) {
        this.updateFontRenderer();
        ListStyle style = this.readListStyle(new ListStyle());
        style.bottom = false;
        String[][] sample = {{"Silent Aura", "Switch"}, {"Velocity", "Hypixel"}, {"Scaffold", "GodBridge"},
                {"Sprint", ""}};
        int[] categories = {0, 0, 1, 2};
        List<ListRow> rows = new ArrayList<>();
        for (int i = 0; i < sample.length; i++) {
            String name = this.lowerCase.getValue() ? sample[i][0].toLowerCase(Locale.ROOT) : sample[i][0];
            String tag = this.lowerCase.getValue() ? sample[i][1].toLowerCase(Locale.ROOT) : sample[i][1];
            String suffix = !this.suffixes.getValue() || tag.isEmpty() ? "" : this.formatSuffix(tag);
            rows.add(new ListRow(sample[i][0], name, suffix, categories[i], this.getStringWidth(name),
                    suffix.isEmpty() ? 0.0F : this.getStringWidth(suffix)));
        }
        ListStyle still = new ListStyle();
        still.anim = ListStyle.Anim.NONE;
        rows = new ListAnim().step(ListRow.sorted(rows, style.sort), still, 0.0F);
        ListFrame frame = ListLayout.frame(style, rows, this.listTextHeight(), time, this.listSource(style, time));
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y - frame.minY, 0.0F);
        ListGlPainter painter = this.listPainter();
        frame.paint(painter);
        painter.finish();
        GlStateManager.enableBlend();
        GlStateManager.popMatrix();
        return y + (frame.maxY - frame.minY);
    }

    private boolean shouldShowInArraylist(Module module) {
        return module != null && module.isEnabled() && !module.isHidden() && !isHiddenByArraylistCategory(module)
                && !Debug.hidesWithPanel(module);
    }

    private boolean isHiddenByArraylistCategory(Module module) {
        Class<?> moduleClass = module.getClass();
        return (this.hideRender.getValue() && RENDER_MODULES.contains(moduleClass))
                || (this.hidePlayer.getValue() && PLAYER_MODULES.contains(moduleClass))
                || (this.hideMisc.getValue() && MISC_MODULES.contains(moduleClass));
    }

    /** Tells the HUD editor where the module list is: the same geometry the list is drawn with. */
    private void reportListBounds(float x, float y, float height) {
        int count = this.activeModules.size();
        if (count == 0) {
            return;
        }
        float s = this.scale.getValue();
        float pad = this.padding.getValue();
        float widest = 0.0F;
        for (Module module : this.activeModules) {
            widest = Math.max(widest, this.getModuleRenderWidth(module));
        }
        float step = (height + (this.shadow.getValue() ? 1.0F : 0.0F) + pad * 2.0F) * s;
        boolean right = this.posX.getValue() == 1;
        boolean bottom = this.posY.getValue() == 1;
        float left = right ? x - (1.0F + pad + widest) * s : x - (1.0F + pad) * s;
        float width = (widest + 2.0F + pad * 2.0F) * s;
        float top = bottom ? y - (count - 1) * step - (pad + 1.0F) * s : y - (pad + 1.0F) * s;
        float total = (count - 1) * step + (height + pad * 2.0F + 2.0F) * s;
        HudLayout.report("HUD.list", "Module list", left, top, width, total,
                HudLayout.ints(this.offsetX, right ? -1 : 1, this.offsetY, bottom ? -1 : 1), HudLayout.scale(this.scale));
    }

    /* 2026-11-14 (owner: "the Theme preview colours are completely unlike the game"): the preview asked
       getColor for every row, but a JELLO list draws its names white with no edge bar. These answer as the
       render below does. */

    /** The colour a module-list name is drawn in, as the list draws it. */
    public int listNameColour(long time, long offset) {
        return HudPanel.active() ? 0xFFFFFFFF : this.getColor(time, offset).getRGB();
    }

    /** Whether the list draws its coloured edge bar. */
    public boolean listEdgeBar() {
        return !HudPanel.active() && this.hasSidebar();
    }

    /** The background behind each row when the list is not JELLO: 0 for none. */
    public int listRowBackground() {
        int alpha = (int) (255 * (this.background.getValue().floatValue() / 100.0F));
        return alpha <= 0 ? 0 : (alpha << 24) | 0x0F0F0F;
    }

    private boolean hasSidebar() {
        return this.showBar.getValue() && this.sidebarMode.getValue() != 4;
    }

    private float getModuleRenderWidth(Module module) {
        Float cached = this.lineWidth.get(module);
        return cached != null ? cached : (float) this.calculateStringWidth(this.getModuleName(module), this.getModuleSuffix(module));
    }

    /** This frame's rows: the enabled modules, then those still sliding out; positions eased. */
    private List<Module> animateRows(float rowStep) {
        long now = System.nanoTime();
        float dt = this.lastRowFrame == 0L ? 0.016F : Math.min(0.1F, (now - this.lastRowFrame) / 1.0E9F);
        this.lastRowFrame = now;
        /* Settles in about 200ms, like the theme's flip. */
        float follow = 1.0F - (float) Math.exp(-dt / 0.05F);
        List<Module> rows = new ArrayList<>();
        Set<Module> shown = new HashSet<>(this.activeModules);
        int index = 0;
        for (Module module : this.activeModules) {
            RowAnim anim = this.rowAnims.get(module);
            if (anim == null) {
                anim = new RowAnim();
                this.rowAnims.put(module, anim);
            }
            float target = index * rowStep;
            if (Float.isNaN(anim.y)) {
                anim.y = target;
            }
            anim.y += (target - anim.y) * follow;
            anim.appear = Math.min(1.0F, anim.appear + dt / 0.2F);
            rows.add(module);
            index++;
        }
        java.util.Iterator<java.util.Map.Entry<Module, RowAnim>> it = this.rowAnims.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Module, RowAnim> entry = it.next();
            if (shown.contains(entry.getKey())) {
                continue;
            }
            RowAnim anim = entry.getValue();
            anim.appear -= dt / 0.2F;
            if (anim.appear <= 0.0F || Float.isNaN(anim.y)) {
                it.remove();
            } else {
                rows.add(entry.getKey());
            }
        }
        return rows;
    }

    private String cachedName(Module module) {
        String cached = this.lineName.get(module);
        return cached != null ? cached : this.getModuleName(module);
    }

    private String[] cachedSuffix(Module module) {
        String[] cached = this.lineSuffix.get(module);
        return cached != null ? cached : this.getModuleSuffix(module);
    }

    private void renderSidebar(Module module, int index, float bgX1, float bgY1, float bgX2, float bgY2, int color) {
        if (!this.hasSidebar()) {
            return;
        }

        float thickness = 1.0F;
        boolean first = index == 0;
        boolean last = index == this.activeModules.size() - 1;
        boolean topList = this.posY.getValue() == 0;
        boolean visualFirst = topList ? first : last;
        boolean visualLast = topList ? last : first;

        switch (this.sidebarMode.getValue()) {
            case 1:
                drawVerticalSidebar(bgX1 - thickness, bgY1, bgY2, thickness, color);
                break;
            case 2:
                if (visualFirst) {
                    RenderUtil.drawRect(bgX1, bgY1 - thickness, bgX2, bgY1, color);
                }
                break;
            case 3:
                drawVerticalSidebar(bgX2, bgY1, bgY2, thickness, color);
                drawVerticalSidebar(bgX1 - thickness, bgY1, bgY2, thickness, color);
                if (visualFirst) {
                    RenderUtil.drawRect(bgX1 - thickness, bgY1 - thickness, bgX2 + thickness, bgY1, color);
                } else {
                    renderOutlineConnector(module, index, bgX1, bgY1, bgX2, bgY2, thickness, color);
                }
                if (visualLast) {
                    RenderUtil.drawRect(bgX1 - thickness, bgY2, bgX2 + thickness, bgY2 + thickness, color);
                }
                break;
            default:
                drawVerticalSidebar(bgX2, bgY1, bgY2, thickness, color);
                break;
        }
    }

    private void renderOutlineConnector(Module module, int index, float bgX1, float bgY1, float bgX2, float bgY2, float thickness, int color) {
        if (index <= 0) {
            return;
        }

        Module previous = this.activeModules.get(index - 1);
        float previousWidth = this.getModuleRenderWidth(previous);
        float rectExtraWidth = 2.0F + this.padding.getValue() * 2.0F;
        float boundaryY1 = this.posY.getValue() == 0 ? bgY1 - thickness : bgY2;
        float boundaryY2 = this.posY.getValue() == 0 ? bgY1 : bgY2 + thickness;

        if (this.posX.getValue() == 0) {
            float previousRight = bgX1 + previousWidth + rectExtraWidth;
            float start = Math.min(previousRight, bgX2);
            float end = Math.max(previousRight, bgX2) + thickness;
            RenderUtil.drawRect(start, boundaryY1, end, boundaryY2, color);
        } else {
            float previousLeft = bgX2 - previousWidth - rectExtraWidth;
            float start = Math.min(previousLeft, bgX1) - thickness;
            float end = Math.max(previousLeft, bgX1);
            RenderUtil.drawRect(start, boundaryY1, end, boundaryY2, color);
        }
    }

    private void drawVerticalSidebar(float x, float y1, float y2, float width, int color) {
        RenderUtil.drawRect(x, y1, x + width, y2, color);
    }

    @EventTarget(whenDisabled = true)
    public void onRender2D(Render2DEvent event) {
        // Update font renderer to ensure it uses current setting
        updateFontRenderer();
        
        if (this.chatOutline.getValue() && mc.currentScreen instanceof GuiChat) {
            String text = ((IAccessorGuiChat) mc.currentScreen).getInputField().getText().trim();
            if (Atlas.commandManager != null && Atlas.commandManager.isTypingCommand(text)) {
                RenderUtil.enableRenderState();
                RenderUtil.drawOutlineRect(
                        2.0F,
                        (float) (mc.currentScreen.height - 14),
                        (float) (mc.currentScreen.width - 2),
                        (float) (mc.currentScreen.height - 2),
                        1.5F,
                        0,
                        this.getColor(System.currentTimeMillis()).getRGB()
                );
                RenderUtil.disableRenderState();
            }
        }
        if (this.isEnabled() && !mc.gameSettings.showDebugInfo) {
            if (this.clientInfo.getValue()) {
                this.renderClientInfo();
            }
            if (this.interfaceMode.getValue() == 1) {
                renderCreidaInterface();
            } else if (this.customList()) {
                this.renderCustomList();
            } else {
            float height = (float) fontRenderer.FONT_HEIGHT - 1.0F;
            float x = (float) this.offsetX.getValue()
                    + (1.0F + (this.hasSidebar() ? 1.0F : 0.0F)) * this.scale.getValue();
            float y = (float) this.offsetY.getValue() + 1.0F * this.scale.getValue();
            if (this.posX.getValue() == 1) {
                x = (float) new ScaledResolution(mc).getScaledWidth() - x;
            }
            if (this.posY.getValue() == 1) {
                y = (float) new ScaledResolution(mc).getScaledHeight() - y - height * this.scale.getValue();
            }
            GlStateManager.pushMatrix();
            reportListBounds(x, y, height);
            GlStateManager.scale(this.scale.getValue(), this.scale.getValue(), 0.0F);

            long l = System.currentTimeMillis();

            /* JELLO draws each row's own background below: the old blur and
               glow passes would double it. Mobile-safe mode (2026-10-15) skips
               them too: there both would only draw masks nobody sees, a whole
               extra pass over the list each, every frame. */
            if (!HudPanel.active() && !atlas.util.render.MobileSafe.active()
                    && (this.glow.getValue() || this.blur.getValue())) {
                if (this.glow.getValue()) {
                    atlas.util.shader.BlurUtils.prepareBloom();
                    long offset = 0L;
                    float currentY = y;
                    for (Module module : this.activeModules) {
                        float totalWidth = this.getModuleRenderWidth(module);
                        float pad = this.padding.getValue();
                        float bgX1 = x / this.scale.getValue() - 1.0F - pad - (this.posX.getValue() == 0 ? 0.0F : totalWidth);
                        float bgY1 = currentY / this.scale.getValue() - pad - (this.posY.getValue() == 0 ? (offset == 0L ? 1.0F : 0.0F) : (this.shadow.getValue() ? 1.0F : 0.0F));
                        float bgX2 = x / this.scale.getValue() + 1.0F + pad + (this.posX.getValue() == 0 ? totalWidth : 0.0F);
                        float bgY2 = currentY / this.scale.getValue() + height + pad + (this.posY.getValue() == 0 ? (this.shadow.getValue() ? 1.0F : 0.0F) : (offset == 0L ? 1.0F : 0.0F));
                        int glowColor = new Color(0, 0, 0, 150).getRGB(); // Glassmorphic drop shadow instead of gradient
                        glowColor = RenderUtil.mergeAlpha(glowColor, this.glowAlpha.getValue());
                        if (this.rounded.getValue()) {
                            float bgW = bgX2 - bgX1;
                            float bgH = bgY2 - bgY1;
                            float rad = this.cornerRadius.getValue();
                            boolean isFirst = (offset == 0L);
                            boolean isLast = (offset == this.activeModules.size() - 1);
                            boolean sideLeft = this.posX.getValue() == 1;
                            boolean sideRight = this.posX.getValue() == 0;
                            boolean isTopEntry = (this.posY.getValue() == 0) ? isFirst : isLast;
                            boolean isBottomEntry = (this.posY.getValue() == 0) ? isLast : isFirst;
                            RenderUtil.drawRoundedRect(bgX1, bgY1, bgW, bgH, rad, glowColor, sideLeft && isTopEntry, sideRight && isTopEntry, sideLeft && isBottomEntry, sideRight && isBottomEntry);
                        } else {
                            RenderUtil.drawRect(bgX1, bgY1, bgX2, bgY2, glowColor);
                        }
                        currentY += (height + (this.shadow.getValue() ? 1.0F : 0.0F) + this.padding.getValue() * 2.0F) * this.scale.getValue() * (this.posY.getValue() == 0 ? 1.0F : -1.0F);
                        offset++;
                    }
                    atlas.util.shader.BlurUtils.bloomEnd(3, this.glowRadius.getValue());
                }
                if (this.blur.getValue()) {
                    atlas.util.shader.BlurUtils.prepareBlur();
                    long offset = 0L;
                    float currentY = y;
                    for (Module module : this.activeModules) {
                        float totalWidth = this.getModuleRenderWidth(module);
                        float pad = this.padding.getValue();
                        float bgX1 = x / this.scale.getValue() - 1.0F - pad - (this.posX.getValue() == 0 ? 0.0F : totalWidth);
                        float bgY1 = currentY / this.scale.getValue() - pad - (this.posY.getValue() == 0 ? (offset == 0L ? 1.0F : 0.0F) : (this.shadow.getValue() ? 1.0F : 0.0F));
                        float bgX2 = x / this.scale.getValue() + 1.0F + pad + (this.posX.getValue() == 0 ? totalWidth : 0.0F);
                        float bgY2 = currentY / this.scale.getValue() + height + pad + (this.posY.getValue() == 0 ? (this.shadow.getValue() ? 1.0F : 0.0F) : (offset == 0L ? 1.0F : 0.0F));
                        int blurColor = 0xFFFFFFFF; // White mask for blur
                        if (this.rounded.getValue()) {
                            float bgW = bgX2 - bgX1;
                            float bgH = bgY2 - bgY1;
                            float rad = this.cornerRadius.getValue();
                            boolean isFirst = (offset == 0L);
                            boolean isLast = (offset == this.activeModules.size() - 1);
                            boolean sideLeft = this.posX.getValue() == 1;
                            boolean sideRight = this.posX.getValue() == 0;
                            boolean isTopEntry = (this.posY.getValue() == 0) ? isFirst : isLast;
                            boolean isBottomEntry = (this.posY.getValue() == 0) ? isLast : isFirst;
                            RenderUtil.drawRoundedRect(bgX1, bgY1, bgW, bgH, rad, blurColor, sideLeft && isTopEntry, sideRight && isTopEntry, sideLeft && isBottomEntry, sideRight && isBottomEntry);
                        } else {
                            RenderUtil.drawRect(bgX1, bgY1, bgX2, bgY2, blurColor);
                        }
                        currentY += (height + (this.shadow.getValue() ? 1.0F : 0.0F) + this.padding.getValue() * 2.0F) * this.scale.getValue() * (this.posY.getValue() == 0 ? 1.0F : -1.0F);
                        offset++;
                    }
                    atlas.util.shader.BlurUtils.blurEnd(2, this.blurRadius.getValue());
                }
            }

            long offset = 0L;
            boolean jelloRows = HudPanel.active();
            float rowStep = (height + (this.shadow.getValue() ? 1.0F : 0.0F) + this.padding.getValue() * 2.0F)
                    * this.scale.getValue() * (this.posY.getValue() == 0 ? 1.0F : -1.0F);
            float listTop = y;
            List<Module> drawnRows = jelloRows ? this.animateRows(rowStep) : this.activeModules;
            for (Module module : drawnRows) {
                float rowFade = 1.0F;
                if (jelloRows) {
                    RowAnim anim = this.rowAnims.get(module);
                    y = listTop + anim.y;
                    rowFade = anim.appear;
                    float eased = 1.0F - (1.0F - rowFade) * (1.0F - rowFade) * (1.0F - rowFade);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate((1.0F - eased) * 25.0F * (this.posX.getValue() == 1 ? 1.0F : -1.0F),
                            0.0F, 0.0F);
                }
                String moduleName = this.cachedName(module);
                String[] moduleSuffix = this.cachedSuffix(module);
                float totalWidth = this.getModuleRenderWidth(module);
                boolean jello = HudPanel.active();
                /* JELLO (ArrayList.svelte): white names, grey tags, no edge bar.
                   Never below alpha 4: the font would read 0 as opaque. */
                int color = jello ? (Math.max(4, Math.round(rowFade * 255.0F)) << 24) | 0xFFFFFF
                        : this.getColor(l, offset).getRGB();
                float pad = this.padding.getValue();
                float bgX1 = x / this.scale.getValue() - 1.0F - pad - (this.posX.getValue() == 0 ? 0.0F : totalWidth);
                float bgY1 = y / this.scale.getValue() - pad - (this.posY.getValue() == 0 ? (offset == 0L ? 1.0F : 0.0F) : (this.shadow.getValue() ? 1.0F : 0.0F));
                float bgX2 = x / this.scale.getValue() + 1.0F + pad + (this.posX.getValue() == 0 ? totalWidth : 0.0F);
                float bgY2 = y / this.scale.getValue() + height + pad + (this.posY.getValue() == 0 ? (this.shadow.getValue() ? 1.0F : 0.0F) : (offset == 0L ? 1.0F : 0.0F));
                RenderUtil.enableRenderState();
                if (jello) {
                    /* JELLO: each row a square black-45% slab, flush with the
                       next, with the theme's soft shadow; 7px a side (3.5 at
                       GUI scale) where the rows pad 2. */
                    HudPanel.row(bgX1 - 1.5F, bgY1, bgX2 + 1.5F, bgY2, rowFade);
                } else if (this.background.getValue() > 0) {
                    int alpha = (int) (255 * (this.background.getValue().floatValue() / 100.0F));
                    int bgColor = UiMode.adapt(new Color(15, 15, 15, alpha).getRGB()); // Glassmorphic dark background
                    if (this.rounded.getValue()) {
                        float bgW = bgX2 - bgX1;
                        float bgH = bgY2 - bgY1;
                        float rad = this.cornerRadius.getValue();
                        boolean isFirst = (offset == 0L);
                        boolean isLast = (offset == this.activeModules.size() - 1);
                        boolean sideLeft = this.posX.getValue() == 1;
                        boolean sideRight = this.posX.getValue() == 0;
                        boolean isTopEntry = (this.posY.getValue() == 0) ? isFirst : isLast;
                        boolean isBottomEntry = (this.posY.getValue() == 0) ? isLast : isFirst;
                        RenderUtil.drawRoundedRect(
                                bgX1, bgY1, bgW, bgH, rad, bgColor,
                                sideLeft && isTopEntry, sideRight && isTopEntry,
                                sideLeft && isBottomEntry, sideRight && isBottomEntry
                        );
                    } else {
                        RenderUtil.drawRect(bgX1, bgY1, bgX2, bgY2, bgColor);
                    }
                }
                
                // Draw a subtle white edge for glassmorphism instead of a colored sidebar
                if (this.showBar.getValue() && !jello) {
                    int edgeColor = UiMode.adapt(new Color(255, 255, 255, 40).getRGB());
                    if (this.posX.getValue() == 0) { // Right side
                        RenderUtil.drawRect(bgX2 - 1.0F, bgY1, bgX2, bgY2, edgeColor);
                    } else { // Left side
                        RenderUtil.drawRect(bgX1, bgY1, bgX1 + 1.0F, bgY2, edgeColor);
                    }
                }

                RenderUtil.disableRenderState();
                GlStateManager.disableDepth();
                if (this.shadow.getValue()) {
                    if (fontMode.getValue() == 1) { // MINECRAFT
                        mcFont.drawStringWithShadow(moduleName, x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F), y / this.scale.getValue(), color);
                    } else {
                        fontRenderer.drawStringWithShadow(moduleName, x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F), y / this.scale.getValue(), color);
                    }
                } else {
                    if (fontMode.getValue() == 1) { // MINECRAFT
                        mcFont.drawString(
                                moduleName,
                                x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F),
                                y / this.scale.getValue() + (this.posY.getValue() == 1 ? 1.0F : 0.0F),
                                color,
                                false
                        );
                    } else {
                        fontRenderer.drawString(
                                moduleName,
                                x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F),
                                y / this.scale.getValue() + (this.posY.getValue() == 1 ? 1.0F : 0.0F),
                                color,
                                false
                        );
                    }
                }
                if (this.suffixes.getValue() && moduleSuffix.length > 0) {
                    float width;
                    switch (fontMode.getValue()) {
                        case 1: // MINECRAFT
                            width = (float) mcFont.getStringWidth(moduleName);
                            break;
                        default:
                            width = (float) fontRenderer.getStringWidth(moduleName);
                            break;
                    }
                    for (String suffix : moduleSuffix) {
                        String string = this.formatSuffix(suffix);
                        if (this.shadow.getValue()) {
                            if (fontMode.getValue() == 1) { // MINECRAFT
                                mcFont.drawStringWithShadow(
                                        string,
                                        x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F) + width,
                                        y / this.scale.getValue(),
                                        ChatColors.GRAY.toAwtColor()
                                );
                            } else {
                                fontRenderer.drawStringWithShadow(
                                        string,
                                        x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F) + width,
                                        y / this.scale.getValue(),
                                        ChatColors.GRAY.toAwtColor()
                                );
                            }
                        } else {
                            if (fontMode.getValue() == 1) { // MINECRAFT
                                mcFont.drawString(
                                        string,
                                        x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F) + width,
                                        y / this.scale.getValue() + (this.posY.getValue() == 1 ? 1.0F : 0.0F),
                                        ChatColors.GRAY.toAwtColor(),
                                        false
                                );
                            } else {
                                fontRenderer.drawString(
                                        string,
                                        x / this.scale.getValue() - (this.posX.getValue() == 1 ? totalWidth : 0.0F) + width,
                                        y / this.scale.getValue() + (this.posY.getValue() == 1 ? 1.0F : 0.0F),
                                        ChatColors.GRAY.toAwtColor(),
                                        false
                                );
                            }
                        }
                        width += this.getStringWidth(string);
                    }
                }
                y += (height + (this.shadow.getValue() ? 1.0F : 0.0F) + this.padding.getValue() * 2.0F) * this.scale.getValue() * (this.posY.getValue() == 0 ? 1.0F : -1.0F);
                offset++;
                if (jelloRows) {
                    GlStateManager.popMatrix();
                }
            }
            this.renderBlinkTimer(l, offset);
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();


            }
        }
        renderNotifications();
    }

    /** The blink packet count in the middle of the screen; drawn in a matrix scaled by the HUD's scale. */
    private void renderBlinkTimer(long time, long offset) {
        if (this.blinkTimer.getValue()) {
            BlinkModules blinkingModule = Atlas.blinkManager.getBlinkingModule();
            if (blinkingModule != BlinkModules.NONE && blinkingModule != BlinkModules.AUTO_BLOCK) {
                long movementPacketSize = Atlas.blinkManager.countMovement();
                if (movementPacketSize > 0L) {
                    GlStateManager.enableBlend();
                    GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    if (fontMode.getValue() == 1) { // MINECRAFT
                        mcFont.drawString(
                                String.valueOf(movementPacketSize),
                                (float) new ScaledResolution(mc).getScaledWidth() / 2.0F / this.scale.getValue()
                                        - (float) mcFont.getStringWidth(String.valueOf(movementPacketSize)) / 2.0F,
                                (float) new ScaledResolution(mc).getScaledHeight() / 5.0F * 3.0F / this.scale.getValue(),
                                this.getColor(time, offset).getRGB() & 16777215 | -1090519040,
                                this.shadow.getValue()
                        );
                    } else {
                        fontRenderer.drawString(
                                String.valueOf(movementPacketSize),
                                (float) new ScaledResolution(mc).getScaledWidth() / 2.0F / this.scale.getValue()
                                        - (float) fontRenderer.getStringWidth(String.valueOf(movementPacketSize)) / 2.0F,
                                (float) new ScaledResolution(mc).getScaledHeight() / 5.0F * 3.0F / this.scale.getValue(),
                                this.getColor(time, offset).getRGB() & 16777215 | -1090519040,
                                this.shadow.getValue()
                        );
                    }
                    GlStateManager.disableBlend();
                }
            }
        }
    }

    private void renderCreidaInterface() {
        ScaledResolution sr = new ScaledResolution(mc);
        float hudScale = Math.max(0.5F, Math.min(1.5F, this.scale.getValue()));
        float scaledWidth = sr.getScaledWidth() / hudScale;
        float edgeOffset = Math.max(3.0F, this.offsetX.getValue());
        float right = scaledWidth - edgeOffset;
        float y = Math.max(3.0F, this.offsetY.getValue() + 1.0F);
        float entryHeight = getCreidaEntryHeight();
        long time = System.currentTimeMillis();

        GlStateManager.pushMatrix();
        GlStateManager.scale(hudScale, hudScale, 1.0F);

        if (this.creidaWatermark.getValue()) {
            renderCreidaWatermark(time);
        }

        long offset = 0L;
        for (Module module : this.activeModules) {
            renderCreidaModule(module, right, y, time, offset, entryHeight);
            y += entryHeight;
            offset++;
        }

        GlStateManager.popMatrix();
    }

    private void renderCreidaModule(Module module, float right, float y, long time, long offset, float entryHeight) {
        String moduleName = this.getModuleName(module);
        String[] moduleSuffix = this.getModuleSuffix(module);
        float nameWidth = this.getCreidaTextWidth(moduleName);
        float tagWidth = this.getCreidaTagWidth(moduleSuffix);
        float boxWidth = nameWidth + tagWidth + 7.0F;
        float boxHeight = entryHeight;
        float x = right - boxWidth;
        float textY = y + getCreidaTextOffset(entryHeight);
        int accent = this.getColor(time, offset).getRGB();
        int backgroundColor = new Color(0, 0, 0, 110).getRGB();
        int depthColor = new Color(0, 0, 0, 65).getRGB();

        RenderUtil.enableRenderState();
        RenderUtil.drawRect(x - 1.0F, y, x + boxWidth, y + boxHeight, depthColor);
        RenderUtil.drawRect(x - 1.0F, y, x + boxWidth, y + boxHeight, backgroundColor);
        RenderUtil.drawRect(x + boxWidth - 1.0F, y, x + boxWidth, y + boxHeight, accent);
        RenderUtil.disableRenderState();

        GlStateManager.disableDepth();
        drawCreidaStringWithShadow(moduleName, x + 1.0F, textY, accent);

        if (this.suffixes.getValue() && moduleSuffix.length > 0) {
            float suffixX = x + 1.0F + nameWidth + 4.0F;
            for (String suffix : moduleSuffix) {
                drawCreidaStringWithShadow(suffix, suffixX, textY, 0xFFCCCCCC);
                suffixX += this.getCreidaTextWidth(suffix) + 3.0F;
            }
        }
        GlStateManager.enableDepth();
    }

    private void renderCreidaWatermark(long time) {
        String text = getCreidaWatermarkText();
        float textWidth = getCreidaTextWidth(text);
        float boxWidth = textWidth + 8.0F;
        float boxHeight = Math.max(15.0F, getCreidaTextHeight() + 6.0F);
        float x = 2.0F;
        float y = 3.0F;

        RenderUtil.drawRoundedRect(x, y, boxWidth, boxHeight, 4.0F,
                new Color(0, 0, 0, 100).getRGB(), true, true, true, true);

        GlStateManager.resetColor();
        GlStateManager.disableDepth();

        float currentX = x + 4.0F;
        float textY = y + (boxHeight - getCreidaTextHeight()) / 2.0F + 1.0F;
        for (int i = 0; i < text.length(); i++) {
            String character = String.valueOf(text.charAt(i));
            int color = this.getColor(time, (long) (i * this.colorDistance.getValue())).getRGB();
            drawCreidaStringWithShadow(character, currentX, textY, color);
            currentX += getCreidaTextWidth(character);
        }

        GlStateManager.enableDepth();
    }

    private String getCreidaWatermarkText() {
        String playerName = mc.thePlayer == null ? "Player" : mc.thePlayer.getName();
        String versionText = Atlas.version == null ? "dev" : Atlas.version;
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        return "Atlas | " + versionText + " | " + playerName + " | " + time;
    }

    private float getCreidaModuleWidth(Module module) {
        return getCreidaTextWidth(this.getModuleName(module)) + getCreidaTagWidth(this.getModuleSuffix(module)) + 7.0F;
    }

    private float getCreidaTagWidth(String[] suffixes) {
        if (!this.suffixes.getValue() || suffixes.length == 0) {
            return 1.0F;
        }

        float width = 0.0F;
        for (String suffix : suffixes) {
            width += this.getCreidaTextWidth(suffix) + 3.0F;
        }
        return width + 1.0F;
    }

    private float getCreidaEntryHeight() {
        return Math.max(14.0F, this.getCreidaTextHeight() + 5.0F);
    }

    private float getCreidaTextOffset(float entryHeight) {
        return Math.max(2.0F, (entryHeight - this.getCreidaTextHeight()) / 2.0F);
    }

    private float getCreidaTextWidth(String text) {
        if (isCreidaMinecraftFont()) {
            return mcFont.getStringWidth(text);
        }
        return fontRenderer.getStringWidth(text);
    }

    private float getCreidaTextHeight() {
        if (isCreidaMinecraftFont()) {
            return mcFont.FONT_HEIGHT;
        }
        return fontRenderer.FONT_HEIGHT;
    }

    private void drawCreidaStringWithShadow(String text, float x, float y, int color) {
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (isCreidaMinecraftFont()) {
            mcFont.drawStringWithShadow(text, x, y, color);
        } else {
            fontRenderer.drawStringWithShadow(text, x, y, color);
        }
        GlStateManager.disableBlend();
    }

    private boolean isCreidaMinecraftFont() {
        return !this.creidaFont.getValue() || this.fontMode.getValue() == 1;
    }

    // "Version: x.x.x Username: name" bottom-left line, ported from Miau Client's HUD (Normal mode).
    private void renderClientInfo() {
        float fontHeight = this.fontMode.getValue() == 1 ? mcFont.FONT_HEIGHT : fontRenderer.FONT_HEIGHT;
        ScaledResolution sr = new ScaledResolution(mc);
        float yCoord = sr.getScaledHeight() - fontHeight - 2.0F;
        int hudColor = this.getColor(System.currentTimeMillis()).getRGB();
        int whiteColor = -1;

        float currentX = 2.0F;
        currentX += this.drawClientInfoText("Version: ", currentX, yCoord, whiteColor);

        String ver = Atlas.version;
        if (ver != null && ver.length() > 0) {
            String firstChar = ver.substring(0, 1);
            String restVer = ver.substring(1);
            currentX += this.drawClientInfoText(firstChar, currentX, yCoord, hudColor);
            currentX += this.drawClientInfoText(restVer, currentX, yCoord, whiteColor);
        }

        currentX += this.drawClientInfoText(" Username: ", currentX, yCoord, whiteColor);
        this.drawClientInfoText(mc.getSession().getUsername(), currentX, yCoord, hudColor);
    }

    private float drawClientInfoText(String text, float x, float y, int color) {
        if (this.fontMode.getValue() == 1) {
            mcFont.drawStringWithShadow(text, x, y, color);
            return mcFont.getStringWidth(text);
        }
        fontRenderer.drawStringWithShadow(text, x, y, color);
        return fontRenderer.getStringWidth(text);
    }

    private void renderNotifications() {
        if (!this.notifications.getValue()) return;

        try {
            if (Atlas.notificationManager == null) return;

            java.util.List<atlas.management.NotificationManager.NotificationEntry> entries = Atlas.notificationManager.getActive();
            if (entries.isEmpty()) return;

            float notificationScale = Math.max(0.5F, Math.min(1.5F, this.scale.getValue()));
            ScaledResolution sr = new ScaledResolution(mc);
            float scaledWidth = sr.getScaledWidth() / notificationScale;
            float scaledHeight = sr.getScaledHeight() / notificationScale;
            float margin = 8.0F;
            float paddingX = 8.0F;
            float paddingY = 5.0F;
            float spacing = 4.0F;
            float y = scaledHeight - margin;

            GlStateManager.pushMatrix();
            GlStateManager.scale(notificationScale, notificationScale, 1.0F);

            for (int i = entries.size() - 1; i >= 0; i--) {
                atlas.management.NotificationManager.NotificationEntry entry = entries.get(i);
                float alpha = notificationAlpha(entry);
                if (alpha <= 0.01F) continue;

                String text = modernNotificationText(entry.message);
                float textWidth = getHudTextWidth(text);
                float textHeight = getHudTextHeight();
                float boxWidth = Math.max(86.0F, textWidth + paddingX * 2.0F + 2.0F);
                float boxHeight = textHeight + paddingY * 2.0F + 3.0F;
                float x = scaledWidth - margin - boxWidth;
                y -= boxHeight;

                drawModernNotification(entry, text, x, y, boxWidth, boxHeight, paddingX, paddingY, alpha);
                y -= spacing;
            }

            GlStateManager.popMatrix();
        } catch (Exception ignored) {
        }
    }

    private void drawModernNotification(atlas.management.NotificationManager.NotificationEntry entry, String text,
                                        float x, float y, float boxWidth, float boxHeight,
                                        float paddingX, float paddingY, float alpha) {
        float motion = notificationMotion(entry);
        float slide = (1.0F - motion) * 14.0F + (1.0F - alpha) * 5.0F;
        float renderX = x + slide;
        int statusColor = notificationStatusColor(text, alpha);
        /* 2026-11-12: with accent everywhere, the glass is the Appearance glass colour and the bar the accent. */
        int sharedAccent = atlas.ui.impl.clickgui.atlas.AtlasClickGui.sharedAccent();
        int sharedGlass = atlas.ui.impl.clickgui.atlas.AtlasClickGui.sharedGlass();
        int glass = sharedGlass != 0 ? colorWithAlpha(sharedGlass, (int) (150 * alpha))
                : new Color(10, 12, 16, (int) (92 * alpha)).getRGB();
        int barColor = sharedAccent != 0 ? colorWithAlpha(sharedAccent, (int) (245 * alpha)) : statusColor;
        int hoverLayer = new Color(255, 255, 255, (int) (9 * alpha)).getRGB();
        int border = new Color(255, 255, 255, (int) (24 * alpha)).getRGB();
        int depth = new Color(0, 0, 0, (int) (28 * alpha)).getRGB();
        int neutralText = new Color(238, 241, 245, (int) (242 * alpha)).getRGB();
        float radius = 6.0F;

        RenderUtil.drawRoundedRect(renderX + 1.0F, y + 1.5F, boxWidth, boxHeight, radius + 1.0F,
                depth, true, true, true, true);
        RenderUtil.drawRoundedRect(renderX, y, boxWidth, boxHeight, radius,
                glass, true, true, true, true);
        RenderUtil.drawRoundedRect(renderX + 1.0F, y + 1.0F, boxWidth - 2.0F, boxHeight - 2.0F, radius - 1.0F,
                hoverLayer, true, true, true, true);
        RenderUtil.drawRoundedRectOutline(renderX + 0.5F, y + 0.5F, boxWidth - 1.0F, boxHeight - 1.0F,
                radius, 1.0F, border, true, true, true, true);

        float progress = notificationProgress(entry);
        float progressX = renderX + 8.0F;
        float progressY = y + boxHeight - 2.0F;
        float progressW = boxWidth - 16.0F;
        RenderUtil.drawRoundedRect(progressX, progressY, progressW, 1.0F, 0.5F,
                new Color(255, 255, 255, (int) (10 * alpha)).getRGB(), true, true, true, true);
        RenderUtil.drawRoundedRect(progressX, progressY, Math.max(1.0F, progressW * progress), 1.0F, 0.5F,
                barColor, true, true, true, true);

        drawNotificationText(text, renderX + paddingX + 1.0F, y + paddingY + 1.0F, neutralText, statusColor);
    }

    private float notificationAlpha(atlas.management.NotificationManager.NotificationEntry entry) {
        if (entry.durationMillis <= 0) return 1.0F;

        float age = entry.getAge();
        float remaining = entry.durationMillis - age;
        float fade = Math.min(220.0F, entry.durationMillis / 3.0F);
        float alpha = Math.min(1.0F, Math.min(age / fade, remaining / fade));
        alpha = Math.max(0.0F, Math.min(1.0F, alpha));
        return alpha * alpha * (3.0F - 2.0F * alpha);
    }

    private float notificationProgress(atlas.management.NotificationManager.NotificationEntry entry) {
        if (entry.durationMillis <= 0) return 1.0F;
        return Math.max(0.0F, Math.min(1.0F, 1.0F - entry.getAge() / (float) entry.durationMillis));
    }

    private float notificationMotion(atlas.management.NotificationManager.NotificationEntry entry) {
        if (entry.durationMillis <= 0) return 1.0F;

        float age = entry.getAge();
        float remaining = entry.durationMillis - age;
        float in = Math.max(0.0F, Math.min(1.0F, age / 260.0F));
        float out = Math.max(0.0F, Math.min(1.0F, remaining / 220.0F));
        float motion = Math.min(in, out);
        return motion * motion * (3.0F - 2.0F * motion);
    }

    private String modernNotificationText(String message) {
        if (message == null) return "";
        return message
                .replace(" was toggled successfully", " enabled")
                .replace(" was untoggled successfully", " disabled");
    }

    private int softenColor(int rgb, float amount) {
        amount = Math.max(0.0F, Math.min(1.0F, amount));
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return (r << 16) | (g << 8) | b;
    }

    private int notificationStatusColor(String text, float alpha) {
        String lower = text.toLowerCase(Locale.ROOT);
        int accent = atlas.ui.impl.clickgui.atlas.AtlasClickGui.sharedAccent();
        /* Enabled and disabled keep green and red: they say something. Anything else takes the accent. */
        int rgb = lower.endsWith(" enabled") ? 0x41D982 : lower.endsWith(" disabled") ? 0xFF5C6C
                : accent != 0 ? accent & 0xFFFFFF : 0xE5E9F0;
        return colorWithAlpha(rgb, (int) (245 * alpha));
    }

    private int colorWithAlpha(int rgb, int alpha) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF,
                Math.max(0, Math.min(255, alpha))).getRGB();
    }

    private float getHudTextWidth(String text) {
        return fontMode.getValue() == 1 ? mcFont.getStringWidth(text) : fontRenderer.getStringWidth(text);
    }

    private float getHudTextHeight() {
        return fontMode.getValue() == 1 ? mcFont.FONT_HEIGHT : fontRenderer.FONT_HEIGHT;
    }

    private void drawHudText(String text, float x, float y, int color) {
        if (fontMode.getValue() == 1) {
            mcFont.drawString(text, x, y, color, false);
        } else {
            fontRenderer.drawString(text, x, y, color, false);
        }
    }

    private void drawNotificationText(String text, float x, float y, int neutralColor, int statusColor) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.endsWith(" enabled")) {
            drawSplitNotificationText(text, " enabled", x, y, neutralColor, statusColor);
        } else if (lower.endsWith(" disabled")) {
            drawSplitNotificationText(text, " disabled", x, y, neutralColor, statusColor);
        } else {
            drawHudText(text, x, y, neutralColor);
        }
    }

    private void drawSplitNotificationText(String text, String suffix, float x, float y, int neutralColor, int statusColor) {
        String main = text.substring(0, text.length() - suffix.length());
        drawHudText(main, x, y, neutralColor);
        drawHudText(suffix.trim(), x + getHudTextWidth(main + " "), y, statusColor);
    }

    @Override
    public boolean shouldKeepSprint() {
        return false;
    }
}
