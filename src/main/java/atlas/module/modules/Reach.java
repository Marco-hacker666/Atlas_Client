package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PickEvent;
import atlas.events.RaytraceEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.PercentProperty;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Random;

public class Reach extends Module {
    private static final DecimalFormat df = new DecimalFormat("0.0#", new DecimalFormatSymbols(Locale.US));
    private final Random theRandom = new Random();
    private boolean expanding = true;
    public final FloatProperty range = new FloatProperty("range", 3.1F, 3.0F, 6.0F);
    public final PercentProperty chance = new PercentProperty("chance", 100);

    public Reach() {
        super("Reach", false, false, "Reach higher using nano technologo make ur dick bigger to hit further");
    }

    @EventTarget
    public void onPick(PickEvent event) {
        if (this.isEnabled() && this.expanding) {
            event.setRange(this.range.getValue().doubleValue());
        }
    }

    @EventTarget
    public void onRaytrace(RaytraceEvent event) {
        if (this.isEnabled() && this.expanding) {
            event.setRange(Math.max(event.getRange(), this.range.getValue().doubleValue() + 0.5));
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            this.expanding = this.theRandom.nextDouble() <= (double) this.chance.getValue() / 100.0;
        }
    }

    /**
     * The entity reach in effect right now: this module's range when it is on
     * and this tick's chance roll came up, vanilla's 3.0 otherwise. HitBox
     * computes its own mouse-over and needs the same number, or the two
     * modules overrule each other.
     */
    public static double effectiveRange() {
        Module module = atlas.Atlas.moduleManager.modules.get(Reach.class);
        if (module instanceof Reach && module.isEnabled() && ((Reach) module).expanding) {
            return ((Reach) module).range.getValue().doubleValue();
        }
        return 3.0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{df.format(this.range.getValue())};
    }

    /* Merged modules as modes (2026-10-11, ModuleBundle): the module's own
       work pauses while another type is picked. */
    public final atlas.property.properties.ModeProperty mergedType = new atlas.property.properties.ModeProperty("type", 0, new String[]{"Normal", "Legit"});
    public final atlas.module.ModuleBundle legitMerged = atlas.module.ModuleBundle.forMode(this, new atlas.module.modules.LegitReach(), "legit", "Legit", () -> this.mergedType.getValue() == 1).deselectWith(() -> this.mergedType.setValue(0));

    {
        this.pauseWhen(() -> this.mergedType.getValue() != 0);
    }
}
