package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.util.ChatUtil;
import atlas.util.SoundUtil;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;

public class FlagResponder extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final String[] PROTECTED = {
            "Debug", "FlagDetector", "FlagResponder", "HitCheck", "LatencyCrosshair",
            "LatencyGovernor", "HUD", "ESP", "Chams", "BedESP", "ItemESP", "TargetESP",
            "Indicators", "Hotbar", "Animations", "RenderFixes", "Fullbright",
            "ClientSpoofer", "ResourceSpoofer", "AntiBot", "BedTracker", "ServerProfiles",

            "Adaptive", "AutoTune", "ServerFingerprint", "FightLog"
    };

    private static boolean isProtected(String name) {
        for (String entry : PROTECTED) {
            if (entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public final IntProperty window = new IntProperty("window-seconds", 30, 5, 300);
    public final IntProperty threshold = new IntProperty("threshold", 5, 2, 50);

    public final IntProperty cooldown = new IntProperty("cooldown-seconds", 60, 5, 600);

    public final BooleanProperty dryRun = new BooleanProperty("dry-run", false);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty sound = new BooleanProperty("sound", true);

    private final Map<String, Long> lastAction = new HashMap<String, Long>();
    private int disabled;
    private int checkTimer;

    public FlagResponder() {
        super("FlagResponder", false, false,
                "Turns off whichever module keeps being blamed for server corrections");
    }

    @Override
    public void onEnabled() {
        this.lastAction.clear();
        this.disabled = 0;
        this.checkTimer = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (++this.checkTimer < 20) {
            return;
        }
        this.checkTimer = 0;

        Module module = Atlas.moduleManager.modules.get(FlagDetector.class);
        if (!(module instanceof FlagDetector) || !module.isEnabled()) {
            return;
        }
        FlagDetector detector = (FlagDetector) module;
        long windowMillis = this.window.getValue() * 1000L;
        long now = System.currentTimeMillis();

        for (String name : detector.implicatedNames()) {
            if (isProtected(name)) {
                continue;
            }
            Module watched = Atlas.moduleManager.getModule(name);
            if (watched == null || !watched.isEnabled()) {
                continue;
            }
            Long acted = this.lastAction.get(name);
            if (acted != null && now - acted < this.cooldown.getValue() * 1000L) {
                continue;
            }
            int count = detector.implicationsWithin(name, windowMillis);
            if (count < this.threshold.getValue()) {
                continue;
            }
            this.lastAction.put(name, now);
            act(watched, name, count);
        }
    }

    private void act(Module watched, String name, int count) {
        String verb;
        if (this.dryRun.getValue()) {
            verb = "&ewould disable";
        } else {
            watched.setEnabled(false);
            Module detector = Atlas.moduleManager.modules.get(FlagDetector.class);
            if (detector instanceof FlagDetector) {
                ((FlagDetector) detector).clearImplications(name);
            }
            this.disabled++;
            verb = "&cdisabled";
        }
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted(String.format(
                    "&7[&bFlagResponder&7] %s &f%s&7 &8(&f%d&7 flags in %ds&8)",
                    verb, name, count, this.window.getValue()));
        }
        if (this.sound.getValue()) {
            SoundUtil.playSound("note.pling");
        }
    }

    @Override
    public String[] getSuffix() {
        if (this.dryRun.getValue()) {
            return new String[]{"dry-run"};
        }
        return new String[]{this.disabled == 0 ? "watching" : this.disabled + " off"};
    }
}
