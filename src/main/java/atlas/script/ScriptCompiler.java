package atlas.script;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns script sources into classes (2026-11-02, owner: "a tab that loads everyone's scripts, Raven's API and ours").
 *
 * Minecraft 1.8.9 runs on Java 8, and the launchers ship a JRE: javac is not there, so ToolProvider has no compiler
 * for nearly every player (Raven's scripts need a JDK for that reason). The JDK's compiler is used when there is
 * one; otherwise the Eclipse compiler (ECJ, which runs on a JRE) is downloaded once from Maven Central into
 * config/Atlas/scripts/.compiler and checked against its SHA-256 before it is ever loaded (owner's choice: download
 * on first use, so the jar stays its size and ECJ's EPL licence stays out of it). ECJ 3.21.0 is class version 52
 * (Java 8) and was tried on the launcher's 8u312 JRE: lambdas, generics, and API classes naming types it cannot
 * see all compile.
 *
 * ECJ's javax.tools front end reads sources from files, not memory, so each source is written under the build
 * folder first; the classes come out next to it.
 */
public final class ScriptCompiler {
    static final String ECJ_VERSION = "3.21.0";
    static final String ECJ_URL = "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/" + ECJ_VERSION
            + "/ecj-" + ECJ_VERSION + ".jar";
    static final String ECJ_SHA256 = "9082211f48782750093f07822d1ae481e8ece250449578f372334da626ccdead";
    static final long ECJ_SIZE = 2989263L;
    private static final String ECJ_CLASS = "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler";

    /** Where the compiler is kept; tests point it elsewhere. */
    static File compilerDir = new File("./config/Atlas/scripts/.compiler");

    private static JavaCompiler compiler;
    private static URLClassLoader ecjLoader;
    private static volatile String status = "not loaded yet";
    private static volatile boolean downloading;

    private ScriptCompiler() {
    }

    /** One source to compile: the file it is written to, and how many lines the wrapper put before the user's. */
    public static final class Unit {
        public final File file;
        public final String code;
        public final int headerLines;
        public final String shownName;

        public Unit(File file, String code, int headerLines, String shownName) {
            this.file = file;
            this.code = code;
            this.headerLines = headerLines;
            this.shownName = shownName;
        }
    }

    /** One error or warning, at the line of the user's file. */
    public static final class Problem {
        public final boolean error;
        public final String file;
        public final long line;
        public final String message;

        Problem(boolean error, String file, long line, String message) {
            this.error = error;
            this.file = file;
            this.line = line;
            this.message = message;
        }

        @Override
        public String toString() {
            return (this.line > 0 ? "line " + this.line + ": " : "") + this.message;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final List<Problem> problems;

        Result(boolean ok, List<Problem> problems) {
            this.ok = ok;
            this.problems = problems;
        }

        public String firstError() {
            for (Problem problem : this.problems) {
                if (problem.error) {
                    return problem.toString();
                }
            }
            return this.ok ? "" : "compile failed";
        }
    }

    /** What the compiler is doing, for the Scripts tab. */
    public static String status() {
        return status;
    }

    public static boolean isDownloading() {
        return downloading;
    }

    /**
     * The compiler: the JDK's, else ECJ (downloaded the first time). Null when there is none; status() says why.
     * Blocking (a download, a class load): never on the client thread.
     */
    static synchronized JavaCompiler get() {
        if (compiler != null) {
            return compiler;
        }
        JavaCompiler system = null;
        try {
            system = ToolProvider.getSystemJavaCompiler();
        } catch (Throwable ignored) {
            // A JRE: no tools.jar.
        }
        if (system != null) {
            compiler = system;
            status = "JDK compiler";
            return compiler;
        }
        File jar = new File(compilerDir, "ecj-" + ECJ_VERSION + ".jar");
        try {
            if (!jar.isFile() || jar.length() != ECJ_SIZE || !ECJ_SHA256.equals(sha256(jar))) {
                download(jar);
            }
            ecjLoader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ScriptCompiler.class.getClassLoader());
            compiler = (JavaCompiler) Class.forName(ECJ_CLASS, true, ecjLoader).newInstance();
            status = "Eclipse compiler " + ECJ_VERSION;
            return compiler;
        } catch (Throwable e) {
            status = "no compiler: " + e.getMessage();
            return null;
        }
    }

    /** Downloads ECJ into a temporary file, checks size and SHA-256, and only then moves it into place. */
    private static void download(File jar) throws Exception {
        downloading = true;
        status = "downloading the compiler (3 MB, Maven Central)";
        File dir = jar.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }
        File temp = new File(dir, jar.getName() + ".part");
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(ECJ_URL).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Atlas");
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Maven Central answered " + connection.getResponseCode());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > ECJ_SIZE) {
                        throw new IllegalStateException("the download is larger than ECJ " + ECJ_VERSION);
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            String hash = hex(digest.digest());
            if (total != ECJ_SIZE || !ECJ_SHA256.equals(hash)) {
                throw new IllegalStateException("the download does not match ECJ " + ECJ_VERSION + "'s SHA-256");
            }
            Files.move(temp.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temp.delete();
            downloading = false;
        }
    }

    /**
     * Compiles the units into {@code outDir} against {@code classpath} (plus the running Java's own classes).
     * Problems come back at the lines of the user's files.
     */
    static Result compile(JavaCompiler javac, List<Unit> units, File outDir, List<File> classpath) {
        List<Problem> problems = new ArrayList<Problem>();
        try {
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                problems.add(new Problem(true, "", 0, "cannot create " + outDir));
                return new Result(false, problems);
            }
            List<File> files = new ArrayList<File>();
            for (Unit unit : units) {
                File dir = unit.file.getParentFile();
                if (dir != null && !dir.isDirectory()) {
                    dir.mkdirs();
                }
                Files.write(unit.file.toPath(), unit.code.getBytes(StandardCharsets.UTF_8));
                files.add(unit.file);
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
            StandardJavaFileManager manager = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                    StandardCharsets.UTF_8);
            StringBuilder path = new StringBuilder();
            for (File entry : classpath) {
                if (path.length() > 0) {
                    path.append(File.pathSeparatorChar);
                }
                path.append(entry.getAbsolutePath());
            }
            List<String> options = new ArrayList<String>();
            options.add("-d");
            options.add(outDir.getAbsolutePath());
            options.add("-classpath");
            options.add(path.toString());
            options.add("-source");
            options.add("1.8");
            options.add("-target");
            options.add("1.8");
            options.add("-encoding");
            options.add("UTF-8");
            options.add("-proc:none");
            options.add("-nowarn");
            options.add("-g");
            boolean ok;
            try {
                ok = javac.getTask(null, manager, diagnostics, options, null,
                        manager.getJavaFileObjectsFromFiles(files)).call();
            } finally {
                manager.close();
            }
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                boolean error = diagnostic.getKind() == Diagnostic.Kind.ERROR;
                if (!error && diagnostic.getKind() != Diagnostic.Kind.WARNING
                        && diagnostic.getKind() != Diagnostic.Kind.MANDATORY_WARNING) {
                    continue;
                }
                Unit unit = unitOf(units, diagnostic.getSource());
                long line = diagnostic.getLineNumber();
                if (unit != null && line > 0) {
                    line = Math.max(1, line - unit.headerLines);
                }
                String message = diagnostic.getMessage(Locale.ROOT);
                int newline = message == null ? -1 : message.indexOf('\n');
                if (newline > 0) {
                    message = message.substring(0, newline);
                }
                problems.add(new Problem(error, unit == null ? "" : unit.shownName, line, message));
            }
            return new Result(ok, problems);
        } catch (Throwable e) {
            problems.add(new Problem(true, "", 0, "the compiler failed: " + e));
            return new Result(false, problems);
        }
    }

    private static Unit unitOf(List<Unit> units, JavaFileObject source) {
        if (source == null) {
            return null;
        }
        String name = new File(source.toUri().getPath()).getName();
        for (Unit unit : units) {
            if (unit.file.getName().equals(name)) {
                return unit;
            }
        }
        return null;
    }

    /**
     * What scripts compile against: this client's own jar (or classes folder), and the game's libraries -- not the
     * obfuscated Minecraft jar, whose one-letter classes would only be noise.
     */
    static List<File> classpath() {
        Set<File> out = new LinkedHashSet<File>();
        File own = codeSource(ScriptCompiler.class);
        if (own != null) {
            out.add(own);
        }
        File minecraft = null;
        try {
            minecraft = codeSource(Class.forName("net.minecraft.client.Minecraft"));
        } catch (Throwable ignored) {
            // Not in a game (tests).
        }
        String path = System.getProperty("java.class.path", "");
        for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isEmpty()) {
                continue;
            }
            File file = new File(entry);
            if (file.exists() && !file.equals(minecraft)) {
                out.add(file);
            }
        }
        return new ArrayList<File>(out);
    }

    /** The jar or folder a class was loaded from, or null. */
    static File codeSource(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String location = source.getLocation().toString();
            if (location.startsWith("jar:")) {
                location = location.substring(4);
                int bang = location.indexOf("!/");
                if (bang >= 0) {
                    location = location.substring(0, bang);
                }
            }
            if (location.startsWith("file:")) {
                File file = new File(URLDecoder.decode(new URL(location).getPath(), "UTF-8"));
                return file.exists() ? file.getAbsoluteFile() : null;
            }
        } catch (Throwable ignored) {
            // Unknown.
        }
        return null;
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(Files.readAllBytes(file.toPath())));
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b & 0xFF));
        }
        return out.toString();
    }
}
