package atlas.ui;

import atlas.util.AsyncLog;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class GuiPerf {
    private static final File FILE = new File("./config/Atlas/", "gui-perf.txt");

    private static long openStart;
    private static long stepStart;
    private static final StringBuilder STEPS = new StringBuilder();
    private static int framesLeft;
    private static final StringBuilder FRAMES = new StringBuilder();

    private GuiPerf() {
    }

    public static void open() {
        openStart = System.nanoTime();
        stepStart = openStart;
        STEPS.setLength(0);
        FRAMES.setLength(0);
        framesLeft = 0;
    }

    public static void step(String name) {
        if (openStart == 0L) {
            return;
        }
        long now = System.nanoTime();
        STEPS.append(String.format(" %s %.1f", name, (now - stepStart) / 1.0E6));
        stepStart = now;
    }

    public static void opened() {
        if (openStart == 0L) {
            return;
        }
        step("rest");
        framesLeft = 3;
    }

    public static void frame(double totalMs, double blurMs) {
        if (framesLeft <= 0) {
            return;
        }
        FRAMES.append(String.format(" %.1f(blur %.1f)", totalMs, blurMs));
        if (--framesLeft == 0) {
            AsyncLog.append(FILE, String.format("%s  OPEN %.1f ms |%s | first frames%s",
                    new SimpleDateFormat("HH:mm:ss").format(new Date()), (stepStart - openStart) / 1.0E6,
                    STEPS, FRAMES));
            openStart = 0L;
        }
    }
}
