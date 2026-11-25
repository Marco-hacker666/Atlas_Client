package atlas.management;

import atlas.Atlas;
import atlas.util.ChatUtil;
import atlas.util.render.Effects;
import atlas.util.render.GpuStats;
import atlas.util.render.GpuTimer;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * ".perf compare": fast-blur off, then on (one second to settle and four measured each), then the FPS and
 * the GPU time of the blur and glow in each, in chat. The setting itself is not changed.
 */
public final class PerfCompare {
    private static final int SETTLE_TICKS = 20;
    private static final int MEASURE_TICKS = 80;
    private static final long GAP_MS = 3000L;

    private static int phase;
    private static int ticks;
    private static long fpsSum;
    private static int fpsCount;
    private static long lastTick;
    private static final Result[] RESULTS = {new Result(), new Result()};

    private static final class Result {
        double fps;
        GpuStats gpu = new GpuStats();
    }

    private PerfCompare() {
    }

    public static boolean running() {
        return phase != 0;
    }

    public static void start() {
        if (Effects.lite()) {
            say("&eEffects are Lite (RenderFixes > effects), so no blur or glow is drawn: nothing to compare.");
            return;
        }
        phase = 1;
        ticks = 0;
        lastTick = System.currentTimeMillis();
        Effects.overrideFastBlur(false);
        say("&7Comparing fast-blur off and on, about 10 seconds. Keep the view still; leave menus closed.");
    }

    /** Client tick, in a world. */
    static void tick() {
        if (phase == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTick > GAP_MS) {
            stop();
            say("&eThe comparison stopped: the game was away from the world.");
            return;
        }
        lastTick = now;
        if (Effects.lite()) {
            // Auto turned blur and glow off mid-way: the halves no longer measure the same work
            stop();
            say("&eThe comparison stopped: effects went Lite (low FPS), so its numbers would be wrong.");
            return;
        }
        ticks++;
        if (ticks == SETTLE_TICKS) {
            GpuTimer.resetProbe();
            fpsSum = 0L;
            fpsCount = 0;
        }
        if (ticks > SETTLE_TICKS) {
            fpsSum += Minecraft.getDebugFPS();
            fpsCount++;
        }
        if (ticks < SETTLE_TICKS + MEASURE_TICKS) {
            return;
        }
        Result result = RESULTS[phase - 1];
        result.fps = fpsCount == 0 ? 0.0 : fpsSum / (double) fpsCount;
        result.gpu = GpuTimer.probe();
        if (phase == 1) {
            phase = 2;
            ticks = 0;
            Effects.overrideFastBlur(true);
            return;
        }
        stop();
        report();
    }

    private static void stop() {
        phase = 0;
        ticks = 0;
        Effects.overrideFastBlur(null);
    }

    private static void report() {
        Result off = RESULTS[0];
        Result on = RESULTS[1];
        say(String.format("&fFast-blur off: &e%.0f fps&r  ·  fast-blur on: &a%.0f fps", off.fps, on.fps));
        if (!GpuTimer.on()) {
            say("&7GPU timing is " + GpuTimer.describe() + ", so only FPS is shown.");
            return;
        }
        if (off.gpu.frames() == 0 || on.gpu.frames() == 0) {
            // too few frames read back in four seconds (very low FPS): no GPU figures rather than empty ones
            say("&7GPU timing had no frames read back in " + (off.gpu.frames() == 0 ? "the off half" : "the on half")
                    + " (FPS too low for four seconds), so only FPS is shown.");
            return;
        }
        double offEffects = effects(off.gpu);
        double onEffects = effects(on.gpu);
        say(String.format("&fBlur and glow on the GPU: &e%.2f ms&r per frame off, &a%.2f ms&r on", offEffects, onEffects));
        say(String.format("&fWhole frame on the GPU: &e%.2f ms&r off, &a%.2f ms&r on", off.gpu.msPerFrame(GpuStats.FRAME),
                on.gpu.msPerFrame(GpuStats.FRAME)));
        List<String> names = off.gpu.sections();
        StringBuilder parts = new StringBuilder();
        for (String name : names) {
            if (!isEffect(name)) {
                continue;
            }
            if (parts.length() > 0) {
                parts.append("&7, ");
            }
            parts.append(String.format("&f%s &e%.2f&7/&a%.2f", name, off.gpu.msPerFrame(name), on.gpu.msPerFrame(name)));
        }
        if (parts.length() > 0) {
            say("&7Each (ms off/on): " + parts);
        }
    }

    private static double effects(GpuStats stats) {
        return stats.msPerFrameEndingWith(" blur", " glow");
    }

    private static boolean isEffect(String name) {
        return name.endsWith(" blur") || name.endsWith(" glow");
    }

    private static void say(String message) {
        ChatUtil.sendFormatted(Atlas.clientName + message);
    }
}
