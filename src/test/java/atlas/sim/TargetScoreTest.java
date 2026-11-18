package atlas.sim;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TargetScoreTest {
    private static TargetForecast forecast(double x, double perMove, boolean simulated) {
        TargetForecast forecast = new TargetForecast(TargetScore.AHEAD);
        for (int i = 0; i <= TargetScore.AHEAD; i++) {
            forecast.x[i] = x + perMove * (double) i;
        }
        forecast.simulated = simulated;
        return forecast;
    }

    @Test
    public void aForecastIsHeldAgainstThePositionsThatCome() {
        TargetScore score = new TargetScore();
        assertEquals("nothing measured yet", score.summary());

        score.position(7, 10, 0.0, 0.0, forecast(0.0, 0.2, true), 0.3, 0.0);
        score.position(7, 12, 0.4, 0.0, null, 0.2, 0.0);
        assertEquals(1, score.count(2));
        assertEquals(0.0, score.engine(2), 1.0E-12);
        assertEquals(0.2, score.line(2), 1.0E-12);

        score.position(8, 13, 9.0, 9.0, null, 0.0, 0.0);
        assertEquals(0, score.count(3));
        score.position(7, 15, 1.1, 0.0, null, 0.2, 0.0);
        assertEquals(1, score.count(5));
        assertEquals(0.1, score.engine(5), 1.0E-12);
        assertEquals(0.4, score.line(5), 1.0E-12);

        score.position(7, 16, 1.2, 0.0, null, 0.2, 0.0);
        assertEquals(0, score.count(1) + score.count(3) + score.count(4));
        assertTrue(score.summary(), score.summary().startsWith("2t: 0.000 vs line 0.200 (n 1) | 5t: 0.100"));
        assertTrue(score.summary(), score.summary().endsWith("simulated 100% of 1 forecasts"));
    }

    @Test
    public void aForecastThatIsTheLineIsCountedAndNotCompared() {
        TargetScore score = new TargetScore();
        score.position(1, 0, 0.0, 0.0, forecast(0.0, 0.2, false), 0.2, 0.0);

        score.position(2, 0, 0.0, 0.0, forecast(0.0, 0.2, true), Double.NaN, Double.NaN);
        score.position(1, 2, 0.4, 0.0, forecast(0.4, 0.2, true), 0.2, 0.0);
        assertEquals(0, score.count(2));
        assertEquals("nothing compared yet; simulated 50% of 2 forecasts", score.summary());

        score.forget(1);
        score.position(1, 4, 0.8, 0.0, null, 0.2, 0.0);
        assertEquals(0, score.count(2));
        score.reset();
        assertEquals("nothing measured yet", score.summary());
    }
}
