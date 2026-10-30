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

/**
 * ResetVL (2026-10-30, ported from LiquidBounce's ModuleResetVL, GPL-3).
 *
 * Stands still and makes small hops on the spot: on the ground the motion is
 * set to 0.1 up with no horizontal speed, the next tick to 0.1 down, and that
 * counts one. After times hops it turns itself off. The game runs at timer
 * speed meanwhile, so it is over sooner. The idea is that an anticheat whose
 * violation levels decay with clean movement sees a run of short, legal-looking
 * moves and lowers them.
 *
 * Only older or flawed anticheats fall for it. A 0.1 step off the ground is not
 * a move vanilla makes (a jump is 0.42) and a timer above 1 is a timer, so a
 * prediction anticheat (Grim, Polar) is more likely to flag it than to forget.
 */
public class ResetVL extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final IntProperty times = new IntProperty("times", 10, 1, 50);
    public final FloatProperty timer = new FloatProperty("timer", 2.0F, 0.1F, 10.0F);

    private int count;
    /** Went up last tick; this tick goes down. */
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

    /** TickEvent PRE (runTick HEAD): the motion set here is this tick's move. */
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
