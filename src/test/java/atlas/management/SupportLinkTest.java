package atlas.management;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SupportLinkTest {
    @Test
    public void aMissingOrMalformedChangeIdIsMinusOne() {
        JsonObject none = new JsonObject();
        assertEquals(-1L, SupportLink.changeId(none));
        JsonObject text = new JsonObject();
        text.addProperty("id", "abc");
        assertEquals(-1L, SupportLink.changeId(text));
        JsonObject nested = new JsonObject();
        nested.add("id", new JsonObject());
        assertEquals(-1L, SupportLink.changeId(nested));
        JsonObject good = new JsonObject();
        good.addProperty("id", 42L);
        assertEquals(42L, SupportLink.changeId(good));
    }
}
