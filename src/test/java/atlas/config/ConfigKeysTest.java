package atlas.config;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Every setting key and module name a saved config uses is still declared
 * (2026-10-23, before the menu redesign).
 *
 * Config writes each module under its name and each setting under its key,
 * and atlas-theme.json does the same for the menu's own settings. A key that
 * stops being declared is a setting every saved profile silently loses on its
 * next load. The keys declared when the redesign started are listed in
 * config-keys-baseline.txt, one "Class key" or "Class #name Name" a line. New
 * keys need no entry. Removing or renaming one needs a migration in Config
 * first, and then its line removed from the list.
 *
 * Read from the source, as ModuleCategoriesTest reads the registry: the
 * modules cannot be built without a running game. Only literal keys are seen,
 * and a merged module's keys are checked under its own class, before
 * ModuleBundle adds the host's prefix.
 */
public class ConfigKeysTest {

    private static final Path MODULES = Paths.get("src/main/java/atlas/module/modules");
    private static final Path THEME = Paths.get("src/main/java/atlas/ui/impl/clickgui/atlas/AtlasTheme.java");
    private static final Path BASELINE = Paths.get("src/test/resources/atlas/config/config-keys-baseline.txt");

    private static final Pattern PROPERTY = Pattern.compile("new (\\w+Property)\\(\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("public\\s+(\\w+)\\s*\\(\\s*\\)\\s*\\{\\s*super\\(\\s*\"([^\"]+)\"");

    /** Every "Class key" and "Class #name Name" declared in the source now (RiskRulesTest checks its rules against it). */
    public static Set<String> declared() throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(MODULES)) {
            files.addAll(walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList()));
        }
        files.add(THEME);
        Set<String> keys = new TreeSet<String>();
        for (Path file : files) {
            String type = file.getFileName().toString().replace(".java", "");
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Matcher property = PROPERTY.matcher(source);
            while (property.find()) {
                keys.add(type + " " + property.group(2));
            }
            Matcher name = NAME.matcher(source);
            while (name.find()) {
                if (name.group(1).equals(type)) {
                    keys.add(type + " #name " + name.group(2));
                }
            }
        }
        return keys;
    }

    private static Set<String> baseline() throws IOException {
        Set<String> keys = new TreeSet<String>();
        for (String line : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                keys.add(line);
            }
        }
        return keys;
    }

    @Test
    public void everyBaselineKeyIsStillDeclared() throws IOException {
        Set<String> missing = new TreeSet<String>(baseline());
        missing.removeAll(declared());
        assertTrue("settings saved configs use are no longer declared (add a migration first): " + missing,
                missing.isEmpty());
    }

    @Test
    public void theBaselineCoversTheClient() throws IOException {
        Set<String> baseline = baseline();
        assertTrue(baseline.size() > 1500);
        assertTrue(baseline.contains("ClickGUIModule #name ClickGUI"));
        assertTrue(baseline.contains("ServerProfiles profiles"));
        assertTrue(baseline.contains("AtlasTheme appearance-mode"));
    }
}
