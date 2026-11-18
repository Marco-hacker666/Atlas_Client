package atlas.script.raven.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Bridge {
    private static final Object PRESENT = new Object();
    private final Map<String, Object> values = new ConcurrentHashMap<String, Object>();

    public void add(String key, Object value) {
        this.values.put(key, value == null ? PRESENT : value);
    }

    public void add(String key) {
        this.add(key, null);
    }

    public void remove(String key) {
        this.values.remove(key);
    }

    public boolean has(String key) {
        return this.values.containsKey(key);
    }

    public Object get(String key) {
        Object value = this.values.get(key);
        return value == PRESENT ? null : value;
    }

    public void clear() {
        this.values.clear();
    }
}
