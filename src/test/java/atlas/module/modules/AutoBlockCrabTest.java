package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoBlockCrabTest {

    @Test
    public void wholeTimesAreExact() {
        assertEquals(5, AutoBlockCrab.ticks(5.0F, 0.0F));
        assertEquals(5, AutoBlockCrab.ticks(5.0F, 0.999F));
    }

    @Test
    public void aFractionIsAChanceOfOneMoreTick() {
        assertEquals(3, AutoBlockCrab.ticks(2.4F, 0.3F));
        assertEquals(2, AutoBlockCrab.ticks(2.4F, 0.5F));
    }

    @Test
    public void nothingBelowZero() {
        assertEquals(0, AutoBlockCrab.ticks(0.0F, 0.0F));
        assertEquals(0, AutoBlockCrab.ticks(-3.0F, 0.0F));
        assertEquals(0, AutoBlockCrab.ticks(Float.NaN, 0.0F));
    }
}
