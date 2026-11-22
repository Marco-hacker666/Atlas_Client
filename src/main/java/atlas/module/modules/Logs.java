package atlas.module.modules;

import atlas.module.Module;
import atlas.property.properties.IntProperty;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Logs extends Module {
    private static final Pattern SESSION_LOG = Pattern.compile("^([a-z0-9]+(?:-[a-z0-9]+)*)-(\\d{8})-(\\d{6})\\.txt$");
    private static final File DIR = new File("./config/Atlas/");
    private static volatile boolean full = true;
    private static final java.util.Set<String> FIXED_DIAGNOSTICS = new java.util.HashSet<String>(
            java.util.Arrays.asList("gui-perf.txt", "servers.txt", "adaptive.txt", "autotune.txt"));

    private static final long FIXED_MAX_BYTES = 1024L * 1024L;

    public final IntProperty keepDays = new IntProperty("keep-days", 7, 1, 60);
    public final atlas.property.properties.BooleanProperty defaultsApplied =
            new atlas.property.properties.BooleanProperty("diagnostics-defaults", false, () -> false);

    public Logs() {
        super("Logs", true, true, "Writes every diagnostic log while on; off keeps only the flags log. Old logs are deleted at startup.");
    }

    public static void applyDiagnosticDefaults() {
        if (atlas.Atlas.moduleManager == null) {
            return;
        }
        Logs logs = (Logs) atlas.Atlas.moduleManager.modules.get(Logs.class);
        Debug debug = (Debug) atlas.Atlas.moduleManager.modules.get(Debug.class);
        if (logs == null || logs.defaultsApplied.getValue()) {
            return;
        }
        logs.defaultsApplied.setValue(true);
        if (!logs.isEnabled()) {
            logs.setEnabled(true);
            logs.setHidden(true);
        }
        if (debug != null && !debug.isEnabled()) {
            debug.chat.setValue(false);
            debug.autoDisable.setValue(false);
            debug.governor.setValue(false);
            debug.setEnabled(true);
            debug.setHidden(true);
        }
    }

    @Override
    public void onEnabled() {
        full = true;
    }

    @Override
    public void onDisabled() {
        full = false;
    }

    static String sessionPrefix(String name) {
        Matcher m = SESSION_LOG.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    public static boolean allows(File file) {
        if (file == null || full) {
            return true;
        }
        String name = file.getName();
        String prefix = sessionPrefix(name);
        if (prefix != null) {
            return "flags".equals(prefix);
        }

        return !FIXED_DIAGNOSTICS.contains(name);
    }

    public static void cleanOld(final int days) {
        Thread thread = new Thread(() -> {
            File[] files = DIR.listFiles();
            if (files == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L;
            int deleted = 0;
            for (File file : files) {
                if (file.isFile() && sessionPrefix(file.getName()) != null && file.lastModified() < cutoff
                        && file.delete()) {
                    deleted++;
                }
            }
            if (deleted > 0) {
                System.out.println("[Atlas] Logs: deleted " + deleted + " log(s) older than " + days + " day(s)");
            }

            for (String name : FIXED_DIAGNOSTICS) {
                File file = new File(DIR, name);
                if (file.isFile() && file.length() > FIXED_MAX_BYTES) {
                    try {
                        java.nio.file.Files.move(file.toPath(), new File(DIR, name + ".old").toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.io.IOException ignored) {
                    }
                }
            }
        }, "Atlas-LogCleanup");
        thread.setDaemon(true);
        thread.start();
    }
}
