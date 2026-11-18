package atlas.module.modules;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

public class VelocityJitterTest {
    @Test
    public void aReductionMovesByAtMostThreeHundredths() {
        Random random = new Random(1L);
        boolean varied = false;
        for (int i = 0; i < 1000; i++) {
            double share = Velocity.jittered(40, random.nextDouble());
            assertTrue(share >= 0.37 - 1e-9 && share <= 0.43 + 1e-9);
            varied |= Math.abs(share - 0.40) > 0.001;
        }
        assertTrue(varied);
    }

    @Test
    public void zeroStaysWithinZeroToThreeAndNeverNegative() {
        assertEquals(0.0, Velocity.jittered(0, 0.0), 0.0);
        assertEquals(0.03, Velocity.jittered(0, 1.0), 1e-9);
    }

    @Test
    public void aFullHitIsLeftWhole() {
        assertEquals(1.0, Velocity.jittered(100, 0.0), 0.0);
        assertEquals(1.0, Velocity.jittered(100, 0.99), 0.0);
    }

    @Test
    public void theEndsAreTheSetShare() {
        assertEquals(0.37, Velocity.jittered(40, 0.0), 1e-9);
        assertEquals(0.40, Velocity.jittered(40, 0.5), 1e-9);
    }
}
