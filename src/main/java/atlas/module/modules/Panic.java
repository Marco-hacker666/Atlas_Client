package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.TextProperty;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public class Panic extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final String[] KEEP = {
            "Panic", "ClickGUI", "ClickGui", "HUD",
            "Debug", "FlagDetector", "FlagResponder", "HitCheck", "PacketLogger",
            "ServerFingerprint", "ServerProfiles", "Statistics", "Adaptive", "AutoTune",
            "LatencyCrosshair", "TargetHUD", "TargetESP", "ESP", "ESP2D", "Chams",
            "BedESP", "ItemESP", "ChestESP", "Tracers", "NameTags", "Indicators",
            "Hotbar", "Animations", "RenderFixes", "FullBright", "BlockOverlay",
            "Radar", "BedTracker", "BedwarUtils", "TeamHealthDisplay", "DynamicIsland",
            "WaterMark", "WaterMark2", "FPScounter", "Capes", "AntiBot",
            "ClientSpoofer", "ResourceSpoofer", "MouseRawInput", "Ambience"
    };

    public final TextProperty keepAlso = new TextProperty("keep-also", "");

    public final BooleanProperty report = new BooleanProperty("report", true);

    public final BooleanProperty dropHeld = new BooleanProperty("drop-held-packets", true);

    private boolean armed;

    public Panic() {
        super("Panic", false, false,
                "Switches every behaviour module off at once and releases anything being withheld");
    }

    @Override
    public void onEnabled() {
        this.armed = true;
    }

    @EventTarget(whenDisabled = true)
    public void onTick(TickEvent event) {
        if (!this.armed || event.getType() != EventType.POST) {
            return;
        }
        this.armed = false;
        panic();

        this.setEnabled(false);
    }

    private void panic() {
        List<String> stopped = new ArrayList<String>();

        releaseHeld(stopped);

        for (Module module : Atlas.moduleManager.modules.values()) {
            if (module == null || !module.isEnabled() || keep(module)) {
                continue;
            }
            module.setEnabled(false);
            if (!stopped.contains(module.getName())) {
                stopped.add(module.getName());
            }
        }

        if (this.report.getValue()) {
            announce(stopped);
        }
    }

    private void releaseHeld(List<String> stopped) {
        try {
            if (Atlas.blinkManager != null && Atlas.blinkManager.isBlinking()) {
                if (this.dropHeld.getValue()) {
                    Atlas.blinkManager.discardHeld();
                }
                Atlas.blinkManager.setBlinkState(false, Atlas.blinkManager.getBlinkingModule());
                stopped.add("Blink queue");
            }
        } catch (Exception ignored) {
        }
        try {
            if (Atlas.lagManager != null) {
                Atlas.lagManager.setDelay(0);
            }
        } catch (Exception ignored) {
        }

        Module fakeLag = Atlas.moduleManager.getModule("FakeLag");
        if (fakeLag != null && fakeLag.isEnabled()) {
            fakeLag.setEnabled(false);
            stopped.add("FakeLag");
        }
    }

    private boolean keep(Module module) {
        String name = module.getName();
        String className = module.getClass().getSimpleName();
        for (String entry : KEEP) {
            if (entry.equalsIgnoreCase(name) || entry.equalsIgnoreCase(className)) {
                return true;
            }
        }
        String extra = this.keepAlso.getValue();
        if (extra == null || extra.trim().isEmpty()) {
            return false;
        }
        for (String entry : extra.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.equalsIgnoreCase(name) || trimmed.equalsIgnoreCase(className)) {
                return true;
            }
        }
        return false;
    }

    private void announce(List<String> stopped) {
        if (stopped.isEmpty()) {
            ChatUtil.sendFormatted("&7[&cPanic&7] &fnothing was on");
            return;
        }
        ChatUtil.sendFormatted("&7[&cPanic&7] &fstopped &c" + stopped.size() + "&f:");
        StringBuilder line = new StringBuilder();
        for (String name : stopped) {
            if (line.length() > 0 && line.length() + name.length() > 60) {
                ChatUtil.sendFormatted("&8  " + line);
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append("&7, &8");
            }
            line.append(name);
        }
        if (line.length() > 0) {
            ChatUtil.sendFormatted("&8  " + line);
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{"bind a key"};
    }
}
