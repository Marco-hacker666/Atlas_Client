package atlas.module;

import atlas.Atlas;
import atlas.module.modules.HUD;
import atlas.util.KeyBindUtil;

public abstract class Module {
    protected final String name;
    protected final String description;
    protected final boolean defaultEnabled;
    protected final int defaultKey;
    protected final boolean defaultHidden;
    /* volatile: packet handlers read it on the network thread (2026-10-30, audit). */
    protected volatile boolean enabled;
    protected int key;
    protected boolean hidden;

    public Module(String name, boolean enabled) {
        this(name, enabled, false, "");
    }

    public Module(String name, boolean enabled, boolean hidden) {
        this(name, enabled, hidden, "");
    }

    public Module(String name, boolean enabled, boolean hidden, String description) {
        this.name = name;
        this.description = description;
        this.enabled = this.defaultEnabled = enabled;
        this.key = this.defaultKey = 0;
        this.hidden = this.defaultHidden = hidden;
    }

    public String getName() {
        return this.name;
    }

    /* The description the menus show (ModuleDocs, 2026-10-04): a Chinese one
       written for every module, over the module's own, which most lacked. */
    private String shownDescription;
    private String shownDescriptionEn;

    /** In the menu's language (ModuleDocs.isEnglish): the docs' text, else the module's own. */
    public String getDescription() {
        String shown = ModuleDocs.isEnglish() ? this.shownDescriptionEn : this.shownDescription;
        return shown != null ? shown : this.description;
    }

    public void setShownDescription(String description) {
        this.shownDescription = description;
    }

    public void setShownDescriptionEn(String description) {
        this.shownDescriptionEn = description;
    }

    public String formatModule() {
        return String.format(
                "%s%s &r(%s&r)",
                this.key == 0 ? "" : String.format("&l[%s] &r", KeyBindUtil.getKeyName(this.key)),
                this.name,
                this.enabled ? "&a&lON" : "&c&lOFF"
        );
    }

    public String[] getSuffix() {
        return new String[0];
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    /*
     * 2026-10-11 (ModuleBundle): a host module whose mode hands the work to a
     * merged module stays on -- its key, its HUD entry -- but its own event
     * handlers pause while that mode is picked.
     */
    private java.util.function.BooleanSupplier pausedWhen;

    public void pauseWhen(java.util.function.BooleanSupplier condition) {
        this.pausedWhen = condition;
    }

    /** On, and not handing its work to a merged module right now. */
    public boolean isRunning() {
        return this.enabled && (this.pausedWhen == null || !this.pausedWhen.getAsBoolean());
    }

    public void setEnabled(boolean enabled) {
        /* 2026-10-30 (audit): Blink, Fly and LongJump switch themselves off from a packet handler, which runs on the
           network thread, so onDisabled (motion, timer, flushing held packets) raced the tick. From there the switch
           waits for the client thread. Only from there: the exit hook restores modules on its own thread on purpose,
           before the config is saved, when no client tick will run again. */
        if (Thread.currentThread().getName().startsWith("Netty")) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> this.setEnabled(enabled));
            return;
        }
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) {
                /* Left on if onEnabled throws: at the startup config load several touch mc.thePlayer before there
                   is one, and they are meant to be on once in a world (Config.loadNow). */
                this.onEnabled();
            } else {
                this.onDisabled();
            }
        }
    }

    public boolean toggle() {
        boolean enabled = !this.enabled;
        this.setEnabled(enabled);
        if (this.enabled == enabled) {
            if (((HUD) Atlas.moduleManager.modules.get(HUD.class)).toggleSound.getValue()) {
                Atlas.moduleManager.playSound();
            }

            // Add a transient in-game notification for toggles
            try {
                if (Atlas.notificationManager != null) {
                    String action = this.enabled ? "was toggled successfully" : "was untoggled successfully";
                    // green for enabled, red for disabled
                    int color = this.enabled ? 0x00FF00 : 0xFF0000;
                    Atlas.notificationManager.add(this.getName() + " " + action, color);
                }
            } catch (Exception ignored) {
            }
            /* What it now works against, said once (2026-10-23, Conflicts). */
            if (this.enabled) {
                atlas.management.Conflicts.warnOnEnable(this);
            }

            return true;
        } else {
            return false;
        }
    }

    public int getKey() {
        return this.key;
    }

    public void setKey(int integer) {
        this.key = integer;
    }

    public boolean isHidden() {
        return this.hidden;
    }

    public void setHidden(boolean boolean1) {
        this.hidden = boolean1;
    }

    public void onEnabled() {
    }

    public void onDisabled() {
    }

    public void verifyValue(String string) {
    }

    public boolean shouldKeepSprint() {
        return false;
    }
}
