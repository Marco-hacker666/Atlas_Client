package atlas.setup;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SetupCatalogTest {
    @Test
    public void everyEntryIsComplete() {
        for (SetupEntry entry : SetupCatalog.all()) {
            assertFalse(entry.fileName, entry.fileName == null || entry.fileName.trim().isEmpty());
            assertTrue(entry.fileName, entry.url != null && entry.url.startsWith("http"));
            assertFalse(entry.name, entry.displayName().isEmpty());
        }
    }

    @Test
    public void modsAreJarsAndDirect() {
        List<SetupEntry> mods = SetupCatalog.mods();
        assertFalse(mods.isEmpty());
        for (SetupEntry entry : mods) {
            assertEquals(entry.fileName, SetupEntry.Section.MODS, entry.section);

            assertEquals(entry.fileName, "OptiFine".equals(entry.displayName())
                    ? SetupEntry.Source.OPTIFINE : SetupEntry.Source.DIRECT, entry.source);
            assertTrue(entry.fileName, entry.fileName.endsWith(".jar"));
        }
    }

    @Test
    public void optiFineIsOfficialAndPinnedAndOneConfigIsGone() {
        boolean optifine = false;
        boolean oneconfig = false;
        for (SetupEntry entry : SetupCatalog.mods()) {
            if ("OptiFine".equals(entry.displayName())) {
                optifine = true;
                assertTrue(entry.url, entry.url.startsWith("https://optifine.net/"));
                assertEquals(40, entry.sha1.length());
            } else if ("OneConfig".equals(entry.displayName())) {
                oneconfig = true;
            }
            assertFalse(entry.fileName, entry.fileName.contains("ModernF3"));
        }
        assertTrue(optifine);
        assertFalse("OneConfig was removed (2026-10-11)", oneconfig);
    }

    @Test
    public void packsAreZipsFromSharePages() {
        List<SetupEntry> packs = SetupCatalog.packs();
        assertFalse(packs.isEmpty());
        for (SetupEntry entry : packs) {
            assertEquals(entry.fileName, SetupEntry.Section.PACKS, entry.section);
            assertEquals(entry.fileName, SetupEntry.Source.MEDIAFIRE_SHARE, entry.source);
            assertTrue(entry.fileName, entry.fileName.endsWith(".zip"));
        }
    }

    @Test
    public void allIsModsPlusPacks() {
        assertEquals(SetupCatalog.mods().size() + SetupCatalog.packs().size(), SetupCatalog.all().size());
    }

    @Test
    public void onlyOptiFineInstallsItself() {
        List<SetupEntry> auto = SetupCatalog.autoEntries();
        assertEquals(1, auto.size());
        assertEquals("OptiFine", auto.get(0).displayName());
        assertTrue(auto.get(0).matchPrefix);
    }

    @Test
    public void bySectionKeepsOrder() {
        assertEquals(names(SetupCatalog.mods()), names(SetupCatalog.bySection(SetupEntry.Section.MODS)));
        assertEquals(names(SetupCatalog.packs()), names(SetupCatalog.bySection(SetupEntry.Section.PACKS)));
    }

    private static java.util.List<String> names(List<SetupEntry> entries) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SetupEntry entry : entries) {
            out.add(entry.displayName());
        }
        return out;
    }

    @Test
    public void controllingPointsAtAWorkingHost() {
        for (SetupEntry entry : SetupCatalog.mods()) {
            if (entry.displayName().equals("Controlling")) {
                assertTrue(entry.url, entry.url.contains("forgecdn"));
                return;
            }
        }
        throw new AssertionError("Controlling entry is missing from the catalog");
    }
}
