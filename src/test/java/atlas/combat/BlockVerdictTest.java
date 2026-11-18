package atlas.combat;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BlockVerdictTest {
    private static final float[][] WOOD_ON_7 = {
            {3.6F, 3.6F}, {5.4F, 5.4F}, {2.16F, 2.16F}, {3.06F, 3.06F}};

    @Test
    public void fullHitsAndBlockedHitsAreToldApart() {
        assertEquals("FULL", BlockVerdict.classify(3.6F, WOOD_ON_7, 0.15F));
        assertEquals("FULL", BlockVerdict.classify(5.4F, WOOD_ON_7, 0.15F));
        assertEquals("REDUCED", BlockVerdict.classify(2.16F, WOOD_ON_7, 0.15F));
        assertEquals("REDUCED", BlockVerdict.classify(3.0F, WOOD_ON_7, 0.15F));
    }

    private static final float[][] WOOD_ON_7_PIKA = {
            {3.6F, 3.6F}, {5.4F, 5.4F}, {2.16F, 2.16F}, {3.06F, 3.06F}, {1.8F, 1.8F}, {2.7F, 2.7F}};

    @Test
    public void pikasHalfDamageBlockCounts() {
        assertEquals("REDUCED", BlockVerdict.classify(1.8F, WOOD_ON_7_PIKA, 0.15F));
        assertEquals("REDUCED", BlockVerdict.classify(2.7F, WOOD_ON_7_PIKA, 0.15F));
        assertEquals("FULL", BlockVerdict.classify(3.6F, WOOD_ON_7_PIKA, 0.15F));
        assertEquals("DIFF", BlockVerdict.classify(1.0F, WOOD_ON_7_PIKA, 0.15F));
    }

    @Test
    public void theRestIsNamed() {
        assertEquals("DIFF", BlockVerdict.classify(1.8F, WOOD_ON_7, 0.15F));
        assertEquals("OTHER", BlockVerdict.classify(4.5F, WOOD_ON_7, 0.15F));
    }
}
