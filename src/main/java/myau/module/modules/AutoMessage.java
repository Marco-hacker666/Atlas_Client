package myau.module.modules;

import myau.module.Module;

/** Merged module (2026-10-11): its parts are ModuleBundle fields below. */
public class AutoMessage extends Module {

    public AutoMessage() {
        super("AutoMessage", false);
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final myau.module.ModuleBundle ggMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.AutoGG(), "gg", "After a game", true);
    public final myau.module.ModuleBundle killMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.KillMessage(), "kill", "After a kill", false);
    public final myau.module.ModuleBundle insultMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.KillSults(), "insult", "Kill taunts", false);
}
