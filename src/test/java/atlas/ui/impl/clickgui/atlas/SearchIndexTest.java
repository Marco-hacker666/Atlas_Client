package atlas.ui.impl.clickgui.atlas;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** The menu's search over modules and settings (2026-10-23, GUI redesign M3). */
public class SearchIndexTest {

    private static final Predicate<Object> ALL_HERE = m -> true;

    private SearchIndex index;

    @Before
    public void build() {
        this.index = new SearchIndex();
        this.index.addModule("KillAura", "KillAura", "Attacks the closest target.");
        this.index.addModule("AutoBlock", "AutoBlock", "Raises the sword to block hits.");
        this.index.addModule("Speed", "Speed", "Moves faster.");
        this.index.addModule("SpeedMine", "SpeedMine", "Breaks blocks faster.");
        this.index.addModule("HUD", "HUD", "The module list on screen.");
        this.index.addSetting("AutoBlock", "full-hold", "AutoBlock", "Full Hold", "full-hold",
                "Full Smart: the most ticks one raise lasts; down as soon as the hit lands.");
        this.index.addSetting("AutoBlock", "full-air", "AutoBlock", "Full Air", "full-air",
                "Full Smart: keep it up in the air (blocking barely slows you there).");
        this.index.addSetting("KillAura", "range", "KillAura", "Range", "range", "How far it attacks.");
        this.index.addSetting("Speed", "speed", "Speed", "Speed", "speed", null);
    }

    private static List<Object> modulesOf(SearchIndex.Results results) {
        List<Object> out = new ArrayList<Object>();
        for (SearchIndex.Entry entry : results.modules) {
            out.add(entry.module);
        }
        return out;
    }

    private static List<Object> settingsOf(SearchIndex.Results results) {
        List<Object> out = new ArrayList<Object>();
        for (SearchIndex.Entry entry : results.settings) {
            out.add(entry.setting);
        }
        return out;
    }

    @Test
    public void settingsAreFoundByKeyLabelAndHelp() {
        assertEquals(Arrays.<Object>asList("full-hold"), settingsOf(this.index.search("hold", ALL_HERE, 60)));
        assertEquals(Arrays.<Object>asList("full-hold"), settingsOf(this.index.search("full hold", ALL_HERE, 60)));
        assertEquals(Arrays.<Object>asList("full-hold"), settingsOf(this.index.search("fullhold", ALL_HERE, 60)));
        assertEquals(Arrays.<Object>asList("full-air"), settingsOf(this.index.search("slow", ALL_HERE, 60)));
        assertEquals("every word must be found", 0, this.index.search("hold xyz", ALL_HERE, 60).size());
    }

    @Test
    public void nameMatchesComeBeforeTextMatchesAndExactBeforePrefix() {
        assertEquals(Arrays.<Object>asList("Speed", "SpeedMine"), modulesOf(this.index.search("speed", ALL_HERE, 60)));
        /* "block": AutoBlock by name; SpeedMine only by its text ("Breaks blocks"). */
        assertEquals(Arrays.<Object>asList("AutoBlock", "SpeedMine"), modulesOf(this.index.search("block", ALL_HERE, 60)));
        List<Object> full = settingsOf(this.index.search("full", ALL_HERE, 60));
        assertEquals(Arrays.<Object>asList("full-hold", "full-air"), full);
    }

    @Test
    public void thisPagesMatchesComeFirst() {
        Predicate<Object> notHud = m -> !"HUD".equals(m);
        this.index.addModule("HudColors", "HudColors", "");
        List<Object> found = modulesOf(this.index.search("hud", notHud, 60));
        assertEquals(Arrays.<Object>asList("HudColors", "HUD"), found);
    }

    @Test
    public void settingResultsAreCapped() {
        SearchIndex many = new SearchIndex();
        for (int i = 0; i < 100; i++) {
            many.addSetting("M", "s" + i, "M", "Delay " + i, "delay-" + i, "");
        }
        assertEquals(60, many.search("delay", ALL_HERE, 60).settings.size());
        assertEquals("the first ones, in order", "s0", many.search("delay", ALL_HERE, 60).settings.get(0).setting);
    }

    @Test
    public void anEmptyOrPunctuationQueryFindsNothing() {
        assertEquals(0, this.index.search("", ALL_HERE, 60).size());
        assertEquals(0, this.index.search("  -  ", ALL_HERE, 60).size());
    }

    /** For one-word queries, the modules found by name are exactly the ones the old name-only search found. */
    @Test
    public void oneWordFindsWhatTheOldSearchFound() {
        String[] names = {"KillAura", "KillAura2", "PlainAura", "AutoBlock", "AntiAFK", "FPScounter", "WaterMark2",
                "Speed", "SpeedMine", "NoSlow", "AutoClicker", "ESP", "ItemESP", "HUD", "LegitHUD"};
        SearchIndex built = new SearchIndex();
        for (String name : names) {
            built.addModule(name, name, "");
        }
        Random random = new Random(3L);
        for (int round = 0; round < 2000; round++) {
            String source = names[random.nextInt(names.length)];
            int from = random.nextInt(source.length());
            int to = from + 1 + random.nextInt(source.length() - from);
            String needle = source.substring(from, to);
            if (random.nextBoolean()) {
                needle = needle.toUpperCase();
            }
            Set<Object> old = new HashSet<Object>();
            for (String name : names) {
                if (name.toLowerCase().contains(needle.toLowerCase().trim())) {
                    old.add(name);
                }
            }
            Set<Object> now = new HashSet<Object>();
            for (SearchIndex.Entry entry : built.search(needle, ALL_HERE, 60).modules) {
                now.add(entry.module);
            }
            assertEquals(needle, old, now);
        }
    }
}
