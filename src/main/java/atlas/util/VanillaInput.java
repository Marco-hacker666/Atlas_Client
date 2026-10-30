package atlas.util;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovingObjectPosition;

/**
 * Clicks that go through vanilla's own input (2026-10-26, V1 of "every action
 * through the vanilla input path"; the idea studied from a paid client's
 * silent aura, written here in our own words).
 *
 * Vanilla clicks in runTick, before the player moves, with the crosshair as it
 * stands, and that tick's movement packet then carries the same look. A
 * module that decided this tick's look at the start of the tick (TickEvent
 * PRE) presses the attack key here, with the crosshair it computed along that
 * look; when vanilla's clickMouse runs for that press, the crosshair is
 * swapped for it and put back after (MixinMinecraft). Swing, what is hit, the
 * miss delay, the sprint reset and the packet order are then vanilla's own.
 *
 * A press vanilla drops (an item in use: 1.8 takes no attack then) is
 * forgotten at the end of the tick. Client thread only.
 */
public final class VanillaInput {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ManagedClicks<MovingObjectPosition> ATTACKS = new ManagedClicks<MovingObjectPosition>();
    /* 2026-10-27 (owner: "Silent Aura keeps lagging me back, I can't move"; 18:33:25-32 setbacks, each "dug 0t ago",
       KillAuraLB dig x6 swing x12): while the aura fights on the Input path, clicks that are not its own (AutoClicker,
       the player's finger) and holding the button to mine used the CAMERA's crosshair -- with a silent look the
       camera points elsewhere, often at the wool underfoot -- and dug blocks mid-fight. Now, for the tick, every
       left click points along the aura's look and the camera's block-breaking is held off. */
    private static boolean fighting;
    private static MovingObjectPosition fightOver;
    /* 2026-10-27 (V3, Silent Aura's Rhythm block raises the sword with vanilla's use key): a right click with a sword
       while an aura fights goes along its look too. On the camera's crosshair it would send a placement on whatever
       block the camera points at (a door, a chest, the wool underfoot), judged by the server along a look it never
       got; along the aura's look it is vanilla's right click on the target (or on nothing). */
    private static final ManagedClicks<MovingObjectPosition> USES = new ManagedClicks<MovingObjectPosition>();

    private VanillaInput() {
    }

    /** Vanilla's crosshair (getMouseOver, with Reach/HitBox) along a look; the camera's crosshair is left as it was. */
    public static MovingObjectPosition pick(float lookYaw, float lookPitch) {
        net.minecraft.client.entity.EntityPlayerSP p = mc.thePlayer;
        MovingObjectPosition saved = mc.objectMouseOver;
        net.minecraft.entity.Entity savedPointed = mc.pointedEntity;
        float yaw = p.rotationYaw;
        float pitch = p.rotationPitch;
        float head = p.rotationYawHead;
        float prevYaw = p.prevRotationYaw;
        float prevPitch = p.prevRotationPitch;
        float prevHead = p.prevRotationYawHead;
        p.rotationYaw = p.prevRotationYaw = lookYaw;
        p.rotationYawHead = p.prevRotationYawHead = lookYaw;
        p.rotationPitch = p.prevRotationPitch = lookPitch;
        try {
            mc.entityRenderer.getMouseOver(1.0F);
            return mc.objectMouseOver;
        } finally {
            p.rotationYaw = yaw;
            p.rotationPitch = pitch;
            p.rotationYawHead = head;
            p.prevRotationYaw = prevYaw;
            p.prevRotationPitch = prevPitch;
            p.prevRotationYawHead = prevHead;
            mc.objectMouseOver = saved;
            mc.pointedEntity = savedPointed;
        }
    }

    /** Presses the attack key once this tick; the click is to point at {@code over}. */
    public static void pressAttack(MovingObjectPosition over) {
        ATTACKS.queue(over);
        KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());
    }

    /** A click of ours waits for vanilla this tick (Hit-Window must not raise the sword under it). */
    public static boolean attackQueued() {
        return ATTACKS.pending();
    }

    /** clickMouse HEAD: the next queued target; else, while an aura fights, its look; else null (the camera's). */
    public static MovingObjectPosition nextAttack() {
        MovingObjectPosition queued = ATTACKS.next();
        ownClick = queued != null;
        return queued != null ? queued : fighting ? fightOver : null;
    }

    private static boolean ownClick;

    /** The click clickMouse is running was pressed by a module (pressAttack), not a finger or a clicker. */
    public static boolean ownClick() {
        return ownClick;
    }

    /** An aura fights this tick (fighting was called). */
    public static boolean isFighting() {
        return fighting;
    }

    /**
     * An aura fights this tick along a look whose crosshair is {@code over}
     * (a block there is turned into a miss: the aura never digs).
     */
    public static void fighting(MovingObjectPosition over) {
        fighting = true;
        fightOver = over == null || over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                ? new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS,
                over != null ? over.hitVec : mc.thePlayer.getPositionEyes(1.0F), null, null)
                : over;
    }

    /** sendClickBlockToController: no block-breaking from the camera's crosshair while an aura fights. */
    public static boolean holdsBlockBreaking() {
        return fighting;
    }

    /** clickMouse HEAD, not cancelled: point the crosshair at {@code managed} for this click. */
    public static void apply(MovingObjectPosition managed) {
        mc.objectMouseOver = ATTACKS.swapIn(mc.objectMouseOver, managed);
    }

    /** clickMouse RETURN: the crosshair back. */
    public static void restore() {
        mc.objectMouseOver = ATTACKS.restore(mc.objectMouseOver);
    }

    /** rightClickMouse HEAD, not cancelled: with a sword while an aura fights, the crosshair is its look. */
    public static void applyUse() {
        MovingObjectPosition managed = useOver != null && ItemUtil.isHoldingSword() ? useOver
                : fighting && ItemUtil.isHoldingSword() ? fightOver : null;
        mc.objectMouseOver = USES.swapIn(mc.objectMouseOver, managed);
    }

    /* 2026-10-28 (Silent Aura BlockMode Basic/Interact on the Input path): what this tick's sword right clicks point
       at, when a module decides it -- Basic a miss (only the use), Interact the target (interact-at, interact, use, as
       vanilla's right click on a player sends them). Cleared at the end of the tick. */
    private static MovingObjectPosition useOver;

    /** This tick's right clicks with a sword point at {@code over} (null: the aura's look, as before). */
    public static void useOver(MovingObjectPosition over) {
        useOver = over;
    }

    /** rightClickMouse RETURN: the crosshair back. */
    public static void restoreUse() {
        mc.objectMouseOver = USES.restore(mc.objectMouseOver);
    }

    /** Drops what the tick did not use. */
    public static final class Listener {
        @EventTarget
        public void onTick(TickEvent event) {
            if (event.getType() == EventType.POST) {
                ATTACKS.clear();
                fighting = false;
                fightOver = null;
                useOver = null;
            }
        }
    }
}
