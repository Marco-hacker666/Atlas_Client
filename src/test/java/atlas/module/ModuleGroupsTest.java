package atlas.module;

import atlas.module.modules.KillAuraLB;
import atlas.module.modules.ThemeStyle;
import atlas.property.Property;
import atlas.property.PropertyGroup;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ModuleGroupsTest {
    private static final List<String> LEFT_AS_IS = Arrays.asList("TimerRange");

    @SuppressWarnings("unchecked")
    private static Map<String, String[][]> groups() throws Exception {
        Field field = ModuleDocs.class.getDeclaredField("GROUPS");
        field.setAccessible(true);
        return new TreeMap<String, String[][]>((Map<String, String[][]>) field.get(null));
    }

    private static int match(String[][] spec, String name) throws Exception {
        Method method = ModuleDocs.class.getDeclaredMethod("match", String[][].class, String.class);
        method.setAccessible(true);
        return (Integer) method.invoke(null, spec, name);
    }

    private static List<Property<?>> settingsOf(Module module) throws Exception {
        List<Field> fields = new ArrayList<Field>(Arrays.asList(module.getClass().getDeclaredFields()));
        if (module instanceof ThemeStyle) {
            fields.addAll(Arrays.asList(ThemeStyle.class.getDeclaredFields()));
        }
        List<Property<?>> out = new ArrayList<Property<?>>();
        for (Field field : fields) {
            field.setAccessible(true);
            Object value = field.get(module);
            if (value instanceof Property<?>) {
                out.add((Property<?>) value);
            } else if (value instanceof PropertyGroup) {
                out.addAll(((PropertyGroup) value).properties());
            }
        }
        return out;
    }

    private static final Pattern DECLARED = Pattern.compile("new (\\w+?)(Property|Range)\\(\\s*\"([^\"]+)\"");

    private static List<String> settingNames(String simpleName) throws Exception {
        List<String> names = new ArrayList<String>();
        try {
            Module module = (Module) Class.forName("atlas.module.modules." + simpleName).newInstance();
            for (Property<?> property : settingsOf(module)) {
                names.add(property.getName());
            }
            return names;
        } catch (Throwable notHere) {
            String source = new String(Files.readAllBytes(
                    Paths.get("src/main/java/atlas/module/modules/" + simpleName + ".java")), StandardCharsets.UTF_8);
            Matcher declared = DECLARED.matcher(source);
            while (declared.find()) {
                if ("Range".equals(declared.group(2))) {
                    names.add(declared.group(3) + "-min");
                    names.add(declared.group(3) + "-max");
                } else {
                    names.add(declared.group(3));
                }
            }
            return names;
        }
    }

    @Test
    public void everyGroupedModuleExists() throws Exception {
        StringBuilder missing = new StringBuilder();
        for (String name : groups().keySet()) {
            try {
                Class.forName("atlas.module.modules." + name);
            } catch (ClassNotFoundException e) {
                missing.append(name).append(' ');
            }
        }
        assertEquals("grouping for modules that do not exist: " + missing, 0, missing.length());
    }

    @Test
    public void everyGroupingPatternMatchesASetting() throws Exception {
        StringBuilder stale = new StringBuilder();
        for (Map.Entry<String, String[][]> entry : groups().entrySet()) {
            if (LEFT_AS_IS.contains(entry.getKey())) {
                continue;
            }
            List<String> settings = settingNames(entry.getKey());
            assertTrue(entry.getKey() + " has settings", !settings.isEmpty());
            for (String[] row : entry.getValue()) {
                for (int i = 1; i < row.length; i++) {
                    String[][] one = {{row[0], row[i]}};
                    boolean found = false;
                    for (String name : settings) {
                        if (match(one, name) >= 0) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        stale.append(entry.getKey()).append(": ").append(row[i]).append(" | ");
                    }
                }
            }
        }
        assertEquals("grouping patterns that match no setting: " + stale, 0, stale.length());
    }

    @Test
    public void bundledSettingsKeepTheirHeadingUnderTheBundleTitle() throws Exception {
        KillAuraLB lb = new KillAuraLB();
        assertEquals("LiquidBounce", ModuleDocs.bundleHeading("LiquidBounce", lb, null));
        assertEquals("LiquidBounce", ModuleDocs.bundleHeading("LiquidBounce", lb, "一般"));
        assertEquals("LiquidBounce · Rotations", ModuleDocs.bundleHeading("LiquidBounce", lb, "轉頭"));

        List<Property<?>> shown = ModuleDocs.apply(lb, settingsOf(lb));
        String noise = null;
        String cps = null;
        for (Property<?> property : shown) {
            if ("Noise-Scale".equals(property.getName())) {
                noise = ModuleDocs.bundleHeading("LiquidBounce", lb, property.getGroup());
            } else if ("MinCPS".equals(property.getName())) {
                cps = ModuleDocs.bundleHeading("LiquidBounce", lb, property.getGroup());
            }
        }
        assertEquals("LiquidBounce · Angle smoothing", noise);
        assertEquals("LiquidBounce", cps);
    }

    @Test
    public void killAuraLiquidBounceSettingsAllHaveAHeading() throws Exception {
        KillAuraLB lb = new KillAuraLB();
        List<Property<?>> settings = settingsOf(lb);
        List<Property<?>> shown = ModuleDocs.apply(lb, settings);
        assertEquals(settings.size(), shown.size());
        StringBuilder loose = new StringBuilder();
        String[][] spec = groups().get("KillAuraLB");
        for (Property<?> property : settings) {
            if (match(spec, property.getName()) < 0) {
                loose.append(property.getName()).append(' ');
            }
        }
        assertTrue("LiquidBounce settings under no heading: " + loose, loose.length() == 0);
    }
}
