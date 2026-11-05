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
    /** Above every other look: a look an aura outranks is not the one the hit is judged from. */
    private static final int LOOK_PRIORITY = 100;

    /* 2026-10-24 (owner): KBDisplacement and Displace are modes of this
       module now (ModuleBundle, as AutoClicker's types). Each keeps its own
       code and settings (keys prefixed hazard- / displace-); FLICK is this
       class's own flick, paused while another mode is picked. Settings and
       on/off saved under the old module names are read once (ModuleBundle
       .readLegacy). */
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"FLICK", "HAZARD", "DISPLACE", "VOID"});
    /** VOID is DISPLACE's code with its own rule for which hit turns (Displace.voidMode). */
    static final int MODE_VOID = 3;
    /** The aim error of the turn for a hit, read from Humanization. */
    static final atlas.humanize.Humanizer HUMAN = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_HITFLICK);

    public final ModeProperty direction = new ModeProperty("Direction", 0, new String[]{"Left", "Right", "Back", "Custom"});
    public final FloatProperty customAngle = new FloatProperty("Custom-Angle", 90F, 1F, 180F, () -> this.direction.getValue() == 3);
    public final IntProperty cooldown = new IntProperty("Cooldown", 1, 1, 40);
    public final BooleanProperty blink = new BooleanProperty("Blink", false);
    /* 2026-11-04: the hit now really is judged from the flicked look, so a look that misses the target's box loses the
       hit on a server that checks it (Grim, Polar). On: the flick goes only as far as the look still meets the box. */
    public final BooleanProperty keepHitbox = new BooleanProperty("Keep-hitbox", false);

    /* NONE by default, unlike the other rotating modules: a flick lasts one
       tick, and move-fix costs something on every flick.

       Turning the keys toward a yaw 90 or 180 degrees away leaves no forward
       in them, so vanilla stops sprinting that tick: STOP_SPRINTING on the
       flick, START on the restore, for every flick -- up to a pair every
       three ticks while clicking at cooldown 1. That is a sprint-toggle
       pattern, and a W-tap nobody asked for (each START re-arms sprint
       knockback on the server).

       Without it the flick tick moves by the camera while reporting the flick
       (ENGINEERING-NOTES 4.12), but only for that tick. For Left, Right and
       Back the step taken is exactly what the strafe or back key gives at the
       reported yaw, so a server re-simulating movement can still match it,
       provided it lets a sprinting player strafe. It cannot match a sprint
       jump on the flick tick (the boost goes along the camera) or a Custom
       angle off the 45-degree grid. If lagbacks line up with flicks, use
       SILENT. */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 0, new String[]{"NONE", "SILENT", "REAL"});

    private long sinceLastFlick;
    private float originalYaw;
    private float flickYaw;
    private FlickState state = FlickState.IDLE;
    /* 2026-11-04 (owner: "fix FLICK too"). The flick came after the C02, and a server takes the knockback's yaw when it
       reads the C02, so it never steered anything. Now the hit waits: held at the click, the look turned in this tick's
       update, the hit sent at the start of the next tick, the first packet after the turned one, then the look back. */
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

    /**
     * 2026-11-05 (owner: mitigation with Scaffold, Silent Aura and Hitflick together). Scaffold and a Clutch catch place
     * by their own look at priority 7 at most; a turn here is 100, so it took the look from under a placement. No turn
     * while they run: the hit goes on the look they have.
     */
    static boolean standsAside() {
        if (atlas.management.Arbiter.catching()) {
            return true;
        }
        Module scaffold = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(Scaffold.class);
        return scaffold != null && scaffold.isEnabled();
    }

    /** Called for every attack before anything is sent. True: this module sends the hit itself, after the flick. */
    public boolean holdHit(Entity target) {
        if (sendingHit || !this.isRunning() || mc.thePlayer == null) return false;
        if (state == FlickState.FLICKING_AWAY || state == FlickState.HIT_DUE) {
            /* A second click on the same target would land first, along the look before the flick. */
            return target == heldTarget;
        }
        if (standsAside()) return false;
        if (target == null || target == mc.thePlayer) return false;
        if (!(target instanceof EntityLivingBase)) return false;
        /* Cooldown 1 is every hit (owner: "can't every hit count?"): the tick of the turn back counts, and a hit on it
           starts the next flick from the view that flick was going back to. */
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

    /** Every attack, whichever mode has it. True: the hit is held, and goes out a tick later behind the turned look. */
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

    /** Before this tick's clicks and its update: the look the server has last is the flicked one. */
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
                /* The pitch the server has: with Silent Aura the camera's can be far off the target. */
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

    /** The look for a hit, through the shared engine (2026-11-05): whole mouse counts, its wander with Rotations on, and a turn back handed over gradually. */
    private void rotate(float yaw, float pitch) {
        int move = moveFix.getValue() == 0 ? atlas.management.RotationHelper.MOVE_CAMERA
                : moveFix.getValue() == 1 ? atlas.management.RotationHelper.MOVE_FIX
                : atlas.management.RotationHelper.MOVE_LOOK;
        /* NO_LIMIT as the first step too: this hit goes next tick, so the turn has one tick to land. */
        float[] look = atlas.management.RotationHelper.request(this, yaw, pitch, LOOK_PRIORITY, move,
                false, atlas.management.RotationHelper.NO_LIMIT, atlas.management.RotationHelper.NO_LIMIT);
        if (look == null) {
            return;
        }
        if (moveFix.getModeString().equals("REAL")) {
            /* REAL: the camera turns as well, spread over this tick's frames by
               RotationManager; the mouse still works (not forced). */
            Atlas.rotationManager.setRotation(look[0], look[1], LOOK_PRIORITY, false);
        }
    }

    /** A turn for a hit, with Humanization's aim error on it. Each turn is a fresh roll of overshoot and undershoot. */
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
