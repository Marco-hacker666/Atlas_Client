package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.TickEvent;
import atlas.humanize.HumanProfile;
import atlas.humanize.Humanizer;
import atlas.module.Module;
import atlas.property.FloatRange;
import atlas.property.IntRange;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;

/**
 * One place for how human every combat module looks (2026-10-25, owner: "a
 * person lands about 40 %, misses aims -- one global humanization, every
 * detail adjustable, for anything that needs it later").
 *
 * The module only holds settings: each tick it publishes a HumanProfile and
 * which modules it applies to (atlas.humanize.Humanizer.publish). Every module
 * that uses it keeps its own Humanizer (accuracy, reaction, aim error, stats)
 * reading that profile, so the same rules can drive Silent Aura, KillAura2,
 * AimAssist and AutoClicker. Off, nothing is published and they behave as
 * before.
 *
 * A preset fills every value when it is picked; anything changed afterwards
 * stays until another preset is picked. A loaded profile keeps its own values.
 */
public class Humanization extends Module {
    public final ModeProperty preset = new ModeProperty("preset", 0, new String[]{"Legit", "Good", "Tryhard", "Custom"});
    // ---- who it applies to
    public final BooleanProperty applySilentAura = new BooleanProperty("apply-silent-aura", true);
    public final BooleanProperty applyKillAura2 = new BooleanProperty("apply-killaura2", true);
    public final BooleanProperty applyAimAssist = new BooleanProperty("apply-aimassist", false);
    public final BooleanProperty applyAutoClicker = new BooleanProperty("apply-autoclicker", false);
    // ---- accuracy
    public final PercentProperty hitRate = new PercentProperty("hit-rate", 40);
    public final FloatProperty hitRateSpread = new FloatProperty("hit-rate-spread", 8.0F, 0.0F, 30.0F);
    public final FloatProperty driftSeconds = new FloatProperty("drift-seconds", 20.0F, 2.0F, 120.0F);
    public final IntProperty window = new IntProperty("window", 30, 5, 200);
    public final FloatProperty correction = new FloatProperty("correction", 0.6F, 0.0F, 2.0F);
    public final BooleanProperty streaks = new BooleanProperty("streaks", true);
    public final FloatProperty hotChance = new FloatProperty("hot-chance", 3.0F, 0.0F, 30.0F, this.streaks::getValue);
    public final FloatProperty hotBonus = new FloatProperty("hot-bonus", 15.0F, 0.0F, 50.0F, this.streaks::getValue);
    public final IntRange hotLength = new IntRange("hot-length", 3, 8, 1, 30, this.streaks::getValue);
    public final FloatProperty coldChance = new FloatProperty("cold-chance", 3.0F, 0.0F, 30.0F, this.streaks::getValue);
    public final FloatProperty coldPenalty = new FloatProperty("cold-penalty", 20.0F, 0.0F, 50.0F, this.streaks::getValue);
    public final IntRange coldLength = new IntRange("cold-length", 3, 10, 1, 30, this.streaks::getValue);
    // ---- situations
    public final FloatProperty distanceStart = new FloatProperty("distance-start", 2.5F, 0.0F, 6.0F);
    public final FloatProperty distancePenalty = new FloatProperty("distance-penalty", 8.0F, 0.0F, 40.0F);
    public final FloatProperty strafePenalty = new FloatProperty("strafe-penalty", 4.0F, 0.0F, 20.0F);
    public final FloatProperty targetAirPenalty = new FloatProperty("target-air-penalty", 10.0F, 0.0F, 50.0F);
    public final FloatProperty selfAirPenalty = new FloatProperty("self-air-penalty", 10.0F, 0.0F, 50.0F);
    public final FloatProperty hurtPenalty = new FloatProperty("hurt-penalty", 20.0F, 0.0F, 60.0F);
    public final IntProperty hurtTicks = new IntProperty("hurt-ticks", 10, 0, 20);
    public final FloatProperty firstHitBonus = new FloatProperty("first-hit-bonus", 0.0F, -50.0F, 50.0F);
    public final FloatProperty lowHealthBonus = new FloatProperty("low-health-bonus", 10.0F, 0.0F, 50.0F);
    public final FloatProperty lowHealthThreshold = new FloatProperty("low-health-threshold", 6.0F, 0.0F, 20.0F);
    public final FloatProperty minHitsPerSecond = new FloatProperty("min-hits-per-second", 0.0F, 0.0F, 10.0F);
    public final FloatProperty maxHitsPerSecond = new FloatProperty("max-hits-per-second", 3.0F, 0.0F, 20.0F);
    // ---- misses
    public final IntProperty missAimOff = new IntProperty("miss-aim-off", 70, 0, 100);
    public final IntProperty missSwingOnly = new IntProperty("miss-swing-only", 20, 0, 100);
    public final IntProperty missSkip = new IntProperty("miss-skip", 10, 0, 100);
    public final FloatRange aimOff = new FloatRange("aim-off", 0.10F, 0.45F, 0.02F, 1.5F);
    public final PercentProperty aimOffHorizontal = new PercentProperty("aim-off-horizontal", 70);
    public final BooleanProperty missCooldown = new BooleanProperty("miss-cooldown", false);
    // ---- reaction
    public final IntRange reactionNew = new IntRange("reaction-new", 180, 320, 0, 1000);
    public final IntRange reactionReacquire = new IntRange("reaction-reacquire", 120, 220, 0, 1000);
    public final IntRange reactionSwitch = new IntRange("reaction-switch", 150, 280, 0, 1000);
    public final ModeProperty reactionShape = new ModeProperty("reaction-shape", 0, new String[]{"Triangular", "LogNormal", "Uniform"});
    public final IntProperty reacquireWindow = new IntProperty("reacquire-window", 3000, 0, 10000);
    // ---- aim error
    public final FloatProperty trackingNoise = new FloatProperty("tracking-noise", 0.6F, 0.0F, 5.0F);
    public final FloatProperty noiseSpeed = new FloatProperty("noise-speed", 1.5F, 0.1F, 10.0F);
    public final FloatProperty noiseMotionScale = new FloatProperty("noise-motion-scale", 1.0F, 0.0F, 5.0F);
    public final FloatProperty overshootChance = new FloatProperty("overshoot-chance", 25.0F, 0.0F, 100.0F);
    public final FloatProperty overshootMinTurn = new FloatProperty("overshoot-min-turn", 20.0F, 1.0F, 180.0F);
    public final FloatRange overshoot = new FloatRange("overshoot", 5.0F, 15.0F, 0.0F, 60.0F);
    public final FloatProperty undershootChance = new FloatProperty("undershoot-chance", 15.0F, 0.0F, 100.0F);
    public final FloatRange undershoot = new FloatRange("undershoot", 5.0F, 20.0F, 0.0F, 60.0F);
    public final FloatProperty lapseChance = new FloatProperty("lapse-chance", 4.0F, 0.0F, 50.0F);
    public final IntRange lapseLength = new IntRange("lapse-length", 3, 8, 1, 40);
    public final FloatProperty speedSigma = new FloatProperty("speed-sigma", 0.15F, 0.0F, 1.0F);
    public final FloatRange speed = new FloatRange("speed", 0.6F, 1.6F, 0.1F, 3.0F);
    // ---- clicks
    public final BooleanProperty humanClicks = new BooleanProperty("human-clicks", true);
    public final FloatProperty clickDrop = new FloatProperty("click-drop", 2.0F, 0.0F, 30.0F);
    public final FloatProperty clickDouble = new FloatProperty("click-double", 3.0F, 0.0F, 30.0F);
    // ---- observing
    public final BooleanProperty showStats = new BooleanProperty("show-stats", false);
    public final BooleanProperty log = new BooleanProperty("log", false);

    private int lastPreset = -1;

    public Humanization() {
        super("Humanization", false, false, "Makes combat modules land, miss, react and aim like a person.");
    }

    @Override
    public void onEnabled() {
        publishNow();
    }

    @Override
    public void onDisabled() {
        Humanizer.publish(null, null);
    }

    @EventTarget(Priority.HIGHEST)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || !this.isEnabled()) {
            return;
        }
        int now = this.preset.getValue();
        if (this.lastPreset < 0) {
            /* First tick after a load: the values are the profile's own, not the preset's. */
            this.lastPreset = now;
        } else if (now != this.lastPreset) {
            this.lastPreset = now;
            if (now != HumanProfile.PRESET_CUSTOM) {
                applyPreset(HumanProfile.preset(now));
            }
        }
        publishNow();
    }

    /** Writes a preset's values into the settings. */
    private void applyPreset(HumanProfile p) {
        this.hitRate.setValue((int) p.hitRate);
        this.hitRateSpread.setValue(p.hitRateSpread);
        this.driftSeconds.setValue(p.driftSeconds);
        this.window.setValue(p.window);
        this.correction.setValue(p.correction);
        this.streaks.setValue(p.streaks);
        this.hotChance.setValue(p.hotChance);
        this.hotBonus.setValue(p.hotBonus);
        this.coldChance.setValue(p.coldChance);
        this.coldPenalty.setValue(p.coldPenalty);
        this.distanceStart.setValue(p.distanceStart);
        this.distancePenalty.setValue(p.distancePenalty);
        this.strafePenalty.setValue(p.strafePenalty);
        this.targetAirPenalty.setValue(p.targetAirPenalty);
        this.selfAirPenalty.setValue(p.selfAirPenalty);
        this.hurtPenalty.setValue(p.hurtPenalty);
        this.hurtTicks.setValue(p.hurtTicks);
        this.firstHitBonus.setValue(p.firstHitBonus);
        this.lowHealthBonus.setValue(p.lowHealthBonus);
        this.lowHealthThreshold.setValue(p.lowHealthThreshold);
        this.minHitsPerSecond.setValue(p.minHitsPerSecond);
        this.maxHitsPerSecond.setValue(p.maxHitsPerSecond);
        this.missAimOff.setValue(p.missAimOff);
        this.missSwingOnly.setValue(p.missSwingOnly);
        this.missSkip.setValue(p.missSkip);
        this.aimOffHorizontal.setValue((int) p.aimOffHorizontal);
        this.missCooldown.setValue(p.missCooldown);
        this.reactionShape.setValue(p.reactionShape);
        this.reacquireWindow.setValue(p.reacquireWindow);
        this.trackingNoise.setValue(p.trackingNoise);
        this.noiseSpeed.setValue(p.noiseSpeed);
        this.noiseMotionScale.setValue(p.noiseMotionScale);
        this.overshootChance.setValue(p.overshootChance);
        this.overshootMinTurn.setValue(p.overshootMinTurn);
        this.undershootChance.setValue(p.undershootChance);
        this.lapseChance.setValue(p.lapseChance);
        this.speedSigma.setValue(p.speedSigma);
        this.humanClicks.setValue(p.humanClicks);
        this.clickDrop.setValue(p.clickDrop);
        this.clickDouble.setValue(p.clickDouble);
        setRange(this.hotLength, p.hotMin, p.hotMax);
        setRange(this.coldLength, p.coldMin, p.coldMax);
        setRange(this.reactionNew, p.reactionNewMin, p.reactionNewMax);
        setRange(this.reactionReacquire, p.reactionReacquireMin, p.reactionReacquireMax);
        setRange(this.reactionSwitch, p.reactionSwitchMin, p.reactionSwitchMax);
        setRange(this.lapseLength, p.lapseMin, p.lapseMax);
        setRange(this.aimOff, p.aimOffMin, p.aimOffMax);
        setRange(this.overshoot, p.overshootMin, p.overshootMax);
        setRange(this.undershoot, p.undershootMin, p.undershootMax);
        setRange(this.speed, p.speedMin, p.speedMax);
    }

    private static void setRange(IntRange range, int min, int max) {
        java.util.List<atlas.property.Property<?>> members = range.properties();
        members.get(0).setValue(min);
        members.get(1).setValue(max);
    }

    private static void setRange(FloatRange range, float min, float max) {
        java.util.List<atlas.property.Property<?>> members = range.properties();
        members.get(0).setValue(min);
        members.get(1).setValue(max);
    }

    /** The settings as a profile, published with who it applies to. */
    public void publishNow() {
        HumanProfile p = new HumanProfile();
        p.hitRate = this.hitRate.getValue();
        p.hitRateSpread = this.hitRateSpread.getValue();
        p.driftSeconds = this.driftSeconds.getValue();
        p.window = this.window.getValue();
        p.correction = this.correction.getValue();
        p.streaks = this.streaks.getValue();
        p.hotChance = this.hotChance.getValue();
        p.hotBonus = this.hotBonus.getValue();
        p.hotMin = this.hotLength.low();
        p.hotMax = this.hotLength.high();
        p.coldChance = this.coldChance.getValue();
        p.coldPenalty = this.coldPenalty.getValue();
        p.coldMin = this.coldLength.low();
        p.coldMax = this.coldLength.high();
        p.distanceStart = this.distanceStart.getValue();
        p.distancePenalty = this.distancePenalty.getValue();
        p.strafePenalty = this.strafePenalty.getValue();
        p.targetAirPenalty = this.targetAirPenalty.getValue();
        p.selfAirPenalty = this.selfAirPenalty.getValue();
        p.hurtPenalty = this.hurtPenalty.getValue();
        p.hurtTicks = this.hurtTicks.getValue();
        p.firstHitBonus = this.firstHitBonus.getValue();
        p.lowHealthBonus = this.lowHealthBonus.getValue();
        p.lowHealthThreshold = this.lowHealthThreshold.getValue();
        p.minHitsPerSecond = this.minHitsPerSecond.getValue();
        p.maxHitsPerSecond = this.maxHitsPerSecond.getValue();
        p.missAimOff = this.missAimOff.getValue();
        p.missSwingOnly = this.missSwingOnly.getValue();
        p.missSkip = this.missSkip.getValue();
        p.aimOffMin = this.aimOff.low();
        p.aimOffMax = this.aimOff.high();
        p.aimOffHorizontal = this.aimOffHorizontal.getValue();
        p.missCooldown = this.missCooldown.getValue();
        p.reactionNewMin = this.reactionNew.low();
        p.reactionNewMax = this.reactionNew.high();
        p.reactionReacquireMin = this.reactionReacquire.low();
        p.reactionReacquireMax = this.reactionReacquire.high();
        p.reactionSwitchMin = this.reactionSwitch.low();
        p.reactionSwitchMax = this.reactionSwitch.high();
        p.reactionShape = this.reactionShape.getValue();
        p.reacquireWindow = this.reacquireWindow.getValue();
        p.trackingNoise = this.trackingNoise.getValue();
        p.noiseSpeed = this.noiseSpeed.getValue();
        p.noiseMotionScale = this.noiseMotionScale.getValue();
        p.overshootChance = this.overshootChance.getValue();
        p.overshootMinTurn = this.overshootMinTurn.getValue();
        p.overshootMin = this.overshoot.low();
        p.overshootMax = this.overshoot.high();
        p.undershootChance = this.undershootChance.getValue();
        p.undershootMin = this.undershoot.low();
        p.undershootMax = this.undershoot.high();
        p.lapseChance = this.lapseChance.getValue();
        p.lapseMin = this.lapseLength.low();
        p.lapseMax = this.lapseLength.high();
        p.speedSigma = this.speedSigma.getValue();
        p.speedMin = this.speed.low();
        p.speedMax = this.speed.high();
        p.humanClicks = this.humanClicks.getValue();
        p.clickDrop = this.clickDrop.getValue();
        p.clickDouble = this.clickDouble.getValue();
        boolean[] applies = new boolean[Humanizer.CONSUMERS];
        applies[Humanizer.CONSUMER_LIQUIDBOUNCE] = this.applySilentAura.getValue();
        applies[Humanizer.CONSUMER_ATLAS_AURA] = this.applySilentAura.getValue();
        applies[Humanizer.CONSUMER_KILLAURA2] = this.applyKillAura2.getValue();
        applies[Humanizer.CONSUMER_AIMASSIST] = this.applyAimAssist.getValue();
        applies[Humanizer.CONSUMER_AUTOCLICKER] = this.applyAutoClicker.getValue();
        Humanizer.publish(p, applies);
    }

    /** The humanizers in use now, with the name to show: {name, Humanizer}. */
    private static java.util.List<Object[]> consumers() {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        KillAura aura = atlas.Atlas.moduleManager == null ? null : (KillAura) atlas.Atlas.moduleManager.modules.get(KillAura.class);
        KillAuraLB lb = aura == null ? null : aura.liquidBounce();
        if (lb != null && lb.humanizer().profile() != null) {
            out.add(new Object[]{"Silent Aura", lb.humanizer()});
        }
        return out;
    }

    /** show-stats: one line a humanized module, bottom left. */
    @EventTarget
    public void onRender2D(atlas.events.Render2DEvent event) {
        if (!this.isEnabled() || !this.showStats.getValue() || mc().thePlayer == null || mc().currentScreen != null) {
            return;
        }
        net.minecraft.client.gui.ScaledResolution resolution = new net.minecraft.client.gui.ScaledResolution(mc());
        long now = System.currentTimeMillis();
        int y = resolution.getScaledHeight() - 12;
        for (Object[] consumer : consumers()) {
            String line = consumer[0] + ": " + ((Humanizer) consumer[1]).stats().line(now);
            mc().fontRendererObj.drawStringWithShadow(line, 4.0F, (float) y, 0xFFE0E0E0);
            y -= 11;
        }
    }

    private java.io.File logFile;
    private long lastLogAt;

    /** log: every 10 s, a line a humanized module in config/Atlas/humanize-*.txt. */
    @EventTarget
    public void onLogTick(TickEvent event) {
        if (event.getType() != EventType.POST || !this.isEnabled() || !this.log.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastLogAt < 10000L) {
            return;
        }
        this.lastLogAt = now;
        java.util.List<Object[]> list = consumers();
        if (list.isEmpty()) {
            return;
        }
        if (this.logFile == null) {
            this.logFile = new java.io.File("./config/Atlas/", "humanize-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
        }
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        for (Object[] consumer : list) {
            atlas.util.AsyncLog.append(this.logFile, time + "  " + consumer[0] + " | preset " + this.preset.getModeString()
                    + " aim " + this.hitRate.getValue() + "% | " + ((Humanizer) consumer[1]).stats().line(now));
        }
    }

    private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getMinecraft();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.preset.getModeString()};
    }
}
