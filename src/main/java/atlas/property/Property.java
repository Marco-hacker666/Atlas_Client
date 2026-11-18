package atlas.property;

import com.google.gson.JsonObject;
import atlas.module.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public abstract class Property<T> {
    public enum Source {
        DEFAULT,

        PROFILE,

        TUNED,

        USER,

        LEARNED,

        TRIAL,

        GOVERNOR;

        public boolean isOverride() {
            return this.ordinal() >= LEARNED.ordinal();
        }
    }

    private static final class Layer<T> {
        final Source source;
        final String owner;
        final T value;
        final long at;

        Layer(Source source, String owner, T value) {
            this.source = source;
            this.owner = owner;
            this.value = value;
            this.at = System.currentTimeMillis();
        }
    }

    private String name;
    private final T type;
    private final Predicate<T> validator;
    private final BooleanSupplier visibleChecker;

    private volatile T value;

    private volatile T base;
    private volatile Source baseSource = Source.DEFAULT;
    private volatile long baseAt;

    private final List<Layer<T>> layers = new ArrayList<Layer<T>>(2);
    private Module owner;

    protected Property(String name, Object value, BooleanSupplier visibleChecker) {
        this(name, value, null, visibleChecker);
    }

    protected Property(String name, Object value, Predicate<T> predicate, BooleanSupplier visibleChecker) {
        this.name = name;
        this.type = (T) value;
        this.validator = predicate;
        this.visibleChecker = visibleChecker;
        this.value = (T) value;
        this.base = (T) value;
        this.owner = null;
    }

    public String getName() {
        return this.name;
    }

    public Property<T> rekey(String key) {
        if (this.label == null) {
            this.label = prettify(this.name);
        }
        this.name = key;
        return this;
    }

    private boolean groupFixed;

    public Property<T> fixGroup(String group) {
        this.group = group;
        this.groupFixed = true;
        return this;
    }

    public boolean isGroupFixed() {
        return this.groupFixed;
    }

    public Property<T> alsoWhen(java.util.function.BooleanSupplier condition) {
        final java.util.function.BooleanSupplier before = this.alsoVisible;
        this.alsoVisible = before == null ? condition
                : () -> before.getAsBoolean() && condition.getAsBoolean();
        return this;
    }

    private String label;
    private String group;
    private String help;
    private BooleanSupplier alsoVisible;

    public String getLabel() {
        return this.label != null ? this.label : prettify(this.name);
    }

    public Property<T> setLabel(String label) {
        this.label = label;
        return this;
    }

    public String getGroup() {
        return this.group;
    }

    public Property<T> setGroup(String group) {
        this.group = group;
        return this;
    }

    private String helpEn;

    public String getHelp() {
        return atlas.module.ModuleDocs.isEnglish() ? this.helpEn : this.help;
    }

    public Property<T> setHelp(String help) {
        this.help = help;
        return this;
    }

    public Property<T> setHelpEn(String help) {
        this.helpEn = help;
        return this;
    }

    public Property<T> when(BooleanSupplier condition) {
        this.alsoVisible = condition;
        return this;
    }

    public static String prettify(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        StringBuilder words = new StringBuilder();
        char[] c = key.replace('_', ' ').replace('-', ' ').toCharArray();
        for (int i = 0; i < c.length; i++) {
            char ch = c[i];
            boolean boundary = i > 0 && Character.isUpperCase(ch) && c[i - 1] != ' '
                    && (Character.isLowerCase(c[i - 1]) || Character.isDigit(c[i - 1])
                    || i + 1 < c.length && Character.isLowerCase(c[i + 1]));
            if (boundary) {
                words.append(' ');
            }
            words.append(ch);
        }
        String[] parts = words.toString().trim().split(" +");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String w = parts[i];
            if (w.isEmpty()) {
                continue;
            }

            boolean acronym = w.equals(w.toUpperCase()) && !w.matches("\\d+") && Character.isLetter(w.charAt(0));
            String shown = acronym ? w : i == 0 ? Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase()
                    : w.toLowerCase();
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(shown);
        }
        return out.toString();
    }

    public abstract String getValuePrompt();

    public boolean isVisible() {
        return (this.visibleChecker == null || this.visibleChecker.getAsBoolean())
                && (this.alsoVisible == null || this.alsoVisible.getAsBoolean());
    }

    public T getValue() {
        return this.value;
    }

    public abstract String formatValue();

    public boolean setValue(Object object) {
        return setBase(atlas.config.Config.loading ? Source.PROFILE : Source.USER, object);
    }

    public boolean setBase(Source source, Object object) {
        if (source == null || source.isOverride()) {
            throw new IllegalArgumentException("not a base source: " + source);
        }
        if (this.validator != null && !this.validator.test((T) object)) {
            return false;
        }
        synchronized (this) {
            this.base = (T) object;
            this.baseSource = source;
            this.baseAt = System.currentTimeMillis();
            if (this.layers.isEmpty()) {
                this.value = (T) object;
            }
        }
        if (this.owner != null) {
            this.owner.verifyValue(this.name);
        }
        return true;
    }

    protected boolean supportsOverride() {
        return false;
    }

    public boolean override(Source source, String owner, Object object) {
        if (source == null || !source.isOverride()) {
            throw new IllegalArgumentException("not an override source: " + source);
        }
        if (!supportsOverride()) {
            throw new UnsupportedOperationException(getClass().getSimpleName() + " " + this.name
                    + " cannot be overridden");
        }
        if (this.validator != null && !this.validator.test((T) object)) {
            return false;
        }
        synchronized (this) {
            removeLayer(owner);
            this.layers.add(new Layer<T>(source, owner, (T) object));
            recompute();
        }
        return true;
    }

    public void release(String owner) {
        synchronized (this) {
            if (removeLayer(owner)) {
                recompute();
            }
        }
    }

    public synchronized T overrideOf(String owner) {
        for (Layer<T> layer : this.layers) {
            if (layer.owner.equals(owner)) {
                return layer.value;
            }
        }
        return null;
    }

    public synchronized boolean isOverridden() {
        return !this.layers.isEmpty();
    }

    public T getBaseValue() {
        return this.base;
    }

    public T getDefault() {
        return this.type;
    }

    protected T getPersistedValue() {
        return this.base;
    }

    public synchronized Source getSource() {
        Layer<T> top = top();
        return top == null ? this.baseSource : top.source;
    }

    public synchronized String getSourceOwner() {
        Layer<T> top = top();
        return top == null ? null : top.owner;
    }

    public synchronized String describe() {
        long now = System.currentTimeMillis();
        Layer<T> top = top();
        if (top == null) {
            return this.value + " (" + this.baseSource
                    + (this.baseAt == 0L ? "" : " " + (now - this.baseAt) / 1000L + "s ago") + ")";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(this.value).append(" (").append(top.source).append(" by ").append(top.owner)
                .append(' ').append((now - top.at) / 1000L).append("s ago; base ").append(this.base)
                .append(' ').append(this.baseSource);
        if (this.layers.size() > 1) {
            sb.append("; ").append(this.layers.size() - 1).append(" more under it");
        }
        return sb.append(')').toString();
    }

    private boolean removeLayer(String owner) {
        boolean removed = false;
        for (int i = this.layers.size() - 1; i >= 0; i--) {
            if (this.layers.get(i).owner.equals(owner)) {
                this.layers.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    private Layer<T> top() {
        Layer<T> best = null;
        for (Layer<T> layer : this.layers) {
            if (best == null || layer.source.ordinal() >= best.source.ordinal()) {
                best = layer;
            }
        }
        return best;
    }

    private void recompute() {
        Layer<T> top = top();
        this.value = top == null ? this.base : top.value;
    }

    public void parseString() {
    }

    public void setOwner(Module module) {
        this.owner = module;
    }

    public abstract boolean parseString(String string);

    public abstract boolean read(JsonObject jsonObject);

    public abstract void write(JsonObject jsonObject);
}
