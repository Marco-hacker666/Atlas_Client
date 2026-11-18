package atlas.management;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FpsGateTest {
    private static FpsGate gate() {
        return new FpsGate(100, 5, 115, 10);
    }

    private static void feed(FpsGate gate, int fps, int seconds) {
        for (int i = 0; i < seconds; i++) {
            gate.sample(fps);
        }
    }

    @Test
    public void aFastGameIsNeverSlow() {
        FpsGate gate = gate();
        feed(gate, 240, 60);
        assertFalse(gate.slow());
    }

    @Test
    public void fiveSecondsInARowUnderTheLowMarkAreSlow() {
        FpsGate gate = gate();
        feed(gate, 60, 4);
        assertFalse(gate.slow());
        gate.sample(99);
        assertTrue(gate.slow());
    }

    @Test
    public void oneGoodSecondStartsTheCountAgain() {
        FpsGate gate = gate();
        feed(gate, 60, 4);
        gate.sample(100);
        feed(gate, 60, 4);
        assertFalse(gate.slow());
        gate.sample(60);
        assertTrue(gate.slow());
    }

    @Test
    public void itTakesTenSecondsAtTheHighMarkToBeFineAgain() {
        FpsGate gate = gate();
        feed(gate, 60, 5);

        feed(gate, 110, 30);
        assertTrue(gate.slow());
        feed(gate, 115, 9);
        assertTrue(gate.slow());
        gate.sample(120);
        assertFalse(gate.slow());
    }

    @Test
    public void aDipWhileRecoveringStartsThatCountAgainToo() {
        FpsGate gate = gate();
        feed(gate, 60, 5);
        feed(gate, 200, 9);
        gate.sample(110);
        feed(gate, 200, 9);
        assertTrue(gate.slow());
        gate.sample(200);
        assertFalse(gate.slow());

        feed(gate, 60, 4);
        assertFalse(gate.slow());
    }
}
