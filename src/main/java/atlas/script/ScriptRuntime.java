package atlas.script;

import atlas.util.ChatUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ScriptRuntime {
    private static final long ERROR_EVERY_MS = 3000L;
    private static final Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private final ScriptEntry entry;
    final Object instance;
    private final Map<String, List<Method>> byName = new ConcurrentHashMap<String, List<Method>>();
    private final Map<String, Method> found = new ConcurrentHashMap<String, Method>();

    ScriptRuntime(ScriptEntry entry, Object instance) {
        this.entry = entry;
        this.instance = instance;
        for (Method method : instance.getClass().getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {
            }
            String key = method.getName().toLowerCase(Locale.ROOT);
            List<Method> list = this.byName.get(key);
            if (list == null) {
                list = new ArrayList<Method>();
                this.byName.put(key, list);
            }
            list.add(method);
        }
    }

    boolean has(String name) {
        return this.byName.containsKey(name.toLowerCase(Locale.ROOT));
    }

    void call(String name, Object... args) {
        Method method = this.find(name, Void.TYPE, args);
        if (method != null) {
            this.invoke(method, args);
        }
    }

    int ask(String name, Object... args) {
        Method method = this.find(name, Boolean.TYPE, args);
        if (method == null) {
            return -1;
        }
        Object answer = this.invoke(method, args);
        return answer instanceof Boolean ? ((Boolean) answer ? 1 : 0) : -1;
    }

    <T> T value(String name, Class<T> type, Object... args) {
        Method method = this.find(name, type, args);
        if (method == null) {
            return null;
        }
        Object answer = this.invoke(method, args);
        return type.isInstance(answer) ? type.cast(answer) : null;
    }

    String suffix() {
        if (this.instance instanceof atlas.script.api.AtlasScript) {
            String own = ((atlas.script.api.AtlasScript) this.instance).currentSuffix();
            if (own != null) {
                return own;
            }
        }
        return this.entry.kind.bs17() ? this.value("getSuffix", String.class) : null;
    }

    private Method find(String name, Class<?> returns, Object[] args) {
        StringBuilder key = new StringBuilder(name).append('/').append(returns.getName());
        for (Object arg : args) {
            key.append('/').append(arg == null ? "null" : arg.getClass().getName());
        }
        String cacheKey = key.toString();
        Method cached = this.found.get(cacheKey);
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        Method match = null;
        List<Method> candidates = this.byName.get(name.toLowerCase(Locale.ROOT));
        if (candidates != null) {
            for (Method method : candidates) {
                if (method.getParameterTypes().length == args.length && returnsAs(method, returns)
                        && accepts(method.getParameterTypes(), args)) {
                    match = method;
                    break;
                }
            }
        }
        this.found.put(cacheKey, match == null ? NONE : match);
        return match;
    }

    private static boolean returnsAs(Method method, Class<?> returns) {
        Class<?> actual = method.getReturnType();
        if (returns == Void.TYPE || returns == Boolean.TYPE) {
            return actual == returns || returns == Boolean.TYPE && actual == Boolean.class;
        }
        return returns.isAssignableFrom(actual);
    }

    private static boolean accepts(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            if (args[i] == null ? parameter.isPrimitive()
                    : parameter.isPrimitive() ? !widens(args[i].getClass(), parameter) : !parameter.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean widens(Class<?> boxed, Class<?> primitive) {
        if (box(primitive) == boxed) {
            return true;
        }
        int from = boxed == Character.class ? 1 : WIDENING_BOXES.indexOf(boxed);
        return from >= 0 && WIDENING.indexOf(primitive) > from;
    }

    private static final List<Class<?>> WIDENING = java.util.Arrays.<Class<?>>asList(
            Byte.TYPE, Short.TYPE, Integer.TYPE, Long.TYPE, Float.TYPE, Double.TYPE);
    private static final List<Class<?>> WIDENING_BOXES = java.util.Arrays.<Class<?>>asList(
            Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class);

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Integer.TYPE) {
            return Integer.class;
        }
        if (type == Float.TYPE) {
            return Float.class;
        }
        if (type == Double.TYPE) {
            return Double.class;
        }
        if (type == Boolean.TYPE) {
            return Boolean.class;
        }
        if (type == Long.TYPE) {
            return Long.class;
        }
        if (type == Character.TYPE) {
            return Character.class;
        }
        if (type == Short.TYPE) {
            return Short.class;
        }
        return type == Byte.TYPE ? Byte.class : type;
    }

    private Object invoke(Method method, Object[] args) {
        try {
            return method.invoke(Modifier.isStatic(method.getModifiers()) ? null : this.instance, args);
        } catch (InvocationTargetException e) {
            this.report(e.getCause() == null ? e : e.getCause(), method.getName());
        } catch (Throwable e) {
            this.report(e, method.getName());
        }
        return null;
    }

    void report(Throwable error, String hook) {
        int line = -1;
        for (StackTraceElement element : error.getStackTrace()) {
            String type = element.getClassName();
            if (type.startsWith(ScriptSource.PACKAGE + "." + this.entry.scriptClass)) {
                line = Math.max(1, element.getLineNumber() - this.entry.headerLines);
                break;
            }
        }
        String message = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage())
                + " in " + hook + (line > 0 ? ", line " + line : "");
        this.entry.runtimeError = message;
        int count = ++this.entry.runtimeErrors;
        long now = System.currentTimeMillis();
        if (count == 1) {
            System.err.println("[Atlas] Script " + this.entry.name + " threw in " + hook + ":");
            error.printStackTrace();
        }
        if (count == 1 || now - this.entry.lastErrorShown >= ERROR_EVERY_MS) {
            this.entry.lastErrorShown = now;
            ChatUtil.sendFormatted("&7[&bScripts&7] &c" + this.entry.name + ": " + message
                    + (count > 1 ? " &7(" + count + "x)" : "") + "&r");
        }
    }
}
