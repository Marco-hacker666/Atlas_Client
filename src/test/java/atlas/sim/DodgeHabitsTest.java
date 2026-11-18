package atlas.sim;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DodgeHabitsTest {
    private static double[][] path(int ticks, int at, double vx, double vz) {
        double[][] out = new double[ticks][];
        double x = 0.0;
        double z = 0.0;
        for (int i = 0; i < ticks; i++) {
            if (i >= at) {
                x += vx;
                z += vz;
            }
            out[i] = new double[]{x, z};
        }
        return out;
    }

    @Test
    public void theReactionIsTheFirstTickOffThePath() {
        double[][] carry = path(20, 99, 0.0, 0.0);
        assertEquals(6, DodgeHabits.reactionTick(path(20, 4, 0.1, 0.0), carry));
        assertEquals(-1, DodgeHabits.reactionTick(carry, carry));
    }

    @Test
    public void theNearestWayIsWhatItDid() {
        double[][] carry = path(20, 99, 0.0, 0.0);
        double[][][] ways = new double[DodgeHabits.WAYS][][];
        for (int w = 0; w < ways.length; w++) {
            ways[w] = path(20, 5, 0.2 * Math.cos(w), 0.2 * Math.sin(w));
        }
        double[][] went = path(20, 5, 0.2 * Math.cos(3), 0.2 * Math.sin(3));
        assertEquals(3, DodgeHabits.classify(went, carry, ways, 5));
        assertEquals(-1, DodgeHabits.classify(carry, carry, ways, 5));
    }

    @Test
    public void aHabitWeighsItsWayMost() {
        DodgeHabits habits = new DodgeHabits();
        double[] unknown = habits.weights("a");
        assertEquals(unknown[0], unknown[DodgeHabits.WAYS], 1.0E-12);
        for (int i = 0; i < 5; i++) {
            habits.record("a", "Alice", 4.0, 1);
        }
        habits.record("a", "Alice", 6.0, -1);
        double[] w = habits.weights("a");
        double sum = 0.0;
        for (double v : w) {
            sum += v;
        }
        assertEquals(1.0, sum, 1.0E-9);
        for (int i = 0; i < w.length; i++) {
            if (i != 1) {
                assertTrue(w[1] > w[i]);
            }
        }
        assertEquals("left", habits.get("a").usual());
        double reaction = habits.reaction("a", 5.0);
        assertTrue(reaction > 4.0 && reaction < 6.0);
        assertEquals(5.0, habits.reaction("nobody", 5.0), 1.0E-12);
    }

    @Test
    public void onlyALateTurnIsAReaction() {
        assertTrue(Double.isNaN(DodgeHabits.reactionSample(0, 1)));
        assertTrue(Double.isNaN(DodgeHabits.reactionSample(16, -1)));
        assertTrue(Double.isNaN(DodgeHabits.reactionSample(-1, -1)));
        assertEquals(5.0, DodgeHabits.reactionSample(5, 2), 1.0E-12);
    }

    @Test
    public void itIsKeptAndReadBack() {
        DodgeHabits habits = new DodgeHabits();
        habits.record("uuid-1", "Bob", 3.0, 7);
        habits.record("uuid-1", "Bob", Double.NaN, -1);
        DodgeHabits read = new DodgeHabits();
        read.load(habits.save() + "garbage line\n");
        assertEquals("Bob", read.get("uuid-1").name);
        assertEquals(2, read.get("uuid-1").shots());
        assertEquals(1, read.get("uuid-1").counts[7]);
        assertEquals(3.0, read.get("uuid-1").reaction, 1.0E-9);
    }
}
