package atlas.module.modules;

import atlas.module.Module;

public class MLG extends Module {
    public MLG() {
        super("MLG", false);
    }

    public final atlas.module.ModuleBundle waterMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.WaterClutch(), "water", "Water", true);
    public final atlas.module.ModuleBundle ladderMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.LadderClutch(), "ladder", "Ladder", true);
    public final atlas.module.ModuleBundle blockLadderMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.BlockLadder(), "block-ladder", "Ladder prediction", false);
}
