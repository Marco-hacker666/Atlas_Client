package atlas.management;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PerfGuardTest {
    private static PerfGuard.Second slow() {
        PerfGuard.Second s = new PerfGuard.Second();
        s.fps = 20;
        s.inWorld = true;
        s.effectsDrawn = true;
        s.frameLimit = 260;
        return s;
    }

    private static int secondsUntil(PerfGuard guard, PerfGuard.Second s, PerfGuard.Decision wanted, int max) {
        for (int i = 1; i <= max; i++) {
            if (guard.second(s) == wanted) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void goesLiteAfterFifteenSlowSecondsOnce() {
        PerfGuard guard = new PerfGuard();
        assertEquals(PerfGuard.LOW_FPS_SECONDS, secondsUntil(guard, slow(), PerfGuard.Decision.GO_LITE, 60));
        assertTrue(guard.wentLite());
        assertEquals(-1, secondsUntil(guard, slow(), PerfGuard.Decision.GO_LITE, 60));
    }

    @Test
    public void oneFastSecondStartsTheCountAgain() {
        PerfGuard guard = new PerfGuard();
        for (int i = 0; i < PerfGuard.LOW_FPS_SECONDS - 1; i++) {
            assertEquals(PerfGuard.Decision.NONE, guard.second(slow()));
        }
        PerfGuard.Second fast = slow();
        fast.fps = 90;
        guard.second(fast);
        assertEquals(PerfGuard.LOW_FPS_SECONDS, secondsUntil(guard, slow(), PerfGuard.Decision.GO_LITE, 60));
    }

    @Test
    public void slowSecondsThatAreNotOursDoNotCount() {
        PerfGuard.Second noEffects = slow();
        noEffects.effectsDrawn = false;
        PerfGuard.Second menu = slow();
        menu.screenOpen = true;
        PerfGuard.Second away = slow();
        away.windowActive = false;
        PerfGuard.Second capped = slow();
        capped.frameLimit = 30;
        PerfGuard.Second already = slow();
        already.liteAlready = true;
        for (PerfGuard.Second s : new PerfGuard.Second[]{noEffects, menu, away, capped, already}) {
            assertEquals(-1, secondsUntil(new PerfGuard(), s, PerfGuard.Decision.GO_LITE, 60));
        }
    }

    @Test
    public void warnsOnceWhenCollectingGarbageTakesTooMuchTime() {
        PerfGuard guard = new PerfGuard();
        PerfGuard.Second s = new PerfGuard.Second();
        s.fps = 100;
        s.inWorld = true;
        s.gcMillis = 200;
        assertEquals(PerfGuard.GC_SECONDS, secondsUntil(guard, s, PerfGuard.Decision.WARN_MEMORY, 100));
        assertEquals(-1, secondsUntil(guard, s, PerfGuard.Decision.WARN_MEMORY, 100));
    }

    @Test
    public void normalGarbageCollectionDoesNotWarn() {
        PerfGuard.Second s = new PerfGuard.Second();
        s.fps = 100;
        s.inWorld = true;
        s.gcMillis = 40;
        assertEquals(-1, secondsUntil(new PerfGuard(), s, PerfGuard.Decision.WARN_MEMORY, 200));
    }
}
