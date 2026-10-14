package myau.init;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DataDirMigrationTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void oldFolderMovesToTheNewName() throws Exception {
        File game = this.folder.getRoot();
        write(new File(game, "config/Myau/default.json"), "settings");
        DataDirMigration.move(new File(game, "config/Myau"), new File(game, "config/Atlas"));
        assertEquals("settings", read(new File(game, "config/Atlas/default.json")));
        assertFalse(new File(game, "config/Myau").exists());
    }

    @Test
    public void existingNewFolderIsNeverOverwritten() throws Exception {
        File game = this.folder.getRoot();
        write(new File(game, "config/Myau/default.json"), "old");
        write(new File(game, "config/Atlas/default.json"), "new");
        DataDirMigration.move(new File(game, "config/Myau"), new File(game, "config/Atlas"));
        assertEquals("new", read(new File(game, "config/Atlas/default.json")));
        assertTrue(new File(game, "config/Myau/default.json").exists());
    }

    @Test
    public void missingOldDataDoesNothing() {
        File game = this.folder.getRoot();
        DataDirMigration.move(new File(game, "openmyau.accounts.json"), new File(game, "atlas.accounts.json"));
        assertFalse(new File(game, "atlas.accounts.json").exists());
    }
}
