package atlas.module.modules;

import atlas.util.Ping;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.EntityLivingBase;

public class InvulnTiming extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float WINDOW = 10.0F;

    public final FloatProperty compensation = new FloatProperty("ping-compensation", 1.0F, 0.0F, 1.5F);

    public final FloatProperty safety = new FloatProperty("safety-ticks", 0.5F, 0.0F, 4.0F);

    public final BooleanProperty redirect = new BooleanProperty("redirect", true);

    public final BooleanProperty holdFire = new BooleanProperty("hold-fire", true);

    public final IntProperty maxHold = new IntProperty("max-hold-ticks", 6, 1, 20,
            this.holdFire::getValue);
    public final BooleanProperty hud = new BooleanProperty("suffix", true);

    private int held;
    private int savedSwings;
    private int redirects;

    public InvulnTiming() {
        super("InvulnTiming", false, false,
                "Times attacks to the end of the target's invulnerability window");
    }

    public static InvulnTiming instance() {
        Module module = Atlas.moduleManager.modules.get(InvulnTiming.class);
        return module instanceof InvulnTiming ? (InvulnTiming) module : null;
    }

    @Override
    public void onEnabled() {
        this.held = 0;
        this.savedSwings = 0;
        this.redirects = 0;
    }

    private int ping() {
        return Ping.own();
    }

    private float lagTicks() {
        int ping = ping();
        if (ping <= 0) {
            return 0.0F;
        }

        return Math.min(WINDOW, ping / 50.0F) * this.compensation.getValue();
    }

    public float predict(EntityLivingBase target) {
        if (target == null) {
            return 0.0F;
        }
        return target.hurtTime - lagTicks() + this.safety.getValue();
    }

    public boolean canDamage(EntityLivingBase target) {
        if (!this.isEnabled() || target == null) {
            return true;
        }
        return predict(target) <= 0.0F;
    }

    public boolean allowAttack(EntityLivingBase target) {
        if (!this.isEnabled() || !this.holdFire.getValue() || target == null) {
            return true;
        }
        if (canDamage(target)) {
            this.held = 0;
            return true;
        }
        if (this.held >= this.maxHold.getValue()) {
            this.held = 0;
            return true;
        }
        this.held++;
        this.savedSwings++;
        return false;
    }

    public void noteRedirect() {
        this.redirects++;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (mc.thePlayer.isDead) {
            this.held = 0;
        }
    }

    @Override
    public String[] getSuffix() {
        if (!this.hud.getValue()) {
            return new String[0];
        }
        return new String[]{this.savedSwings + "/" + this.redirects};
    }
}
