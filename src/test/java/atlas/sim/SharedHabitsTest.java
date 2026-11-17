package atlas.sim;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SharedHabitsTest {

    @Test
    public void namesAreOneAndCrackedOnesApart() {
        assertEquals(SharedHabits.key("Steve", false), SharedHabits.key("steve", false));
        assertFalse(SharedHabits.key("Steve", false).equals(SharedHabits.key("Steve", true)));
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Steve".getBytes());
        assertTrue(SharedHabits.cracked(offline));
        assertFalse(SharedHabits.cracked(UUID.randomUUID()));
    }

    @Test
    public void everyonesCountsAndOursSinceAddUp() {
        SharedHabits shared = new SharedHabits();
        String key = SharedHabits.key("bob", false);
        assertNull(shared.weights(key));
        double[] counts = new double[DodgeHabits.WAYS + 1];
        counts[2] = 8.0;
        shared.fetched(key, counts, 40.0, 8.0);
        shared.saw(key, 3.0, 2);
        double[] w = shared.weights(key);
        double sum = 0.0;
        for (double v : w) {
            sum += v;
        }
        assertEquals(1.0, sum, 1.0E-9);
        for (int i = 0; i < w.length; i++) {
            if (i != 2) {
                assertTrue(w[2] > w[i]);
            }
        }
        assertEquals(43.0 / 9.0, shared.reaction(key, 5.0), 1.0E-9);
        assertEquals(9.0, shared.shots(key), 1.0E-9);
        /* A new answer from the relay already holds what we sent: ours since is dropped. */
        shared.fetched(key, counts, 40.0, 8.0);
        assertEquals(8.0, shared.shots(key), 1.0E-9);
    }
}
