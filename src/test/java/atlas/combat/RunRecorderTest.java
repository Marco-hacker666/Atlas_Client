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
        /* The last cell (engineErr) is empty with no simulation: keep it in the count. */
        String[] cells = first.split(",", -1);
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

    @Test
    public void theSimulationsAnswerGetsItsOwnColumn() {
        final java.util.List<String> rows = new java.util.ArrayList<String>();
        RunRecorder recorder = new RunRecorder(rows::add);
        /* A player going 0.2 a tick along x. The simulation is handed a path 0.1 off to the side. */
        double[][] engine = new double[RunRecorder.LENGTH][];
        for (int i = 0; i < engine.length; i++) {
            engine[i] = new double[]{0.2 * (double) (i + 1), 0.1};
        }
        assertTrue(recorder.samples(0));
        assertFalse(recorder.samples(3));
        recorder.observe(0, 4, "e4", 0.0, 0.0, 0.0, 0.0, true, 0.2, 0.0, -90.0, 0.0, 50.0, true, false, engine);
        recorder.observe(5, 4, "e4", 1.0, 0.0, 1.0, 0.0, true, 0.2, 0.0, -90.0, 0.0, 50.0, true, false, null);
        assertEquals(1, rows.size());
        String[] cells = rows.get(0).split(",", -1);
        assertEquals(RunRecorder.HEADER.split(",").length, cells.length);
        assertEquals("0.100", cells[cells.length - 1]);
        assertTrue(recorder.summary(), recorder.summary().contains(", engine 0.10 (n 1)"));
    }

    @Test
    public void aRowOfAnOlderHeaderGetsItsEmptyCells() {
        String old = "40,e7,5,3.20,0.280,0.100,0,1,1,1,0.050,0.090";
        assertEquals(old + ",", RunRecorder.padded(old));
        assertEquals(RunRecorder.HEADER.split(",").length, RunRecorder.padded(old).split(",", -1).length);
        String now = old + ",0.040";
        assertEquals(now, RunRecorder.padded(now));
        assertEquals(old + ",", RunRecorder.padded(old + ","));
    }
}
