package atlas.module.modules;

import atlas.module.Module;

/** Merged module (2026-10-11): its parts are ModuleBundle fields below. */
public class AutoMessage extends Module {

    public AutoMessage() {
        super("AutoMessage", false);
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final atlas.module.ModuleBundle ggMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.AutoGG(), "gg", "After a game", true);
    public final atlas.module.ModuleBundle killMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.KillMessage(), "kill", "After a kill", false);
    public final atlas.module.ModuleBundle insultMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.KillSults(), "insult", "Kill taunts", false);
}
