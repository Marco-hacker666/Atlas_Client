package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InvulnBlinkPlanTest {
    private static InvulnBlinkPlan.Input hit(int ping) {
        InvulnBlinkPlan.Input in = new InvulnBlinkPlan.Input();
        in.freshHit = true;
        in.enemyNear = true;
        in.pingMs = ping;
        return in;
    }

    private static InvulnBlinkPlan.Input holding() {
        InvulnBlinkPlan.Input in = hit(50);
        in.freshHit = false;
        return in;
    }

    @Test
    public void theHoldAlwaysEndsBeforeTheImmunityAtTheServer() {
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        s.maxTicks = 9;
        s.margin = 0;
        for (int ping = 0; ping <= 1000; ping += 10) {
            int b = InvulnBlinkPlan.budget(ping, s);
            int travel = (ping + 49) / 50;
            assertTrue("ping " + ping + " budget " + b, b == 0 || b + travel + 1 <= InvulnBlinkPlan.IMMUNE_TICKS);
            assertTrue(b >= 0);
        }
        InvulnBlinkPlan.Settings d = new InvulnBlinkPlan.Settings();
        assertEquals(8, InvulnBlinkPlan.budget(0, d));
        assertEquals(6, InvulnBlinkPlan.budget(100, d));
        assertEquals(0, InvulnBlinkPlan.budget(500, d));
    }

    @Test
    public void startsOnAHitHoldsThenLetsGoInTime() {
        InvulnBlinkPlan plan = new InvulnBlinkPlan();
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        assertEquals(InvulnBlinkPlan.Action.START, plan.tick(hit(100), s));
        int budget = plan.budget();
        int holds = 0;
        InvulnBlinkPlan.Action a;
        do {
            a = plan.tick(holding(), s);
            if (a == InvulnBlinkPlan.Action.HOLD) {
                holds++;
            }
        } while (a == InvulnBlinkPlan.Action.HOLD && holds < 100);
        assertEquals(InvulnBlinkPlan.Action.RELEASE, a);
        assertEquals(budget - 1, holds);
        assertFalse(plan.holding());
    }

    @Test
    public void everyWayOutOfAHoldLetsGo() {
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        s.cooldown = 0;
        String[] ways = {"unsafe", "no enemy", "hit"};
        for (String way : ways) {
            InvulnBlinkPlan plan = new InvulnBlinkPlan();
            assertEquals(InvulnBlinkPlan.Action.START, plan.tick(hit(0), s));
            InvulnBlinkPlan.Input in = holding();
            in.safe = !way.equals("unsafe");
            in.enemyNear = !way.equals("no enemy");
            in.attacked = way.equals("hit");
            assertEquals(way, InvulnBlinkPlan.Action.RELEASE, plan.tick(in, s));
            assertFalse(plan.holding());
            assertEquals(way, plan.why());
        }
    }

    @Test
    public void aHoldTakenAwayIsNotReleasedAgain() {
        InvulnBlinkPlan plan = new InvulnBlinkPlan();
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        plan.tick(hit(0), s);
        InvulnBlinkPlan.Input in = holding();
        in.stillHeld = false;
        assertEquals(InvulnBlinkPlan.Action.NONE, plan.tick(in, s));
        assertFalse(plan.holding());
    }

    @Test
    public void noStartWithoutAHitAnEnemyRoomOrPermission() {
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        InvulnBlinkPlan.Input noHit = hit(0);
        noHit.freshHit = false;
        InvulnBlinkPlan.Input alone = hit(0);
        alone.enemyNear = false;
        InvulnBlinkPlan.Input voidBelow = hit(0);
        voidBelow.safe = false;
        InvulnBlinkPlan.Input busy = hit(0);
        busy.mayHold = false;
        for (InvulnBlinkPlan.Input in : new InvulnBlinkPlan.Input[]{noHit, alone, voidBelow, busy, hit(400)}) {
            InvulnBlinkPlan plan = new InvulnBlinkPlan();
            assertEquals(InvulnBlinkPlan.Action.NONE, plan.tick(in, s));
            assertFalse(plan.holding());
        }
    }

    @Test
    public void theCooldownKeepsTheNextHitFromStartingAtOnce() {
        InvulnBlinkPlan plan = new InvulnBlinkPlan();
        InvulnBlinkPlan.Settings s = new InvulnBlinkPlan.Settings();
        s.cooldown = 3;
        plan.tick(hit(0), s);
        InvulnBlinkPlan.Input in = holding();
        in.attacked = true;
        assertEquals(InvulnBlinkPlan.Action.RELEASE, plan.tick(in, s));
        assertEquals(InvulnBlinkPlan.Action.NONE, plan.tick(hit(0), s));
        assertEquals(InvulnBlinkPlan.Action.NONE, plan.tick(hit(0), s));
        assertEquals(InvulnBlinkPlan.Action.START, plan.tick(hit(0), s));
    }
}
