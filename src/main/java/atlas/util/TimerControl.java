package atlas.util;

import atlas.mixin.IAccessorMinecraft;
import net.minecraft.client.Minecraft;

public final class TimerControl {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static volatile Object owner;

    private TimerControl() {
    }

    private static net.minecraft.util.Timer timer() {
        return mc == null ? null : ((IAccessorMinecraft) mc).getTimer();
    }

    public static void set(Object who, float speed) {
        net.minecraft.util.Timer timer = timer();
        if (timer == null) {
            return;
        }
        timer.timerSpeed = speed;
        owner = speed == 1.0F ? null : who;
    }

    public static void release(Object who) {
        Object current = owner;
        if (current != null && current != who) {
            return;
        }
        owner = null;
        net.minecraft.util.Timer timer = timer();
        if (timer != null) {
            timer.timerSpeed = 1.0F;
        }
    }

    public static Object owner() {
        return owner;
    }
}
