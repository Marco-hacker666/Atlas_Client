package atlas.ui.impl.clickgui.atlas;

import atlas.config.Config;
import atlas.module.modules.BackTrack;
import atlas.module.modules.BlockHit;
import atlas.module.modules.Speed;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** The menu's risky-value notes (2026-10-23, GUI redesign M4, owner D4). */
public class RiskRulesTest {

    @After
    public void notLoading() {
        Config.loading = false;
    }

    @Test
    public void backtrackRangeAboveThreeIsFlagged() {
        FloatProperty range = new FloatProperty("range", 3.0F, 1.0F, 8.0F);
        assertNull(RiskRules.warning(BackTrack.class, range));
        range.setValue(3.1F);
        assertNotNull(RiskRules.warning(BackTrack.class, range));
        assertTrue(RiskRules.warning(BackTrack.class, range).contains("AttackRaytrace"));
        assertNull("another module's range is not Backtrack's", RiskRules.warning(Speed.class, range));
    }

    @Test
    public void autoBlockLagChanceCountsOnlyWhereItIsUsed() {
        ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Slinky", "Full"});
        PercentProperty chance = new PercentProperty("lag-chance", 0, () -> mode.getValue() == 0);
        assertNull(RiskRules.warning(BlockHit.class, chance));
        chance.setValue(20);
        assertNotNull(RiskRules.warning(BlockHit.class, chance));
        mode.setValue(1);
        assertNull("hidden outside Slinky, so not in use", RiskRules.warning(BlockHit.class, chance));
    }

    @Test
    public void rulesKnowTheirModules() {
        assertTrue(RiskRules.covers(BlockHit.class));
        assertTrue(RiskRules.covers(BackTrack.class));
        assertFalse(RiskRules.covers(Speed.class));
        assertNull(RiskRules.warning(null, null));
    }

    @Test
    public void everyRuleNamesARealModuleAndKey() throws Exception {
        java.util.Set<String> keys = atlas.config.ConfigKeysTest.declared();
        for (RiskRules.Rule rule : RiskRules.RULES) {
            assertTrue(rule.module + " " + rule.key, keys.contains(rule.module + " " + rule.key));
        }
    }
}
