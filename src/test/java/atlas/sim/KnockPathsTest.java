package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KnockPathsTest {

    private static final class Ground implements PlayerSim.World {
        final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();

        Ground box(double x1, double y1, double z1, double x2, double y2, double z2) {
            this.solid.add(new AxisAlignedBB(x1, y1, z1, x2, y2, z2));
            return this;
        }

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            for (AxisAlignedBB b : this.solid) {
                if (b.intersectsWith(area)) {
                    out.add(b);
                }
            }
            return out;
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6F;
        }
    }

    private static PlayerSim standing(double x, double y, double z) {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
        sim.onGround = true;
        return sim;
    }

    private static PlayerSim.Input[] responses(float backYaw) {
        PlayerSim.Input none = new PlayerSim.Input();
        PlayerSim.Input back = new PlayerSim.Input();
        back.yaw = backYaw;
        back.forward = 1.0F;
        return new PlayerSim.Input[]{none, back};
    }

    @Test
    public void theYawPartIsHalfABlockPerLevelAlongTheLook() {
        double[] v = KnockPaths.knock(0.0, -1.0, 0.0, 0.0, 0.0F, 1);
        assertEquals(0.0, v[0], 1.0E-9);
        assertEquals(0.4 + 0.5, v[2], 1.0E-9);
        assertEquals(0.5, v[1], 1.0E-9);
        double[] w = KnockPaths.knock(0.0, -1.0, 0.0, 0.0, 90.0F, 0);
        assertEquals("no sprint, no enchant: the look plays no part", 0.4, w[2], 1.0E-9);
        assertEquals(0.0, w[0], 1.0E-9);
        assertEquals(0.4, w[1], 1.0E-9);
    }

    @Test
    public void theWindowHoldsOnlyLooksThatHitTheBox() {
        AxisAlignedBB box = new AxisAlignedBB(-0.3, 64.0, 1.7, 0.3, 65.8, 2.3);
        float[] yaws = KnockPaths.window(0.0, 65.62, 0.0, 10.0F, box, 3.0, 0.0F, 40.0F, 41);
        assertTrue(yaws.length > 3);
        for (float yaw : yaws) {
            double t = KnockPaths.hitDistance(0.0, 65.62, 0.0, yaw, 10.0F, box);
            assertFalse(Double.isNaN(t));
            assertTrue(t <= 3.0);
            assertTrue("within the box's width: " + yaw, Math.abs(yaw) < 15.0F);
        }
        assertEquals(0, KnockPaths.window(0.0, 65.62, 0.0, 10.0F, box, 3.0, 180.0F, 20.0F, 21).length);
    }

    @Test
    public void atAnEdgeTheLookTurnsTheKnockOffIt() {
        Ground ground = new Ground().box(-10.0, 63.0, -10.0, 10.0, 64.0, 0.0);
        PlayerSim target = standing(0.0, 64.0, -0.5);
        double ax = -1.2;
        double az = -0.5;
        AxisAlignedBB box = target.box;
        float aim = -90.0F;
        float[] yaws = KnockPaths.window(ax, 65.62, az, 15.0F, box, 3.0, aim, 45.0F, 31);
        assertTrue(yaws.length > 3);
        KnockPaths.Choice c = KnockPaths.best(ground, target, ax, az, aim, yaws, 1, responses(90.0F), 14);
        assertFalse("straight on it stays on the island", c.baseScore >= KnockPaths.VOID_SCORE);
        assertTrue("turned it goes off: " + c.yaw + " " + c.score, c.lethal);
        assertTrue("turned toward the void side (+z): " + c.yaw, c.yaw > aim);
        double t = KnockPaths.hitDistance(ax, 65.62, az, c.yaw, 15.0F, box);
        assertTrue("and the look still hits", !Double.isNaN(t) && t <= 3.0);
    }

    @Test
    public void withNoYawPartOrNoDropTheAimIsKept() {
        Ground ground = new Ground().box(-10.0, 63.0, -10.0, 10.0, 64.0, 0.0);
        PlayerSim target = standing(0.0, 64.0, -0.5);
        float[] yaws = KnockPaths.window(-1.2, 65.62, -0.5, 15.0F, target.box, 3.0, -90.0F, 45.0F, 31);
        KnockPaths.Choice noLevel = KnockPaths.best(ground, target, -1.2, -0.5, -90.0F, yaws, 0, responses(90.0F), 14);
        assertEquals(-90.0F, noLevel.yaw, 0.0F);
        Ground floor = new Ground().box(-30.0, 63.0, -30.0, 30.0, 64.0, 30.0);
        PlayerSim middle = standing(0.0, 64.0, 0.0);
        float[] around = KnockPaths.window(-1.2, 65.62, 0.0, 15.0F, middle.box, 3.0, -90.0F, 45.0F, 31);
        KnockPaths.Choice flat = KnockPaths.best(floor, middle, -1.2, 0.0, -90.0F, around, 1, responses(90.0F), 14);
        assertEquals("flat ground: nothing to gain, nothing turned", -90.0F, flat.yaw, 0.0F);
        assertFalse(flat.lethal);
    }
}
