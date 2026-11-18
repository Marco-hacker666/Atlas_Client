package atlas.script;

import atlas.property.Property;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ScriptEntry {
    public final String name;
    public final ScriptKind kind;
    public final File file;

    long lastModified;
    long length;
    String hash = "";

    volatile ScriptHost host;
    volatile ScriptRuntime runtime;
    ScriptLoader loader;

    String scriptClass = "";
    int headerLines;

    ScriptHost.Placeholder placeholder;

    volatile List<String> problems = Collections.emptyList();
    volatile boolean compiling;

    volatile String runtimeError;
    volatile int runtimeErrors;
    volatile long lastErrorShown;

    final ArrayList<Property<?>> properties = new ArrayList<Property<?>>();

    String group;

    final Map<String, Double> steps = new HashMap<String, Double>();

    final Map<String, Property<?>> alphas = new HashMap<String, Property<?>>();

    ScriptEntry(String name, ScriptKind kind, File file) {
        this.name = name;
        this.kind = kind;
        this.file = file;
    }

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

    public String shownPath() {
        String folder = this.file.getAbsoluteFile().getParentFile().getName();
        return (this.kind == ScriptKind.ATLAS ? "" : folder + "/") + this.file.getName();
    }

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
