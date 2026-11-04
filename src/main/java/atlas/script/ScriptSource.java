package atlas.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A script file as the compiler sees it (2026-11-02). Both kinds are written the same way, as the body of a class
 * -- fields and methods, no class around them -- and are wrapped here:
 *
 *  - Raven bS 16: extends atlas.script.raven.RavenScript, which carries Raven bS's API (client, world, modules,
 *    render, gl, inventory, keybinds, util, config, bridge) under the same names, with Raven's imports and its
 *    models and packets (Entity, Vec3, C03, S12 ...) importable by their simple names. A Raven script runs unchanged.
 *  - Raven bS 17: extends atlas.script.raven.Raven17Script, which adds what bS 17 added.
 *  - Atlas: extends atlas.script.api.AtlasScript, which is all of that plus this client's own: real settings in
 *    the menu, the shared rotation engine, other modules, raw events.
 *
 * Lines at the top that start with "import " are moved above the class, so a script may import what it likes
 * (Raven's own wrapper allows none). The user's lines keep their numbers: a moved import leaves a blank line.
 *
 * Each script compiles into package {@value #PACKAGE}, with a host module class of its own beside it (the menu,
 * the config and the key handler find modules and their settings by class).
 */
public final class ScriptSource {
    public static final String PACKAGE = "atlasscripts";

    /** Raven bS puts these imports before every script; its scripts rely on them. */
    static final List<String> RAVEN_IMPORTS = Arrays.asList(
            "java.awt.Color", "java.util.Collections", "java.util.List", "java.util.ArrayList", "java.util.Arrays",
            "java.util.Map", "java.util.Set", "java.util.HashMap", "java.util.HashSet",
            "java.util.concurrent.ConcurrentHashMap", "java.util.LinkedHashMap", "java.util.LinkedHashSet",
            "java.util.Iterator", "java.util.Comparator", "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong", "java.util.concurrent.atomic.AtomicBoolean", "java.util.Random",
            "java.util.regex.Matcher");
    static final List<String> RAVEN_PACKAGES = Arrays.asList(
            "atlas.script.raven.model", "atlas.script.raven.packet.clientbound", "atlas.script.raven.packet.serverbound");
    private static final java.util.regex.Pattern OWN_SCRIPT_NAME =
            java.util.regex.Pattern.compile("\\bString\\s+getScriptName\\s*\\(");

    private ScriptSource() {
    }

    /** A wrapped script: the code, the class names, how many lines come before the user's first. */
    public static final class Wrapped {
        public final String scriptClass;
        public final String hostClass;
        public final String code;
        public final String hostCode;
        public final int headerLines;
        /** A problem found before compiling (an unsupported line), or null. */
        public final String problem;

        Wrapped(String scriptClass, String hostClass, String code, String hostCode, int headerLines, String problem) {
            this.scriptClass = scriptClass;
            this.hostClass = hostClass;
            this.code = code;
            this.hostCode = hostCode;
            this.headerLines = headerLines;
            this.problem = problem;
        }
    }

    /** A Java identifier made from the script's name, unique per load ({@code generation}). */
    static String classId(String name, int generation) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) && c < 128 ? c : '_');
        }
        if (out.length() == 0) {
            out.append("script");
        }
        return out + "_" + Integer.toHexString(name.hashCode() & 0xFFFF) + "_" + generation;
    }

    public static Wrapped wrap(ScriptKind kind, String name, String source, int generation) {
        String id = classId(name, generation);
        String scriptClass = (kind == ScriptKind.RAVEN16 ? "s16_" : kind == ScriptKind.RAVEN17 ? "s17_" : "sa_") + id;
        String hostClass = "sh_" + id;
        String base = kind.base;
        if (kind.bs17()) {
            /* bS 17 (ScriptSourceTransformer): a class the script declares that holds an enum or an interface
               becomes static, which Java 8 asks of it. Inserting a word keeps every line where it was. */
            source = NestedTypes.makeStatic(source);
        }

        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> hoisted = new ArrayList<String>();
        String problem = null;
        boolean header = true;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("load - \"") || trimmed.startsWith("load -\"")) {
                /* Raven fetches code from a URL here. Not done: what runs would change after it was read. */
                problem = "line " + (i + 1) + ": \"load - url\" is not supported; paste that code into the file";
                lines[i] = "";
                continue;
            }
            if (!header) {
                continue;
            }
            if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                hoisted.add(trimmed);
                lines[i] = "";
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                header = false;
            }
        }

        StringBuilder head = new StringBuilder();
        head.append("package ").append(PACKAGE).append(";\n");
        for (String type : RAVEN_IMPORTS) {
            head.append("import ").append(type).append(";\n");
        }
        for (String pack : RAVEN_PACKAGES) {
            head.append("import ").append(pack).append(".*;\n");
        }
        if (kind == ScriptKind.ATLAS) {
            head.append("import atlas.script.api.*;\n");
            head.append("import atlas.property.properties.*;\n");
            head.append("import atlas.events.*;\n");
        }
        for (String line : hoisted) {
            head.append(line).append('\n');
        }
        String quoted = quote(name);
        head.append("public class ").append(scriptClass).append(" extends ").append(base).append(" {\n");
        head.append("public static final ").append(kind.modulesOwner).append(".modules modules = new ")
                .append(kind.modulesOwner).append(".modules(").append(quoted).append(");\n");
        head.append("public static final String scriptName = ").append(quoted).append(";\n");
        int headerLines = count(head);

        StringBuilder code = new StringBuilder(head);
        for (int i = 0; i < lines.length; i++) {
            code.append(lines[i]).append('\n');
        }
        /* OpenB4's getScriptName(), which scripts written for that Raven fork call. Added here, after the user's lines,
           only when one is called and the script has none: a script may well declare its own. */
        if (source.contains("getScriptName") && !OWN_SCRIPT_NAME.matcher(source).find()) {
            code.append("public static String getScriptName() { return scriptName; }\n");
        }
        code.append("}\n");

        String hostCode = "package " + PACKAGE + ";\n"
                + "public final class " + hostClass + " extends atlas.script.ScriptHost {\n"
                + "    public " + hostClass + "(String name, String description) { super(name, description); }\n"
                + "}\n";
        return new Wrapped(scriptClass, hostClass, code.toString(), hostCode, headerLines, problem);
    }

    private static int count(CharSequence text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /** A Java string literal. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 32) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** A new file's contents. */
    public static String template(ScriptKind kind) {
        if (kind == ScriptKind.RAVEN17) {
            return "// A Raven bS 17 script: bS 17's API (client, world, modules, render, gl, keybinds, inventory ...)\n"
                    + "void onLoad() {\n"
                    + "    modules.registerSlider(\"Range\", 3.0, 1.0, 6.0, 0.1);\n"
                    + "    modules.registerButton(\"Chat\", true);\n"
                    + "}\n\n"
                    + "void onEnable() {\n"
                    + "}\n\n"
                    + "void onPreUpdate() {\n"
                    + "    if (modules.getButton(scriptName, \"Chat\") && client.getPlayer().getTicksExisted() % 100 == 0) {\n"
                    + "        client.print(\"&bHello from \" + scriptName);\n"
                    + "    }\n"
                    + "}\n\n"
                    + "void onKey(String keyName, int keyCode, boolean state, boolean inGui) {\n"
                    + "}\n\n"
                    + "String getSuffix() {\n"
                    + "    return \"\" + (int) modules.getSlider(scriptName, \"Range\");\n"
                    + "}\n\n"
                    + "void onDisable() {\n"
                    + "}\n";
        }
        if (kind == ScriptKind.RAVEN16) {
            return "// A Raven bS 16 script: bS 16's API (client, world, modules, render, keybinds, inventory ...)\n"
                    + "void onLoad() {\n"
                    + "    modules.registerSlider(\"Range\", 3.0, 1.0, 6.0, 0.1);\n"
                    + "    modules.registerButton(\"Chat\", true);\n"
                    + "}\n\n"
                    + "void onEnable() {\n"
                    + "}\n\n"
                    + "void onPreUpdate() {\n"
                    + "    if (modules.getButton(scriptName, \"Chat\") && client.getPlayer().getTicksExisted() % 100 == 0) {\n"
                    + "        client.print(\"&bHello from \" + scriptName);\n"
                    + "    }\n"
                    + "}\n\n"
                    + "void onDisable() {\n"
                    + "}\n";
        }
        return "// An Atlas script: Raven's API (client, world, render ...) plus Atlas's own.\n"
                + "// Settings are real menu settings; see atlas.script.api.AtlasScript for everything.\n"
                + "final BooleanProperty chat = bool(\"chat\", true);\n"
                + "final FloatProperty range = number(\"range\", 3.0F, 1.0F, 6.0F);\n"
                + "final ModeProperty mode = mode(\"mode\", \"Nearest\", \"Nearest\", \"Lowest HP\");\n\n"
                + "void onEnable() {\n"
                + "    print(\"&aon, range \" + range.getValue());\n"
                + "}\n\n"
                + "void onTick() {\n"
                + "    Entity target = nearestPlayer(range.getValue());\n"
                + "    if (target != null && chat.getValue() && client.getPlayer().getTicksExisted() % 40 == 0) {\n"
                + "        print(\"&7nearest: &f\" + target.getName());\n"
                + "    }\n"
                + "}\n\n"
                + "void onDisable() {\n"
                + "}\n";
    }
}
