package atlas.management;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * The modules moved onto the shared server look (2026-10-31) leave the packet and the keys to it: a module that
 * still set the look itself would fight its own request, and one that still turned the keys would turn them twice.
 */
public class RotationHelperMigrationTest {
    private static final String[] MOVED = {
            "AimAssist", "ProjectileAimBot", "AntiFireball", "ChestAura", "ThrowAura", "PearlSaver",
            "AutoHeadHitter", "AutoBedDef", "BedNuker", "Scaffold", "KillAura", "KillAuraLB"
    };

    private static String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void movedModulesAskTheEngineAndNothingElse() throws IOException {
        for (String name : MOVED) {
            String source = read("src/main/java/atlas/module/modules/" + name + ".java");
            assertTrue(name + " asks the engine", source.contains("RotationHelper.request("));
            assertFalse(name + " sets the packet's look itself", source.contains("event.setRotation("));
            assertFalse(name + " turns the keys itself", source.contains("MoveUtil.fixStrafe("));
        }
    }

    @Test
    public void theEngineIsRegisteredAfterEveryModule() throws IOException {
        String atlas = read("src/main/java/atlas/Atlas.java");
        int modules = atlas.indexOf("EventManager.register(module);");
        int engine = atlas.indexOf("EventManager.register(atlas.management.RotationHelper.get());");
        assertTrue("modules are registered", modules >= 0);
        assertTrue("the engine is registered", engine >= 0);
        assertTrue("the engine comes after the modules", engine > modules);
        assertEquals("registered once", atlas.indexOf("RotationHelper.get()"), atlas.lastIndexOf("RotationHelper.get()"));
    }
}
