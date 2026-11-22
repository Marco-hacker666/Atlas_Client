package atlas.module.modules;

import net.minecraft.util.AxisAlignedBB;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class EdgeGuardPlanTest {
    private static final class Blocks implements EdgeGuardPlan.World {
        final Set<String> cells = new HashSet<String>();

        Blocks fill(int x0, int z0, int x1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    this.cells.add(x + "," + z);
                }
            }
            return this;
        }

        @Override
        public List<AxisAlignedBB> boxes(AxisAlignedBB r) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            if (r.minY > 64.0 || r.maxY < 63.0) {
                return out;
            }
            for (int x = (int) Math.floor(r.minX); x <= (int) Math.floor(r.maxX); x++) {
                for (int z = (int) Math.floor(r.minZ); z <= (int) Math.floor(r.maxZ); z++) {
                    if (this.cells.contains(x + "," + z)) {
                        out.add(new AxisAlignedBB(x, 63, z, x + 1, 64, z + 1));
                    }
                }
            }
            return out;
        }
    }

    private static AxisAlignedBB feet(double x, double z) {
        return new AxisAlignedBB(x - 0.3, 64.0, z - 0.3, x + 0.3, 65.8, z + 0.3);
    }

    private static EdgeGuardPlan.Settings settings(boolean blockRescue) {
        EdgeGuardPlan.Settings s = new EdgeGuardPlan.Settings();
        s.maxTicks = 4;
        s.blockRescue = blockRescue;
        return s;
    }

    @Test
    public void middleOfAnIslandIsLeftAlone() {
        Blocks island = new Blocks().fill(-4, -4, 4, 4);
        EdgeGuardPlan.Decision d = EdgeGuardPlan.decide(island, feet(0.5, 0.5), 0.9, 0.43, 0.0, 0.0F, settings(true));
        assertEquals(EdgeGuardPlan.Kind.RELEASE, d.kind);
    }

    @Test
    public void nearTheEdgeAPlainHitIsWalkedOrSteeredAway() {
        Blocks island = new Blocks().fill(-8, -4, 4, 4);
        EdgeGuardPlan.Settings s = settings(false);
        assertFalse(EdgeGuardPlan.fall(island, feet(4.4, 0.5), 0.4, 0.36, 0.0, Float.NaN, s.maxDrop).safe);
        EdgeGuardPlan.Decision d = EdgeGuardPlan.decide(island, feet(4.4, 0.5), 0.4, 0.36, 0.0, 90.0F, s);
        assertNotEquals(EdgeGuardPlan.Kind.GIVE_UP, d.kind);

        assertTrue("walks inward, got " + d.yaw, Math.abs(EdgeGuardPlan.wrap(d.yaw - 90.0F)) <= 45.0F);
    }

    @Test
    public void walkNeverStepsOffTheEdgeItself() {
        Blocks island = new Blocks().fill(-8, -4, 4, 4);
        assertNull(EdgeGuardPlan.walk(island, feet(4.6, 0.5), 270.0F, 4, true));
        assertNotNull(EdgeGuardPlan.walk(island, feet(4.6, 0.5), 90.0F, 4, true));
    }

    @Test
    public void oneWideBridgeHitSidewaysHoldsForABlock() {
        Blocks bridge = new Blocks().fill(0, -20, 0, 20);
        EdgeGuardPlan.Decision d = EdgeGuardPlan.decide(bridge, feet(0.5, 0.5), 0.9, 0.43, 0.0, 0.0F, settings(true));
        assertEquals(EdgeGuardPlan.Kind.HOLD, d.kind);
        assertTrue(d.standStill());
        EdgeGuardPlan.Decision none = EdgeGuardPlan.decide(bridge, feet(0.5, 0.5), 0.9, 0.43, 0.0, 0.0F,
                settings(false));
        assertEquals(EdgeGuardPlan.Kind.GIVE_UP, none.kind);
    }

    @Test
    public void aBlockBesideTheBridgeMakesItSafe() {
        Blocks bridge = new Blocks().fill(0, -20, 0, 20).fill(1, 0, 5, 0);
        assertTrue(EdgeGuardPlan.safeNow(bridge, feet(0.5, 0.5), 0.9, 0.43, 0.0, Float.NaN, settings(true)));
    }

    @Test
    public void oneWideBridgeHitAlongItLands() {
        Blocks bridge = new Blocks().fill(0, -20, 0, 20);
        EdgeGuardPlan.Decision d = EdgeGuardPlan.decide(bridge, feet(0.5, 0.5), 0.0, 0.43, 0.9, 0.0F, settings(true));
        assertEquals(EdgeGuardPlan.Kind.RELEASE, d.kind);
    }

    @Test
    public void groundFrictionOnTheFirstTickShortensThePush() {
        Blocks far = new Blocks().fill(0, -2, 0, 2).fill(5, -2, 9, 2);
        EdgeGuardPlan.Fall f = EdgeGuardPlan.fall(far, feet(0.5, 0.5), 0.9, 0.43, 0.0, Float.NaN, 6.0);
        assertTrue(f.safe);
    }

    @Test
    public void budgetFollowsPing() {
        assertEquals(4, EdgeGuardPlan.budgetTicks(50, 300, 4));
        assertEquals(2, EdgeGuardPlan.budgetTicks(180, 300, 4));
        assertEquals(0, EdgeGuardPlan.budgetTicks(320, 300, 4));
    }
}
