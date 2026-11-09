package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AutoBlockSlinkyTest {

    @Test
    public void wholeTimesAreExact() {
        assertEquals(5, AutoBlockSlinky.ticks(5.0F, 0.0F));
        assertEquals(5, AutoBlockSlinky.ticks(5.0F, 0.999F));
    }

    @Test
    public void aFractionIsAChanceOfOneMoreTick() {
        assertEquals(3, AutoBlockSlinky.ticks(2.4F, 0.3F));
        assertEquals(2, AutoBlockSlinky.ticks(2.4F, 0.5F));
    }

    @Test
    public void nothingBelowZero() {
        assertEquals(0, AutoBlockSlinky.ticks(0.0F, 0.0F));
        assertEquals(0, AutoBlockSlinky.ticks(-3.0F, 0.0F));
        assertEquals(0, AutoBlockSlinky.ticks(Float.NaN, 0.0F));
    }
}
