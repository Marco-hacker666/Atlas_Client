package atlas.event;

import atlas.event.events.Event;
import atlas.event.events.EventStoppable;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.PacketEvent;
import atlas.module.Module;
import atlas.util.ActionLedger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author DarkMagician6
 * @since February 2, 2014
 */
public final class EventManager {
    private static final Map<Class<? extends Event>, List<MethodData>> REGISTRY_MAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    private EventManager() {
    }

    public static void register(Object object) {
        for (final Method method : object.getClass().getDeclaredMethods()) {
            if (!isMethodBad(method)) {
                register(method, object);
            }
        }
    }

    public static void register(Object object, Class<? extends Event> eventClass) {
        for (final Method method : object.getClass().getDeclaredMethods()) {
            if (!isMethodBad(method, eventClass)) {
                register(method, object);
            }
        }
    }

    public static void unregister(Object object) {
        for (final List<MethodData> dataList : REGISTRY_MAP.values()) {
            for (final MethodData data : dataList) {
                if (data.getSource().equals(object)) {
                    dataList.remove(data);
                }
            }
        }
        cleanMap(true);
    }

    public static void unregister(Object object, Class<? extends Event> eventClass) {
        if (REGISTRY_MAP.containsKey(eventClass)) {
            for (final MethodData data : REGISTRY_MAP.get(eventClass)) {
                if (data.getSource().equals(object)) {
                    REGISTRY_MAP.get(eventClass).remove(data);
                }
            }
            cleanMap(true);
        }
    }

    private static void register(Method method, Object object) {
        Class<? extends Event> indexClass = (Class<? extends Event>) method.getParameterTypes()[0];

        EventTarget annotation = method.getAnnotation(EventTarget.class);
        final MethodData data = new MethodData(object, method, annotation.value(), annotation.whenDisabled());

        if (!data.getTarget().isAccessible()) {
            data.getTarget().setAccessible(true);
        }
        if (REGISTRY_MAP.containsKey(indexClass)) {
            if (!isRegistered(REGISTRY_MAP.get(indexClass), object, method)) {
                REGISTRY_MAP.get(indexClass).add(data);
                sortListValue(indexClass);
            }
        } else {
            REGISTRY_MAP.put(indexClass, new CopyOnWriteArrayList<MethodData>() {
                private static final long serialVersionUID = 666L;

                {
                    add(data);
                }
            });
        }
    }

    public static void removeEntry(Class<? extends Event> indexClass) {
        Iterator<Map.Entry<Class<? extends Event>, List<MethodData>>> mapIterator = REGISTRY_MAP.entrySet().iterator();
        while (mapIterator.hasNext()) {
            if (mapIterator.next().getKey().equals(indexClass)) {
                mapIterator.remove();
                break;
            }
        }
    }

    public static void cleanMap(boolean onlyEmptyEntries) {
        Iterator<Map.Entry<Class<? extends Event>, List<MethodData>>> mapIterator = REGISTRY_MAP.entrySet().iterator();
        while (mapIterator.hasNext()) {
            Map.Entry<Class<? extends Event>, List<MethodData>> entry = mapIterator.next();
            if (!onlyEmptyEntries || entry.getValue().isEmpty()) {
                mapIterator.remove();
            }
        }
    }

    private static boolean isRegistered(List<MethodData> list, Object object, Method method) {
        for (MethodData existing : list) {
            if (existing.getSource() == object && existing.getTarget().equals(method)) {
                return true;
            }
        }
        return false;
    }

    static void clearForTests() {
        REGISTRY_MAP.clear();
    }

    private static void sortListValue(Class<? extends Event> indexClass) {
        List<MethodData> sortedList = new CopyOnWriteArrayList<>();
        for (final byte priority : Priority.VALUE_ARRAY) {
            for (final MethodData data : REGISTRY_MAP.get(indexClass)) {
                if (data.getPriority() == priority) {
                    sortedList.add(data);
                }
            }
        }

        REGISTRY_MAP.put(indexClass, sortedList);
    }

    private static boolean isMethodBad(Method method) {
        return method.getParameterTypes().length != 1 || !method.isAnnotationPresent(EventTarget.class);
    }

    private static boolean isMethodBad(Method method, Class<? extends Event> eventClass) {
        return isMethodBad(method) || !method.getParameterTypes()[0].equals(eventClass);
    }

    public static Event call(final Event event) {
        List<MethodData> dataList = REGISTRY_MAP.get(event.getClass());
        if (dataList != null) {
            final boolean watched = event instanceof PacketEvent;

            final boolean moved = atlas.util.ActionWatch.watches(event);

            java.util.function.Consumer<atlas.events.UpdateEvent> endOfTick = atlas.util.ActionWatch.beforeUpdatePost;
            if (endOfTick != null && event instanceof atlas.events.UpdateEvent
                    && ((atlas.events.UpdateEvent) event).getType() == atlas.event.types.EventType.POST) {
                try {
                    endOfTick.accept((atlas.events.UpdateEvent) event);
                } catch (RuntimeException e) {
                    System.err.println("[Atlas] the end-of-tick hook threw:");
                    e.printStackTrace();
                }
            }
            if (event instanceof EventStoppable) {
                EventStoppable stoppable = (EventStoppable) event;
                for (final MethodData data : dataList) {
                    if (data.skipped()) {
                        continue;
                    }
                    boolean before = watched && ((PacketEvent) event).isCancelled();
                    if (moved) {
                        atlas.util.ActionWatch.before(event);
                    }
                    invoke(data, event);
                    if (moved) {
                        atlas.util.ActionWatch.after(data.getSource(), event);
                    }
                    if (watched && !before && ((PacketEvent) event).isCancelled()) {
                        noteCancel(data, (PacketEvent) event);
                    }
                    if (stoppable.isStopped()) {
                        break;
                    }
                }
            } else {
                for (final MethodData data : dataList) {
                    if (data.skipped()) {
                        continue;
                    }
                    boolean before = watched && ((PacketEvent) event).isCancelled();
                    if (moved) {
                        atlas.util.ActionWatch.before(event);
                    }
                    invoke(data, event);
                    if (moved) {
                        atlas.util.ActionWatch.after(data.getSource(), event);
                    }
                    if (watched && !before && ((PacketEvent) event).isCancelled()) {
                        noteCancel(data, (PacketEvent) event);
                    }
                }
            }
        }
        return event;
    }

    private static void noteCancel(MethodData data, PacketEvent event) {
        Object source = data.getSource();
        if (!(source instanceof Module)) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            atlas.management.MoveCheck.packetHeld(((Module) source).getName(), event.getPacket());
        }

        if (!ActionLedger.cancelMatters(event.getPacket())) {
            return;
        }
        ActionLedger.note(((Module) source).getName(),
                ActionLedger.holdKind(event.getPacket(), event.getType() == EventType.SEND));
    }

    private static void invoke(MethodData data, Event argument) {
        long start = System.nanoTime();
        try {
            data.getTarget().invoke(data.getSource(), argument);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            System.err.println("[Atlas] event handler " + data.getSource().getClass().getSimpleName()
                    + "." + data.getTarget().getName() + "(" + argument.getClass().getSimpleName()
                    + ") threw:");
            e.printStackTrace();
        }
        data.record(System.nanoTime() - start);
    }

    public static List<String> takeProfile(double seconds, int top) {
        List<MethodData> all = new ArrayList<MethodData>();
        Map<String, long[]> byEvent = new HashMap<String, long[]>();
        for (Map.Entry<Class<? extends Event>, List<MethodData>> entry : REGISTRY_MAP.entrySet()) {
            for (MethodData data : entry.getValue()) {
                if (data.calls == 0) {
                    continue;
                }
                all.add(data);
                long[] sum = byEvent.get(entry.getKey().getSimpleName());
                if (sum == null) {
                    sum = new long[2];
                    byEvent.put(entry.getKey().getSimpleName(), sum);
                }
                sum[0] += data.nanos;
                sum[1] += data.calls;
            }
        }
        Collections.sort(all, new Comparator<MethodData>() {
            @Override
            public int compare(MethodData a, MethodData b) {
                return Long.compare(b.nanos, a.nanos);
            }
        });
        List<String> lines = new ArrayList<String>();
        long total = 0L;
        for (MethodData data : all) {
            total += data.nanos;
        }
        lines.add(String.format("handlers %.2f ms/s in total", total / 1e6 / seconds));
        for (int i = 0; i < Math.min(top, all.size()); i++) {
            MethodData data = all.get(i);
            lines.add(String.format("  %7.3f ms/s  %8d calls  avg %6.1f us  max %7.1f us  %s.%s(%s)",
                    data.nanos / 1e6 / seconds, data.calls, data.nanos / 1e3 / data.calls, data.maxNanos / 1e3,
                    data.getSource().getClass().getSimpleName(), data.getTarget().getName(),
                    data.getTarget().getParameterTypes()[0].getSimpleName()));
        }
        List<Map.Entry<String, long[]>> events = new ArrayList<Map.Entry<String, long[]>>(byEvent.entrySet());
        Collections.sort(events, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        lines.add("by event:");
        for (Map.Entry<String, long[]> entry : events) {
            lines.add(String.format("  %7.3f ms/s  %8d calls  %s",
                    entry.getValue()[0] / 1e6 / seconds, entry.getValue()[1], entry.getKey()));
        }
        for (MethodData data : all) {
            data.reset();
        }
        return lines;
    }

    /**
     * @author DarkMagician6
     * @since January 2, 2014
     */
    private static final class MethodData {
        private final Object source;
        private final Method target;
        private final byte priority;

        private final atlas.module.Module skipWhenOff;

        private long nanos;
        private long calls;
        private long maxNanos;

        public MethodData(Object source, Method target, byte priority, boolean whenDisabled) {
            this.source = source;
            this.target = target;
            this.priority = priority;
            this.skipWhenOff = !whenDisabled && source instanceof atlas.module.Module
                    ? (atlas.module.Module) source : null;
        }

        boolean skipped() {
            return this.skipWhenOff != null && !this.skipWhenOff.isRunning();
        }

        void record(long elapsed) {
            this.nanos += elapsed;
            this.calls++;
            if (elapsed > this.maxNanos) {
                this.maxNanos = elapsed;
            }
        }

        void reset() {
            this.nanos = 0L;
            this.calls = 0L;
            this.maxNanos = 0L;
        }

        public Object getSource() {
            return source;
        }

        public Method getTarget() {
            return target;
        }

        public byte getPriority() {
            return priority;
        }
    }
}
