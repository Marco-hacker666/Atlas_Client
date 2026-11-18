package atlas.script;

import atlas.module.Module;

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
