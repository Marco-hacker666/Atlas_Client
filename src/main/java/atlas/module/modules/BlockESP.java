package atlas.module.modules;

import atlas.module.Module;

public class BlockESP extends Module {
    public BlockESP() {
        super("BlockESP", false);
    }

    public final atlas.module.ModuleBundle chestMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.ChestESP(), "chest", "Chests", true);
    public final atlas.module.ModuleBundle bedMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.BedESP(), "bed", "Beds", true);
    public final atlas.module.ModuleBundle blocksMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.BlocksESP(), "blocks", "Custom blocks", false);
}
