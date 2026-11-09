package atlas.lb.aiming;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Aim rows as the Recorder writes them (2026-11-10): whoever listens --
 * Silent Aura's Recorded turn -- learns from a recording while it is being
 * made, instead of after the next switch-on. Client thread.
 */
public final class AimFeed {
    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<Consumer<String>>();

    private AimFeed() {
    }

    public static void listen(Consumer<String> listener) {
        LISTENERS.addIfAbsent(listener);
    }

    /** One header or row, exactly as written to the aim file. */
    public static void publish(String line) {
        for (Consumer<String> listener : LISTENERS) {
            try {
                listener.accept(line);
            } catch (RuntimeException ignored) {
                // A listener's trouble never stops the recording.
            }
        }
    }
}
