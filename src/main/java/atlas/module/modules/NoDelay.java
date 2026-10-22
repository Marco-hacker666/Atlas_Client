package atlas.module.modules;

import atlas.module.Module;

/** Merged module (2026-10-11): its parts are ModuleBundle fields below. */
public class NoDelay extends Module {

    public NoDelay() {
        super("NoDelay", false);
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final atlas.module.ModuleBundle hitMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.NoHitDelay(), "hit", "Hit delay", true);
    public final atlas.module.ModuleBundle clickMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.NoClickDelay(), "click", "Click delay", true);
    public final atlas.module.ModuleBundle jumpMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.NoJumpDelay(), "jump", "Jump delay", true);
    public final atlas.module.ModuleBundle removerMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.DelayRemover(), "remover", "Delay remover", false);
}
