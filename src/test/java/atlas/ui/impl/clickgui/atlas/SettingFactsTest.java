package atlas.ui.impl.clickgui.atlas;

import atlas.config.Config;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.TextProperty;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Defaults, ranges, "changed" and Reset (2026-10-23, GUI redesign M2). */
public class SettingFactsTest {

    @After
    public void notLoading() {
        Config.loading = false;
    }

    @Test
    public void aSettingIsChangedOnlyWhenItsSavedValueDiffers() {
        IntProperty range = new IntProperty("range", 3, 1, 6);
        assertFalse(SettingFacts.changed(range));
        range.setValue(5);
        assertTrue(SettingFacts.changed(range));
        range.setValue(3);
        assertFalse(SettingFacts.changed(range));

        FloatProperty hold = new FloatProperty("hold", 0.15F, 0.0F, 1.0F);
        hold.setValue(Math.round(0.15F * 100.0F) / 100.0F);
        assertFalse("the menu's 0.01 rounding of an unchanged value", SettingFacts.changed(hold));
    }

    @Test
    public void resetPutsBackTheDefaultAndOnlyWhenChanged() {
        ModeProperty mode = new ModeProperty("mode", 1, new String[]{"A", "B", "C"});
        assertFalse(SettingFacts.reset(mode));
        mode.setValue(2);
        assertTrue(SettingFacts.reset(mode));
        assertEquals(Integer.valueOf(1), mode.getValue());
        assertEquals(Property.Source.USER, mode.getSource());

        TextProperty text = new TextProperty("profiles", "");
        text.setValue("pika=pika");
        assertTrue(SettingFacts.reset(text));
        assertEquals("", text.getValue());
    }

    @Test
    public void resetLeavesATunersOverrideOnTop() {
        IntProperty delay = new IntProperty("normal-delay", 100, 50, 1000);
        delay.setValue(300);
        assertTrue(delay.override(Property.Source.TRIAL, "AutoTune", 200));
        assertTrue(SettingFacts.reset(delay));
        assertEquals(Integer.valueOf(100), delay.getBaseValue());
        assertEquals(Integer.valueOf(200), delay.getValue());
        JsonObject json = new JsonObject();
        delay.write(json);
        assertEquals("the default is what is saved", 100, json.get("normal-delay").getAsInt());
    }

    @Test
    public void aColourComparesItsRgbOnly() {
        ColorProperty colour = new ColorProperty("accent", 0xDD4747);
        colour.setValue(0xDD4747);
        assertFalse(SettingFacts.changed(colour));
        colour.setValue(0x123456);
        assertTrue(SettingFacts.changed(colour));
        assertEquals("#DD4747", SettingFacts.defaultText(colour));
    }

    @Test
    public void defaultsAndRangesAreWrittenForPeople() {
        assertEquals("On", SettingFacts.defaultText(new BooleanProperty("blur", true)));
        assertEquals("B", SettingFacts.defaultText(new ModeProperty("mode", 1, new String[]{"A", "B"})));
        assertEquals("40%", SettingFacts.defaultText(new PercentProperty("chance", 40)));
        assertEquals("empty", SettingFacts.defaultText(new TextProperty("rules", "")));
        assertEquals("3.5", SettingFacts.defaultText(new FloatProperty("range", 3.5F, 1.0F, 6.0F)));
        assertEquals("1 to 6", SettingFacts.range(new IntProperty("ticks", 3, 1, 6)));
        assertEquals("1.0 to 6.0", SettingFacts.range(new FloatProperty("range", 3.5F, 1.0F, 6.0F)));
        assertEquals("0% to 100%", SettingFacts.range(new PercentProperty("chance", 40)));
        assertEquals("2 options", SettingFacts.range(new ModeProperty("mode", 1, new String[]{"A", "B"})));
        assertEquals("", SettingFacts.range(new BooleanProperty("blur", true)));
    }

    @Test
    public void aModuleResetCountsWhatItChanged() {
        IntProperty a = new IntProperty("a", 1, 0, 9);
        IntProperty b = new IntProperty("b", 2, 0, 9);
        BooleanProperty c = new BooleanProperty("c", false);
        a.setValue(5);
        c.setValue(true);
        List<Property<?>> all = new ArrayList<Property<?>>(Arrays.<Property<?>>asList(a, b, c));
        assertEquals(2, SettingFacts.countChanged(all));
        assertEquals(2, SettingFacts.resetAll(all));
        assertEquals(0, SettingFacts.countChanged(all));
    }

    @Test
    public void theMenusModeListIsThePromptsList() {
        ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Normal", "Old", "Full"});
        mode.hide("Old");
        assertArrayEquals(new String[]{"Normal", "Full"}, mode.visibleModes());
        assertEquals(String.join(", ", mode.visibleModes()), mode.getValuePrompt());
        mode.setValue(1);
        assertArrayEquals("the current mode is listed even when hidden",
                new String[]{"Normal", "Old", "Full"}, mode.visibleModes());
    }
}
