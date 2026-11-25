package atlas.management;

import atlas.event.EventManager;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.util.render.Effects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class PerfLog {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final int WINDOW_TICKS = 1200;
    private static final int TOP = 15;
    private static final long SERVER_LOOKUP_MS = 5000L;

    private final StackSampler sampler;
    private final PerfGuard guard = new PerfGuard();
    private File target;
    private int ticks;
    private long windowStart;
    private long fpsSum;
    private int fpsMin = Integer.MAX_VALUE;
    private long windowGcStart;
    private long secondStart;
    private long secondGc;
    private long serverLookedUp;
    private int secondWorld;

    public PerfLog() {
        this.sampler = new StackSampler(Thread.currentThread(), () -> mc.theWorld != null);
        this.sampler.start();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        long now = System.nanoTime();
        long wall = System.currentTimeMillis();
        PerfCompare.tick();
        findServer(wall);
        if (this.ticks == 0) {
            this.windowStart = now;
            this.fpsSum = 0L;
            this.fpsMin = Integer.MAX_VALUE;
            this.windowGcStart = gcMillis();
            EventManager.takeProfile(1.0, 0);
            this.sampler.take();
            atlas.util.render.GpuTimer.takeWindow();
        }
        this.ticks++;
        int fps = Minecraft.getDebugFPS();
        this.fpsSum += fps;
        if (fps > 0) {
            this.fpsMin = Math.min(this.fpsMin, fps);
        }
        everySecond(wall);
        if (this.ticks < WINDOW_TICKS) {
            return;
        }
        double seconds = (now - this.windowStart) / 1e9;
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        String header = String.format("window %.1fs | fps avg %d min %d | gc %.0f ms/s | heap %d/%d MB | effects %s%s | %s",
                seconds, this.fpsSum / this.ticks, this.fpsMin == Integer.MAX_VALUE ? 0 : this.fpsMin,
                (gcMillis() - this.windowGcStart) / Math.max(0.001, seconds), usedMb, runtime.maxMemory() >> 20,
                Effects.lite() ? "lite" : "full", Effects.fastBlur() ? ", fast blur" : ", full-screen blur",
                mc.getCurrentServerData() == null ? "local" : mc.getCurrentServerData().serverIP);
        List<String> lines = new ArrayList<String>();
        lines.add(atlas.util.render.GpuTimer.on() ? atlas.util.render.GpuTimer.takeWindow().line(12)
                : "gpu: timing " + atlas.util.render.GpuTimer.describe());
        lines.addAll(this.sampler.take());
        lines.addAll(EventManager.takeProfile(seconds, TOP));
        write(header, lines);
        this.ticks = 0;
    }

    private void everySecond(long wall) {
        if (this.secondStart == 0L) {
            this.secondStart = wall;
            this.secondGc = gcMillis();
            return;
        }
        long elapsed = wall - this.secondStart;
        if (elapsed < 1000L) {
            return;
        }
        long gc = gcMillis();
        int world = System.identityHashCode(mc.theWorld);
        if (world != this.secondWorld) {
            // a new world (or back from the menu): start counting again instead of judging the time away
            this.secondWorld = world;
            this.secondStart = wall;
            this.secondGc = gc;
            this.guard.newWorld();
            return;
        }
        PerfGuard.Second s = new PerfGuard.Second();
        s.fps = Minecraft.getDebugFPS();
        s.inWorld = mc.theWorld != null && mc.thePlayer != null;
        s.screenOpen = mc.currentScreen != null && !(mc.currentScreen instanceof GuiChat);
        s.windowActive = windowActive();
        s.frameLimit = mc.gameSettings.limitFramerate;
        s.effectsDrawn = Effects.drawnWithin(1500L);
        s.liteAlready = Effects.lite();
        s.auto = Effects.auto();
        s.gcMillis = gc - this.secondGc;
        s.wallMillis = elapsed;
        this.secondStart = wall;
        this.secondGc = gc;
        PerfGuard.Decision decision = this.guard.second(s);
        if (decision == PerfGuard.Decision.GO_LITE) {
            Effects.goLiteForThisSession();
            tell("Low FPS: Atlas turned its blur and glow off for this session. RenderFixes > effects: Full keeps them on.");
            atlas.util.DiagNotes.note("Perf", "effects lite: under " + PerfGuard.LOW_FPS + " fps for "
                    + PerfGuard.LOW_FPS_SECONDS + " s with blur or glow drawn", false);
        } else if (decision == PerfGuard.Decision.WARN_MEMORY) {
            long maxMb = Runtime.getRuntime().maxMemory() >> 20;
            tell("Minecraft is short on memory (" + maxMb + " MB): it spends a lot of time freeing memory, which"
                    + " causes lag. Give it 3-4 GB in your launcher's memory settings.");
            atlas.util.DiagNotes.note("Perf", "memory warning: gc over " + Math.round(PerfGuard.GC_SHARE * 100)
                    + "% of " + PerfGuard.GC_SECONDS + " s, max " + maxMb + " MB", false);
        }
    }

    private static void tell(String message) {
        try {
            if (atlas.Atlas.notificationManager != null) {
                atlas.Atlas.notificationManager.add(message, 8000L, 0xFFC857);
            }
            atlas.util.ChatUtil.sendFormatted(atlas.Atlas.clientName + "&e" + message);
        } catch (Throwable ignored) {
        }
    }

    private static boolean windowActive() {
        try {
            return org.lwjgl.opengl.Display.isActive();
        } catch (Throwable e) {
            return true;
        }
    }

    private void findServer(long wall) {
        if (wall - this.serverLookedUp < SERVER_LOOKUP_MS) {
            return;
        }
        this.serverLookedUp = wall;
        if (!mc.isSingleplayer()) {
            this.sampler.setServer(null);
            return;
        }
        Thread[] threads = new Thread[Thread.activeCount() + 16];
        int count = Thread.enumerate(threads);
        for (int i = 0; i < count; i++) {
            if (threads[i] != null && "Server thread".equals(threads[i].getName())) {
                this.sampler.setServer(threads[i]);
                return;
            }
        }
        this.sampler.setServer(null);
    }

    static long gcMillis() {
        long total = 0L;
        try {
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                total += Math.max(0L, gc.getCollectionTime());
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private void write(String header, List<String> lines) {
        if (this.target == null) {
            this.target = new File(LOG_DIR, "perf-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        List<String> out = new ArrayList<String>();
        out.add(LINE_STAMP.format(new Date()) + "  " + header);
        out.addAll(lines);
        out.add("");
        atlas.util.AsyncLog.append(this.target, out);
    }
}
