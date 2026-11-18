package atlas.management;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Shutdown {
    public enum Stage {
        RESTORE,

        SAVE_CONFIG,

        SAVE_STATE,

        FLUSH_LOGS
    }

    private static final class Task {
        final String name;
        final Runnable body;

        Task(String name, Runnable body) {
            this.name = name;
            this.body = body;
        }
    }

    private static final Map<Stage, List<Task>> TASKS = new EnumMap<Stage, List<Task>>(Stage.class);
    private static final AtomicBoolean RAN = new AtomicBoolean(false);
    private static boolean installed;

    private Shutdown() {
    }

    public static synchronized void register(Stage stage, String name, Runnable body) {
        List<Task> list = TASKS.get(stage);
        if (list == null) {
            list = new ArrayList<Task>();
            TASKS.put(stage, list);
        }
        list.add(new Task(name, body));
        if (!installed) {
            installed = true;
            Runtime.getRuntime().addShutdownHook(new Thread(Shutdown::run, "Atlas-Shutdown"));
        }
    }

    public static void run() {
        if (!RAN.compareAndSet(false, true)) {
            return;
        }
        for (Stage stage : Stage.values()) {
            List<Task> list;
            synchronized (Shutdown.class) {
                List<Task> registered = TASKS.get(stage);
                list = registered == null ? new ArrayList<Task>() : new ArrayList<Task>(registered);
            }
            for (Task task : list) {
                try {
                    task.body.run();
                } catch (Throwable t) {
                    System.err.println("[Atlas] shutdown task " + stage + "/" + task.name + " failed:");
                    t.printStackTrace();
                }
            }
        }
    }

    static synchronized void resetForTests() {
        TASKS.clear();
        RAN.set(false);
    }
}
