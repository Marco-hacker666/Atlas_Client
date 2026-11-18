package atlas.management;

import atlas.event.EventManager;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class PerfLog {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final int WINDOW_TICKS = 1200;
    private static final int TOP = 20;

    private File target;
    private int ticks;
    private long windowStart;
    private long fpsSum;

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        long now = System.nanoTime();
        if (this.ticks == 0) {
            this.windowStart = now;
            this.fpsSum = 0L;

            EventManager.takeProfile(1.0, 0);
        }
        this.ticks++;
        this.fpsSum += Minecraft.getDebugFPS();
        if (this.ticks < WINDOW_TICKS) {
            return;
        }
        double seconds = (now - this.windowStart) / 1e9;
        List<String> lines = EventManager.takeProfile(seconds, TOP);
        write(String.format("window %.1fs | fps avg %d | %s", seconds, this.fpsSum / this.ticks,
                mc.getCurrentServerData() == null ? "local" : mc.getCurrentServerData().serverIP), lines);
        this.ticks = 0;
    }

    private void write(String header, List<String> lines) {
        if (this.target == null) {
            this.target = new File(LOG_DIR, "perf-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        List<String> out = new java.util.ArrayList<String>();
        out.add(LINE_STAMP.format(new Date()) + "  " + header);
        out.addAll(lines);
        out.add("");
        atlas.util.AsyncLog.append(this.target, out);
    }
}
