package atlas.combat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** SpeedBlocker's run recorder settles each prediction at its horizons, on fresh server updates (2026-11-02). */
public class RunRecorderTest {

    /** A sprint along +x, looking along +x, with the server sending a position every second tick. */
    private static void sprint(RunRecorder recorder, int id, int ticks) {
        double v = 0.2806;
        for (int t = 0; t <= ticks; t++) {
            boolean fresh = t % 2 == 0;
            double raw = (t / 2) * 2 * v;
            double now = t * v;
            recorder.observe(t, id, "e" + id, now, 0.0, raw, 0.0, fresh, v, 0.0, -90.0, 0.0, 100.0, true, true);
        }
    }

    @Test
    public void aStraightRunnerIsSettledOnFreshUpdates() {
        List<String> rows = new ArrayList<String>();
        RunRecorder recorder = new RunRecorder(rows::add);
        sprint(recorder, 7, 40);
        assertTrue(rows.size() >= 4);
        String first = rows.get(0);
        /* Sampled at tick 0; horizon 5 is settled at the first fresh tick past it, 6. */
        assertTrue(first, first.startsWith("0,e7,6,"));
        String[] cells = first.split(",");
        assertEquals(RunRecorder.HEADER.split(",").length, cells.length);
        /* A steady straight sprint at vanilla speed: the model and the line both land within a few hundredths. */
        assertTrue(first, Double.parseDouble(cells[10]) < 0.02);
        assertTrue(first, Double.parseDouble(cells[11]) < 0.02);
        for (String row : rows) {
            int horizon = Integer.parseInt(row.split(",")[2]);
            assertEquals(row, 0, horizon % 2);
        }
        assertTrue(recorder.summary(), recorder.summary().startsWith("5t: "));
    }

    @Test
    public void standingPlayersAreNotSampledAndForgottenOnesNotSettled() {
        List<String> rows = new ArrayList<String>();
        RunRecorder recorder = new RunRecorder(rows::add);
        for (int t = 0; t <= 25; t++) {
            recorder.observe(t, 1, "e1", 0.0, 0.0, 0.0, 0.0, true, 0.0, 0.0, 0.0, 5.0, 5.0, true, false);
        }
        assertTrue(rows.isEmpty());
        recorder.observe(0, 2, "e2", 0.0, 0.0, 0.0, 0.0, true, 0.2, 0.0, -90.0, 0.0, 50.0, true, false);
        recorder.forget(2);
        recorder.observe(10, 2, "e2", 2.0, 0.0, 2.0, 0.0, true, 0.2, 0.0, -90.0, 0.0, 50.0, true, false);
        assertTrue(rows.isEmpty());
        assertEquals("nothing settled yet", recorder.summary());
    }
}
