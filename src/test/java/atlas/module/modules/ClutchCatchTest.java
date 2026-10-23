package atlas.module.modules;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/** Clutch: a jump straight up under keep-y, and stopping once caught (2026-10-23). */
public class ClutchCatchTest {

    /** A player's box (0.6 wide, 1.8 tall) with its feet at (x, y, z). */
    private static AxisAlignedBB player(double x, double y, double z) {
        return new AxisAlignedBB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    public void aJumpStraightUpIsPlain() {
        /* The first tick in the air after a jump in place, as the 11:49 log has it: v +0.00 +0.33 +0.00. */
        assertTrue(Clutch.isPlainJump(0.333, 0.0, 0.0, false));
        assertTrue(Clutch.isPlainJump(0.333, 0.03, -0.02, false));
    }

    @Test
    public void keysSpeedOrNoRiseAreNot() {
        assertFalse("a key held", Clutch.isPlainJump(0.333, 0.0, 0.0, true));
        assertFalse("a sprint jump", Clutch.isPlainJump(0.333, 0.25, 0.0, false));
        assertFalse("walking off an edge", Clutch.isPlainJump(-0.08, 0.0, 0.0, false));
        assertFalse("a hit's knock up", Clutch.isPlainJump(0.2, 0.0, 0.0, false));
    }

    @Test
    public void aBoxRestsOnACellUnderAnyCornerOfIt() {
        /* Feet on the top of y=88 cells, the box over four columns (a diagonal). */
        AxisAlignedBB box = player(5000.0, 89.0, 3998.0);
        Set<BlockPos> one = new HashSet<BlockPos>(Collections.singletonList(new BlockPos(4999, 88, 3997)));
        assertTrue(Clutch.restsOn(box, one));
        Set<BlockPos> other = new HashSet<BlockPos>(Collections.singletonList(new BlockPos(5000, 88, 3998)));
        assertTrue(Clutch.restsOn(box, other));
    }

    @Test
    public void theLandingCellIsTheColumnUnderTheMiddle() {
        AxisAlignedBB box = player(182.11, 83.0, 226.64);
        assertTrue(Clutch.isUnderMiddle(box, new BlockPos(182, 82, 226)));
        assertFalse("the box overlaps x=181 too, but its middle is not there",
                Clutch.isUnderMiddle(box, new BlockPos(181, 82, 226)));
        assertFalse(Clutch.isUnderMiddle(box, new BlockPos(182, 82, 227)));
    }

    @Test
    public void notOnACellBesideItOrBelowIt() {
        AxisAlignedBB box = player(5000.5, 89.0, 3998.5);
        Set<BlockPos> beside = new HashSet<BlockPos>(Arrays.asList(new BlockPos(5001, 88, 3998),
                new BlockPos(5000, 87, 3998), new BlockPos(5000, 89, 3998)));
        assertFalse(Clutch.restsOn(box, beside));
        assertFalse(Clutch.restsOn(box, Collections.<BlockPos>emptySet()));
        assertFalse(Clutch.restsOn(null, beside));
    }
}
