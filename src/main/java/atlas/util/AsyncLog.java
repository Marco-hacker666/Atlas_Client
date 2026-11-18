package atlas.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public final class AsyncLog {
    private static final class Entry {
        final File file;
        final List<String> lines;

        Entry(File file, List<String> lines) {
            this.file = file;
            this.lines = lines;
        }
    }

    static final int CAPACITY = 20000;
    private static final LinkedBlockingQueue<Entry> QUEUE = new LinkedBlockingQueue<Entry>(CAPACITY);
    private static Thread worker;
    private static boolean exitRegistered;
    private static final java.util.concurrent.atomic.AtomicLong DROPPED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong FAILED = new java.util.concurrent.atomic.AtomicLong();

    private AsyncLog() {
    }

    public static long dropped() {
        return DROPPED.get();
    }

    public static long failed() {
        return FAILED.get();
    }

    public static int queued() {
        return QUEUE.size();
    }

    public static void append(File file, String line) {
        append(file, Collections.singletonList(line));
    }

    public static void append(File file, List<String> lines) {
        if (file == null || lines.isEmpty()) {
            return;
        }

        if (!atlas.module.modules.Logs.allows(file)) {
            return;
        }
        start();
        if (!QUEUE.offer(new Entry(file, new ArrayList<String>(lines)))) {
            DROPPED.incrementAndGet();
        }
    }

    private static synchronized void start() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Entry entry;
                    try {
                        entry = QUEUE.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        write(entry);
                    } catch (Throwable t) {
                        FAILED.incrementAndGet();
                    }
                }
            }
        }, "Atlas-AsyncLog");
        worker.setDaemon(true);
        worker.start();
        if (!exitRegistered) {
            exitRegistered = true;

            atlas.management.Shutdown.register(atlas.management.Shutdown.Stage.FLUSH_LOGS, "AsyncLog",
                    AsyncLog::drain);
        }
    }

    static void drain() {
        Thread running;
        synchronized (AsyncLog.class) {
            running = worker;
            worker = null;
        }
        if (running != null) {
            running.interrupt();
            try {
                running.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Entry entry;
        while ((entry = QUEUE.poll()) != null) {
            try {
                write(entry);
            } catch (Throwable t) {
                FAILED.incrementAndGet();
            }
        }
    }

    private static synchronized void write(Entry entry) {
        PrintWriter writer = null;
        try {
            File dir = entry.file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            writer = new PrintWriter(new FileWriter(entry.file, true));
            for (String line : entry.lines) {
                writer.println(line);
            }
            writer.flush();
        } catch (Exception ignored) {
            FAILED.incrementAndGet();
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
