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

public class ConfigKeysTest {
    private static final Path MODULES = Paths.get("src/main/java/atlas/module/modules");
    private static final Path THEME = Paths.get("src/main/java/atlas/ui/impl/clickgui/atlas/AtlasTheme.java");
    private static final Path BASELINE = Paths.get("src/test/resources/atlas/config/config-keys-baseline.txt");

    private static final Pattern PROPERTY = Pattern.compile("new (\\w+Property)\\(\\s*\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("public\\s+(\\w+)\\s*\\(\\s*\\)\\s*\\{\\s*super\\(\\s*\"([^\"]+)\"");

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
