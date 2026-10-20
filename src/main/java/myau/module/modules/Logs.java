package myau.module.modules;

import myau.module.Module;
import myau.property.properties.IntProperty;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The master switch for the diagnostic logs (2026-10-20, owner: "too many
 * logs -- one switch, keep only flags normally, delete old ones").
 *
 * Every module that keeps a session log (clutch-, places-, hits-, fights-,
 * fakelag-, perf-, speedmine-, stuck-, lagrange-, timerrange-, bednuker-,
 * noslow-...) writes it through AsyncLog, off the game thread, so the cost
 * was never frames; it was volume: 521 files and 28 MB in config/Atlas in
 * five days, clutch and places logs 0.5-1.5 MB a session each.
 *
 * Off (the default): only flags-*.txt is written -- FlagDetector's record of
 * corrections, which is what a lagback is looked into from. On: every
 * session log, for a test. Either way, at startup session logs older than
 * keep-days are deleted. The fixed-name diagnostics (gui-perf.txt, the
 * fingerprint's servers.txt, adaptive.txt, autotune.txt) are written only
 * while on, never deleted. Settings and learned data (default.json,
 * server-profiles.csv, hud-anchors.txt...) are never touched.
 */
public class Logs extends Module {
    /** "clutch-20261020-191450.txt": a module's session log, named by when it began. */
    private static final Pattern SESSION_LOG = Pattern.compile("^([a-z0-9]+(?:-[a-z0-9]+)*)-(\\d{8})-(\\d{6})\\.txt$");
    private static final File DIR = new File("./config/Atlas/");
    private static volatile boolean full;
    private static final java.util.Set<String> FIXED_DIAGNOSTICS = new java.util.HashSet<String>(
            java.util.Arrays.asList("gui-perf.txt", "servers.txt", "adaptive.txt", "autotune.txt"));

    public final IntProperty keepDays = new IntProperty("keep-days", 7, 1, 60);

    public Logs() {
        super("Logs", false, false, "Writes every diagnostic log while on; off keeps only the flags log. Old logs are deleted at startup.");
    }

    @Override
    public void onEnabled() {
        full = true;
    }

    @Override
    public void onDisabled() {
        full = false;
    }

    /** A session log's module prefix ("clutch"), or null for any other file. */
    static String sessionPrefix(String name) {
        Matcher m = SESSION_LOG.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    /** Whether a log line for this file may be written now. */
    public static boolean allows(File file) {
        if (file == null || full) {
            return true;
        }
        String name = file.getName();
        String prefix = sessionPrefix(name);
        if (prefix != null) {
            return "flags".equals(prefix);
        }
        /* Diagnostics under fixed names: the menu's frame times, the server fingerprint and tuning logs. */
        return !FIXED_DIAGNOSTICS.contains(name);
    }

    /** Deletes session logs older than the given number of days, on a thread of its own. */
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
        }, "Atlas-LogCleanup");
        thread.setDaemon(true);
        thread.start();
    }
}
