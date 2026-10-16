package myau.ui;

import myau.util.AsyncLog;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * How long opening the click menu takes, step by step, and its first frames
 * (2026-10-17). The perf log only saw "ModuleManager.onKey 110 ms" for each
 * Right Shift, with no way to tell which part. One line per open in
 * config/Atlas/gui-perf.txt.
 */
public final class GuiPerf {
    private static final File FILE = new File("./config/Atlas/", "gui-perf.txt");

    private static long openStart;
    private static long stepStart;
    private static final StringBuilder STEPS = new StringBuilder();
    private static int framesLeft;
    private static final StringBuilder FRAMES = new StringBuilder();

    private GuiPerf() {
    }

    /** The key press, or whatever opens the menu, starts here. */
    public static void open() {
        openStart = System.nanoTime();
        stepStart = openStart;
        STEPS.setLength(0);
        FRAMES.setLength(0);
        framesLeft = 0;
    }

    /** One step of the opening, since the last one. */
    public static void step(String name) {
        if (openStart == 0L) {
            return;
        }
        long now = System.nanoTime();
        STEPS.append(String.format(" %s %.1f", name, (now - stepStart) / 1.0E6));
        stepStart = now;
    }

    /** The menu is up: its first frames are timed too. */
    public static void opened() {
        if (openStart == 0L) {
            return;
        }
        step("rest");
        framesLeft = 3;
    }

    /** A frame of the menu, with the part spent on the background blur. */
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
