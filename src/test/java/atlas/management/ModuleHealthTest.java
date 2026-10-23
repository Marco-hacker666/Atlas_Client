package atlas.management;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/** The menu's per-module count of corrections it was named for (2026-10-23, GUI redesign M4). */
public class ModuleHealthTest {

    @After
    public void clear() {
        ModuleHealth.resetSession();
    }

    @Test
    public void namedAndInvolvedAreCountedApart() {
        ModuleHealth.named("FakeLag", 1000L);
        ModuleHealth.named("FakeLag", 2000L);
        ModuleHealth.involved("FakeLag", 3000L);
        ModuleHealth.Report report = ModuleHealth.of(4000L, "FakeLag");
        assertEquals(2, report.named);
        assertEquals(1, report.involved);
        assertEquals(2000L, report.lastAt);
        assertSame(ModuleHealth.Report.NONE, ModuleHealth.of(4000L, "Speed"));
        assertFalse(ModuleHealth.Report.NONE.any());
    }

    @Test
    public void aModuleIsAskedByItsMenuNameAndItsClassName() {
        ModuleHealth.named("AutoBlock", 1000L);
        ModuleHealth.named("BlockHit", 1500L);
        assertEquals(2, ModuleHealth.of(2000L, "AutoBlock", "BlockHit").named);
        assertEquals("the same name twice counts once", 1, ModuleHealth.of(2000L, "AutoBlock", "AutoBlock").named);
    }

    @Test
    public void recentIsTheLastFiveMinutes() {
        long now = 10L * 60L * 1000L;
        ModuleHealth.named("Scaffold", now - ModuleHealth.RECENT_MS - 1L);
        ModuleHealth.named("Scaffold", now - 1000L);
        ModuleHealth.named("Scaffold", now);
        ModuleHealth.Report report = ModuleHealth.of(now, "Scaffold");
        assertEquals(3, report.named);
        assertEquals(2, report.recent);
    }

    @Test
    public void aNewConnectionStartsAgain() {
        ModuleHealth.named("Clutch", 1000L);
        ModuleHealth.resetSession();
        assertFalse(ModuleHealth.of(2000L, "Clutch").any());
    }

    @Test
    public void nothingIsKeptForNoName() {
        ModuleHealth.named(null, 1000L);
        ModuleHealth.named("", 1000L);
        ModuleHealth.involved(null, 1000L);
        assertFalse(ModuleHealth.of(2000L, "").any());
    }
}
