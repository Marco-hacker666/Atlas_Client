package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0). Auto-jumps at ledges while sprinting; the edge
// test works out whether the player could still move if they kept going.
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.MoveUtil;
import atlas.util.PlayerUtil;
import net.minecraft.client.Minecraft;

public class Parkour extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Jump", "JumpSprint"});
    public final FloatProperty edgeDistance = new FloatProperty("edge-distance", 1.0F, 0.5F, 3.0F);
    public final BooleanProperty onlySprinting = new BooleanProperty("only-sprinting", true);

    public Parkour() {
        super("Parkour", false, false, "Automatically jumps at block edges while sprinting.");
    }

    private boolean canMoveSafely() {
        double[] offset = MoveUtil.predictMovement();
        float scale = this.edgeDistance.getValue();
        return PlayerUtil.canMove(mc.thePlayer.motionX + offset[0] * scale, mc.thePlayer.motionZ + offset[1] * scale);
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!mc.thePlayer.onGround || mc.currentScreen != null) {
            return;
        }
        if (this.onlySprinting.getValue() && !mc.thePlayer.isSprinting()) {
            return;
        }
        if (!this.canMoveSafely()) {
            mc.thePlayer.jump();
            if (this.mode.getValue() == 1 && !mc.thePlayer.isSprinting()) {
                mc.thePlayer.setSprinting(true);
            }
        }
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }
}
