package atlas.property;

import atlas.property.properties.ColorProperty;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ColorPropertyTest {
    private static int roundTrip(int value) {
        ColorProperty saved = new ColorProperty("c", value);
        JsonObject json = new JsonObject();
        saved.write(json);
        ColorProperty loaded = new ColorProperty("c", 0);
        loaded.read(json);
        return loaded.getValue();
    }

    @Test
    public void plainRgbRoundTrips() {
        assertEquals(0x55CDFC, roundTrip(0x55CDFC));
        assertEquals(0x000000, roundTrip(0x000000));
    }

    @Test
    public void awtColourWithAlphaRoundTrips() {
        assertEquals(0xFF808080, roundTrip(0xFF808080));
        assertEquals(0xFFFFFFFF, roundTrip(0xFFFFFFFF));
    }

    @Test
    public void hexWithHashAndSixOrEightDigits() {
        assertEquals(0xCCFF00, ColorProperty.parseHex("#CCFF00"));
        assertEquals(0xFF404040, ColorProperty.parseHex("FF404040"));
    }

    @Test(expected = NumberFormatException.class)
    public void tooLongIsRefused() {
        ColorProperty.parseHex("123456789");
    }
}
