package myau.module.modules;

import myau.module.Module;

/** Merged module (2026-10-11): its parts are ModuleBundle fields below. */
public class BlockESP extends Module {

    public BlockESP() {
        super("BlockESP", false);
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final myau.module.ModuleBundle chestMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.ChestESP(), "chest", "Chests", true);
    public final myau.module.ModuleBundle bedMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.BedESP(), "bed", "Beds", true);
    public final myau.module.ModuleBundle blocksMerged = myau.module.ModuleBundle.withSwitch(this, new myau.module.modules.BlocksESP(), "blocks", "Custom blocks", false);
}
