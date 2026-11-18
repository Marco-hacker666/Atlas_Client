package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;

// ResetVL (ported from LiquidBounce's ModuleResetVL, GPL-3).

public class ResetVL extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty times = new IntProperty("times", 10, 1, 50);
    public final FloatProperty timer = new FloatProperty("timer", 2.0F, 0.1F, 10.0F);

    private int count;

    private boolean rising;

    public ResetVL() {
        super("ResetVL", false, false, "Hops on the spot a number of times to let old anticheats drop their violation levels.");
    }

    @Override
    public void onEnabled() {
        this.count = 0;
        this.rising = false;
    }

    @Override
    public void onDisabled() {
        this.count = 0;
        this.rising = false;
        net.minecraft.util.Timer gameTimer = ((IAccessorMinecraft) mc).getTimer();
        if (gameTimer != null) {
            atlas.util.TimerControl.release(this);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null) {
            return;
        }
        net.minecraft.util.Timer gameTimer = ((IAccessorMinecraft) mc).getTimer();
        if (gameTimer != null) {
            atlas.util.TimerControl.set(this, this.timer.getValue());
        }
        if (this.rising) {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionY = -0.1;
            mc.thePlayer.motionZ = 0.0;
            this.rising = false;
            this.count++;
        } else if (mc.thePlayer.onGround) {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionY = 0.1;
            mc.thePlayer.motionZ = 0.0;
            this.rising = true;
        }
        if (this.count >= this.times.getValue()) {
            ChatUtil.sendFormatted(String.format("%sResetVL: &fdone, %d hops.", Atlas.clientName, this.count));
            this.setEnabled(false);
        }
    }
}
