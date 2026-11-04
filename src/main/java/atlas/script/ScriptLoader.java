package atlas.script;

import java.util.Map;

/**
 * Defines one script's classes from the compiler's output (2026-11-02). One loader per load, so a reloaded script
 * is a new class and the old one goes with its loader. Everything else -- this client, the game, Java -- comes
 * from the parent, the loader that loaded this client.
 */
final class ScriptLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    ScriptLoader(ClassLoader parent, Map<String, byte[]> classes) {
        super(parent);
        this.classes = classes;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this.getClassLoadingLock(name)) {
            Class<?> type = this.findLoadedClass(name);
            if (type == null && this.classes.containsKey(name)) {
                /* Its own classes first: the parent would only go looking through every mod for them. */
                type = this.findClass(name);
            }
            if (type == null) {
                return super.loadClass(name, resolve);
            }
            if (resolve) {
                this.resolveClass(type);
            }
            return type;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = this.classes.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return this.defineClass(name, bytes, 0, bytes.length);
    }
}
