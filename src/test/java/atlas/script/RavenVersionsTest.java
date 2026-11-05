package atlas.script;

import org.junit.Assume;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Raven bS 16 and bS 17 as two versions (2026-11-03): bS 17's additions exist only for bS 17 scripts, its rule
 * for nested types, and a real bS 17 script (the owner's LEGITTELLY, kept out of the repository in
 * backups/scripts-work) compiling against this client's classes alone with both compilers and answering its
 * hooks with bS 17's arguments.
 */
public class RavenVersionsTest {
    private static final File REAL_SCRIPT = new File("../backups/scripts-work/LEGITTELLY.java");

    static final String BS17_ONLY = String.join("\n",
            "long started = client.nanoTime();",
            "void onPreUpdate() {",
            "    if (world.exists()) { String option = modules.getSliderString(scriptName, \"Mode\"); }",
            "    gl.color(255, 85, 85, 60);",
            "    render.text(\"x\", 1f, 1f, 1f, -1, true);",
            "}",
            "String getSuffix() { return \"17\"; }");

    private static ScriptCompiler.Result compile(JavaCompiler javac, ScriptKind kind, String name, String source,
                                                 File dir) {
        ScriptSource.Wrapped wrapped = ScriptSource.wrap(kind, name, source, 1);
        assertNull(wrapped.problem);
        File sources = new File(dir, "src");
        List<ScriptCompiler.Unit> units = Arrays.asList(
                new ScriptCompiler.Unit(new File(sources, wrapped.scriptClass + ".java"), wrapped.code,
                        wrapped.headerLines, name + ".java"),
                new ScriptCompiler.Unit(new File(sources, wrapped.hostClass + ".java"), wrapped.hostCode, 0, "(host)"));
        return ScriptCompiler.compile(javac, units, new File(dir, "classes"),
                Collections.singletonList(ScriptCompiler.codeSource(ScriptSource.class)));
    }

    @Test
    public void bs17sAdditionsAreOnlyBs17s() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        File dir = Files.createTempDirectory("atlas-bs").toFile();
        try {
            ScriptCompiler.Result as17 = compile(javac, ScriptKind.RAVEN17, "Only17", BS17_ONLY, new File(dir, "17"));
            assertTrue(as17.problems.toString(), as17.ok);
            ScriptCompiler.Result asAtlas = compile(javac, ScriptKind.ATLAS, "Only17", BS17_ONLY, new File(dir, "a"));
            assertTrue("Atlas scripts have bS 17's API: " + asAtlas.problems, asAtlas.ok);
            ScriptCompiler.Result as16 = compile(javac, ScriptKind.RAVEN16, "Only17", BS17_ONLY, new File(dir, "16"));
            assertFalse("bS 16 has no nanoTime, exists, getSliderString, text", as16.ok);
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    @Test
    public void bs17MakesClassesHoldingEnumsStatic() {
        String source = String.join("\n",
                "class Holder {",
                "    enum Mode { A, B }",
                "}",
                "class Plain { int x; }",
                "class Outer { class Inner { interface Callback { void run(); } } }",
                "void method() { class Local { } }",
                "String text = \"class Fake { enum E {} }\";",
                "static class Already { enum E { A } }",
                "Class<?> literal = Holder.class;");
        String out = NestedTypes.makeStatic(source);
        assertTrue(out, out.contains("static class Holder {"));
        assertTrue(out, out.contains("\nclass Plain"));
        assertTrue(out, out.contains("static class Outer { static class Inner {"));
        assertTrue(out, out.contains("void method() { class Local { } }"));
        assertTrue(out, out.contains("\"class Fake { enum E {} }\""));
        assertTrue(out, out.contains("static class Already"));
        assertFalse(out, out.contains("static static"));
        assertEquals("no line moves", source.split("\n").length, out.split("\n").length);
    }

    /** Full check, 2026-11-03: Method.invoke widens, so Raven calls these; the hook lookup has to find them too. */
    @Test
    public void hookArgumentsWidenAsInRaven() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        String source = String.join("\n",
                "double seen = -1;",
                "void onRenderTick(double partial) { seen = partial; }",
                "boolean onChat(String message, int type) { return type == 2; }");
        File dir = Files.createTempDirectory("atlas-widen").toFile();
        try {
            ScriptSource.Wrapped wrapped = ScriptSource.wrap(ScriptKind.RAVEN17, "Widen", source, 1);
            List<ScriptCompiler.Unit> units = Arrays.asList(
                    new ScriptCompiler.Unit(new File(dir, "src/" + wrapped.scriptClass + ".java"), wrapped.code,
                            wrapped.headerLines, "Widen.java"),
                    new ScriptCompiler.Unit(new File(dir, "src/" + wrapped.hostClass + ".java"), wrapped.hostCode, 0, "(host)"));
            ScriptCompiler.Result result = ScriptCompiler.compile(javac, units, new File(dir, "classes"),
                    ScriptCompiler.classpath());
            assertTrue(result.problems.toString(), result.ok);
            ScriptLoader loader = new ScriptLoader(getClass().getClassLoader(),
                    ScriptManager.readClasses(new File(dir, "classes")));
            Object instance = loader.loadClass(ScriptSource.PACKAGE + "." + wrapped.scriptClass).newInstance();
            ScriptEntry entry = new ScriptEntry("Widen", ScriptKind.RAVEN17, new File("Widen.java"));
            entry.scriptClass = wrapped.scriptClass;
            ScriptRuntime runtime = new ScriptRuntime(entry, instance);

            runtime.call("onRenderTick", 0.5F);
            java.lang.reflect.Field seen = instance.getClass().getDeclaredField("seen");
            seen.setAccessible(true);
            assertEquals("a float reaches a double parameter", 0.5, seen.getDouble(instance), 0.0);
            assertEquals("a byte reaches an int parameter", 1, runtime.ask("onChat", "line", (byte) 2));
            assertEquals(0, runtime.ask("onChat", "line", (byte) 0));
            assertEquals("a String is not an int", -1, runtime.ask("onChat", "line", "2"));
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    /** A script for OpenB4 calls getScriptName(); one that declares it itself keeps its own. */
    @Test
    public void getScriptNameIsThereWhenCalled() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        File dir = Files.createTempDirectory("atlas-name").toFile();
        try {
            ScriptCompiler.Result called = compile(javac, ScriptKind.RAVEN17, "Called",
                    "void onLoad() { String name = getScriptName(); }", new File(dir, "called"));
            assertTrue(called.problems.toString(), called.ok);
            ScriptCompiler.Result own = compile(javac, ScriptKind.RAVEN17, "Own",
                    "String getScriptName() { return \"mine\"; }\nvoid onLoad() { String name = getScriptName(); }",
                    new File(dir, "own"));
            assertTrue(own.problems.toString(), own.ok);
            assertFalse("nothing is added to a script that does not call it",
                    ScriptSource.wrap(ScriptKind.RAVEN17, "None", "void onLoad() { }", 1).code.contains("getScriptName"));
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    @Test
    public void ravensSmallRules() {
        assertEquals("§aTom § Jerry", atlas.script.raven.RavenScript.util.color("&aTom & Jerry"));
        assertEquals(1200.0, atlas.script.raven.RavenScript.util.round(1234.0, -2), 0.0);
        assertEquals(1.23, atlas.script.raven.RavenScript.util.round(1.234, 2), 0.0);
        assertEquals("GET", new atlas.script.raven.model.Request("post", "http://x").method);
        assertEquals("POST", new atlas.script.raven.model.Request("POST", "http://x").method);
        assertEquals("Vec3(1.0,2.0,3.0)", new atlas.script.raven.model.Vec3(1, 2, 3).toString());
    }

    private static String realScript() throws Exception {
        Assume.assumeTrue("LEGITTELLY.java is not in backups/scripts-work", REAL_SCRIPT.isFile());
        return new String(Files.readAllBytes(REAL_SCRIPT.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void legitTellyCompilesAsBs17WithBothCompilers() throws Exception {
        String source = realScript();
        File dir = Files.createTempDirectory("atlas-telly").toFile();
        try {
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac != null) {
                ScriptCompiler.Result result = compile(javac, ScriptKind.RAVEN17, "LEGITTELLY", source, new File(dir, "javac"));
                assertTrue("javac: " + result.problems, result.ok);
            }
            IsolatedEcj.Outcome outcome = IsolatedEcj.compile(ScriptKind.RAVEN17, Collections.singletonList(source)).get(0);
            assertTrue("ecj: " + outcome.errors, outcome.ok);
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    @Test
    public void legitTellyAnswersBs17sHooks() throws Exception {
        String source = realScript();
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        File dir = Files.createTempDirectory("atlas-telly-run").toFile();
        try {
            ScriptSource.Wrapped wrapped = ScriptSource.wrap(ScriptKind.RAVEN17, "LEGITTELLY", source, 1);
            List<ScriptCompiler.Unit> units = Arrays.asList(
                    new ScriptCompiler.Unit(new File(dir, "src/" + wrapped.scriptClass + ".java"), wrapped.code,
                            wrapped.headerLines, "LEGITTELLY.java"),
                    new ScriptCompiler.Unit(new File(dir, "src/" + wrapped.hostClass + ".java"), wrapped.hostCode, 0, "(host)"));
            ScriptCompiler.Result result = ScriptCompiler.compile(javac, units, new File(dir, "classes"),
                    ScriptCompiler.classpath());
            assertTrue(result.problems.toString(), result.ok);
            Map<String, byte[]> classes = ScriptManager.readClasses(new File(dir, "classes"));
            ScriptLoader loader = new ScriptLoader(getClass().getClassLoader(), classes);
            Object instance = loader.loadClass(ScriptSource.PACKAGE + "." + wrapped.scriptClass).newInstance();
            assertTrue(instance instanceof atlas.script.raven.Raven17Script);
            ScriptEntry entry = new ScriptEntry("LEGITTELLY", ScriptKind.RAVEN17, REAL_SCRIPT);
            entry.scriptClass = wrapped.scriptClass;
            entry.headerLines = wrapped.headerLines;
            ScriptRuntime runtime = new ScriptRuntime(entry, instance);
            for (String hook : new String[]{"onLoad", "onEnable", "onDisable", "onPreUpdate", "onPreMotion",
                    "onPostPlayerInput", "onRenderTick", "onRenderWorld", "onPacketSent", "onPacketReceived",
                    "onWorldJoin", "onPostMotion", "onMouse", "onKey"}) {
                assertTrue(hook, runtime.has(hook));
            }
            /* bS 17 asks onMouse with the pointer; not running, not armed: the click goes through. */
            assertEquals(1, runtime.ask("onMouse", 1, true, 100, 100));
            assertEquals("bS 16's two-argument onMouse is not this script's", -1, runtime.ask("onMouse", 1, true));
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }
}
