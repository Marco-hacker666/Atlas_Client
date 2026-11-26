package atlas.util.render;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GpuStatsTest {
    private static Map<String, Long> frame(long frameNanos, Object... pairs) {
        Map<String, Long> out = new HashMap<String, Long>();
        out.put(GpuStats.FRAME, frameNanos);
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Long) pairs[i + 1]);
        }
        return out;
    }

    @Test
    public void averagesOverEveryFrameEvenWhereASectionDidNotRun() {
        GpuStats stats = new GpuStats();
        stats.frame(frame(10_000_000L, "Hotbar blur", 2_000_000L));
        stats.frame(frame(12_000_000L));
        assertEquals(2, stats.frames());
        assertEquals(11.0, stats.msPerFrame(GpuStats.FRAME), 1.0E-9);
        assertEquals(1.0, stats.msPerFrame("Hotbar blur"), 1.0E-9);
        assertEquals(0.0, stats.msPerFrame("missing"), 1.0E-9);
    }

    @Test
    public void sectionsComeMostExpensiveFirstWithoutTheFrame() {
        GpuStats stats = new GpuStats();
        stats.frame(frame(10_000_000L, "HUD list glow", 300_000L, "HUD list blur", 900_000L, "Atlas 2D", 1_500_000L));
        List<String> names = stats.sections();
        assertEquals("Atlas 2D", names.get(0));
        assertEquals("HUD list blur", names.get(1));
        assertEquals("HUD list glow", names.get(2));
        assertEquals(3, names.size());
        assertEquals(1.2, stats.msPerFrameEndingWith(" blur", " glow"), 1.0E-9);
    }

    @Test
    public void skippedFramesAreCountedButNotAveraged() {
        GpuStats stats = new GpuStats();
        stats.dropped();
        stats.frame(frame(8_000_000L));
        assertEquals(1, stats.frames());
        assertEquals(1, stats.droppedFrames());
        assertTrue(stats.line(5).contains("1 skipped"));
        assertTrue(stats.line(5).contains("frame 8.00"));
    }

    @Test
    public void copyIsIndependentAndResetEmpties() {
        GpuStats stats = new GpuStats();
        stats.frame(frame(5_000_000L, "Hotbar blur", 1_000_000L));
        GpuStats copy = stats.copy();
        stats.reset();
        assertEquals(0, stats.frames());
        assertEquals(1, copy.frames());
        assertEquals(1.0, copy.msPerFrame("Hotbar blur"), 1.0E-9);
        assertTrue(new GpuStats().line(5).startsWith("gpu: no frames"));
    }
}
