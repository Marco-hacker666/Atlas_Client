package atlas.module.modules;

import atlas.Atlas;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;

public class Rotations extends Module {
    public final IntProperty speedNoise = new IntProperty("speed-noise", 12, 0, 40);
    public final IntProperty curve = new IntProperty("curve", 8, 0, 30);
    public final IntProperty curveSmooth = new IntProperty("curve-smooth", 70, 0, 95);
    public final BooleanProperty ease = new BooleanProperty("ease", true);
    public final FloatProperty easeZone = new FloatProperty("ease-zone", 25.0F, 5.0F, 90.0F);
    public final FloatProperty easeFloor = new FloatProperty("ease-floor", 5.0F, 1.0F, 30.0F);

    public Rotations() {
        super("Rotations", true, false,
                "Shared rotation engine settings: speed noise, curved turns and easing for the modules that use it");
    }

    public static Rotations active() {
        try {
            Rotations rotations = (Rotations) Atlas.moduleManager.modules.get(Rotations.class);
            return rotations != null && rotations.isEnabled() ? rotations : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
