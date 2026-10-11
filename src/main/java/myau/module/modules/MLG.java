package myau.module.modules;

import myau.module.Module;

/** Merged module (2026-10-11): its parts are ModuleBundle fields below. */
public class MLG extends Module {

    public MLG() {
        super("MLG", false);
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final myau.module.ModuleBundle waterMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.WaterClutch(), "water", "Water", true);
    public final myau.module.ModuleBundle ladderMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.LadderClutch(), "ladder", "Ladder", true);
    public final myau.module.ModuleBundle blockLadderMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.BlockLadder(), "block-ladder", "Ladder prediction", false);
}
