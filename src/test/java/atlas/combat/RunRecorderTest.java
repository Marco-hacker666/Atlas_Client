package atlas.combat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** SpeedBlocker's run recorder settles each prediction at its horizons (2026-11-01). */
public class RunRecorderTest {

    @Test
    public void aStraightRunnerIsSettledAtEveryHorizon() {
        List<String> rows = new ArrayList<String>();
        RunRecorder recorder = new RunRecorder(rows::add);
        /* Runs along +x at 0.286 a tick, looking along +x (yaw -90); we are far off to +z, so it is not chasing. */
        double v = 0.2863;
        for (int t = 0; t <= 25; t++) {
            recorder.observe(t, 7, "runner", t * v, 0.0, v, 0.0, -90.0, 0.0, 100.0, true, true);
        }
        /* The prediction made at tick 0 (and the ones at 5 for 5, 10, 15, 20 and at 10 for 5, 10, 15) settled. */
        assertTrue(rows.size() >= 4);
        String first = rows.get(0);
        assertTrue(first, first.startsWith("0,runner,5,"));
        String[] cells = first.split(",");
        assertEquals(RunRecorder.HEADER.split(",").length, cells.length);
        /* A steady straight sprint: the model and the line both land within a few hundredths. */
        assertTrue(first, Double.parseDouble(cells[10]) < 0.05);
        assertTrue(first, Double.parseDouble(cells[11]) < 0.05);
        assertTrue(recorder.summary(), recorder.summary().startsWith("5t: "));
    }

    @Test
    public void standingPlayersAreNotSampledAndForgottenOnesNotSettled() {
        List<String> rows = new ArrayList<String>();
        RunRecorder recorder = new RunRecorder(rows::add);
        for (int t = 0; t <= 25; t++) {
            recorder.observe(t, 1, "still", 0.0, 0.0, 0.0, 0.0, 0.0, 5.0, 5.0, true, false);
        }
        assertTrue(rows.isEmpty());
        recorder.observe(0, 2, "gone", 0.0, 0.0, 0.2, 0.0, -90.0, 0.0, 50.0, true, false);
        recorder.forget(2);
        recorder.observe(5, 2, "gone", 1.0, 0.0, 0.2, 0.0, -90.0, 0.0, 50.0, true, false);
        assertTrue(rows.isEmpty());
        assertEquals("nothing settled yet", recorder.summary());
    }
}
