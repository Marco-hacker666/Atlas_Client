package atlas.lb.aiming;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class AimFeed {
    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<Consumer<String>>();

    private AimFeed() {
    }

    public static void listen(Consumer<String> listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void publish(String line) {
        for (Consumer<String> listener : LISTENERS) {
            try {
                listener.accept(line);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
