package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.MoveInputEvent;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.util.MoveUtil;
import net.minecraft.client.Minecraft;

public class MoveFix extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final BooleanProperty groundOnly = new BooleanProperty("ground-only", false);

    public MoveFix() {
        super("MoveFix", false, false, "Corrects strafing while silent rotations are active so your movement matches your server rotation.");
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (this.groundOnly.getValue() && !mc.thePlayer.onGround) {
            return;
        }
        if (RotationState.isActived() && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }
}
