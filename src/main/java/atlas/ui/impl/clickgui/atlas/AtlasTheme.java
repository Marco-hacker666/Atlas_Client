package atlas.ui.impl.clickgui.atlas;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AtlasTheme {
    private static final File FILE = new File("./config/Atlas/atlas-theme.json");

    static final String[] ACCENTS = {"ClickGUI", "Sky", "Mint", "Violet", "Rose", "Amber", "Coral", "Ice", "Custom", "Lime"};
    private static final int[] ACCENT_COLOURS = {0, 0xFF4FC3F7, 0xFF5FFFC1, 0xFFA78BFA, 0xFFFF6B9D,
            0xFFFFC857, 0xFFFF8A65, 0xFFB3E5FC, 0, 0xFFC6FF00};

    private static final int LIGHT_TINT = 0xFFF3F5F9;
    static final String[] TINTS = {"Midnight", "Graphite", "Ocean", "Aurora", "Ember", "Forest", "Custom", "Void"};
    private static final int[] TINT_COLOURS = {0xFF0E121D, 0xFF121316, 0xFF08182A, 0xFF1A1030,
            0xFF24120C, 0xFF0B1A12, 0, 0xFF07080A};
    static final String[] FONTS = {"SF Pro", "Product Sans", "Google Sans", "Nunito", "HarmonyOS", "Minecraft"};
    private static final String[] FONT_FILES = {"San-Francisco-Pro-Fonts.ttf", "product_sans_regular.ttf",
            "Google-Sans.ttf", "nunito.ttf", "harmonyOS_Sans.ttf", null};

    final ModeProperty mode = new ModeProperty("appearance-mode", 0, new String[]{"Dark", "Light"});
    final ModeProperty accent = new ModeProperty("accent", 0, ACCENTS);
    final IntProperty accentHue = new IntProperty("accent-hue", 200, 0, 360, () -> this.accent.getValue() == 8);
    final PercentProperty accentSaturation = new PercentProperty("accent-saturation", 70, 0, 100,
            () -> this.accent.getValue() == 8);
    final ModeProperty tint = new ModeProperty("glass-tint", 1, TINTS);
    final IntProperty tintHue = new IntProperty("tint-hue", 225, 0, 360, () -> this.tint.getValue() == 6);
    final PercentProperty opacity = new PercentProperty("glass-opacity", 42, 0, 90, null);

    final BooleanProperty accentEverywhere = new BooleanProperty("accent-everywhere", true);

    final PercentProperty blur = new PercentProperty("blur", 70, 0, 100, null);
    final PercentProperty saturation = new PercentProperty("saturation", 155, 50, 250, null);
    final PercentProperty brightness = new PercentProperty("brightness", 62, 30, 130, null);
    final PercentProperty refraction = new PercentProperty("refraction", 100, 0, 200, null);
    final IntProperty bezel = new IntProperty("edge-width", 16, 4, 30);
    final PercentProperty clearEdge = new PercentProperty("clear-edge", 85, 0, 100, null);
    final PercentProperty dispersion = new PercentProperty("dispersion", 10, 0, 40, null);
    final PercentProperty edgeLight = new PercentProperty("edge-light", 55, 0, 100, null);
    final BooleanProperty followCursor = new BooleanProperty("light-follows-cursor", true);
    final IntProperty radius = new IntProperty("corner-radius", 18, 6, 30);
    final PercentProperty sheen = new PercentProperty("sheen", 50, 0, 100, null);

    final PercentProperty shadow = new PercentProperty("shadow", 55, 0, 100, null);
    final IntProperty shadowSize = new IntProperty("shadow-size", 30, 0, 60);
    final PercentProperty dim = new PercentProperty("world-dim", 32, 0, 90, null);

    final ModeProperty selection = new ModeProperty("selection-style", 0, new String[]{"Liquid", "Solid", "Outline"});
    final PercentProperty selectionTint = new PercentProperty("selection-tint", 10, 0, 60, null);
    final PercentProperty selectionRefraction = new PercentProperty("selection-refraction", 100, 0, 300,
            () -> this.selection.getValue() == 0);
    final PercentProperty selectionLight = new PercentProperty("selection-light", 75, 0, 100,
            () -> this.selection.getValue() == 0);

    final BooleanProperty cosmos = new BooleanProperty("cosmos-background", true);
    final PercentProperty stars = new PercentProperty("stars", 60, 0, 100, this.cosmos::getValue);
    final BooleanProperty comets = new BooleanProperty("comets", true, this.cosmos::getValue);
    final BooleanProperty planet = new BooleanProperty("planet", true, this.cosmos::getValue);

    final BooleanProperty animations = new BooleanProperty("animations", true);
    final PercentProperty speed = new PercentProperty("speed", 100, 50, 200, this.animations::getValue);
    final PercentProperty bounce = new PercentProperty("bounce", 40, 0, 100, this.animations::getValue);
    final PercentProperty jelly = new PercentProperty("jelly", 100, 0, 200, this.animations::getValue);
    final ModeProperty opening = new ModeProperty("open-animation", 0, new String[]{"Pop", "Fade", "Slide", "None"},
            this.animations::getValue);
    final BooleanProperty smoothScroll = new BooleanProperty("smooth-scroll", true);

    final ModeProperty font = new ModeProperty("font", 0, FONTS);
    final PercentProperty textSize = new PercentProperty("text-size", 100, 90, 115, null);

    final ModeProperty language = new ModeProperty("language", 0, new String[]{"中文", "English"});

    final ModeProperty density = new ModeProperty("density", 0, new String[]{"Comfortable", "Compact"});
    final BooleanProperty accountCard = new BooleanProperty("account-card", true);
    final BooleanProperty hintBar = new BooleanProperty("hint-bar", true);
    final BooleanProperty moduleStatus = new BooleanProperty("module-status", true);
    final BooleanProperty counts = new BooleanProperty("category-counts", true);
    final BooleanProperty stateDots = new BooleanProperty("state-dots", true);

    final BooleanProperty autoSendLogs = new BooleanProperty(atlas.management.LogUploader.SETTING, false);

    final BooleanProperty shareRunData = new BooleanProperty("share-run-data", false);

    final BooleanProperty shareDodgeData = new BooleanProperty("share-dodge-data", true);
    private boolean runDataSeen;

    final BooleanProperty shareUsage = new BooleanProperty("share-usage", false);
    final atlas.property.properties.TextProperty publicName = new atlas.property.properties.TextProperty("public-name",
            atlas.management.UsageReporter.alias(), () -> this.shareUsage.getValue());
    private Boolean usageSeen;
    private String nameSeen;

    final ModeProperty mobileSafe = new ModeProperty(atlas.util.render.MobileSafe.SETTING, 0,
            atlas.util.render.MobileSafe.MODES);

    final ModeProperty moveCheck = new ModeProperty(atlas.management.MoveCheck.SETTING, 0, atlas.management.MoveCheck.MODES);

    final ModeProperty moveCheckChat = new ModeProperty(atlas.management.MoveCheck.CHAT_SETTING, 0,
            atlas.management.MoveCheck.CHAT_MODES);
    final ModeProperty deviceLayout = new ModeProperty(atlas.util.render.DeviceProfile.SETTING, 0,
            atlas.util.render.DeviceProfile.MODES);

    final Map<String, List<Property<?>>> groups = new LinkedHashMap<String, List<Property<?>>>();
    final Map<String, String> descriptions = new LinkedHashMap<String, String>();

    static final String[] PRESETS = {"Cosmos", "Liquid", "Frost", "Crystal", "Solid", "Neon"};

    private String saved = "";
    private long changedAt;
    private long checkedAt;

    AtlasTheme() {
        group("Colors", "The accent that marks what is on and selected, and the colour of the glass itself. Accent "
                        + "everywhere: the HUD module list, the HUD notifications and the [Atlas] chat prefix take the "
                        + "accent too (chat in its nearest chat colour); off, they keep their own colours.",
                this.mode, this.accent, this.accentHue, this.accentSaturation, this.accentEverywhere, this.tint,
                this.tintHue, this.opacity);
        group("Glass", "The window's material: how much it blurs and bends the world behind it, and how "
                        + "light catches its edge.",
                this.blur, this.saturation, this.brightness, this.refraction, this.bezel, this.clearEdge,
                this.dispersion, this.edgeLight, this.followCursor, this.radius, this.sheen);
        group("Depth", "How far the window floats above the game, and how much the game fades behind it.",
                this.shadow, this.shadowSize, this.dim);
        group("Selection", "The droplet of glass that marks the selected category and module.",
                this.selection, this.selectionTint, this.selectionRefraction, this.selectionLight);
        group("Cosmos", "Space behind the window, like the Atlas banner: stars, comets and a ringed planet.",
                this.cosmos, this.stars, this.comets, this.planet);
        group("Motion", "The springs everything moves on: how fast, how much it overshoots, how much it "
                        + "stretches while moving.",
                this.animations, this.speed, this.bounce, this.jelly, this.opening, this.smoothScroll);
        group("Text", "The typeface and its size.", this.font, this.textSize);
        group("Layout", "How tightly rows are packed, and which extras are shown.",
                this.density, this.accountCard, this.hintBar, this.moduleStatus, this.counts, this.stateDots);
        group("Privacy", "When a server kicks you, or you get flagged many times in a minute, the client sends its "
                        + "log and config to the developers to help fix it. Off unless you turn it on. Your name, other players' names, "
                        + "tokens and numeric IP addresses are removed first; the server's address (its domain) is kept so the problem "
                        + "can be traced. Like any website, the relay sees your IP address; it uses it only to limit how often you can "
                        + "send. Share run data: anonymous SpeedBlocker movement numbers (no names, no server, no coordinates). "
                        + "Share dodge data: when ProjectileAimBot watches a player dodge your arrow, that player's name "
                        + "and how it dodged go to the shared record every Atlas player reads (nothing about you). "
                        + "Share usage: while you play, your Minecraft name, server, Atlas version, FPS, ping, "
                        + "modules on and system details go to the Atlas developer (your IP address and location are never "
                        + "shown to anyone), and your account is checked with "
                        + "Mojang (your login stays between you and Mojang). Only the owner and admins see it; the public "
                        + "Atlas site shows only your public name. Deleted after 30 days. Public name: 2-20 letters, "
                        + "digits, spaces, _ - or .",
                this.autoSendLogs, this.shareRunData, this.shareDodgeData, this.shareUsage, this.publicName);
        group("Performance", "Mobile-safe drawing for phones (PojavLauncher and its forks on gl4es or Krypton Wrapper): "
                        + "no blur, glass shaders or glow, plain rounded shapes. Auto turns it on only when such a "
                        + "renderer is found; it changes nothing on a PC. Device layout: on a phone the menu fills the "
                        + "screen, scrolls with a finger drag, has bigger text and rows and keeps its hints shown, and HUD "
                        + "elements still at their default place move away from the touch buttons. Auto finds a phone by "
                        + "itself. Move check: checks every move against the movement simulation, as an anticheat "
                        + "that re-simulates moves would, and logs what does not fit (with Logs on). Auto runs it except "
                        + "on a phone or under 100 fps. Move check chat: a line in your own chat (never sent) when a move "
                        + "breaks the simulation or knockback is not taken, naming the module that changed the move; at "
                        + "most once every 3 seconds for each.",
                this.mobileSafe, this.deviceLayout, this.moveCheck, this.moveCheckChat);
        load();
        this.runDataSeen = Boolean.TRUE.equals(atlas.management.RunCollector.consent());
        this.shareRunData.setValue(this.runDataSeen);
        this.usageSeen = atlas.management.UsageReporter.consent();
        this.shareUsage.setValue(Boolean.TRUE.equals(this.usageSeen));
        this.nameSeen = atlas.management.UsageReporter.alias();
        this.publicName.setValue(this.nameSeen);
        this.saved = snapshot();
        atlas.management.LogUploader.setEnabled(this.autoSendLogs.getValue());
        atlas.util.render.MobileSafe.setSetting(this.mobileSafe.getValue());
        atlas.util.render.DeviceProfile.setSetting(this.deviceLayout.getValue());
        atlas.management.MoveCheck.setSetting(this.moveCheck.getValue());
        atlas.management.MoveCheck.setChat(this.moveCheckChat.getValue());
        atlas.management.HabitsCloud.setSharing(this.shareDodgeData.getValue());
    }

    private void group(String name, String description, Property<?>... properties) {
        this.groups.put(name, new ArrayList<Property<?>>(Arrays.asList(properties)));
        this.descriptions.put(name, description);
    }

    List<Property<?>> all() {
        List<Property<?>> out = new ArrayList<Property<?>>();
        for (List<Property<?>> list : this.groups.values()) {
            out.addAll(list);
        }
        return out;
    }

    boolean owns(Property<?> property) {
        return all().contains(property);
    }

    boolean isLook(Property<?> property) {
        return property != this.autoSendLogs && property != this.shareRunData && property != this.shareDodgeData
                && property != this.shareUsage && property != this.publicName
                && property != this.mobileSafe
                && property != this.deviceLayout && property != this.moveCheck && property != this.moveCheckChat;
    }

    int accent(int clickGui) {
        int mode = this.accent.getValue();
        if (mode == 0) {
            return clickGui;
        }
        if (mode == 8) {
            return hsb(this.accentHue.getValue(), this.accentSaturation.getValue() / 100.0F, 0.97F);
        }
        return ACCENT_COLOURS[mode];
    }

    int swatch(ModeProperty property, int index, int clickGui) {
        if (property == this.accent) {
            return index == 0 ? clickGui : index == 8
                    ? hsb(this.accentHue.getValue(), this.accentSaturation.getValue() / 100.0F, 0.97F)
                    : ACCENT_COLOURS[index];
        }
        if (property == this.tint) {
            return index == 6 ? tintBase() : lift(TINT_COLOURS[index]);
        }
        return 0;
    }

    int glassColour() {
        return isLight() ? LIGHT_TINT : tintBase();
    }

    static final String[] SYSTEM_GROUPS = {"Performance", "Privacy"};

    private int tintBase() {
        return this.tint.getValue() == 6 ? hsb(this.tintHue.getValue(), 0.55F, 0.12F) : TINT_COLOURS[this.tint.getValue()];
    }

    private static int lift(int colour) {
        int r = Math.min(255, ((colour >> 16) & 0xFF) * 4);
        int g = Math.min(255, ((colour >> 8) & 0xFF) * 4);
        int b = Math.min(255, (colour & 0xFF) * 4);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int hsb(float hue, float saturation, float brightness) {
        return 0xFF000000 | (Color.HSBtoRGB(hue / 360.0F, saturation, brightness) & 0x00FFFFFF);
    }

    Liquid.Style pane() {
        boolean light = isLight();

        int percent = light ? Math.max(this.opacity.getValue(), 72) : this.opacity.getValue();
        int alpha = Math.round(percent / 100.0F * 255.0F);
        int base = light ? LIGHT_TINT : tintBase();
        float brightness = this.brightness.getValue() / 100.0F;
        if (light) {
            brightness = Math.max(1.12F, brightness);
        }
        return new Liquid.Style(this.bezel.getValue(), 26.0F * this.refraction.getValue() / 100.0F,
                this.dispersion.getValue() / 100.0F, this.saturation.getValue() / 100.0F,
                brightness, (alpha << 24) | (base & 0x00FFFFFF),
                this.edgeLight.getValue() / 100.0F, 0.10F, this.clearEdge.getValue() / 100.0F);
    }

    boolean isLight() {
        return this.mode.getValue() == 1;
    }

    void toggleMode() {
        this.mode.setValue(isLight() ? 0 : 1);
    }

    Liquid.Style pill(int accent, float shown) {
        int alpha = Math.round(this.selectionTint.getValue() / 100.0F * shown * 255.0F);
        return new Liquid.Style(7.0F, 5.0F * this.selectionRefraction.getValue() / 100.0F, 0.12F, 1.15F, 1.12F,
                (Math.min(255, alpha) << 24) | (accent & 0x00FFFFFF),
                this.selectionLight.getValue() / 100.0F * shown, 0.0F, 0.0F);
    }

    int blurLevels() {
        int b = this.blur.getValue();
        return b <= 0 ? 0 : b < 20 ? 1 : b < 45 ? 2 : b < 70 ? 3 : 4;
    }

    float blurOffset() {
        return 1.2F + 2.6F * this.blur.getValue() / 100.0F;
    }

    String fontFile() {
        return FONT_FILES[Math.max(0, Math.min(FONT_FILES.length - 1, this.font.getValue()))];
    }

    float springSpeed() {
        return this.speed.getValue() / 100.0F;
    }

    float damping() {
        return 1.6F - 1.05F * this.bounce.getValue() / 100.0F;
    }

    float jelly() {
        return this.jelly.getValue() / 100.0F;
    }

    boolean compact() {
        return this.density.getValue() == 1;
    }

    String summary(String group) {
        if ("Colors".equals(group)) {
            return this.accent.getModeString() + "  ·  " + this.tint.getModeString();
        }
        if ("Glass".equals(group)) {
            return "blur " + this.blur.getValue() + "%";
        }
        if ("Depth".equals(group)) {
            return "shadow " + this.shadow.getValue() + "%";
        }
        if ("Selection".equals(group)) {
            return this.selection.getModeString();
        }
        if ("Motion".equals(group)) {
            return this.animations.getValue() ? this.opening.getModeString() : "off";
        }
        if ("Performance".equals(group)) {
            return this.mobileSafe.getModeString() + "  ·  " + (atlas.util.render.MobileSafe.active() ? "on" : "off")
                    + "  ·  " + atlas.util.render.DeviceProfile.describe()
                    + "  ·  move check " + atlas.management.MoveCheck.describe();
        }
        if ("Text".equals(group)) {
            return this.font.getModeString();
        }
        if ("Layout".equals(group)) {
            return this.density.getModeString();
        }
        return "";
    }

    void reset() {
        int keepMode = this.mode.getValue();
        for (Property<?> property : all()) {
            if (isLook(property)) {
                resetOne(property);
            }
        }
        this.mode.setValue(keepMode);
    }

    private static void resetOne(Property<?> property) {
        property.setValue(property.getDefault());
    }

    java.util.Map<Property<?>, Object> values() {
        java.util.Map<Property<?>, Object> values = new java.util.LinkedHashMap<Property<?>, Object>();
        for (Property<?> property : all()) {
            values.put(property, property.getValue());
        }
        return values;
    }

    void restore(java.util.Map<Property<?>, Object> values) {
        for (java.util.Map.Entry<Property<?>, Object> entry : values.entrySet()) {
            entry.getKey().setValue(entry.getValue());
        }
    }

    void apply(String preset) {
        reset();
        if ("Frost".equals(preset)) {
            this.tint.setValue(1);
            this.blur.setValue(100);
            this.opacity.setValue(55);
            this.brightness.setValue(75);
            this.saturation.setValue(120);
            this.clearEdge.setValue(40);
            this.refraction.setValue(60);
            this.dim.setValue(45);
        } else if ("Crystal".equals(preset)) {
            this.blur.setValue(35);
            this.opacity.setValue(25);
            this.brightness.setValue(70);
            this.saturation.setValue(170);
            this.clearEdge.setValue(100);
            this.refraction.setValue(160);
            this.dispersion.setValue(20);
            this.edgeLight.setValue(80);
            this.bezel.setValue(22);
        } else if ("Solid".equals(preset)) {
            this.blur.setValue(80);
            this.opacity.setValue(85);
            this.refraction.setValue(30);
            this.clearEdge.setValue(0);
            this.dispersion.setValue(0);
            this.selection.setValue(1);
            this.selectionTint.setValue(20);
            this.dim.setValue(55);
        } else if ("Cosmos".equals(preset)) {
            this.accent.setValue(9);
            this.tint.setValue(7);
            this.opacity.setValue(62);
            this.saturation.setValue(110);
            this.sheen.setValue(25);
            this.dim.setValue(70);
            this.selectionTint.setValue(16);
            this.cosmos.setValue(true);
        } else if ("Neon".equals(preset)) {
            this.accent.setValue(3);
            this.tint.setValue(3);
            this.saturation.setValue(220);
            this.edgeLight.setValue(90);
            this.selectionTint.setValue(22);
            this.dispersion.setValue(18);
        }
    }

    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (Property<?> property : all()) {
            sb.append(property.getName()).append('=').append(property.getValue()).append(';');
        }
        return sb.toString();
    }

    void autosave() {
        long time = System.currentTimeMillis();
        if (time - this.checkedAt < 250L) {
            return;
        }
        this.checkedAt = time;
        this.syncRunData();
        this.syncUsage();
        String now = snapshot();
        if (now.equals(this.saved)) {
            this.changedAt = 0L;
            return;
        }
        if (this.changedAt == 0L) {
            this.changedAt = time;
        } else if (time - this.changedAt > 500L) {
            save();
        }
    }

    private void syncRunData() {
        boolean collector = Boolean.TRUE.equals(atlas.management.RunCollector.consent());
        if (collector != this.runDataSeen) {
            this.runDataSeen = collector;
            this.shareRunData.setValue(collector);
        } else if (this.shareRunData.getValue() != collector) {
            atlas.management.RunCollector.setConsent(this.shareRunData.getValue());
            this.runDataSeen = this.shareRunData.getValue();
        }
    }

    private void syncUsage() {
        Boolean consent = atlas.management.UsageReporter.consent();
        if (consent != this.usageSeen) {
            this.usageSeen = consent;
            this.shareUsage.setValue(Boolean.TRUE.equals(consent));
        } else if (this.shareUsage.getValue() != Boolean.TRUE.equals(consent)) {
            atlas.management.UsageReporter.setConsent(this.shareUsage.getValue());
            this.usageSeen = this.shareUsage.getValue();
        }
        String alias = atlas.management.UsageReporter.alias();
        if (!alias.equals(this.nameSeen)) {
            this.nameSeen = alias;
            this.publicName.setValue(alias);
        } else if (!alias.equals(this.publicName.getValue())) {
            String typed = this.publicName.getValue();
            if (atlas.management.UsageReporter.setAlias(typed)) {
                this.nameSeen = atlas.management.UsageReporter.alias();
            } else {
                this.publicName.setValue(alias);
            }
        }
    }

    void save() {
        this.syncUsage();
        atlas.management.LogUploader.setEnabled(this.autoSendLogs.getValue());
        atlas.management.HabitsCloud.setSharing(this.shareDodgeData.getValue());
        atlas.util.render.MobileSafe.setSetting(this.mobileSafe.getValue());
        atlas.util.render.DeviceProfile.setSetting(this.deviceLayout.getValue());
        atlas.management.MoveCheck.setSetting(this.moveCheck.getValue());
        atlas.management.MoveCheck.setChat(this.moveCheckChat.getValue());
        String now = snapshot();
        if (now.equals(this.saved)) {
            return;
        }
        PrintWriter writer = null;
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            JsonObject object = new JsonObject();
            for (Property<?> property : all()) {
                property.write(object);
            }
            writer = new PrintWriter(FILE, "UTF-8");
            writer.println(new GsonBuilder().setPrettyPrinting().create().toJson(object));
            writer.flush();
            this.saved = now;
            this.changedAt = 0L;
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void load() {
        if (!FILE.exists()) {
            return;
        }
        Reader reader = null;
        try {
            reader = new FileReader(FILE);
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return;
            }
            JsonObject object = parsed.getAsJsonObject();
            for (Property<?> property : all()) {
                if (object.has(property.getName())) {
                    try {
                        property.read(object);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
