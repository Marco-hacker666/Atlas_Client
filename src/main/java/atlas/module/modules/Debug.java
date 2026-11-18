package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.IntProperty;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;

public class Debug extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty flags = new BooleanProperty("flags", true);

    public final BooleanProperty hits = new BooleanProperty("hits", true);

    public final BooleanProperty fights = new BooleanProperty("fights", true);

    public final BooleanProperty governor = new BooleanProperty("governor", false);

    public final BooleanProperty autoDisable = new BooleanProperty("auto-disable", true);

    public final BooleanProperty autoDisableDryRun = new BooleanProperty("auto-disable-dry-run", false,
            this.autoDisable::getValue);

    public final IntProperty severity = new IntProperty("severity", 5, 2, 30,
            this.autoDisable::getValue);
    public final IntProperty window = new IntProperty("window-seconds", 30, 5, 300,
            this.autoDisable::getValue);

    public final BooleanProperty logFiles = new BooleanProperty("log-files", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    private int timer;
    private boolean applied;

    public Debug() {
        super("Debug", false, false,
                "One switch for the diagnostic tools, with each behind its own section");
    }

    @Override
    public void onEnabled() {
        this.applied = false;
        this.timer = 0;
    }

    @Override
    public void onDisabled() {
        set(FlagDetector.class, false);
        set(HitCheck.class, false);
        set(FightLog.class, false);
        set(LatencyGovernor.class, false);
        set(FlagResponder.class, false);
    }

    public static final Class<?>[] MANAGED = {FlagDetector.class, HitCheck.class, FightLog.class,
            LatencyGovernor.class, FlagResponder.class};

    public static boolean hidesWithPanel(Module module) {
        Module panel = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(Debug.class);
        if (panel == null || !panel.isHidden()) {
            return false;
        }
        for (Class<?> type : MANAGED) {
            if (type == module.getClass()) {
                return true;
            }
        }
        return false;
    }

    private static void set(Class<?> type, boolean wanted) {
        Module module = Atlas.moduleManager.modules.get(type);
        if (module != null && module.isEnabled() != wanted) {
            module.setEnabled(wanted);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }

        if (this.applied && ++this.timer < 20) {
            return;
        }
        this.timer = 0;
        this.applied = true;
        apply();
    }

    private void apply() {
        set(FlagDetector.class, this.flags.getValue());
        set(HitCheck.class, this.hits.getValue());
        set(FightLog.class, this.fights.getValue());
        set(LatencyGovernor.class, this.governor.getValue());

        boolean responder = this.autoDisable.getValue() && this.flags.getValue();
        set(FlagResponder.class, responder);

        Module module = Atlas.moduleManager.modules.get(FlagResponder.class);
        if (module instanceof FlagResponder) {
            FlagResponder found = (FlagResponder) module;
            found.threshold.setValue(this.severity.getValue());
            found.window.setValue(this.window.getValue());
            found.dryRun.setValue(this.autoDisableDryRun.getValue());
            found.chat.setValue(this.chat.getValue());
        }
        Module detector = Atlas.moduleManager.modules.get(FlagDetector.class);
        if (detector instanceof FlagDetector) {
            ((FlagDetector) detector).logFile.setValue(this.logFiles.getValue());
            ((FlagDetector) detector).chat.setValue(this.chat.getValue());
        }
        Module hitCheck = Atlas.moduleManager.modules.get(HitCheck.class);
        if (hitCheck instanceof HitCheck) {
            ((HitCheck) hitCheck).logFile.setValue(this.logFiles.getValue());
        }
    }

    public void report() {
        StringBuilder sb = new StringBuilder("&7[&bDebug&7] ");
        sb.append(this.flags.getValue() ? "&aflags " : "&8flags ");
        sb.append(this.hits.getValue() ? "&ahits " : "&8hits ");
        sb.append(this.governor.getValue() ? "&agovernor " : "&8governor ");
        sb.append(this.autoDisable.getValue() ? "&aauto-disable" : "&8auto-disable");
        ChatUtil.sendFormatted(sb.toString());
    }

    @Override
    public String[] getSuffix() {
        int on = 0;
        if (this.flags.getValue()) {
            on++;
        }
        if (this.hits.getValue()) {
            on++;
        }
        if (this.governor.getValue()) {
            on++;
        }
        if (this.autoDisable.getValue()) {
            on++;
        }
        return new String[]{on + "/4"};
    }
}
