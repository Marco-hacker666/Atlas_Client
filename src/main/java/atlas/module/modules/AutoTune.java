package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.Property;
import atlas.util.ChatUtil;
import atlas.util.TuneExperiment;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public class AutoTune extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final String OWNER = "AutoTune";

    public final IntProperty trialMinutes = new IntProperty("trial-minutes", 5, 1, 60);

    public final IntProperty minSwings = new IntProperty("min-swings", 25, 5, 200);

    public final FloatProperty flagWeight = new FloatProperty("flag-weight", 6.0F, 0.0F, 40.0F);

    public final FloatProperty stepFraction = new FloatProperty("step", 0.12F, 0.02F, 0.5F);

    public final FloatProperty margin = new FloatProperty("margin", 2.0F, 0.0F, 20.0F);

    public final IntProperty maxReplays = new IntProperty("max-replays", 3, 0, 10);

    public final BooleanProperty tuneReach = new BooleanProperty("tune-reach", true);
    public final BooleanProperty tuneBacktrack = new BooleanProperty("tune-backtrack", true);
    public final BooleanProperty tuneAura = new BooleanProperty("tune-aura", false);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty logFile = new BooleanProperty("log-file", true);

    private final List<Knob> knobs = new ArrayList<Knob>();
    private int knobIndex = -1;
    private Knob knob;
    private TuneExperiment experiment;
    private int nextId = 1;
    private int committed;
    private int finished;

    private long blockStart;
    private int blockFlags;
    private int blockSwings;
    private int blockHits;
    private File logTarget;

    private static final class Knob {
        final String label;
        final Property<?> property;
        final double min;
        final double max;
        final boolean integral;

        final BooleanSupplier active;
        double step;
        int direction = 1;

        boolean byHand;

        Knob(String label, Property<?> property, double min, double max, boolean integral,
             BooleanSupplier active, double step) {
            this.label = label;
            this.property = property;
            this.min = min;
            this.max = max;
            this.integral = integral;
            this.active = active;
            this.step = step;
        }

        double base() {
            Object value = this.property.getBaseValue();
            return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
        }

        Object typed(double value) {
            double clamped = Math.max(this.min, Math.min(this.max, value));
            return this.integral ? (Object) Integer.valueOf((int) Math.round(clamped))
                    : (Object) Float.valueOf((float) clamped);
        }

        void apply(double value) {
            this.property.override(Property.Source.TRIAL, OWNER, typed(value));
        }

        void release() {
            this.property.release(OWNER);
        }

        boolean inEffect() {
            return OWNER.equals(this.property.getSourceOwner());
        }
    }

    public AutoTune() {
        super("AutoTune", false, false,
                "Tries settings and keeps the ones that measure better");

        atlas.management.Shutdown.register(atlas.management.Shutdown.Stage.RESTORE, "AutoTune.release",
                this::releaseForExit);
    }

    private void releaseForExit() {
        if (!this.isEnabled()) {
            return;
        }
        releaseAll();
    }

    private void releaseAll() {
        for (Knob each : this.knobs) {
            each.release();
        }
    }

    @Override
    public void onEnabled() {
        this.knobs.clear();
        this.knobIndex = -1;
        this.knob = null;
        this.experiment = null;
        this.committed = 0;
        this.finished = 0;
        this.logTarget = null;
        collectKnobs();
        if (this.knobs.isEmpty()) {
            say("&cnothing to tune -- enable a section, or the module that owns it");
            this.setEnabled(false);
            return;
        }
        say("&7tuning &f" + this.knobs.size() + "&7 settings, &f" + this.trialMinutes.getValue()
                + "&7 min per block, four blocks per candidate");
        startNext();
    }

    @Override
    public void onDisabled() {
        if (this.experiment != null && !this.experiment.finished()) {
            log("ABANDON " + this.experiment + " (switched off)");
        }
        releaseAll();
        this.experiment = null;
        this.knob = null;
    }

    private void collectKnobs() {
        double fraction = this.stepFraction.getValue();

        if (this.tuneReach.getValue()) {
            Module module = Atlas.moduleManager.modules.get(Reach.class);
            if (module instanceof Reach && module.isEnabled()) {
                Reach reach = (Reach) module;
                add("Reach.range", reach.range, 3.0, 3.4, false, () -> true, fraction);
            }
        }
        if (this.tuneBacktrack.getValue()) {
            Module module = Atlas.moduleManager.modules.get(BackTrack.class);
            if (module instanceof BackTrack && module.isEnabled()) {
                final BackTrack backtrack = (BackTrack) module;

                add("BackTrack.normal-delay", backtrack.normalDelay, 50, 250, true,
                        () -> !backtrack.adaptive.getValue(), fraction);
                add("BackTrack.adaptive-delay", backtrack.adaptiveDelay, 50, 250, true,
                        () -> backtrack.adaptive.getValue(), fraction);
            }
        }
        if (this.tuneAura.getValue()) {
            Module module = Atlas.moduleManager.modules.get(KillAura.class);
            if (module instanceof KillAura && module.isEnabled()) {
                KillAura aura = (KillAura) module;
                add("KillAura.AttackRange", aura.attackRange, 3.0, 3.4, false, () -> true, fraction);
                add("KillAura.SwitchDelay", aura.switchDelay, 0, 600, true, () -> true, fraction);
            }
        }
    }

    private void add(String label, Property<?> property, double min, double max,
                     boolean integral, BooleanSupplier active, double fraction) {
        if (!(property.getBaseValue() instanceof Number)) {
            return;
        }
        double step = Math.max(integral ? 1.0 : 0.01, (max - min) * fraction);
        this.knobs.add(new Knob(label, property, min, max, integral, active, step));
    }

    private void startNext() {
        this.experiment = null;
        this.knob = null;
        for (int tried = 0; tried < this.knobs.size(); tried++) {
            this.knobIndex = (this.knobIndex + 1) % this.knobs.size();
            Knob next = this.knobs.get(this.knobIndex);
            if (!next.active.getAsBoolean() || next.byHand) {
                continue;
            }
            double from = next.base();
            double candidate = from + next.step * next.direction;
            if (candidate < next.min || candidate > next.max) {
                next.direction = -next.direction;
                candidate = from + next.step * next.direction;
            }
            candidate = ((Number) next.typed(candidate)).doubleValue();
            if (candidate == from) {
                continue;
            }
            this.knob = next;
            this.experiment = new TuneExperiment(this.nextId++, next.label, from, candidate,
                    this.margin.getValue(), this.maxReplays.getValue());
            log(String.format(Locale.ROOT, "START   %s step %.3f margin %.1f", this.experiment, next.step,
                    this.margin.getValue()));
            say(String.format("&7trying &f%s &7%.3f &8->&f %.3f &8(4 blocks)", next.label, from, candidate));
            beginBlock();
            return;
        }
        say("&7no setting in use to tune");
    }

    private void beginBlock() {
        this.knob.apply(this.experiment.valueNow());
        this.blockStart = System.currentTimeMillis();
        FlagDetector detector = detector();
        HitCheck hitCheck = hitCheck();
        this.blockFlags = detector == null ? 0 : detector.violationCount();
        this.blockSwings = hitCheck == null ? 0 : hitCheck.judgedSwings();
        this.blockHits = hitCheck == null ? 0 : hitCheck.landedHits();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null || this.experiment == null) {
            return;
        }
        if (this.knob.base() != this.experiment.current) {
            this.knob.byHand = true;
            say("&7" + this.knob.label + " &fchanged by hand &8-- left alone until AutoTune is toggled");
            this.experiment.expire("changed by the player");
            finish();
            return;
        }
        if (System.currentTimeMillis() - this.blockStart < this.trialMinutes.getValue() * 60000L) {
            return;
        }
        endBlock();
    }

    @EventTarget
    public void onSession(atlas.events.SessionEvent event) {
        if (event.getType() != atlas.events.SessionEvent.Type.START || this.experiment == null) {
            return;
        }
        if (event.serverChanged()) {
            this.experiment.expire("server changed");
            finish();
        } else {
            beginBlock();
        }
    }

    private FlagDetector detector() {
        Module module = Atlas.moduleManager.modules.get(FlagDetector.class);
        return module instanceof FlagDetector && module.isEnabled() ? (FlagDetector) module : null;
    }

    private HitCheck hitCheck() {
        Module module = Atlas.moduleManager.modules.get(HitCheck.class);
        return module instanceof HitCheck && module.isEnabled() ? (HitCheck) module : null;
    }

    private void endBlock() {
        FlagDetector detector = detector();
        HitCheck hitCheck = hitCheck();
        if (detector == null || hitCheck == null) {
            say("&cFlagDetector and HitCheck must both be on -- pausing");
            this.setEnabled(false);
            return;
        }
        String block = this.experiment.state().name();
        double value = this.experiment.valueNow();
        int flags = detector.violationCount() - this.blockFlags;
        int swings = hitCheck.judgedSwings() - this.blockSwings;
        int hits = hitCheck.landedHits() - this.blockHits;
        double minutes = Math.max(0.5, (System.currentTimeMillis() - this.blockStart) / 60000.0);

        String thin = null;
        if (!this.knob.inEffect()) {
            thin = "masked: " + this.knob.property.describe();
        } else if (flags < 0 || swings < 0 || hits < 0) {
            thin = "instruments were reset during the block";
        } else if (swings < this.minSwings.getValue()) {
            thin = swings + " swings, " + this.minSwings.getValue() + " needed";
        }
        if (thin != null) {
            this.experiment.blockThin();
            log(String.format(Locale.ROOT, "REPLAY  #%d %s value=%.3f (%s)", this.experiment.id, block, value, thin));
        } else {
            double hitRate = 100.0 * hits / swings;
            double flagsPerMinute = flags / minutes;
            double score = hitRate - flagsPerMinute * this.flagWeight.getValue();
            this.experiment.blockScored(score, swings);
            log(String.format(Locale.ROOT,
                    "BLOCK   #%d %s value=%.3f swings=%d hits=%.1f%% flags=%d (%.2f/min over %.1f min) score=%.1f",
                    this.experiment.id, block, value, swings, hitRate, flags, flagsPerMinute, minutes, score));
        }
        if (this.experiment.finished()) {
            finish();
        } else {
            beginBlock();
        }
    }

    private void finish() {
        TuneExperiment done = this.experiment;
        Knob tuned = this.knob;
        if (done == null || tuned == null) {
            return;
        }
        tuned.release();
        this.finished++;
        switch (done.state()) {
            case COMMITTED:

                tuned.property.setBase(Property.Source.TUNED, tuned.typed(done.candidate));
                this.committed++;
                log(String.format(Locale.ROOT, "COMMIT  #%d %s %.3f -> %.3f: %s", done.id, done.label,
                        done.current, done.candidate, done.reason()));
                say(String.format("&a+ &f%s &7%.3f &8->&f %.3f &8(%s)", done.label, done.current,
                        done.candidate, done.reason()));
                break;
            case REJECTED:
                if (tuned.direction > 0) {
                    tuned.direction = -1;
                } else {
                    tuned.direction = 1;
                    tuned.step = Math.max(tuned.integral ? 1.0 : 0.005, tuned.step * 0.5);
                }
                log(String.format(Locale.ROOT, "REJECT  #%d %s stays %.3f: %s (next step %.3f)", done.id,
                        done.label, done.current, done.reason(), tuned.step));
                say(String.format("&c- &f%s &7stays &f%.3f &8(%s)", done.label, done.current, done.reason()));
                break;
            default:
                log(String.format(Locale.ROOT, "EXPIRE  #%d %s stays %.3f: %s", done.id, done.label,
                        done.current, done.reason()));
                break;
        }
        startNext();
    }

    private void say(String message) {
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&bAutoTune&7] " + message);
        }
    }

    private void log(String line) {
        if (!this.logFile.getValue()) {
            return;
        }
        PrintWriter writer = null;
        try {
            if (!LOG_DIR.exists() && !LOG_DIR.mkdirs()) {
                return;
            }
            if (this.logTarget == null) {
                this.logTarget = new File(LOG_DIR, "autotune.txt");
            }
            if (!atlas.module.modules.Logs.allows(this.logTarget)) {
                return;
            }
            writer = new PrintWriter(new FileWriter(this.logTarget, true));
            writer.println(LINE_STAMP.format(new Date()) + "  " + line);
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
        if (this.experiment == null) {
            return new String[]{"idle"};
        }
        long left = this.trialMinutes.getValue() * 60000L - (System.currentTimeMillis() - this.blockStart);
        return new String[]{"#" + this.experiment.id + " " + this.experiment.state().name().toLowerCase(Locale.ROOT)
                + " " + Math.max(0, left / 1000) + "s, " + this.committed + "/" + this.finished + " kept"};
    }
}
