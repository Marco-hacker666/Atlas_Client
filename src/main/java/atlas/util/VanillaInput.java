package atlas.util;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovingObjectPosition;

public final class VanillaInput {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ManagedClicks<MovingObjectPosition> ATTACKS = new ManagedClicks<MovingObjectPosition>();

    private static boolean fighting;
    private static MovingObjectPosition fightOver;

    private static final ManagedClicks<MovingObjectPosition> USES = new ManagedClicks<MovingObjectPosition>();

    private VanillaInput() {
    }

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

    public static void pressAttack(MovingObjectPosition over) {
        ATTACKS.queue(over);
        KeyBinding.onTick(mc.gameSettings.keyBindAttack.getKeyCode());
    }

    public static boolean attackQueued() {
        return ATTACKS.pending();
    }

    public static MovingObjectPosition nextAttack() {
        MovingObjectPosition queued = ATTACKS.next();
        ownClick = queued != null;
        return queued != null ? queued : fighting ? fightOver : null;
    }

    private static boolean ownClick;

    public static boolean ownClick() {
        return ownClick;
    }

    public static boolean isFighting() {
        return fighting;
    }

    public static void fighting(MovingObjectPosition over) {
        fighting = true;
        fightOver = over == null || over.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                ? new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS,
                over != null ? over.hitVec : mc.thePlayer.getPositionEyes(1.0F), null, null)
                : over;
    }

    public static boolean holdsBlockBreaking() {
        return fighting;
    }

    public static void apply(MovingObjectPosition managed) {
        mc.objectMouseOver = ATTACKS.swapIn(mc.objectMouseOver, managed);
    }

    public static void restore() {
        mc.objectMouseOver = ATTACKS.restore(mc.objectMouseOver);
    }

    public static void applyUse() {
        MovingObjectPosition managed = useOver != null && ItemUtil.isHoldingSword() ? useOver
                : fighting && ItemUtil.isHoldingSword() ? fightOver : null;
        mc.objectMouseOver = USES.swapIn(mc.objectMouseOver, managed);
    }

    private static MovingObjectPosition useOver;

    public static void useOver(MovingObjectPosition over) {
        useOver = over;
    }

    public static void restoreUse() {
        mc.objectMouseOver = USES.restore(mc.objectMouseOver);
    }

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
