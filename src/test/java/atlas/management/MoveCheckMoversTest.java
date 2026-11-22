package atlas.management;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class MoveCheckMoversTest {
    @Test
    public void moduleNamesWithSpacesAreKeptWhole() {
        assertEquals(Arrays.asList("Silent Aura", "Sprint"), MoveCheck.movers("Silent Aura:rotation Sprint:sprint"));
        assertEquals(Arrays.asList("ghetto scaffold"), MoveCheck.movers("ghetto scaffold:motion,keys"));
        assertEquals(Arrays.asList("Velocity", "a b c"), MoveCheck.movers("Velocity:knockback a b c:x"));
    }

    @Test
    public void aStrayWordWithNoKindIsNotCounted() {
        assertEquals(Arrays.asList("Scaffold"), MoveCheck.movers("Scaffold:keys trailing"));
    }
}
