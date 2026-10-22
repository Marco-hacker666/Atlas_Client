package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.SafeWalkEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.util.ItemUtil;
import atlas.util.MoveUtil;
import atlas.util.PlayerUtil;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import net.minecraft.client.Minecraft;

public class SafeWalk extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    public final FloatProperty motion = new FloatProperty("motion", 1.0F, 0.5F, 1.0F);
    public final FloatProperty speedMotion = new FloatProperty("speed-motion", 1.0F, 0.5F, 1.5F);
    public final BooleanProperty air = new BooleanProperty("air", false);
    public final BooleanProperty directionCheck = new BooleanProperty("direction-check", true);
    public final BooleanProperty pitCheck = new BooleanProperty("pitch-check", true);
    public final BooleanProperty requirePress = new BooleanProperty("require-press", false);
    public final BooleanProperty blocksOnly = new BooleanProperty("blocks-only", true);

    private boolean canSafeWalk() {
        Scaffold scaffold = (Scaffold) Atlas.moduleManager.modules.get(Scaffold.class);
        if (scaffold.isEnabled()) {
            return false;
        } else if (atlas.management.Arbiter.catching() || atlas.management.Arbiter.viewHeld()) {
            /* Looking down with blocks in hand is the catch's doing, not
               bridging; stopping at an edge here eats the knockback the
               server expects to see. See Arbiter. */
            return false;
        } else if (this.directionCheck.getValue() && mc.gameSettings.keyBindForward.isKeyDown()) {
            return false;
        } else if (this.pitCheck.getValue() && mc.thePlayer.rotationPitch < 69.0F) {
            return false;
        } else if (this.blocksOnly.getValue() && !ItemUtil.isHoldingBlock()) {
            return false;
        } else {
            return (!this.requirePress.getValue() || mc.gameSettings.keyBindUseItem.isKeyDown()) && (mc.thePlayer.onGround && PlayerUtil.canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0)
                    || this.air.getValue() && PlayerUtil.canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -2.0));
        }
    }

    public SafeWalk() {
        super("SafeWalk", false, false, "U cant js shift so u need safewalk ok:L");
    }

    @EventTarget
    public void onMove(SafeWalkEvent event) {
        if (this.isEnabled()) {
            if (this.canSafeWalk()) {
                event.setSafeWalk(true);
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (this.isEnabled() && event.getType() == EventType.PRE) {
            if (mc.thePlayer.onGround && MoveUtil.isForwardPressed() && this.canSafeWalk()) {
                if (MoveUtil.getSpeedLevel() <= 0) {
                    if (this.motion.getValue() != 1.0F) {
                        MoveUtil.setSpeed(MoveUtil.getSpeed() * (double) this.motion.getValue());
                    }
                } else if (this.speedMotion.getValue() != 1.0F) {
                    MoveUtil.setSpeed(MoveUtil.getSpeed() * (double) this.speedMotion.getValue());
                }
            }
        }
    }
}
