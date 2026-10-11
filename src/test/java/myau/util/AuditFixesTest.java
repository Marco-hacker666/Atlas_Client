package myau.util;

import myau.module.Module;
import myau.module.ModuleBundle;
import org.junit.Test;

import static org.junit.Assert.*;

/** 2026-10-11 audit fixes: new ledger kinds, merged modules by name, Debug's panel list. */
public class AuditFixesTest {

    @Test
    public void newKindsWeighLikeWhatTheyDo() {
        /* A module-made movement packet moves the player as surely as a held one. */
        assertEquals(Attribution.weight("held-send"), Attribution.weight("move-packet"), 1e-9);
        assertTrue(Attribution.weight("move") > Attribution.weight("place"));
        assertEquals(Attribution.weight("move"), Attribution.weight("timer"), 1e-9);
        assertTrue(Attribution.weight("rotate") < Attribution.weight("move"));
        assertTrue(Attribution.weight("rotate") > Attribution.weight("swing"));
    }

    private static final class Host extends Module {
        Host() {
            super("AuditHost", false);
        }
    }

    private static final class Part extends Module {
        Part() {
            super("AuditPart", false);
        }
    }

    @Test
    public void mergedModuleIsFoundByItsOwnName() {
        Part part = new Part();
        ModuleBundle.withSwitch(new Host(), part, "part", "Part", false);
        assertSame(part, ModuleBundle.byName("auditpart"));
        assertSame(part, ModuleBundle.of(Part.class));
        assertNull(ModuleBundle.byName("NoSuchModule"));
    }

    @Test
    public void debugPanelListsItsModules() {
        assertEquals(6, myau.module.modules.Debug.MANAGED.length);
        assertTrue(java.util.Arrays.asList(myau.module.modules.Debug.MANAGED)
                .contains(myau.module.modules.HitCheck.class));
    }
}
