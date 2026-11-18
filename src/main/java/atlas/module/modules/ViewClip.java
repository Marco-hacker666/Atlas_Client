package atlas.module.modules;

import java.lang.reflect.Field;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;

// Adapted from raven donors ExtendCamera (distance)

public class ViewClip extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"CAMERA", "VANILLA", "FALL"});
    public final FloatProperty distance = new FloatProperty("distance", 4.0F, 1.0F, 12.0F);
    public final BooleanProperty noClip = new BooleanProperty("no-clip", true);
    public final FloatProperty fallTilt = new FloatProperty("fall-tilt", 8.0F, 0.0F, 30.0F, () -> mode.getValue() == 2);
    public final FloatProperty minFall = new FloatProperty("min-fall", 3.0F, 1.0F, 10.0F, () -> mode.getValue() == 2);

    private static Field distanceField;
    private static Field distanceTempField;

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
