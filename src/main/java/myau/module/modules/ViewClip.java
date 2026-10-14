package myau.module.modules;

import java.lang.reflect.Field;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.TickEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;

// Camera visuals. Adapted from raven donors ExtendCamera (distance),
// NoCameraClip (clip bypass), FallView (fall gating) and Holdlook
// (third-person save/restore). Rewritten for the client APIs.
// The camera math lives here; MixinEntityRenderer and MixinVisGraph read
// isNoClipActive() to decide whether the camera may pass through blocks.
public class ViewClip extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // No modes existed before. FALL is appended at END. Never reorder.
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"CAMERA", "VANILLA", "FALL"});
    public final FloatProperty distance = new FloatProperty("distance", 4.0F, 1.0F, 12.0F);
    public final BooleanProperty noClip = new BooleanProperty("no-clip", true);
    public final FloatProperty fallTilt = new FloatProperty("fall-tilt", 8.0F, 0.0F, 30.0F, () -> mode.getValue() == 2);
    public final FloatProperty minFall = new FloatProperty("min-fall", 3.0F, 1.0F, 10.0F, () -> mode.getValue() == 2);

    private static Field distanceField;
    private static Field distanceTempField;
    /** The renderer class the two fields above were resolved for. */
    private static Class<?> resolvedRenderer;

    private float savedFov = -1.0F;
    private int savedThirdPerson = -1;
    private float savedDistance = 4.0F;
    private boolean haveSaved = false;
    private float lastAppliedDistance = Float.NaN;
    private float appliedFovKick = 0.0F;

    public ViewClip() {
        super("ViewClip", false, false, "Extends third person camera distance and clip.");
    }

    @Override
    public void onEnabled() {
        this.saveVanillaState();
        if (mc.theWorld != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    @Override
    public void onDisabled() {
        this.restoreVanillaState();
        if (mc.theWorld != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.gameSettings == null || mc.entityRenderer == null) {
            return;
        }
        int m = this.mode.getValue();
        if (m == 1) {
            this.restoreAppliedEffects();
            return;
        }
        float base = this.distance.getValue();
        if (m == 0) {
            this.restoreFovIfKicked();
            this.applyDistance(base);
            return;
        }
        if (FallView.shouldApplyFallView(mc.thePlayer, this.minFall.getValue())) {
            float factor = FallView.fallFactor(mc.thePlayer.fallDistance, this.minFall.getValue());
            this.applyFovKick(FallView.fovKick(this.fallTilt.getValue(), factor));
            this.applyDistance(base + FallView.dollyBoost(factor));
        } else {
            this.restoreFovIfKicked();
            this.applyDistance(base);
        }
    }

    /**
     * Whether the camera may pass through blocks right now: the module is on,
     * "no-clip" is on, and the mode is not VANILLA (which means "leave the
     * camera exactly as vanilla has it"). MixinEntityRenderer (setupFog) and
     * MixinVisGraph ask this rather than isEnabled(), so the setting and the
     * VANILLA mode both do what they say.
     */
    public boolean isNoClipActive() {
        return this.isEnabled() && this.noClip.getValue() && this.mode.getValue() != 1;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    private void saveVanillaState() {
        try {
            if (mc.gameSettings != null) {
                this.savedFov = mc.gameSettings.fovSetting;
                this.savedThirdPerson = mc.gameSettings.thirdPersonView;
            }
        } catch (Exception ignored) {
        }
        this.savedDistance = this.readDistance();
        this.haveSaved = true;
        this.lastAppliedDistance = Float.NaN;
        this.appliedFovKick = 0.0F;
    }

    private void restoreVanillaState() {
        if (!this.haveSaved) {
            return;
        }
        try {
            if (mc.gameSettings != null) {
                if (this.savedFov > 0.0F) {
                    mc.gameSettings.fovSetting = this.savedFov;
                }
                if (this.savedThirdPerson >= 0) {
                    mc.gameSettings.thirdPersonView = this.savedThirdPerson;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            this.writeDistance(this.savedDistance);
        } catch (Exception ignored) {
        }
        this.haveSaved = false;
        this.lastAppliedDistance = Float.NaN;
        this.appliedFovKick = 0.0F;
    }

    private void restoreAppliedEffects() {
        this.restoreFovIfKicked();
        if (this.haveSaved) {
            this.applyDistance(this.savedDistance);
        } else {
            this.applyDistance(4.0F);
        }
    }

    private void restoreFovIfKicked() {
        if (this.appliedFovKick == 0.0F || !this.haveSaved) {
            return;
        }
        try {
            if (mc.gameSettings != null && this.savedFov > 0.0F) {
                mc.gameSettings.fovSetting = this.savedFov;
            }
        } catch (Exception ignored) {
        }
        this.appliedFovKick = 0.0F;
    }

    private void applyFovKick(float kick) {
        if (!this.haveSaved || mc.gameSettings == null) {
            return;
        }
        try {
            mc.gameSettings.fovSetting = this.savedFov + kick;
            this.appliedFovKick = kick;
        } catch (Exception ignored) {
        }
    }

    private void applyDistance(float value) {
        float clamped = Math.max(1.0F, Math.min(12.0F, value));
        if (!Float.isNaN(this.lastAppliedDistance) && Math.abs(this.lastAppliedDistance - clamped) < 0.001F) {
            return;
        }
        this.writeDistance(clamped);
        this.lastAppliedDistance = clamped;
    }

    private float readDistance() {
        resolveDistanceFields();
        try {
            if (mc.entityRenderer != null && distanceField != null) {
                return distanceField.getFloat(mc.entityRenderer);
            }
        } catch (Exception ignored) {
        }
        return 4.0F;
    }

    private void writeDistance(float value) {
        resolveDistanceFields();
        try {
            if (mc.entityRenderer == null) {
                return;
            }
            if (distanceField != null) {
                distanceField.setFloat(mc.entityRenderer, value);
            }
            if (distanceTempField != null) {
                distanceTempField.setFloat(mc.entityRenderer, value);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Resolves the two distance fields for the renderer actually running, once
     * per renderer class (2026-10-11). Resolving from a static initializer
     * found the fields on vanilla EntityRenderer only, because no renderer
     * exists while the class loads; the renderer that ends up running may be a
     * subclass anyway -- OptiFine and shaders builds replace it -- so the
     * lookup is deferred to the first use and re-run if the class changes.
     */
    private static void resolveDistanceFields() {
        Class<?> live = (mc != null && mc.entityRenderer != null)
                ? mc.entityRenderer.getClass()
                : net.minecraft.client.renderer.EntityRenderer.class;
        if (live == resolvedRenderer) {
            return;
        }
        resolvedRenderer = live;
        distanceField = findField(live, "thirdPersonDistance", "field_78430_p");
        distanceTempField = findField(live, "thirdPersonDistanceTemp", "field_78437_w");
    }

    /**
     * The named field on {@code cls} or the nearest superclass declaring it, by
     * deobfuscated name first and SRG name second. getDeclaredField only sees
     * the class it is called on, so walking up is what finds a field a subclass
     * inherits; a Field found on a superclass still reads and writes subclass
     * instances.
     */
    private static Field findField(Class<?> cls, String deobf, String searge) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            Field f = declaredField(c, deobf);
            if (f != null) {
                return f;
            }
            f = declaredField(c, searge);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception ignored) {
            return null;
        }
    }
}
