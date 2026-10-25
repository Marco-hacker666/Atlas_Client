package atlas.lb.combat;

import atlas.lb.render.AuraRender;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The LiquidBounce FightBot's movement planning, and the indicator colors (2026-10-25). */
public class FightBotPlannerTest {

    @Test
    public void yawsFollowMinecraft() {
        assertEquals(0.0F, FightBotPlanner.yawTo(0, 0, 0, 5), 1e-4); // +z
        assertEquals(90.0F, FightBotPlanner.yawTo(0, 0, -5, 0), 1e-4); // -x
        double[] d = FightBotPlanner.direction(90.0F);
        assertEquals(-1.0, d[0], 1e-9);
        assertEquals(0.0, d[1], 1e-9);
    }

    @Test
    public void walkKeysPointAtTheGoal() {
        assertArrayEquals(new int[]{1, 0}, FightBotPlanner.directionalInput(0, 5, 0.0F, 20.0F));
        assertArrayEquals(new int[]{-1, 0}, FightBotPlanner.directionalInput(0, -5, 0.0F, 20.0F));
        assertArrayEquals(new int[]{0, -1}, FightBotPlanner.directionalInput(-5, 0, 0.0F, 20.0F)); // right
        assertArrayEquals(new int[]{0, 1}, FightBotPlanner.directionalInput(5, 0, 0.0F, 20.0F)); // left
        assertArrayEquals(new int[]{1, 1}, FightBotPlanner.directionalInput(5, 5, 0.0F, 20.0F)); // forward-left
        assertArrayEquals(new int[]{0, 0}, FightBotPlanner.directionalInput(0, 0, 0.0F, 20.0F));
    }

    @Test
    public void movementYawOfTheKeys() {
        assertEquals(0.0F, FightBotPlanner.movementYaw(0.0F, 1, 0), 1e-4);
        assertEquals(-90.0F, FightBotPlanner.movementYaw(0.0F, 0, 1), 1e-4); // left
        assertEquals(180.0F, Math.abs(FightBotPlanner.movementYaw(0.0F, -1, 0)), 1e-4);
    }

    @Test
    public void theAttackSpotAvoidsWhereTheTargetLooks() {
        // Target at the origin looking +z (yaw 0), player straight ahead of it at +z.
        double[] spot = FightBotPlanner.attackPosition(0, 0, 0.0F, 3.0, 55.0F, 0, 6, (x, z) -> false);
        float yaw = FightBotPlanner.yawTo(0, 0, spot[0], spot[1]);
        assertTrue("spot at yaw " + yaw, Math.abs(FightBotPlanner.wrap(yaw)) > 55.0F);
        assertEquals(3.0, Math.hypot(spot[0], spot[1]), 1e-6);
        // Everything blocked: the spot along the target's look.
        double[] fallback = FightBotPlanner.attackPosition(0, 0, 0.0F, 3.0, 55.0F, 0, 6, (x, z) -> true);
        assertEquals(0.0, fallback[0], 1e-9);
        assertEquals(3.0, fallback[1], 1e-9);
    }

    @Test
    public void dangerIsTheTargetFacingUs() {
        assertFalse(FightBotPlanner.outOfDanger(0.0F, 10.0F, 55.0F));
        assertTrue(FightBotPlanner.outOfDanger(0.0F, 120.0F, 55.0F));
    }

    @Test
    public void runawayAndLeader() {
        double[] away = FightBotPlanner.runawayPosition(0, 0, 180.0F, 3.0);
        assertEquals(-3.0, away[1], 1e-9);
        double[] near = FightBotPlanner.leaderPosition(0, 0, 5.0, 0, 20);
        assertEquals(5.0, near[1], 1e-9);
    }

    @Test
    public void indicatorColorsBlend() {
        assertEquals(0x80FF0000, AuraRender.withAlpha(0xFF0000, 0x80));
        int mid = AuraRender.lerp(0xFF000000, 0xFFFFFFFF, 0.5F);
        assertEquals(0xFF808080, mid);
    }
}
