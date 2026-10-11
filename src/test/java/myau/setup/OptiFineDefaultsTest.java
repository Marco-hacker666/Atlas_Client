package myau.setup;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class OptiFineDefaultsTest {

    @Test
    public void replacesAnExistingValue() {
        List<String> out = OptiFineDefaults.withConnectedTexturesOff(
                Arrays.asList("ofFastRender:true", "ofConnectedTextures:2", "ofCustomSky:true"));
        assertEquals(Arrays.asList("ofFastRender:true", "ofConnectedTextures:3", "ofCustomSky:true"), out);
    }

    @Test
    public void addsItWhenMissing() {
        assertEquals(Collections.singletonList("ofConnectedTextures:3"),
                OptiFineDefaults.withConnectedTexturesOff(Collections.<String>emptyList()));
    }
}
