package atlas.script;

import org.junit.Assume;

import javax.tools.JavaCompiler;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ECJ in a JVM of its own (2026-11-04). ECJ also reads java.class.path, whatever -classpath says, so inside the test
 * JVM it sees the deobfuscated game classes that no player has: new Vec3(0, 64, 0) passed here and failed for players.
 * The child JVM has this client's classes, the tests' and ECJ, as a game with a JRE only would.
 */
final class IsolatedEcj {
    private static final File JAR = new File("../backups/scripts-work/ecj-" + ScriptCompiler.ECJ_VERSION + ".jar");

    private IsolatedEcj() {
    }

    /** What one source gave: ok, and every error, first lines only. */
    static final class Outcome {
        final boolean ok;
        final List<String> errors;

        Outcome(boolean ok, List<String> errors) {
            this.ok = ok;
            this.errors = errors;
        }
    }

    static void assumeJar() throws Exception {
        Assume.assumeTrue("ECJ is not in backups/scripts-work", JAR.isFile());
        if (!ScriptCompiler.ECJ_SHA256.equals(ScriptCompiler.sha256(JAR))) {
            throw new AssertionError("not the pinned ECJ");
        }
    }

    /** Compiles each source separately, in one child JVM. */
    static List<Outcome> compile(ScriptKind kind, List<String> sources) throws Exception {
        assumeJar();
        File dir = Files.createTempDirectory("atlas-ecj").toFile();
        try {
            List<String> command = new ArrayList<String>(Arrays.asList(
                    new File(System.getProperty("java.home"), "bin/java").getPath(), "-cp",
                    ScriptCompiler.codeSource(ScriptSource.class) + File.pathSeparator
                            + ScriptCompiler.codeSource(IsolatedEcj.class) + File.pathSeparator
                            + JAR.getAbsolutePath(),
                    IsolatedEcj.class.getName(), kind.name(), dir.getAbsolutePath()));
            for (int i = 0; i < sources.size(); i++) {
                File source = new File(dir, "source" + i + ".txt");
                Files.write(source.toPath(), sources.get(i).getBytes(StandardCharsets.UTF_8));
                command.add(source.getAbsolutePath());
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            List<Outcome> outcomes = new ArrayList<Outcome>();
            List<String> errors = new ArrayList<String>();
            List<String> all = new ArrayList<String>();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    all.add(line);
                    if (line.startsWith("@error ")) {
                        errors.add(line.substring(7));
                    } else if (line.startsWith("@done ")) {
                        outcomes.add(new Outcome(line.equals("@done true"), errors));
                        errors = new ArrayList<String>();
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0 || outcomes.size() != sources.size()) {
                throw new AssertionError("the child JVM failed (" + exit + "): "
                        + all.subList(Math.max(0, all.size() - 30), all.size()));
            }
            return outcomes;
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    /** The child JVM: kind, work folder, sources. */
    public static void main(String[] args) throws Exception {
        ScriptKind kind = ScriptKind.valueOf(args[0]);
        File work = new File(args[1]);
        ScriptCompiler.compilerDir = new File(work, "compiler");
        JavaCompiler ecj = (JavaCompiler) Class.forName("org.eclipse.jdt.internal.compiler.tool.EclipseCompiler")
                .newInstance();
        /* ECJ prints its own report as well; only the marked lines count. */
        List<File> classpath = ScriptCompiler.classpath();
        for (int i = 2; i < args.length; i++) {
            String source = new String(Files.readAllBytes(new File(args[i]).toPath()), StandardCharsets.UTF_8);
            ScriptSource.Wrapped wrapped = ScriptSource.wrap(kind, "Isolated", source, i);
            File dir = new File(work, "build" + i);
            File sources = new File(dir, "src");
            ScriptCompiler.Result result = ScriptCompiler.compile(ecj, Arrays.asList(
                    new ScriptCompiler.Unit(new File(sources, wrapped.scriptClass + ".java"), wrapped.code,
                            wrapped.headerLines, "Isolated.java"),
                    new ScriptCompiler.Unit(new File(sources, wrapped.hostClass + ".java"), wrapped.hostCode, 0,
                            "(host)")), new File(dir, "classes"), classpath);
            for (ScriptCompiler.Problem problem : result.problems) {
                if (problem.error) {
                    System.out.println("@error " + problem);
                }
            }
            System.out.println("@done " + result.ok);
        }
    }
}
