package atlas.module.modules;

import atlas.Atlas;
import atlas.enums.BlinkModules;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

public class Hitflick extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int LOOK_PRIORITY = 100;

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"FLICK", "HAZARD", "DISPLACE", "VOID"});

    static final int MODE_VOID = 3;

    static final atlas.humanize.Humanizer HUMAN = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_HITFLICK);

    public final ModeProperty direction = new ModeProperty("Direction", 0, new String[]{"Left", "Right", "Back", "Custom"});
    public final FloatProperty customAngle = new FloatProperty("Custom-Angle", 90F, 1F, 180F, () -> this.direction.getValue() == 3);
    public final IntProperty cooldown = new IntProperty("Cooldown", 1, 1, 40);
    public final BooleanProperty blink = new BooleanProperty("Blink", false);

    public final BooleanProperty keepHitbox = new BooleanProperty("Keep-hitbox", false);

    public final ModeProperty moveFix = new ModeProperty("move-fix", 0, new String[]{"NONE", "SILENT", "REAL"});

    private long sinceLastFlick;
    private float originalYaw;
    private float flickYaw;
    private FlickState state = FlickState.IDLE;

    private Entity heldTarget;
    private boolean sendingHit;

    private enum FlickState {
        IDLE, FLICKING_AWAY, HIT_DUE, RESTORING
    }

    public Hitflick() {
        super("Hitflick", false, true, "Flick away on hit then restore");
    }

    @Override
    public void onEnabled() {
        sinceLastFlick = 0;
        state = FlickState.IDLE;
        heldTarget = null;
    }

    @Override
    public void onDisabled() {
        state = FlickState.IDLE;
        heldTarget = null;
        sinceLastFlick = 0;
        if (blink.getValue()) {
            Atlas.blinkManager.setBlinkState(false, BlinkModules.HITFLICK);
        }
    }

    static boolean standsAside() {
        if (atlas.management.Arbiter.catching()) {
            return true;
        }
        Module scaffold = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(Scaffold.class);
        return scaffold != null && scaffold.isEnabled();
    }

    public boolean holdHit(Entity target) {
        if (sendingHit || !this.isRunning() || mc.thePlayer == null) return false;
        if (state == FlickState.FLICKING_AWAY || state == FlickState.HIT_DUE) {
            return target == heldTarget;
        }
        if (standsAside()) return false;
        if (target == null || target == mc.thePlayer) return false;
        if (!(target instanceof EntityLivingBase)) return false;

        boolean restoring = state == FlickState.RESTORING;
        if (!restoring && sinceLastFlick + 1 < cooldown.getValue()) return false;

        heldTarget = target;
        if (!restoring) {
            originalYaw = mc.thePlayer.rotationYaw;
        }
        flickYaw = originalYaw + getFlickAngle();
        state = FlickState.FLICKING_AWAY;

        if (blink.getValue()) {
            Atlas.blinkManager.setBlinkState(false, Atlas.blinkManager.getBlinkingModule());
            Atlas.blinkManager.setBlinkState(true, BlinkModules.HITFLICK);
        }
        return true;
    }

    public static boolean holdsHit(Entity target) {
        if (Atlas.moduleManager == null) {
            return false;
        }
        Hitflick flick = (Hitflick) Atlas.moduleManager.modules.get(Hitflick.class);
        if (flick != null && flick.holdHit(target)) {
            return true;
        }
        Displace displace = atlas.module.ModuleBundle.of(Displace.class);
        return displace != null && displace.holdHit(target);
    }

    @EventTarget
    public void onGameTick(TickEvent event) {
        if (event.getType() != EventType.PRE || state != FlickState.HIT_DUE) return;
        Entity target = heldTarget;
        heldTarget = null;
        state = FlickState.RESTORING;
        if (target == null || target.isDead || mc.thePlayer == null || mc.thePlayer.getDistanceToEntity(target) > 6.0F) {
            return;
        }
        sendingHit = true;
        try {
            mc.playerController.attackEntity(mc.thePlayer, target);
        } finally {
            sendingHit = false;
        }
    }

    @EventTarget(atlas.event.types.Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled()) return;
        if (event.getType() != EventType.PRE) return;
        if (mc.thePlayer == null) return;

        if (state == FlickState.IDLE) {
            sinceLastFlick++;
            return;
        }

        boolean aside = standsAside();
        if (state == FlickState.FLICKING_AWAY) {
            if (!aside) {
                float[] wanted = humanTurn(flickYaw, atlas.management.RotationHelper.serverPitch());
                rotate(keepHitbox.getValue() && heldTarget != null
                        ? Displace.clampToHitbox(heldTarget, wanted[0], wanted[1]) : wanted[0], wanted[1]);
            }
            state = FlickState.HIT_DUE;
        } else if (state == FlickState.RESTORING) {
            if (!aside) {
                rotate(originalYaw, atlas.management.RotationHelper.serverPitch());
            }
            state = FlickState.IDLE;
            sinceLastFlick = 0;
            if (blink.getValue()) {
                Atlas.blinkManager.setBlinkState(false, BlinkModules.HITFLICK);
            }
        }
    }

    private void rotate(float yaw, float pitch) {
        int move = moveFix.getValue() == 0 ? atlas.management.RotationHelper.MOVE_CAMERA
                : moveFix.getValue() == 1 ? atlas.management.RotationHelper.MOVE_FIX
                : atlas.management.RotationHelper.MOVE_LOOK;

        float[] look = atlas.management.RotationHelper.request(this, yaw, pitch, LOOK_PRIORITY, move,
                false, atlas.management.RotationHelper.NO_LIMIT, atlas.management.RotationHelper.NO_LIMIT);
        if (look == null) {
            return;
        }
        if (moveFix.getModeString().equals("REAL")) {
            Atlas.rotationManager.setRotation(look[0], look[1], LOOK_PRIORITY, false);
        }
    }

    static float[] humanTurn(float toYaw, float toPitch) {
        HUMAN.reset();
        return atlas.humanize.HumanTurn.apply(HUMAN, atlas.management.RotationHelper.serverYaw(),
                atlas.management.RotationHelper.serverPitch(), toYaw, toPitch);
    }

    private float getFlickAngle() {
        switch (direction.getValue()) {
            case 0: return -90F;
            case 1: return 90F;
            case 2: return 180F;
            case 3: return customAngle.getValue();
            default: return 90F;
        }
    }

    public final atlas.module.ModuleBundle hazardMerged = atlas.module.ModuleBundle.forMode(this,
                    new KBDisplacement(), "hazard", "Hazard", () -> this.mode.getValue() == 1)
            .deselectWith(() -> this.mode.setValue(0)).selectWith(() -> this.mode.setValue(1));
    public final atlas.module.ModuleBundle displaceMerged = atlas.module.ModuleBundle.forMode(this,
                    new Displace(), "displace", "Displace", () -> this.mode.getValue() >= 2)
            .deselectWith(() -> this.mode.setValue(0)).selectWith(() -> {
                if (this.mode.getValue() < 2) {
                    this.mode.setValue(2);
                }
            });

    {
        this.pauseWhen(() -> this.mode.getValue() != 0);
        java.util.function.BooleanSupplier flick = () -> this.mode.getValue() == 0;
        this.direction.alsoWhen(flick);
        this.customAngle.alsoWhen(flick);
        this.cooldown.alsoWhen(flick);
        this.blink.alsoWhen(flick);
        this.keepHitbox.alsoWhen(flick);
        this.moveFix.alsoWhen(flick);
    }
}
