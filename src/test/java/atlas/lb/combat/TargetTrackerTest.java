package atlas.lb.combat;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The LiquidBounce target tracker port (2026-10-24). */
public class TargetTrackerTest {

    private static TargetTracker.Candidate<String> c(String name, boolean player, float health, double dist, double cos, int hurt) {
        return new TargetTracker.Candidate<String>(name, player, !player, health, dist * dist, cos, hurt, 0);
    }

    @Test
    public void playersComeFirstThenThePriority() {
        TargetTracker<String> tracker = new TargetTracker<String>();
        tracker.priorities = new int[]{TargetTracker.PRIORITY_HEALTH};
        List<TargetTracker.Candidate<String>> sorted = tracker.targets(Arrays.asList(
                c("zombie", false, 2, 2, 1, 0), c("tank", true, 20, 2, 1, 0), c("weak", true, 5, 4, 1, 0)));
        assertEquals("weak", sorted.get(0).entity);
        assertEquals("tank", sorted.get(1).entity);
        assertEquals("zombie", sorted.get(2).entity);
        assertEquals(4.0, tracker.closestSquaredEnemyDistance(), 1e-9);
    }

    @Test
    public void fovHurtTimeAndRangeFilter() {
        TargetTracker<String> tracker = new TargetTracker<String>();
        tracker.fov = 60.0F;
        tracker.maxHurtTime = 5;
        tracker.range = 6.0F;
        List<TargetTracker.Candidate<String>> kept = tracker.targets(Arrays.asList(
                c("behind", true, 10, 3, -1, 0), c("hurt", true, 10, 3, 1, 8),
                c("far", true, 10, 7, 1, 0), c("ok", true, 10, 3, 0.9, 0)));
        assertEquals(1, kept.size());
        assertEquals("ok", kept.get(0).entity);
    }

    @Test
    public void distanceAndDirectionPriorities() {
        TargetTracker<String> tracker = new TargetTracker<String>();
        tracker.priorities = new int[]{TargetTracker.PRIORITY_DIRECTION, TargetTracker.PRIORITY_DISTANCE};
        List<TargetTracker.Candidate<String>> sorted = tracker.targets(Arrays.asList(
                c("aside", true, 10, 2, 0.5, 0), c("ahead-far", true, 10, 5, 0.99, 0), c("ahead-near", true, 10, 3, 0.99, 0)));
        assertEquals("ahead-near", sorted.get(0).entity);
        assertEquals("ahead-far", sorted.get(1).entity);
        assertEquals("aside", sorted.get(2).entity);
    }
}
