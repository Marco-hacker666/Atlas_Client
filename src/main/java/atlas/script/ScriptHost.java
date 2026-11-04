package atlas.script;

import atlas.module.Module;

/**
 * A script's module (2026-11-02). Every script compiles a subclass of its own next to it (ScriptSource): the menu,
 * the config and the key handler look modules and their settings up by class, so two scripts cannot share one.
 * It keeps a key, a place in the HUD list and its settings in profiles like any module; switching it on and off
 * calls the script's onEnable and onDisable. Its hooks are called by ScriptDispatcher, not by event handlers of
 * its own (the event bus only reads a class's own methods, and these subclasses have none).
 */
public abstract class ScriptHost extends Module {
    ScriptEntry entry;

    protected ScriptHost(String name, String description) {
        super(name, false, false, description);
    }

    public ScriptEntry entry() {
        return this.entry;
    }

    @Override
    public String getDescription() {
        return this.entry == null ? super.getDescription() : this.entry.describe();
    }

    @Override
    public void onEnabled() {
        ScriptRuntime runtime = this.entry == null ? null : this.entry.runtime;
        if (runtime != null) {
            runtime.call("onEnable");
        }
    }

    @Override
    public void onDisabled() {
        ScriptRuntime runtime = this.entry == null ? null : this.entry.runtime;
        if (runtime != null) {
            runtime.call("onDisable");
        }
    }

    @Override
    public String[] getSuffix() {
        ScriptRuntime runtime = this.entry == null ? null : this.entry.runtime;
        String suffix = runtime == null ? null : runtime.suffix();
        return suffix == null || suffix.isEmpty() ? new String[0] : new String[]{suffix};
    }

    /**
     * The menu's row for a script with no module: still compiling, or it did not load. Never registered, so its
     * shared class does not matter; it cannot be switched on.
     */
    public static final class Placeholder extends ScriptHost {
        Placeholder(String name, ScriptEntry entry) {
            super(name, "");
            this.entry = entry;
        }

        @Override
        public void setEnabled(boolean enabled) {
            if (enabled && this.entry != null) {
                atlas.util.ChatUtil.sendFormatted("&7[&bScripts&7] &c" + this.entry.name + " did not load"
                        + (this.entry.problems.isEmpty() ? "" : ": " + this.entry.problems.get(0)) + "&r");
            }
        }

        @Override
        public void onEnabled() {
        }

        @Override
        public void onDisabled() {
        }
    }
}
