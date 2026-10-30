package atlas.util;

import atlas.mixin.IAccessorMinecraft;
import net.minecraft.client.Minecraft;

/**
 * Who set the game timer last (2026-10-30, audit).
 *
 * Nine modules wrote Timer.timerSpeed directly -- Timer, Speed, Fly, NoFall,
 * Tower, Criticals, FastUse, Velocity, ResetVL -- and most put it back to 1.0
 * every tick they were not using it, so one module "resetting" cancelled
 * another's speed (Timer on with NoFall: NoFall's reset won every tick). A
 * module now sets the speed through here and gives it back with release,
 * which only returns the timer to 1.0 when that module is the one that set it
 * last.
 */
public final class TimerControl {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static volatile Object owner;

    private TimerControl() {
    }

    private static net.minecraft.util.Timer timer() {
        return mc == null ? null : ((IAccessorMinecraft) mc).getTimer();
    }

    /** The game timer at {@code speed}, set by {@code who}. */
    public static void set(Object who, float speed) {
        net.minecraft.util.Timer timer = timer();
        if (timer == null) {
            return;
        }
        timer.timerSpeed = speed;
        owner = speed == 1.0F ? null : who;
    }

    /** Back to 1.0 if {@code who} set it last (or nobody known did); otherwise left to whoever did. */
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

    /** For tests and diagnostics. */
    public static Object owner() {
        return owner;
    }
}
