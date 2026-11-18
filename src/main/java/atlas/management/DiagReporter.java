package atlas.management;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class DiagReporter {
    static final long EVERY_MS = 5L * 60_000L;
    static final int PART_CHARS = 48_000;
    private static final int MAX_CRASHES = 3;
    private static final long CRASH_AGE_MS = 7L * 86_400_000L;
    private static final File ATLAS_DIR = new File("./config/Atlas/");
    private static final File GAME_LOG = new File("./logs/latest.log");
    private static final File CRASH_DIR = new File("./crash-reports/");
    private static final File CRASH_SEEN = new File(ATLAS_DIR, "diag-crash-seen.txt");
    private static final String[] LOGS = {"flags-", "moves-", "lagguard-", "perf-", "places-", "snapspeed-",
            "godbridge-"};

    private static final long GAME_START = startTime();

    private static final Pattern AC_CHAT = Pattern.compile(
            "(?i)(intave|polar|grim|vulcan|matrix|verus|spartan|karhu|negativity|nocheat|ncp|aac|watchdog|anticheat)"
                    + ".*(fail|flag|vl|violat|detect|kick|ban)");
    private static final Pattern KEEP = Pattern.compile(
            "WARN|ERROR|Exception|Caused by|^\\s+at |\\[Atlas|Mixin|mixin");
    private static final Pattern USER_DIR = Pattern.compile("(?i)([A-Z]:[\\\\/]+Users[\\\\/]+)[^\\\\/\\s\"']+");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(access_?token|session_?id|--accessToken|token)([\"']?\\s*[:=,]?\\s*[\"']?)[A-Za-z0-9._\\-]{20,}"
                    + "|eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}");

    private static final Map<String, Long> SENT_TO = new HashMap<String, Long>();

    private DiagReporter() {
    }

    static String environment(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# mods\n");
        try {
            for (net.minecraftforge.fml.common.ModContainer m
                    : net.minecraftforge.fml.common.Loader.instance().getModList()) {
                sb.append(m.getModId()).append(" | ").append(m.getName()).append(" | ").append(m.getVersion())
                        .append('\n');
            }
        } catch (Throwable t) {
            sb.append("(not readable: ").append(t.getClass().getSimpleName()).append(")\n");
        }
        sb.append("\n# resource packs (in use)\n");
        try {
            for (Object entry : mc.getResourcePackRepository().getRepositoryEntries()) {
                net.minecraft.client.resources.ResourcePackRepository.Entry e =
                        (net.minecraft.client.resources.ResourcePackRepository.Entry) entry;
                sb.append(e.getResourcePackName()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("(not readable: ").append(t.getClass().getSimpleName()).append(")\n");
        }
        try {
            List<String> chosen = mc.gameSettings.resourcePacks;
            sb.append("\n# resource packs (options.txt)\n");
            for (String s : chosen) {
                sb.append(s).append('\n');
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    static void sessionStart(String sid, String player, String environment) {
        send(sid, player, "env", "mods-and-packs", environment);
        long seen = crashSeen();
        long newest = seen;
        List<File> crashes = new ArrayList<File>();
        File[] reports = CRASH_DIR.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        File[] jvm = new File(".").listFiles((d, n) -> n.startsWith("hs_err_pid") && n.endsWith(".log"));
        for (File[] list : new File[][]{reports, jvm}) {
            if (list != null) {
                crashes.addAll(Arrays.asList(list));
            }
        }
        crashes.removeIf(f -> f.lastModified() <= seen);
        crashes.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < crashes.size() && i < MAX_CRASHES; i++) {
            File f = crashes.get(i);
            try {
                String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                send(sid, player, "crash", f.getName(), text);
            } catch (Exception ignored) {
            }
            newest = Math.max(newest, f.lastModified());
        }
        if (newest > seen) {
            try {
                Files.write(CRASH_SEEN.toPath(), Long.toString(newest).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    static void logs(String sid, String player) {
        File[] files = ATLAS_DIR.listFiles((d, n) -> n.endsWith(".txt") && startsWithAny(n));
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                if (f.lastModified() < GAME_START) {
                    continue;
                }
                String added = readNew(f);
                if (added != null && !added.trim().isEmpty()) {
                    send(sid, player, "log", f.getName(), added);
                }
            }
        }
        String game = readNew(GAME_LOG);
        if (game != null) {
            String kept = filterGameLog(game);
            if (!kept.trim().isEmpty()) {
                send(sid, player, "gamelog", "latest.log", kept);
            }
        }
    }

    static String filterGameLog(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            boolean chat = line.contains("[CHAT]");
            if (chat ? AC_CHAT.matcher(line).find() : KEEP.matcher(line).find()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    static String scrub(String text) {
        String out = USER_DIR.matcher(text).replaceAll("$1~");
        return TOKEN.matcher(out).replaceAll("[token removed]");
    }

    static String tail(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return "[" + (text.length() - max) + " characters before this left out]\n" + text.substring(text.length() - max);
    }

    private static boolean startsWithAny(String name) {
        for (String p : LOGS) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String readNew(File f) {
        if (!f.isFile()) {
            return null;
        }
        String key = f.getAbsolutePath();
        long from = SENT_TO.containsKey(key) ? SENT_TO.get(key) : 0L;
        long length = f.length();
        if (length < from) {
            from = 0L;
        }
        if (length == from) {
            return null;
        }
        long start = Math.max(from, length - PART_CHARS * 2L);
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            in.seek(start);
            byte[] b = new byte[(int) (length - start)];
            in.readFully(b);
            SENT_TO.put(key, length);
            String text = new String(b, StandardCharsets.UTF_8);
            return start > from ? "[earlier lines left out]\n" + text : text;
        } catch (Exception e) {
            return null;
        }
    }

    private static void send(String sid, String player, String kind, String name, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("sid", sid);
        o.addProperty("player", player.isEmpty() ? "-" : player);
        o.addProperty("consent_v", UsageReporter.CONSENT_VERSION);
        o.addProperty("kind", kind);
        o.addProperty("name", name.length() > 80 ? name.substring(0, 80) : name);
        o.addProperty("content", tail(scrub(content), PART_CHARS));
        UsageReporter.postDiag(o);
    }

    private static long crashSeen() {
        try {
            if (CRASH_SEEN.isFile()) {
                return Long.parseLong(new String(Files.readAllBytes(CRASH_SEEN.toPath()), StandardCharsets.UTF_8)
                        .trim());
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis() - CRASH_AGE_MS;
    }

    private static long startTime() {
        try {
            return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }
}
