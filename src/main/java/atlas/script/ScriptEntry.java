package atlas.script;

import atlas.property.Property;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One script file and what became of it: its module when it loaded, its problems when it did not (2026-11-02).
 * Changed on the client thread only; the dispatcher reads {@link #runtime} and {@link #host} from any thread.
 */
public final class ScriptEntry {
    public final String name;
    public final ScriptKind kind;
    public final File file;

    /** What the file was when it was last compiled, to see it change. */
    long lastModified;
    long length;
    String hash = "";

    volatile ScriptHost host;
    volatile ScriptRuntime runtime;
    ScriptLoader loader;
    /** The compiled script class (its name), and the lines the wrapper put before the user's. */
    String scriptClass = "";
    int headerLines;
    /** The menu's row while there is no real module (not compiled yet, or failed). */
    ScriptHost.Placeholder placeholder;

    /** Compile or load problems, one per line; empty when it loaded. */
    volatile List<String> problems = Collections.emptyList();
    volatile boolean compiling;
    /** The last error a hook threw, and how many have been thrown. */
    volatile String runtimeError;
    volatile int runtimeErrors;
    volatile long lastErrorShown;

    /** Its settings, in menu order: the same list the property manager holds for its module. */
    final ArrayList<Property<?>> properties = new ArrayList<Property<?>>();
    /** The heading new settings go under (Raven's registerGroup / registerDescription). */
    String group;
    /** Raven slider steps by setting key, applied when the value is read. */
    final Map<String, Double> steps = new HashMap<String, Double>();
    /** Raven colours' alpha settings by colour key. */
    final Map<String, Property<?>> alphas = new HashMap<String, Property<?>>();

    ScriptEntry(String name, ScriptKind kind, File file) {
        this.name = name;
        this.kind = kind;
        this.file = file;
    }

    /** Its module in the menu: the loaded one, or a placeholder carrying the problem. */
    public atlas.module.Module module() {
        ScriptHost loaded = this.host;
        if (loaded != null) {
            return loaded;
        }
        if (this.placeholder == null) {
            this.placeholder = new ScriptHost.Placeholder(this.name, this);
        }
        return this.placeholder;
    }

    public boolean isLoaded() {
        return this.host != null;
    }

    public boolean isCompiling() {
        return this.compiling;
    }

    public List<String> problems() {
        return this.problems;
    }

    public String runtimeError() {
        return this.runtimeError;
    }

    /** The file as shown: relative to the scripts folder. */
    public String shownPath() {
        String folder = this.file.getAbsoluteFile().getParentFile().getName();
        return (this.kind == ScriptKind.ATLAS ? "" : folder + "/") + this.file.getName();
    }

    /** What the menu says about it, under its name. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append(this.kind.label).append(" script  ·  ").append(this.shownPath());
        if (this.compiling) {
            out.append("\nCompiling ...");
        } else if (!this.problems.isEmpty()) {
            out.append(this.isLoaded() ? "\nThe last save did not compile (the version before it is running):"
                    : "\nDid not load:");
            for (String problem : this.problems) {
                out.append("\n").append(problem);
            }
        } else if (this.runtimeError != null) {
            out.append("\nLast error (").append(this.runtimeErrors).append("x): ").append(this.runtimeError);
        }
        return out.toString();
    }
}
