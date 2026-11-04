package atlas.script;

import atlas.Atlas;
import atlas.module.Module;
import atlas.module.ModuleManager;
import atlas.property.Property;
import atlas.util.ChatUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import javax.tools.JavaCompiler;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The scripts (2026-11-02, owner: "a tab that loads everyone's own scripts; Raven's script API and ours"). Replaces
 * the OpenSkid port, which compiled with javac only -- absent from the launchers' JRE -- and gave scripts strings.
 *
 *  - Atlas scripts are the .java files in config/Atlas/scripts, Raven's those in config/Atlas/scripts/raven16 and
 *    raven17, by the version they are written for (Raven bS's own folder, .minecraft/keystrokes/scripts, can be
 *    copied in from the Scripts tab). A file whose name starts with "_" is left alone, as in Raven.
 *  - Files are watched: a changed file is compiled again and its new version replaces the old, keeping its
 *    settings, key and on/off. A version that does not compile leaves the old one running and shows why.
 *  - Compiling runs on a thread of its own (ScriptCompiler); loading, onLoad and registering on the client
 *    thread, where the module and property maps are read.
 *  - Each script is a module: its key, HUD entry and settings work like any module's and are kept in profiles,
 *    and in config/Atlas/scripts/state.json, which is what a script gets back when it loads.
 *
 * Loading a script runs its code with this client's rights (owner's choice: no sandbox). Only load scripts whose
 * source you have read.
 */
public final class ScriptManager {
    public static final File ROOT = new File("./config/Atlas/scripts");
    /* 2026-11-03 (owner: "a bS 16 and a bS 17 separately"): one folder for each Raven version. raven/, the one
       folder of the first version, is read as bS 16. */
    public static final File RAVEN16_DIR = new File(ROOT, "raven16");
    public static final File RAVEN17_DIR = new File(ROOT, "raven17");
    static final File LEGACY_RAVEN_DIR = new File(ROOT, "raven");
    static final File BUILD_DIR = new File(ROOT, ".build");
    static final File STATE_FILE = new File(ROOT, "state.json");
    /** The rotation priority scripts ask with (modules use 0-6). */
    public static final int PRIORITY = 4;
    private static final int POLL_TICKS = 20;
    private static final int SAVE_TICKS = 200;

    private static final ScriptManager INSTANCE = new ScriptManager();
    /** The script being constructed on this thread, for AtlasScript's constructor (its fields make settings). */
    static final ThreadLocal<ScriptEntry> CONSTRUCTING = new ThreadLocal<ScriptEntry>();

    /** Every script file known, loaded or not. */
    private final List<ScriptEntry> all = new CopyOnWriteArrayList<ScriptEntry>();
    /** The loaded ones, for the dispatcher (any thread). */
    final List<ScriptEntry> running = new CopyOnWriteArrayList<ScriptEntry>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Atlas-Scripts");
        thread.setDaemon(true);
        return thread;
    });
    private JsonObject state = new JsonObject();
    private String savedState = "";
    private int generation;
    /** Goes up whenever a script's module appears, changes or goes: what the menu's search index is checked by. */
    private volatile int version;
    private int ticks;
    private boolean started;
    private List<File> classpath;

    private ScriptManager() {
    }

    public static ScriptManager get() {
        return INSTANCE;
    }

    // ---- what the menu reads ---------------------------------------------

    public List<ScriptEntry> entries() {
        return new ArrayList<ScriptEntry>(this.all);
    }

    public List<ScriptEntry> entries(ScriptKind kind) {
        List<ScriptEntry> out = new ArrayList<ScriptEntry>();
        for (ScriptEntry entry : this.all) {
            if (kind == null || entry.kind == kind) {
                out.add(entry);
            }
        }
        return out;
    }

    public ScriptEntry byName(String name) {
        if (name == null) {
            return null;
        }
        for (ScriptEntry entry : this.all) {
            if (ModuleManager.sameName(entry.name, name)) {
                return entry;
            }
        }
        return null;
    }

    /** The script whose module this is, or null. */
    public static ScriptEntry of(Module module) {
        return module instanceof ScriptHost ? ((ScriptHost) module).entry() : null;
    }

    public int version() {
        return this.version;
    }

    public boolean isBusy() {
        for (ScriptEntry entry : this.all) {
            if (entry.compiling) {
                return true;
            }
        }
        return false;
    }

    public String compilerStatus() {
        return ScriptCompiler.status();
    }

    // ---- start, watch, reload ------------------------------------------

    /** At startup, once the modules and the config are loaded. */
    public void start() {
        if (this.started) {
            return;
        }
        this.started = true;
        ensureDirs();
        this.state = readState();
        this.savedState = this.state.toString();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveState, "Atlas scripts state"));
        this.scan(false);
    }

    /** Every client tick: watch the files, save the state now and then. */
    void tick() {
        if (!this.started) {
            return;
        }
        this.ticks++;
        if (this.ticks % POLL_TICKS == 0) {
            this.scan(false);
        }
        if (this.ticks % SAVE_TICKS == 0) {
            this.saveState();
        }
    }

    /** Compiles every script again. */
    public void reloadAll() {
        ensureDirs();
        this.scan(true);
        ChatUtil.sendFormatted("&7[&bScripts&7] Reloading " + this.all.size() + " script(s) ...&r");
    }

    public void reload(ScriptEntry entry) {
        if (entry != null && entry.file.isFile()) {
            this.compile(entry);
        }
    }

    /** New files get an entry, changed ones are compiled, gone ones unloaded. */
    private void scan(boolean force) {
        List<File> files = new ArrayList<File>();
        files.addAll(javaFiles(ROOT));
        files.addAll(javaFiles(RAVEN16_DIR));
        files.addAll(javaFiles(RAVEN17_DIR));
        files.addAll(javaFiles(LEGACY_RAVEN_DIR));
        for (ScriptEntry entry : this.all) {
            if (!files.contains(entry.file)) {
                this.unload(entry);
                this.all.remove(entry);
            }
        }
        for (File file : files) {
            ScriptEntry entry = this.byFile(file);
            if (entry == null) {
                ScriptKind kind = kindOf(file);
                entry = new ScriptEntry(this.freeName(stripJava(file.getName()), kind), kind, file);
                this.all.add(entry);
                this.version++;
                this.compile(entry);
            } else if (!entry.compiling && (force || file.lastModified() != entry.lastModified
                    || file.length() != entry.length)) {
                this.compile(entry);
            }
        }
    }

    /** The version a file is written for, by its folder. */
    static ScriptKind kindOf(File file) {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir.equals(RAVEN17_DIR.getAbsoluteFile())) {
            return ScriptKind.RAVEN17;
        }
        if (dir.equals(RAVEN16_DIR.getAbsoluteFile()) || dir.equals(LEGACY_RAVEN_DIR.getAbsoluteFile())) {
            return ScriptKind.RAVEN16;
        }
        return ScriptKind.ATLAS;
    }

    /** The folder a version's new scripts go in. */
    public static File dirOf(ScriptKind kind) {
        return kind == ScriptKind.RAVEN16 ? RAVEN16_DIR : kind == ScriptKind.RAVEN17 ? RAVEN17_DIR : ROOT;
    }

    private ScriptEntry byFile(File file) {
        for (ScriptEntry entry : this.all) {
            if (entry.file.equals(file)) {
                return entry;
            }
        }
        return null;
    }

    private static List<File> javaFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] files = dir.getAbsoluteFile().listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.endsWith(".java") && !name.startsWith("_") && name.length() > 5) {
                out.add(file.getAbsoluteFile());
            }
        }
        return out;
    }

    private static String stripJava(String name) {
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }

    /** A module name nothing else has: a built-in module or another script keeps its own. */
    private String freeName(String base, ScriptKind kind) {
        String name = base;
        if (this.taken(name)) {
            name = base + (kind == ScriptKind.RAVEN16 ? " (bS 16)" : kind == ScriptKind.RAVEN17 ? " (bS 17)" : " (script)");
        }
        int n = 2;
        String stem = name;
        while (this.taken(name)) {
            name = stem + " " + n++;
        }
        return name;
    }

    private boolean taken(String name) {
        if (this.byName(name) != null) {
            return true;
        }
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.getModule(name);
        return module != null && !(module instanceof ScriptHost);
    }

    // ---- compile (worker) and install (client) -----------------------------

    /** What compiling one script gave. */
    static final class Compiled {
        final boolean ok;
        final List<String> problems;
        final String scriptClass;
        final String hostClass;
        final int headerLines;
        final Map<String, byte[]> classes;

        Compiled(boolean ok, List<String> problems, ScriptSource.Wrapped wrapped, Map<String, byte[]> classes) {
            this.ok = ok;
            this.problems = problems;
            this.scriptClass = wrapped == null ? "" : wrapped.scriptClass;
            this.hostClass = wrapped == null ? "" : wrapped.hostClass;
            this.headerLines = wrapped == null ? 0 : wrapped.headerLines;
            this.classes = classes;
        }

        static Compiled failed(String... problems) {
            return new Compiled(false, Arrays.asList(problems), null, null);
        }
    }

    private void compile(ScriptEntry entry) {
        entry.compiling = true;
        entry.lastModified = entry.file.lastModified();
        entry.length = entry.file.length();
        final int gen = ++this.generation;
        this.worker.submit(() -> {
            Compiled compiled;
            try {
                String source = new String(Files.readAllBytes(entry.file.toPath()), StandardCharsets.UTF_8);
                compiled = this.build(entry, source, gen);
            } catch (Throwable e) {
                compiled = Compiled.failed("could not read " + entry.shownPath() + ": " + e);
            }
            final Compiled result = compiled;
            onClient(() -> this.install(entry, result));
        });
    }

    /** Wraps and compiles one script (worker thread); the class files come back as bytes. */
    Compiled build(ScriptEntry entry, String source, int gen) throws Exception {
        ScriptSource.Wrapped wrapped = ScriptSource.wrap(entry.kind, entry.name, source, gen);
        if (wrapped.problem != null) {
            return Compiled.failed(wrapped.problem);
        }
        JavaCompiler javac = ScriptCompiler.get();
        if (javac == null) {
            return Compiled.failed(ScriptCompiler.status());
        }
        if (this.classpath == null) {
            this.classpath = ScriptCompiler.classpath();
        }
        File dir = new File(BUILD_DIR, wrapped.scriptClass);
        deleteTree(dir);
        File sources = new File(dir, "src/" + ScriptSource.PACKAGE);
        File out = new File(dir, "classes");
        List<ScriptCompiler.Unit> units = Arrays.asList(
                new ScriptCompiler.Unit(new File(sources, wrapped.scriptClass + ".java"), wrapped.code,
                        wrapped.headerLines, entry.shownPath()),
                new ScriptCompiler.Unit(new File(sources, wrapped.hostClass + ".java"), wrapped.hostCode, 0,
                        "(host)"));
        try {
            ScriptCompiler.Result result = ScriptCompiler.compile(javac, units, out, this.classpath);
            if (!result.ok) {
                List<String> problems = new ArrayList<String>();
                for (ScriptCompiler.Problem problem : result.problems) {
                    if (problem.error && problems.size() < 8) {
                        problems.add(problem.toString());
                    }
                }
                if (problems.isEmpty()) {
                    problems.add(result.firstError());
                }
                return new Compiled(false, problems, wrapped, null);
            }
            return new Compiled(true, Collections.<String>emptyList(), wrapped, readClasses(out));
        } finally {
            deleteTree(dir);
        }
    }

    /** The compiled classes by binary name. */
    static Map<String, byte[]> readClasses(File root) throws Exception {
        Map<String, byte[]> out = new HashMap<String, byte[]>();
        readClasses(root, root, out);
        return out;
    }

    private static void readClasses(File root, File dir, Map<String, byte[]> out) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                readClasses(root, file, out);
            } else if (file.getName().endsWith(".class")) {
                String relative = root.toURI().relativize(file.toURI()).getPath();
                String name = relative.substring(0, relative.length() - 6).replace('/', '.');
                out.put(name, Files.readAllBytes(file.toPath()));
            }
        }
    }

    /** On the client thread: the new version replaces the old, keeping its state. */
    private void install(ScriptEntry entry, Compiled compiled) {
        entry.compiling = false;
        if (!this.all.contains(entry)) {
            return;
        }
        if (!compiled.ok) {
            entry.problems = compiled.problems;
            ChatUtil.sendFormatted("&7[&bScripts&7] &c" + entry.name + " did not compile"
                    + (entry.isLoaded() ? " (the last version keeps running)" : "") + ": "
                    + (compiled.problems.isEmpty() ? "" : compiled.problems.get(0)) + "&r");
            return;
        }
        boolean reload = entry.isLoaded();
        JsonObject saved = reload ? snapshot(entry.host, entry.properties) : objectOf(this.state.get(entry.name));
        this.unload(entry);
        entry.problems = Collections.emptyList();
        entry.properties.clear();
        entry.steps.clear();
        entry.alphas.clear();
        entry.group = null;
        entry.runtimeError = null;
        entry.runtimeErrors = 0;
        entry.scriptClass = compiled.scriptClass;
        entry.headerLines = compiled.headerLines;
        ScriptLoader loader = new ScriptLoader(ScriptManager.class.getClassLoader(), compiled.classes);
        ScriptHost host = null;
        try {
            Class<?> hostType = loader.loadClass(ScriptSource.PACKAGE + "." + compiled.hostClass);
            host = (ScriptHost) hostType.getConstructor(String.class, String.class)
                    .newInstance(entry.name, entry.kind.label + " script");
            host.entry = entry;
            entry.host = host;
            Object instance;
            CONSTRUCTING.set(entry);
            try {
                Constructor<?> constructor = loader.loadClass(ScriptSource.PACKAGE + "." + compiled.scriptClass)
                        .getDeclaredConstructor();
                constructor.setAccessible(true);
                instance = constructor.newInstance();
            } finally {
                CONSTRUCTING.remove();
            }
            entry.loader = loader;
            entry.runtime = new ScriptRuntime(entry, instance);
            entry.runtime.call("onLoad");
            for (Property<?> property : entry.properties) {
                property.setOwner(host);
            }
            Atlas.propertyManager.properties.put(hostType, entry.properties);
            Atlas.moduleManager.modules.put(hostType, host);
            this.running.add(entry);
            this.version++;
            apply(host, entry.properties, saved);
            if (reload) {
                ChatUtil.sendFormatted("&7[&bScripts&7] &aReloaded " + entry.name + "&r");
            }
        } catch (Throwable e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause()
                    : e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
            if (entry.runtime != null) {
                entry.runtime.report(cause, "onLoad");
            }
            this.unregister(entry, host);
            entry.problems = Collections.singletonList("could not start: " + cause);
            ChatUtil.sendFormatted("&7[&bScripts&7] &c" + entry.name + " could not start: " + cause + "&r");
        }
    }

    /** Switched off (its onDisable runs), its state kept, its module taken out. */
    private void unload(ScriptEntry entry) {
        ScriptHost host = entry.host;
        if (host != null && this.running.contains(entry)) {
            try {
                if (host.isEnabled()) {
                    host.setEnabled(false);
                }
            } catch (Throwable ignored) {
                // Its onDisable threw; it is going anyway.
            }
            this.state.add(entry.name, snapshot(host, entry.properties));
        }
        this.unregister(entry, host);
    }

    private void unregister(ScriptEntry entry, ScriptHost host) {
        this.running.remove(entry);
        this.version++;
        if (host != null) {
            if (Atlas.moduleManager != null && Atlas.moduleManager.modules.get(host.getClass()) == host) {
                Atlas.moduleManager.modules.remove(host.getClass());
            }
            if (Atlas.propertyManager != null) {
                Atlas.propertyManager.properties.remove(host.getClass());
            }
        }
        entry.host = null;
        entry.runtime = null;
        entry.loader = null;
    }

    // ---- state ----------------------------------------------------------

    /** A module as profiles write it: on/off, key, hidden, its settings. */
    static JsonObject snapshot(Module module, List<Property<?>> properties) {
        JsonObject object = new JsonObject();
        object.addProperty("toggled", module.isEnabled());
        object.addProperty("key", module.getKey());
        object.addProperty("hidden", module.isHidden());
        for (Property<?> property : properties) {
            try {
                property.write(object);
            } catch (Exception ignored) {
                // One setting that cannot be written does not lose the others.
            }
        }
        return object;
    }

    static void apply(Module module, List<Property<?>> properties, JsonObject object) {
        if (object == null) {
            return;
        }
        for (Property<?> property : properties) {
            if (object.has(property.getName())) {
                try {
                    property.read(object);
                } catch (Exception ignored) {
                    // A value from an older version of the script: its default stays.
                }
            }
        }
        if (object.has("key") && object.get("key").isJsonPrimitive()) {
            module.setKey(object.get("key").getAsInt());
        }
        if (object.has("hidden") && object.get("hidden").isJsonPrimitive()) {
            module.setHidden(object.get("hidden").getAsBoolean());
        }
        if (object.has("toggled") && object.get("toggled").isJsonPrimitive()) {
            module.setEnabled(object.get("toggled").getAsBoolean());
        }
    }

    private static JsonObject objectOf(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /** Writes state.json when something changed (and at exit). */
    public synchronized void saveState() {
        try {
            for (ScriptEntry entry : this.running) {
                ScriptHost host = entry.host;
                if (host != null) {
                    this.state.add(entry.name, snapshot(host, entry.properties));
                }
            }
            String text = new GsonBuilder().setPrettyPrinting().create().toJson(this.state);
            if (text.equals(this.savedState)) {
                return;
            }
            ensureDirs();
            File temp = new File(STATE_FILE.getPath() + ".tmp");
            Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), STATE_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.savedState = text;
        } catch (Exception e) {
            System.err.println("[Atlas] Could not save the scripts' state: " + e);
        }
    }

    private static JsonObject readState() {
        try {
            if (STATE_FILE.isFile()) {
                JsonElement element = new JsonParser().parse(
                        new String(Files.readAllBytes(STATE_FILE.toPath()), StandardCharsets.UTF_8));
                if (element.isJsonObject()) {
                    return element.getAsJsonObject();
                }
            }
        } catch (Exception e) {
            System.err.println("[Atlas] Could not read the scripts' state: " + e);
        }
        return new JsonObject();
    }

    // ---- the tab's buttons -------------------------------------------------

    /** A new script from the template, opened in the system's editor; its file. */
    public File newScript(ScriptKind kind) {
        ensureDirs();
        File dir = dirOf(kind);
        String stem = kind == ScriptKind.RAVEN16 ? "NewBs16Script" : kind == ScriptKind.RAVEN17 ? "NewBs17Script" : "NewScript";
        File file = new File(dir, stem + ".java");
        for (int n = 2; file.exists(); n++) {
            file = new File(dir, stem + n + ".java");
        }
        try {
            Files.write(file.toPath(), ScriptSource.template(kind).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ChatUtil.sendFormatted("&7[&bScripts&7] &cCould not create " + file.getName() + ": " + e + "&r");
            return null;
        }
        this.scan(false);
        openInSystem(file);
        ChatUtil.sendFormatted("&7[&bScripts&7] Created &f" + (kind.folder.isEmpty() ? "" : kind.folder + "/")
                + file.getName() + "&7: saving it reloads the script.&r");
        return file;
    }

    public void openFolder(ScriptKind kind) {
        ensureDirs();
        openInSystem(dirOf(kind));
    }

    public void openFile(ScriptEntry entry) {
        if (entry != null) {
            openInSystem(entry.file);
        }
    }

    /** Raven bS's folder, where Raven players keep their scripts. */
    public static File ravenHome() {
        Minecraft mc = Minecraft.getMinecraft();
        File base = mc == null || mc.mcDataDir == null ? new File(".") : mc.mcDataDir;
        return new File(base, "keystrokes" + File.separator + "scripts");
    }

    /** Copies Raven bS's scripts in as that version's (none overwritten); how many came. */
    public int importRaven(ScriptKind kind) {
        ensureDirs();
        int copied = 0;
        File into = dirOf(kind == ScriptKind.ATLAS ? ScriptKind.RAVEN17 : kind);
        for (File file : javaFiles(ravenHome())) {
            File target = new File(into, file.getName());
            if (target.exists()) {
                continue;
            }
            try {
                Files.copy(file.toPath(), target.toPath());
                copied++;
            } catch (Exception e) {
                ChatUtil.sendFormatted("&7[&bScripts&7] &cCould not copy " + file.getName() + ": " + e + "&r");
            }
        }
        ChatUtil.sendFormatted("&7[&bScripts&7] " + (copied == 0 ? "No new Raven scripts in "
                + ravenHome().getPath() : "Copied " + copied + " Raven script(s) into " + into.getName() + "/") + "&r");
        this.scan(false);
        return copied;
    }

    /** The system's file browser or editor, off the client thread. */
    private static void openInSystem(File file) {
        Thread thread = new Thread(() -> {
            try {
                java.awt.Desktop.getDesktop().open(file.getAbsoluteFile());
            } catch (Throwable e) {
                try {
                    org.lwjgl.Sys.openURL(file.getAbsoluteFile().toURI().toString());
                } catch (Throwable ignored) {
                    ChatUtil.sendFormatted("&7[&bScripts&7] Open it yourself: &f" + file.getAbsolutePath() + "&r");
                }
            }
        }, "Atlas-OpenFile");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- helpers -------------------------------------------------------

    private static void ensureDirs() {
        ROOT.mkdirs();
        RAVEN16_DIR.mkdirs();
        RAVEN17_DIR.mkdirs();
    }

    private static void onClient(Runnable task) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.addScheduledTask(task);
        } else {
            task.run();
        }
    }

    static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }

    /** Every loaded script's module that is switched on, for the dispatcher. */
    List<ScriptEntry> running() {
        return this.running;
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
