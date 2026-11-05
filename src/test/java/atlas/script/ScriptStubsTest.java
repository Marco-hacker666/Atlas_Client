package atlas.script;

import org.junit.Assume;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * A compiler looks at the game types in the signatures of every overload it weighs, and those classes are not there
 * for a player: the Minecraft jar is obfuscated (2026-11-04). The calls here are inexact on purpose: they are what
 * makes a compiler read all the overloads of a name.
 */
public class ScriptStubsTest {
    private static final String[] API_FOLDERS = {"atlas/script", "atlas/property", "atlas/events"};
    /** ECJ stops reporting at 100 problems in one file. */
    private static final int CALLS_PER_UNIT = 30;

    private static void collect(File folder, String name, List<String> out) {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collect(file, name + file.getName() + ".", out);
            } else if (file.getName().endsWith(".class")) {
                out.add(name + file.getName().substring(0, file.getName().length() - 6));
            }
        }
    }

    /** The classes of the script API, without running their static blocks. */
    private static List<Class<?>> apiClasses() throws Exception {
        File own = ScriptCompiler.codeSource(ScriptSource.class);
        List<String> names = new ArrayList<String>();
        for (String folder : API_FOLDERS) {
            collect(new File(own, folder), folder.replace('/', '.') + ".", names);
        }
        List<Class<?>> out = new ArrayList<Class<?>>();
        for (String name : names) {
            out.add(Class.forName(name, false, ScriptStubsTest.class.getClassLoader()));
        }
        return out;
    }

    private static String ints(int count) {
        return String.join(", ", Collections.nCopies(count, "0"));
    }

    /** Every constructor and method of the API, called with ints, which fit nearly none of them. */
    private static List<String> inexactCalls() throws Exception {
        Set<String> calls = new TreeSet<String>();
        for (Class<?> type : apiClasses()) {
            String name = type.getCanonicalName();
            if (name == null || !Modifier.isPublic(type.getModifiers())) {
                continue;
            }
            boolean concrete = !Modifier.isAbstract(type.getModifiers()) && !type.isInterface() && !type.isEnum();
            if (concrete && (type.getEnclosingClass() == null || Modifier.isStatic(type.getModifiers()))) {
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    calls.add("new " + name + "(" + ints(constructor.getParameterCount()) + ");");
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isSynthetic() && !Modifier.isPrivate(method.getModifiers())) {
                    calls.add("((" + name + ") null)." + method.getName() + "(" + ints(method.getParameterCount()) + ");");
                }
            }
        }
        return new ArrayList<String>(calls);
    }

    private static List<String> units() throws Exception {
        List<String> calls = inexactCalls();
        assertTrue("the API was found: " + calls.size(), calls.size() > 500);
        assertTrue(calls.contains("new atlas.script.raven.model.Vec3(0, 0, 0);"));
        List<String> units = new ArrayList<String>();
        for (int from = 0; from < calls.size(); from += CALLS_PER_UNIT) {
            units.add("void calls() {\n" + String.join("\n",
                    calls.subList(from, Math.min(calls.size(), from + CALLS_PER_UNIT))) + "\n}\n");
        }
        return units;
    }

    @Test
    public void ecjNeverMissesAGameTypeInAnOverload() throws Exception {
        Set<String> missing = new TreeSet<String>();
        for (IsolatedEcj.Outcome outcome : IsolatedEcj.compile(ScriptKind.RAVEN17, units())) {
            for (String error : outcome.errors) {
                if (error.contains("indirectly referenced") || error.contains("cannot be resolved to a type")
                        || error.contains("the compiler failed")) {
                    missing.add(error.replaceFirst("^line \\d+: ", ""));
                }
            }
        }
        assertTrue(missing.size() + " missing: " + missing, missing.isEmpty());
    }

    /** javac reads the same signatures, and failed on same-arity overloads until the stubs. */
    @Test
    public void javacNeverMissesAGameTypeInAnOverload() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        File own = ScriptCompiler.codeSource(ScriptSource.class);
        File dir = Files.createTempDirectory("atlas-stubs").toFile();
        try {
            List<File> classpath = Arrays.asList(own, ScriptCompiler.writeStubs(ScriptCompiler.stubTypes(own), dir));
            Set<String> missing = new TreeSet<String>();
            int number = 0;
            for (String unit : units()) {
                ScriptSource.Wrapped wrapped = ScriptSource.wrap(ScriptKind.RAVEN17, "Calls", unit, ++number);
                File base = new File(dir, "unit" + number);
                ScriptCompiler.Result result = ScriptCompiler.compile(javac, Arrays.asList(
                        new ScriptCompiler.Unit(new File(base, wrapped.scriptClass + ".java"), wrapped.code,
                                wrapped.headerLines, "Calls.java"),
                        new ScriptCompiler.Unit(new File(base, wrapped.hostClass + ".java"), wrapped.hostCode, 0,
                                "(host)")), new File(base, "classes"), classpath);
                for (ScriptCompiler.Problem problem : result.problems) {
                    if (problem.error && (problem.message.contains("cannot access")
                            || problem.message.contains("class file for") || problem.message.contains("bad class file"))) {
                        missing.add(problem.message);
                    }
                }
            }
            assertTrue(missing.size() + " missing: " + missing, missing.isEmpty());
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    private static void foreign(Class<?> type, Set<String> out) {
        while (type.isArray()) {
            type = type.getComponentType();
        }
        String name = type.getName();
        if (!type.isPrimitive() && !name.startsWith("atlas.") && !name.startsWith("java.")
                && !name.startsWith("javax.")) {
            out.add(name.replace('.', '/'));
        }
    }

    /** Checked by reflection, against the real game classes: a name no scan finds would be a hole in the scan. */
    @Test
    public void everyTypeInTheApisSignaturesHasAStub() throws Exception {
        Set<String> named = new TreeSet<String>();
        for (Class<?> type : apiClasses()) {
            if (type.getSuperclass() != null) {
                foreign(type.getSuperclass(), named);
            }
            for (Class<?> parent : type.getInterfaces()) {
                foreign(parent, named);
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    foreign(parameter, named);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                foreign(method.getReturnType(), named);
                for (Class<?> parameter : method.getParameterTypes()) {
                    foreign(parameter, named);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                foreign(field.getType(), named);
            }
        }
        assertTrue(named.toString(), named.contains("net/minecraft/util/Vec3"));
        assertTrue(named.toString(), named.contains("net/minecraft/util/BlockPos"));
        Set<String> holes = new TreeSet<String>(named);
        holes.removeAll(ScriptCompiler.stubTypes(ScriptCompiler.codeSource(ScriptSource.class)).keySet());
        assertTrue("no stub: " + holes, holes.isEmpty());
    }

    /** A stub may say less than the real class, never something else: a compiler rejects a wrong shape. */
    @Test
    public void stubsHaveTheShapeOfTheRealClasses() throws Exception {
        Map<String, ScriptCompiler.StubType> types = ScriptCompiler.stubTypes(ScriptCompiler.codeSource(ScriptSource.class));
        assertTrue("generic game type", types.get("net/minecraft/network/Packet").typeParameters > 0);
        assertTrue("game interface", types.get("net/minecraft/network/Packet").isInterface);
        int checked = 0;
        for (Map.Entry<String, ScriptCompiler.StubType> entry : types.entrySet()) {
            Class<?> real;
            try {
                real = Class.forName(entry.getKey().replace('/', '.'), false, getClass().getClassLoader());
            } catch (Throwable absent) {
                continue;
            }
            checked++;
            if (entry.getValue().isInterface) {
                assertTrue(entry.getKey() + " is a class", real.isInterface());
            }
            if (entry.getValue().typeParameters > 0) {
                assertEquals(entry.getKey(), real.getTypeParameters().length, entry.getValue().typeParameters);
            }
        }
        assertTrue("real classes to compare: " + checked, checked > 100);
    }

    @Test
    public void typeArgumentsAreCounted() {
        assertEquals(1, ScriptCompiler.typeArguments("<*>;", 1));
        assertEquals(1, ScriptCompiler.typeArguments("<TT;>;", 1));
        assertEquals(2, ScriptCompiler.typeArguments("<Ljava/util/List<TT;>;[I>;", 1));
        assertEquals(3, ScriptCompiler.typeArguments("<+La/B;-TT;*>;", 1));
    }

    @Test
    public void theRealOverloadCompilesWithEcj() throws Exception {
        IsolatedEcj.Outcome outcome = IsolatedEcj.compile(ScriptKind.RAVEN17,
                Collections.singletonList("Vec3 at = new Vec3(0, 64, 0);\nVec3 half = new Vec3(0.5, 0.5, 0.5);\n")).get(0);
        assertTrue(outcome.errors.toString(), outcome.ok);
    }
}
