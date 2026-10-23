package atlas.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/** "Unsaved changes": what would be saved now against the profile's file (2026-10-23, GUI redesign M7). */
public class ConfigDiffersTest {

    private static JsonObject now(float range, boolean toggled, String mode) {
        JsonObject module = new JsonObject();
        module.addProperty("toggled", toggled);
        module.addProperty("range", range);
        module.addProperty("mode", mode);
        JsonObject all = new JsonObject();
        all.add("KillAura", module);
        return all;
    }

    private static JsonObject file(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    @Test
    public void theSameValuesAreNotAChangeEvenAsFileNumbers() {
        JsonObject saved = file(Config.gson.toJson(now(3.15F, true, "Single")));
        assertFalse(Config.differs(now(3.15F, true, "Single"), saved));
    }

    @Test
    public void aChangedValueOrSwitchIsAChange() {
        JsonObject saved = file(Config.gson.toJson(now(3.15F, true, "Single")));
        assertTrue(Config.differs(now(3.2F, true, "Single"), saved));
        assertTrue(Config.differs(now(3.15F, false, "Single"), saved));
        assertTrue(Config.differs(now(3.15F, true, "Switch"), saved));
    }

    @Test
    public void whatTheFileDoesNotMentionIsNotAChange() {
        JsonObject saved = file("{\"KillAura\": {\"toggled\": true}, \"Old\": {\"x\": 1}}");
        assertFalse(Config.differs(now(4.0F, true, "Single"), saved));
        assertFalse(Config.differs(now(4.0F, true, "Single"), file("{}")));
    }
}
