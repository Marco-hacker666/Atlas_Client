package atlas.module.modules;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TellyRecorderTest {

    @Test
    public void angleBytesBecomeDegrees() {
        assertEquals(0.0F, TellyRecorder.angle((byte) 0), 1.0E-6F);
        assertEquals(90.0F, TellyRecorder.angle((byte) 64), 1.0E-6F);
        assertEquals(-90.0F, TellyRecorder.angle((byte) -64), 1.0E-6F);
        assertEquals(-180.0F, TellyRecorder.angle((byte) -128), 1.0E-6F);
    }

    @Test
    public void rowsMatchTheHeader() {
        String self = TellyRecorder.row(7, "S", "me", 12.5F, 80.25F, 1.0, 64.0, -3.5, true,
                1.0F, -0.3F, true, false, true, true, 2, 45, false);
        assertEquals("7,S,me,12.5000,80.2500,1.0000,64.0000,-3.5000,1,1,-1,1,0,1,1,2,45,0", self);
        String other = TellyRecorder.row(8, "O", "e12w0", -170.0F, 75.0F, 0.0, 70.0, 0.0, false,
                Float.NaN, Float.NaN, false, true, false, false, 1, -1, false);
        assertEquals("8,O,e12w0,-170.0000,75.0000,0.0000,70.0000,0.0000,0,,,0,1,0,0,1,-1,0", other);
        int columns = TellyRecorder.HEADER.split(",").length;
        assertEquals(columns, self.split(",", -1).length);
        assertEquals(columns, other.split(",", -1).length);
    }
}
