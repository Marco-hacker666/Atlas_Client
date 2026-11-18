package atlas.script;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// download on first use, so the jar stays its size and ECJ's EPL licence stays out of it).

public final class ScriptCompiler {
    static final String ECJ_VERSION = "3.21.0";
    static final String ECJ_URL = "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/" + ECJ_VERSION
            + "/ecj-" + ECJ_VERSION + ".jar";
    static final String ECJ_SHA256 = "9082211f48782750093f07822d1ae481e8ece250449578f372334da626ccdead";
    static final long ECJ_SIZE = 2989263L;
    private static final String ECJ_CLASS = "org.eclipse.jdt.internal.compiler.tool.EclipseCompiler";

    static File compilerDir = new File("./config/Atlas/scripts/.compiler");

    private static JavaCompiler compiler;
    private static URLClassLoader ecjLoader;
    private static volatile String status = "not loaded yet";
    private static volatile boolean downloading;
    private static File gameStubs;
    private static boolean gameStubsMade;

    private static final Pattern TYPE_IN_SIGNATURE = Pattern.compile("L([A-Za-z_][\\w]*(?:/[\\w$]+)+)([;<])");

    private ScriptCompiler() {
    }

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

    public static String status() {
        return status;
    }

    public static boolean isDownloading() {
        return downloading;
    }

    static synchronized JavaCompiler get() {
        if (compiler != null) {
            return compiler;
        }
        JavaCompiler system = null;
        try {
            system = ToolProvider.getSystemJavaCompiler();
        } catch (Throwable ignored) {
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
        File stubs = gameStubs(own);
        if (stubs != null) {
            out.add(stubs);
        }
        return new ArrayList<File>(out);
    }

    static final class StubType {
        boolean isInterface;
        int typeParameters;
    }

    private static synchronized File gameStubs(File own) {
        if (!gameStubsMade) {
            gameStubsMade = true;
            try {
                gameStubs = own == null ? null : writeStubs(stubTypes(own), compilerDir);
            } catch (Throwable ignored) {
                gameStubs = null;
            }
        }
        return gameStubs;
    }

    static Map<String, StubType> stubTypes(File own) throws IOException {
        Map<String, StubType> found = new TreeMap<String, StubType>();
        if (own.isDirectory()) {
            scanFolder(new File(own, "atlas"), found);
        } else {
            try (JarFile jar = new JarFile(own)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().startsWith("atlas/") && entry.getName().endsWith(".class")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            scanClass(readAll(in), found);
                        }
                    }
                }
            }
        }

        for (String name : new ArrayList<String>(found.keySet())) {
            for (int dollar = name.indexOf('$', 1); dollar > 0; dollar = name.indexOf('$', dollar + 1)) {
                stubType(name.substring(0, dollar), found);
            }
        }
        return found;
    }

    private static void scanFolder(File folder, Map<String, StubType> found) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                scanFolder(file, found);
            } else if (file.getName().endsWith(".class")) {
                scanClass(Files.readAllBytes(file.toPath()), found);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static StubType stubType(String name, Map<String, StubType> found) {
        if (name.startsWith("atlas/") || name.startsWith("java/") || name.startsWith("javax/")) {
            return null;
        }
        StubType type = found.get(name);
        if (type == null) {
            type = new StubType();
            found.put(name, type);
        }
        return type;
    }

    private static void scanClass(byte[] bytes, Map<String, StubType> found) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();
        in.readInt();
        int count = in.readUnsignedShort();
        String[] texts = new String[count];
        int[] classes = new int[count];
        boolean[] interfaces = new boolean[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    texts[i] = in.readUTF();
                    break;
                case 3:
                case 4:
                    in.readInt();
                    break;
                case 5:
                case 6:
                    in.readLong();
                    i++;
                    break;
                case 7:
                    classes[i] = in.readUnsignedShort();
                    break;
                case 8:
                case 16:
                    in.readUnsignedShort();
                    break;
                case 11:
                    interfaces[in.readUnsignedShort()] = true;
                    in.readUnsignedShort();
                    break;
                case 9:
                case 10:
                case 12:
                case 18:
                    in.readInt();
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("constant tag " + tag);
            }
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        for (int left = in.readUnsignedShort(); left > 0; left--) {
            interfaces[in.readUnsignedShort()] = true;
        }
        for (int i = 1; i < count; i++) {
            if (texts[i] != null) {
                Matcher matcher = TYPE_IN_SIGNATURE.matcher(texts[i]);
                while (matcher.find()) {
                    StubType type = stubType(matcher.group(1), found);
                    if (type != null && matcher.group(2).equals("<")) {
                        type.typeParameters = Math.max(type.typeParameters, typeArguments(texts[i], matcher.end()));
                    }
                }
            }
            if (classes[i] != 0 && texts[classes[i]] != null && texts[classes[i]].charAt(0) != '[') {
                StubType type = stubType(texts[classes[i]], found);
                if (type != null && interfaces[i]) {
                    type.isInterface = true;
                }
            }
        }
    }

    static int typeArguments(String signature, int at) {
        int count = 0;
        int i = at;
        while (i < signature.length()) {
            char c = signature.charAt(i);
            if (c == '>') {
                return count;
            }
            if (c == '+' || c == '-' || c == '[') {
                i++;
                continue;
            }
            count++;
            if (c != 'L' && c != 'T') {
                i++;
                continue;
            }
            int depth = 0;
            for (; i < signature.length(); i++) {
                char inner = signature.charAt(i);
                if (inner == '<') {
                    depth++;
                } else if (inner == '>') {
                    depth--;
                } else if (inner == ';' && depth == 0) {
                    i++;
                    break;
                }
            }
        }
        return 0;
    }

    static File writeStubs(Map<String, StubType> types, File dir) throws Exception {
        StringBuilder list = new StringBuilder();
        for (Map.Entry<String, StubType> entry : types.entrySet()) {
            list.append(entry.getKey()).append(' ').append(entry.getValue().isInterface).append(' ')
                    .append(entry.getValue().typeParameters).append('\n');
        }
        String id = hex(MessageDigest.getInstance("SHA-256").digest(list.toString().getBytes(StandardCharsets.UTF_8)));
        File jar = new File(dir, "game-stubs-" + id.substring(0, 12) + ".jar");
        if (jar.isFile()) {
            return jar;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File temp = File.createTempFile("game-stubs", ".part", dir);
        try {
            try (JarOutputStream out = new JarOutputStream(new FileOutputStream(temp))) {
                for (Map.Entry<String, StubType> entry : types.entrySet()) {
                    out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                    out.write(stubClass(entry.getKey(), entry.getValue()));
                    out.closeEntry();
                }
            }
            Files.move(temp.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            temp.delete();
        }
        return jar;
    }

    static byte[] stubClass(String name, StubType type) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        boolean generic = type.typeParameters > 0;
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(52);
        out.writeShort(generic ? 7 : 5);
        out.writeByte(1);
        out.writeUTF(name);
        out.writeByte(7);
        out.writeShort(1);
        out.writeByte(1);
        out.writeUTF("java/lang/Object");
        out.writeByte(7);
        out.writeShort(3);
        if (generic) {
            StringBuilder signature = new StringBuilder("<");
            for (int i = 0; i < type.typeParameters; i++) {
                signature.append('T').append(i).append(":Ljava/lang/Object;");
            }
            out.writeByte(1);
            out.writeUTF("Signature");
            out.writeByte(1);
            out.writeUTF(signature.append(">Ljava/lang/Object;").toString());
        }
        out.writeShort(type.isInterface ? 0x0601 : 0x0021);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(generic ? 1 : 0);
        if (generic) {
            out.writeShort(5);
            out.writeInt(2);
            out.writeShort(6);
        }
        return bytes.toByteArray();
    }

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
