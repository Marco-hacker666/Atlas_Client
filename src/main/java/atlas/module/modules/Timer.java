package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.UpdateEvent;
import atlas.mixin.IAccessorMinecraft;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;

public class Timer extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public final ModeProperty timerMode = new ModeProperty("mode", 0, new String[]{"Normal", "TimerHop", "TimerBalance"});
    public final FloatProperty speed = new FloatProperty("speed", 1.0F, 0.01F, 10.0F);
    public final FloatProperty hopSpeed = new FloatProperty("hop-speed", 1.5F, 0.1F, 3.0F, () -> timerMode.getValue() == 1);
    public final FloatProperty balanceSpeed = new FloatProperty("balance-speed", 0.5F, 0.1F, 1.0F, () -> timerMode.getValue() == 2);
    public final IntProperty balanceInterval = new IntProperty("balance-interval", 5, 1, 20, () -> timerMode.getValue() == 2);
    public final FloatProperty minSpeed = new FloatProperty("min-speed", 1.0F, 0.01F, 10.0F);
    public final FloatProperty maxSpeed = new FloatProperty("max-speed", 1.0F, 0.01F, 10.0F);
    /* 2026-10-19 (from Slinky): run fast only while these hold; otherwise 1.0. */
    public final BooleanProperty requireAttackKey = new BooleanProperty("require-attack-key", false);
    public final BooleanProperty requireDamage = new BooleanProperty("require-damage", false);
    public final BooleanProperty requireFalling = new BooleanProperty("require-falling", false);

    private int timerTicks = 0;
    private int randomTicks = 0;
    private float randomizedSpeed = 1.0F;

    public Timer() {
        super("Timer", false, false, "Changes client timer speed to move faster.");
    }

    @Override
    public void onEnabled() {
        this.timerTicks = 0;
        this.randomTicks = 0;
        this.randomizedSpeed = this.speed.getValue();
    }

    @Override
    public void onDisabled() {
        this.timerTicks = 0;
        this.randomTicks = 0;
        this.randomizedSpeed = this.speed.getValue();
        net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
        if (timer != null) {
            atlas.util.TimerControl.release(this);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }

        this.randomTicks++;
        if (this.randomTicks >= 20) {
            this.randomTicks = 0;
            float lo = Math.min(this.minSpeed.getValue(), this.maxSpeed.getValue());
            float hi = Math.max(this.minSpeed.getValue(), this.maxSpeed.getValue());
            if (Math.abs(hi - lo) < 1.0E-6F) {
                this.randomizedSpeed = this.speed.getValue();
            } else {
                this.randomizedSpeed = (float) (lo + Math.random() * (hi - lo));
            }
        }

        net.minecraft.util.Timer timer = ((IAccessorMinecraft) mc).getTimer();
        if (timer != null && !conditionsMet()) {
            atlas.util.TimerControl.release(this);
            return;
        }
        if (timer != null) {
            // Ported from Raven GrimBoatLongJump timer-balance idea: hop and balance variants.
            if (this.timerMode.getValue() == 1 && mc.thePlayer != null) {
                atlas.util.TimerControl.set(this, mc.thePlayer.onGround ? this.randomizedSpeed : this.hopSpeed.getValue());
            } else if (this.timerMode.getValue() == 2) {
                this.timerTicks++;
                int interval = this.balanceInterval.getValue();
                atlas.util.TimerControl.set(this, (this.timerTicks % (interval * 2)) < interval
                        ? this.balanceSpeed.getValue() : 1.0F);
            } else {
                atlas.util.TimerControl.set(this, this.randomizedSpeed);
            }
        }
    }

    private boolean conditionsMet() {
        if (mc.thePlayer == null) {
            return true;
        }
        if (this.requireAttackKey.getValue()
                && !atlas.util.KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode())) {
            return false;
        }
        if (this.requireDamage.getValue() && mc.thePlayer.hurtTime <= 0) {
            return false;
        }
        return !this.requireFalling.getValue() || !mc.thePlayer.onGround && mc.thePlayer.motionY < 0.0;
    }

    @Override
    public String[] getSuffix() {
        if (this.timerMode.getValue() == 0) {
            return new String[]{String.format("%.1fx", this.speed.getValue())};
        }
        return new String[]{this.timerMode.getModeString()};
    }
}