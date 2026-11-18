package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.AdaptivePolicy;
import atlas.util.Brain;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Adaptive extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public final FloatProperty threshold = new FloatProperty("excess-threshold", 0.35F, 0.05F, 5.0F);

    public final FloatProperty minConfidence = new FloatProperty("min-confidence", 0.55F, 0.1F, 1.0F);

    public final IntProperty probeMinutes = new IntProperty("probe-minutes", 8, 2, 60);

    public final IntProperty decideMinutes = new IntProperty("decide-minutes", 5, 1, 60);

    public final BooleanProperty dryRun = new BooleanProperty("dry-run", false);

    public final BooleanProperty probe = new BooleanProperty("probe", true);
    public final BooleanProperty chat = new BooleanProperty("chat", true);

    public final IntProperty minFlags = new IntProperty("min-flags", 8, 1, 200);

    public final IntProperty warmupMinutes = new IntProperty("warmup-minutes", 3, 0, 30);

    public final IntProperty maxActs = new IntProperty("max-acts", 2, 1, 10);

    public final IntProperty reactMinutes = new IntProperty("react-minutes", 20, 5, 180);

    public final atlas.property.properties.TextProperty focus =
            new atlas.property.properties.TextProperty("focus",
                    "AimAssist,AutoClicker,Backtrack,AutoBlock,ClickAssits,HitSelect,"
                            + "InvulnTiming,Velocity,WTap,KnockbackDelay,Reach,SilentAura,"
                            + "LagRange,Blink,ServerLag,FakeLag,AntiDebuff,AutoSwap");

    private static final String[] PROTECTED = {
            "Adaptive", "Debug", "FlagDetector", "FlagResponder", "HitCheck",
            "LatencyCrosshair", "LatencyGovernor", "ServerFingerprint", "AutoTune",
            "ServerProfiles", "HUD", "ESP", "Chams", "BedESP", "ItemESP", "TargetESP",
            "Indicators", "Hotbar", "Animations", "RenderFixes", "Fullbright",
            "ClientSpoofer", "ResourceSpoofer", "AntiBot", "BedTracker", "ClickGUIModule"
    };

    private final Map<String, Boolean> restore = new HashMap<String, Boolean>();

    private long lastSample;
    private long lastDecision;
    private long probeUntil;
    private String probing;
    private int actions;
    private double probeStartedOff;
    private String currentServer = "";
    private final java.util.Set<String> unprobeable = new java.util.HashSet<String>();
    private File logTarget;

    private final Map<String, AdaptivePolicy.Epoch> epochs =
            new java.util.concurrent.ConcurrentHashMap<String, AdaptivePolicy.Epoch>();

    private String lastHold;

    private final java.util.ArrayDeque<String> decisions = new java.util.ArrayDeque<String>();

    public Adaptive() {
        super("Adaptive", false, false,
                "Learns which modules this server objects to and turns them down");

        atlas.management.Shutdown.register(atlas.management.Shutdown.Stage.RESTORE, "Adaptive.restore",
                this::restoreAll);
        atlas.management.Shutdown.register(atlas.management.Shutdown.Stage.SAVE_STATE, "Brain", Brain::save);
    }

    private void restoreAll() {
        for (Map.Entry<String, Boolean> entry : this.restore.entrySet()) {
            Module module = Atlas.moduleManager.getModule(entry.getKey());
            if (module != null && module.isEnabled() != entry.getValue()) {
                module.setEnabled(entry.getValue());
            }
        }
        this.restore.clear();
        this.probing = null;

        releaseKnobs();
    }

    private static void releaseKnobs() {
        if (Atlas.moduleManager == null) {
            return;
        }
        Module reach = Atlas.moduleManager.modules.get(Reach.class);
        if (reach instanceof Reach) {
            ((Reach) reach).range.release(OWNER);
        }
        Module backtrack = Atlas.moduleManager.modules.get(BackTrack.class);
        if (backtrack instanceof BackTrack) {
            ((BackTrack) backtrack).normalDelay.release(OWNER);
            ((BackTrack) backtrack).adaptiveDelay.release(OWNER);
        }
        Module delay = Atlas.moduleManager.modules.get(KnockbackDelay.class);
        if (delay instanceof KnockbackDelay) {
            ((KnockbackDelay) delay).chance.release(OWNER);
        }
    }

    private static final String OWNER = "Adaptive";

    @Override
    public void onEnabled() {
        this.restore.clear();
        this.lastSample = System.currentTimeMillis();
        this.lastDecision = System.currentTimeMillis();
        this.probeUntil = 0L;
        this.probing = null;
        this.actions = 0;
        this.unprobeable.clear();
        this.logTarget = null;
        this.epochs.clear();
        this.lastHold = null;
        this.currentServer = serverKey();
        Brain.setServer(this.currentServer);
        Module tuner = Atlas.moduleManager.modules.get(AutoTune.class);
        if (tuner != null && tuner.isEnabled()) {
            say("&cAutoTune is on -- both tune the same settings, turn one off");
        }
        say(String.format("&7learning on &f%s&7 -- &f%d&7 flag kinds known",
                Brain.server(), Brain.kindCount()));
    }

    @Override
    public void onDisabled() {
        restoreAll();
        Brain.save();
    }

    private static boolean isProtected(String name) {
        for (String entry : PROTECTED) {
            if (entry.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean inFocus(String name) {
        String raw = this.focus.getValue();
        if (raw == null || raw.trim().isEmpty()) {
            return true;
        }
        for (String entry : raw.split(",")) {
            if (entry.trim().equalsIgnoreCase(name)) {
                return true;
            }

            Module named = Atlas.moduleManager.getModule(entry.trim());
            if (named != null && named.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static double offMinutes(String module) {
        Brain.Exposure exposure = Brain.exposure(module);
        return exposure == null ? 0.0 : exposure.minutesOff;
    }

    private String serverKey() {
        String key = atlas.management.ServerSession.currentServerKey();
        if (key == null) {
            key = atlas.management.ServerSession.lastServerKey();
        }
        if (key != null) {
            return key;
        }
        ServerData data = mc.getCurrentServerData();
        return data == null || data.serverIP == null ? "singleplayer" : data.serverIP;
    }

    @EventTarget
    public void onSession(atlas.events.SessionEvent event) {
        if (event.getType() == atlas.events.SessionEvent.Type.START) {
            String key = event.getSession().serverKey();
            if (!key.equalsIgnoreCase(this.currentServer)) {
                endProbe();
                restoreAll();
                this.epochs.clear();
                this.lastHold = null;
                this.currentServer = key;
                Brain.setServer(key);
                log("SERVER  now " + key + " (" + Brain.kindCount() + " kinds known"
                        + (Brain.lastProblem().isEmpty() ? "" : "; file: " + Brain.lastProblem()) + ")");
            }
            Brain.beginSession();
        } else if (event.getType() == atlas.events.SessionEvent.Type.END) {
            Brain.save();
        }
    }

    private Map<String, Boolean> snapshotStates() {
        Map<String, Boolean> snapshot = new LinkedHashMap<String, Boolean>();
        for (Module module : Atlas.moduleManager.modules.values()) {
            String name = module.getName();
            if (isProtected(name)) {
                continue;
            }
            snapshot.put(name, module.isEnabled());
        }
        return snapshot;
    }

    public static void observeFlag(String signature, int count) {
        Module module = Atlas.moduleManager.modules.get(Adaptive.class);
        if (!(module instanceof Adaptive) || !module.isEnabled()) {
            return;
        }

        Brain.noteFlag(signature, count, ((Adaptive) module).snapshotStates());
        ((Adaptive) module).noteEpochFlag(signature, count);
    }

    private void noteEpochFlag(String signature, int count) {
        for (AdaptivePolicy.Epoch epoch : this.epochs.values()) {
            if (signature.equals(epoch.kind)) {
                Module target = Atlas.moduleManager.getModule(epoch.module);
                if (target != null && target.isEnabled()) {
                    epoch.flagsOn += count;
                }
            }
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        long now = System.currentTimeMillis();

        double minutes = (now - this.lastSample) / 60000.0;
        if (minutes > 0.0 && minutes < 1.0) {
            Brain.noteExposure(snapshotStates(), minutes);
            for (AdaptivePolicy.Epoch epoch : this.epochs.values()) {
                Module target = Atlas.moduleManager.getModule(epoch.module);
                if (target != null && target.isEnabled()) {
                    epoch.minutesOn += minutes;
                }
            }
        }
        this.lastSample = now;

        if (this.probing != null && now >= this.probeUntil) {
            endProbe();
            return;
        }
        if (now - this.lastDecision < this.decideMinutes.getValue() * 60000L) {
            return;
        }
        this.lastDecision = now;
        Brain.save();
        decide();
    }

    private void decide() {
        if (this.probing != null) {
            return;
        }
        AdaptivePolicy.Settings settings = settings();
        AdaptivePolicy.Context context = context();
        List<Brain.Suspect> suspects = Brain.suspects(this.threshold.getValue());
        AdaptivePolicy.Outcome outcome = AdaptivePolicy.decide(suspects, settings, context);
        if (outcome.acts()) {
            Module module = Atlas.moduleManager.getModule(outcome.chosen.module);
            if (module != null) {
                act(module, outcome.chosen, outcome);
                return;
            }
        }
        String hold = "HOLD    " + outcome.reason
                + (outcome.skipped.isEmpty() ? "" : "; passed over: " + outcome.skippedSummary());
        if (!hold.equals(this.lastHold)) {
            this.lastHold = hold;
            log(hold);
        }
        if (this.probe.getValue() && AdaptivePolicy.mayProbe(settings, context)) {
            beginProbe();
        }
    }

    private AdaptivePolicy.Settings settings() {
        AdaptivePolicy.Settings settings = new AdaptivePolicy.Settings();
        settings.minExcess = this.threshold.getValue();
        settings.minConfidence = this.minConfidence.getValue();
        settings.minSamples = this.minFlags.getValue();
        settings.warmupMs = this.warmupMinutes.getValue() * 60_000L;
        settings.maxActs = this.maxActs.getValue();
        settings.reactMinutes = this.reactMinutes.getValue();
        return settings;
    }

    private AdaptivePolicy.Context context() {
        return new AdaptivePolicy.Context() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public long sessionStartedAt() {
                atlas.management.ServerSession.Session session = atlas.management.ServerSession.current();
                return session == null ? 0L : session.startedAt();
            }

            @Override
            public String ineligible(String name) {
                Module module = Atlas.moduleManager.getModule(name);
                if (module == null) {
                    return "unknown-module";
                }
                if (isProtected(name)) {
                    return "protected";
                }
                if (!module.isEnabled()) {
                    return "off";
                }
                if (!canCause(module)) {
                    return "cannot-cause";
                }
                if (settingsOnly(module)) {
                    return "settings-only";
                }
                return null;
            }

            @Override
            public AdaptivePolicy.Epoch epoch(String name) {
                return epochs.get(name);
            }
        };
    }

    private static final java.util.Set<String> RENDER_EVENTS = new java.util.HashSet<String>(
            java.util.Arrays.asList("Render2DEvent", "Render3DEvent", "RenderLivingEvent", "ResizeEvent"));
    private static final String[] COSMETIC = {"NoHurtCam", "ItemPhysics", "Animations", "Capes", "ViewClip",
            "HitParticleEffects"};
    private static final Map<Class<?>, Boolean> CAN_CAUSE =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>();

    private static final String[] SETTINGS = {
            "TargetFilter", "Theme", "GuiModule", "ClickGUIModule", "Debug"
    };

    static boolean settingsOnly(Module module) {
        if (module instanceof ThemeStyle) {
            return true;
        }
        for (String name : SETTINGS) {
            if (name.equalsIgnoreCase(module.getName())) {
                return true;
            }
        }
        return false;
    }

    static boolean canCause(Module module) {
        Boolean known = CAN_CAUSE.get(module.getClass());
        if (known != null) {
            return known;
        }
        boolean result = true;
        for (String name : COSMETIC) {
            if (name.equalsIgnoreCase(module.getName())) {
                result = false;
            }
        }
        if (result) {
            boolean any = false;
            boolean nonRender = false;
            for (java.lang.reflect.Method method : module.getClass().getDeclaredMethods()) {
                if (method.isAnnotationPresent(EventTarget.class) && method.getParameterTypes().length == 1) {
                    any = true;
                    if (!RENDER_EVENTS.contains(method.getParameterTypes()[0].getSimpleName())) {
                        nonRender = true;
                    }
                }
            }
            result = !any || nonRender;
        }
        CAN_CAUSE.put(module.getClass(), result);
        return result;
    }

    private void act(Module module, Brain.Suspect suspect, AdaptivePolicy.Outcome outcome) {
        double severity = Math.min(1.0, suspect.excess() / 2.0) * suspect.confidence;
        String detail;
        String oldValue;
        String newValue;

        Adjustable knob = knobFor(module);
        if (knob != null && severity < 0.8) {
            double from = knob.get();
            double to = from - (from - knob.floor) * (0.25 + 0.5 * severity);
            oldValue = String.format(java.util.Locale.ROOT, "%.2f", from);
            newValue = String.format(java.util.Locale.ROOT, "%.2f", Math.max(knob.floor, to));
            if (this.dryRun.getValue()) {
                detail = String.format("would set %s %.2f -> %.2f", knob.label, from, to);
            } else {
                knob.set(to);
                detail = String.format("set %s %.2f -> %.2f", knob.label, from, to);
            }
        } else {
            oldValue = "on";
            newValue = "off";
            if (this.dryRun.getValue()) {
                detail = "would disable " + module.getName();
            } else {
                this.restore.put(module.getName(), Boolean.TRUE);
                module.setEnabled(false);
                detail = "disabled " + module.getName();
            }
        }

        AdaptivePolicy.Epoch epoch = this.epochs.get(suspect.module);
        if (epoch == null) {
            epoch = new AdaptivePolicy.Epoch(suspect.module);
            this.epochs.put(suspect.module, epoch);
        }
        epoch.acted(suspect.kind, System.currentTimeMillis(), suspect.rateOff);
        this.actions++;

        long seenAgo = Math.max(0L, System.currentTimeMillis() - suspect.lastSeen) / 60000L;
        String record = String.format(java.util.Locale.ROOT,
                "DECIDE  %s | detector=FlagDetector kind=%s suspect=%s excess=%.2f/min on=%.2f off=%.2f"
                        + " min-on=%.1f min-off=%.1f flags-on=%.1f conf=%.0f%% n=%d recent=%.1f session=%d"
                        + " last-seen=%dm old=%s new=%s act=%d/%d reason=%s%s",
                detail, suspect.kind, suspect.module, suspect.excess(), suspect.rateOn, suspect.rateOff,
                suspect.minutesOn, suspect.minutesOff, suspect.flagsOn, suspect.confidence * 100.0,
                suspect.samples, suspect.recent, suspect.session, seenAgo, oldValue, newValue,
                epoch.acts, this.maxActs.getValue(), outcome.reason,
                outcome.skipped.isEmpty() ? "" : "; passed over: " + outcome.skippedSummary());
        log(record);
        synchronized (this.decisions) {
            this.decisions.addLast(record);
            while (this.decisions.size() > 20) {
                this.decisions.removeFirst();
            }
        }
        this.lastHold = null;
        say(String.format("&c%s &8(&f+%.2f&7 flags/min on &f%s&7, conf &f%.0f%%&8)",
                detail, suspect.excess(), suspect.kind, suspect.confidence * 100.0));
    }

    public List<String> recentDecisions() {
        synchronized (this.decisions) {
            return new ArrayList<String>(this.decisions);
        }
    }

    private void beginProbe() {
        List<String> candidates = new ArrayList<String>();
        for (Module module : Atlas.moduleManager.modules.values()) {
            String name = module.getName();
            if (!isProtected(name) && module.isEnabled() && inFocus(name)
                    && !this.unprobeable.contains(name)) {
                candidates.add(name);
            }
        }
        String target = Brain.leastKnown(candidates);
        if (target == null) {
            return;
        }
        Module module = Atlas.moduleManager.getModule(target);
        if (module == null || !module.isEnabled()) {
            return;
        }
        this.probing = target;
        this.probeUntil = System.currentTimeMillis() + this.probeMinutes.getValue() * 60000L;
        this.probeStartedOff = offMinutes(target);
        this.restore.put(target, Boolean.TRUE);
        module.setEnabled(false);
        Brain.Exposure exposure = Brain.exposure(target);
        log(String.format("PROBE   %s off for %dm (had on=%.1fm off=%.1fm)", target,
                this.probeMinutes.getValue(),
                exposure == null ? 0.0 : exposure.minutesOn,
                exposure == null ? 0.0 : exposure.minutesOff));
        say(String.format("&7measuring without &f%s&7 for &f%d&7 min", target,
                this.probeMinutes.getValue()));
    }

    private void endProbe() {
        String target = this.probing;
        this.probing = null;
        if (target == null) {
            return;
        }
        Boolean was = this.restore.remove(target);
        Module module = Atlas.moduleManager.getModule(target);
        if (module != null && Boolean.TRUE.equals(was)) {
            module.setEnabled(true);
        }

        if (offMinutes(target) - this.probeStartedOff < 0.5) {
            this.unprobeable.add(target);
            log("SKIP    " + target + " gained no off-time while probed; not probing it again");
        }
        Brain.Exposure exposure = Brain.exposure(target);
        log(String.format("PROBED  %s back on (on=%.1fm off=%.1fm)", target,
                exposure == null ? 0.0 : exposure.minutesOn,
                exposure == null ? 0.0 : exposure.minutesOff));
        say("&7done measuring &f" + target);
        Brain.save();
    }

    private static final class Adjustable {
        final String label;
        final double floor;
        final boolean integral;
        private final java.util.function.Supplier<atlas.property.Property<?>> target;

        Adjustable(String label, double floor, boolean integral,
                   java.util.function.Supplier<atlas.property.Property<?>> target) {
            this.label = label;
            this.floor = floor;
            this.integral = integral;
            this.target = target;
        }

        double get() {
            atlas.property.Property<?> property = this.target.get();
            Object value = property.overrideOf(OWNER);
            if (value == null) {
                value = property.getBaseValue();
            }
            return ((Number) value).doubleValue();
        }

        void set(double value) {
            double bounded = Math.max(this.floor, value);
            this.target.get().override(atlas.property.Property.Source.LEARNED, OWNER, this.integral
                    ? (Object) Integer.valueOf((int) Math.round(bounded))
                    : (Object) Float.valueOf((float) bounded));
        }
    }

    private Adjustable knobFor(Module module) {
        if (module instanceof Reach) {
            final Reach reach = (Reach) module;
            return new Adjustable("Reach.range", 3.0, false, () -> reach.range);
        }
        if (module instanceof BackTrack) {
            final BackTrack backtrack = (BackTrack) module;
            return new Adjustable("BackTrack.delay", 50.0, true,
                    () -> backtrack.adaptive.getValue() ? backtrack.adaptiveDelay : backtrack.normalDelay);
        }
        if (module instanceof KnockbackDelay) {
            final KnockbackDelay delay = (KnockbackDelay) module;
            return new Adjustable("KnockbackDelay.chance", 0.0, true, () -> delay.chance);
        }
        return null;
    }

    private void say(String message) {
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bAdaptive&7] " + message);
        }
    }

    private void log(String line) {
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            if (this.logTarget == null) {
                this.logTarget = new File(LOG_DIR, "adaptive.txt");
            }
            if (!atlas.module.modules.Logs.allows(this.logTarget)) {
                return;
            }
            writer = new PrintWriter(new FileWriter(this.logTarget, true));
            writer.println(LINE_STAMP.format(new Date()) + "  [" + Brain.server() + "]  " + line);
            writer.flush();
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    @Override
    public String[] getSuffix() {
        atlas.management.ServerSession.Session session = atlas.management.ServerSession.current();
        if (session != null && System.currentTimeMillis() - session.startedAt()
                < this.warmupMinutes.getValue() * 60_000L) {
            return new String[]{"warming up"};
        }
        if (this.probing != null) {
            long left = Math.max(0L, this.probeUntil - System.currentTimeMillis()) / 60000L;
            return new String[]{"probing " + this.probing + " " + left + "m"};
        }
        return new String[]{Brain.kindCount() + " kinds, " + this.actions + " acts"};
    }
}
