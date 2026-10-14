package myau.module.modules;

import myau.module.Module;

/** Merged module (2026-10-11): its parts are ModuleBundle fields below. */
public class NoDelay extends Module {

    public NoDelay() {
        super("NoDelay", false);
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final myau.module.ModuleBundle hitMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.NoHitDelay(), "hit", "Hit delay", true);
    public final myau.module.ModuleBundle clickMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.NoClickDelay(), "click", "Click delay", true);
    public final myau.module.ModuleBundle jumpMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.NoJumpDelay(), "jump", "Jump delay", true);
    public final myau.module.ModuleBundle removerMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.DelayRemover(), "remover", "Delay remover", false);
}
