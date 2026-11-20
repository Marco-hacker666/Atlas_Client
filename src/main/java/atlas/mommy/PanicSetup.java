package atlas.mommy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class PanicSetup {
    static final File DIR = new File("./config/Atlas/");
    static final File FILE = new File(DIR, "mommy.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Cover {
        public String type = "url";
        public String value = "";
        public String label = "";

        public Cover() {
        }

        public Cover(String type, String value, String label) {
            this.type = type;
            this.value = value;
            this.label = label;
        }
    }

    public List<Cover> cover = new ArrayList<Cover>();

    public List<String> close = new ArrayList<String>();
    public boolean closeForce;

    public String remoteKey = "";
    public int remotePort = 25599;

    private static PanicSetup current;

    public static synchronized PanicSetup get() {
        if (current == null) {
            current = load();
        }
        return current;
    }

    private static PanicSetup load() {
        PanicSetup s = null;
        try {
            if (FILE.isFile()) {
                s = GSON.fromJson(new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8), PanicSetup.class);
            }
        } catch (Exception e) {
            System.err.println("[Atlas] mommy.json could not be read: " + e);
        }
        if (s == null) {
            s = new PanicSetup();
        }
        if (s.cover == null) {
            s.cover = new ArrayList<Cover>();
        }
        if (s.close == null) {
            s.close = new ArrayList<String>();
        }
        if (s.remoteKey == null || s.remoteKey.length() < 32) {
            s.remoteKey = newKey();
            s.saveQuietly();
        }
        if (s.remotePort < 1024 || s.remotePort > 65535) {
            s.remotePort = 25599;
        }
        return s;
    }

    public synchronized void save() throws Exception {
        DIR.mkdirs();
        Files.write(FILE.toPath(), GSON.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    public void saveQuietly() {
        try {
            this.save();
        } catch (Exception e) {
            System.err.println("[Atlas] mommy.json could not be saved: " + e);
        }
    }

    static String newKey() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        }
        return sb.toString();
    }

    public void newRemoteKey() {
        this.remoteKey = newKey();
        this.saveQuietly();
    }

    public static boolean validUrl(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase();
        return (t.startsWith("https://") || t.startsWith("http://")) && t.length() > 10 && !t.contains(" ");
    }

    public static String normalizeUrl(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() || t.contains("://") ? t : "https://" + t;
    }

    public static File customFile(int shell) {
        return new File(DIR, PanicCommands.customFileName(shell));
    }

    static boolean commentLine(String line, int shell) {
        String t = line.trim();
        if (t.isEmpty()) {
            return true;
        }
        return shell == PanicCommands.SHELL_CMD ? t.toUpperCase().startsWith("REM ") || t.equalsIgnoreCase("REM")
                || t.startsWith("::") : t.startsWith("#");
    }

    public static String firstCommand(int shell) {
        File f = ensureCustomFile(shell);
        try {
            for (String line : new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\r?\n")) {
                if (!commentLine(line, shell)) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static int commandLines(int shell) {
        int n = 0;
        try {
            for (String line : new String(Files.readAllBytes(ensureCustomFile(shell).toPath()), StandardCharsets.UTF_8)
                    .split("\r?\n")) {
                if (!commentLine(line, shell)) {
                    n++;
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    public static void setFirstCommand(int shell, String command) throws Exception {
        File f = ensureCustomFile(shell);
        String nl = shell == PanicCommands.SHELL_SH ? "\n" : "\r\n";
        java.util.List<String> lines = new java.util.ArrayList<String>(java.util.Arrays.asList(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\r?\n")));
        String c = command == null ? "" : command.trim();
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (!commentLine(lines.get(i), shell)) {
                at = i;
                break;
            }
        }
        if (at >= 0) {
            if (c.isEmpty()) {
                lines.remove(at);
            } else {
                lines.set(at, c);
            }
        } else if (!c.isEmpty()) {
            lines.add(c);
        }
        Files.write(f.toPath(), (String.join(nl, lines) + nl).getBytes(StandardCharsets.UTF_8));
    }

    public static File ensureCustomFile(int shell) {
        File f = customFile(shell);
        if (!f.isFile()) {
            try {
                DIR.mkdirs();
                Files.write(f.toPath(), PanicCommands.template(shell).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        return f;
    }
}
