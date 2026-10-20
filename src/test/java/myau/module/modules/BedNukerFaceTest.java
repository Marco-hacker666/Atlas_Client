package myau.module.modules;

import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** BedNuker's dig face falls back to the target's side toward the eye (2026-10-20). */
public class BedNukerFaceTest {
    private static final BlockPos BLOCK = new BlockPos(10, 64, 10);

    @Test
    public void aboveIsUp() {
        assertEquals(EnumFacing.UP, BedNuker.facingToward(BLOCK, new Vec3(10.5, 66.6, 11.0)));
    }

    @Test
    public void besideIsTheSideFacingTheEye() {
        assertEquals(EnumFacing.EAST, BedNuker.facingToward(BLOCK, new Vec3(13.0, 65.0, 10.4)));
        assertEquals(EnumFacing.NORTH, BedNuker.facingToward(BLOCK, new Vec3(10.6, 65.0, 7.0)));
    }

    @Test
    public void belowIsDown() {
        assertEquals(EnumFacing.DOWN, BedNuker.facingToward(BLOCK, new Vec3(10.5, 60.0, 10.5)));
    }
}
