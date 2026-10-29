package atlas.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Online updates (2026-10-13).
 *
 * On start the client reads update.properties from the newest GitHub release.
 * If that release is newer than the running jar, then depending on the mode:
 *   OFF    -- nothing.
 *   NOTIFY -- a chat line says a new version exists (".update download" gets it).
 *   AUTO   -- the jar is downloaded in the background, its size, hash and
 *             signature are checked, and it is swapped in after the game
 *             closes (UpdateInstaller). The next start runs the new version.
 *
 * Nothing here touches Minecraft classes: messages go out through a
 * listener (UpdateNotifier shows them in chat), so the logic can be tested
 * on its own.
 *
 * Files, all under .minecraft/atlas-update/:
 *   pending/      the downloaded, verified jar waiting for the game to close
 *   installer/    the copy of UpdateInstaller.class that does the swap
 *   backup/       the last few replaced jars
 *   install.log   what the installer did
 * Settings: config/Atlas/updater.properties (mode=auto|notify|off).
 */
public final class Updater {

    public enum Mode { OFF, NOTIFY, AUTO }

    public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

    public interface Listener {
        void message(String zh, String en);
    }

    public static final String REPO = "Marco-hacker666/Atlas_Client";
    public static final String MANIFEST_NAME = "update.properties";
    static String releasesBase = "https://github.com/" + REPO + "/releases";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024;
    /**
     * Waits before each further attempt, ms (2026-10-29: a connection reset half way, a name that would not
     * resolve, a stall -- each used to end the update until the next start, and the half-downloaded jar was thrown
     * away). A jar download goes on from where it stopped.
     */
    static long[] retryDelaysMs = {1000L, 3000L, 8000L};

    private static Updater instance;

    private final File gameDir;
    private final File workDir;
    private final File settingsFile;
    private final UpdateVersion current;
    private final PublicKey key;
    private final List<Listener> listeners = new ArrayList<Listener>();

    private volatile Mode mode = Mode.AUTO;
    private volatile State state = State.IDLE;
    private volatile UpdateManifest latest;
    private volatile File pendingJar;
    private volatile boolean installCancelled;
    private volatile String lastError;
    private volatile File currentJarOverride;
    /* 2026-10-26: one background check or download at a time. The state checks in checkAsync/downloadAsync were
       check-then-act: the start-up check and a ".update check" (or ".update mode auto") could both download into
       the same .part file, and one's failure deleted the other's file. */
    private final java.util.concurrent.atomic.AtomicBoolean working = new java.util.concurrent.atomic.AtomicBoolean();
    /* 2026-10-26: the version ".update cancel" skipped (updater.properties "skip"); null when none. It is not
       downloaded again on later starts; a newer version, or ".update download", clears it. */
    private volatile UpdateVersion skipped;

    Updater(File gameDir, String currentVersion, PublicKey key) {
        this.gameDir = gameDir;
        this.workDir = new File(gameDir, "atlas-update");
        this.settingsFile = new File(gameDir, "config/Atlas/updater.properties");
        this.current = UpdateVersion.parse(currentVersion);
        this.key = key;
        loadSettings();
    }

    // ------------------------------------------------------------------ setup

    /** Called once from the client's init. Starts a background check unless OFF. */
    public static synchronized Updater start(File gameDir, String currentVersion, Listener listener) {
        if (instance != null) {
            return instance;
        }
        instance = new Updater(gameDir, currentVersion, UpdateSignature.bundledKey());
        if (listener != null) {
            instance.addListener(listener);
        }
        instance.cleanStaleFiles();
        atlas.management.Shutdown.register(atlas.management.Shutdown.Stage.FLUSH_LOGS, "updater", new Runnable() {
            @Override
            public void run() {
                instance.launchInstallerIfReady();
            }
        });
        if (instance.mode != Mode.OFF) {
            instance.checkAsync(false);
        }
        return instance;
    }

    public static Updater get() {
        return instance;
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    private void say(String zh, String en) {
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<Listener>(listeners);
        }
        for (Listener l : copy) {
            try {
                l.message(zh, en);
            } catch (Throwable ignored) {
            }
        }
    }

    // --------------------------------------------------------------- settings

    private void loadSettings() {
        if (!settingsFile.isFile()) {
            return;
        }
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(settingsFile);
            p.load(in);
            Mode m = parseMode(p.getProperty("mode"));
            if (m != null) {
                mode = m;
            }
            skipped = UpdateVersion.parse(p.getProperty("skip"));
        } catch (IOException ignored) {
        } finally {
            closeQuietly(in);
        }
    }

    private void saveSettings() {
        Properties p = new Properties();
        p.setProperty("mode", mode.name().toLowerCase(Locale.ROOT));
        UpdateVersion skip = skipped;
        if (skip != null) {
            p.setProperty("skip", skip.toString());
        }
        OutputStream out = null;
        try {
            settingsFile.getParentFile().mkdirs();
            out = new FileOutputStream(settingsFile);
            p.store(out, "Atlas online updates: mode = auto | notify | off; skip = a version not to install");
        } catch (IOException ignored) {
        } finally {
            closeQuietly(out);
        }
    }

    public static Mode parseMode(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.equals("auto") || t.equals("on") || t.equals("自動")) {
            return Mode.AUTO;
        }
        if (t.equals("notify") || t.equals("ask") || t.equals("提醒")) {
            return Mode.NOTIFY;
        }
        if (t.equals("off") || t.equals("關閉")) {
            return Mode.OFF;
        }
        return null;
    }

    public void setMode(Mode m) {
        mode = m;
        saveSettings();
        UpdateManifest l = latest;
        UpdateVersion skip = skipped;
        if (m == Mode.AUTO && state == State.AVAILABLE && l != null && (skip == null || l.version.isNewerThan(skip))) {
            /* Switching to auto gets what is waiting, but not a version the player skipped. */
            startDownload(false);
        }
    }

    // ------------------------------------------------------------------ check

    public void checkAsync(final boolean manual) {
        if (!working.compareAndSet(false, true)) {
            if (manual) {
                say("已經在檢查或下載更新了，請稍等。", "An update check or download is already running; wait a moment.");
            }
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check(manual);
                } finally {
                    working.set(false);
                }
            }
        }, "Atlas-Updater");
        t.setDaemon(true);
        t.start();
    }

    void check(boolean manual) {
        if (current == null) {
            state = State.FAILED;
            lastError = "development build";
            if (manual) {
                say("開發版（沒有版本號）不檢查更新。", "Development builds (no version) do not check for updates.");
            }
            return;
        }
        if (state == State.READY && pendingJar != null && pendingJar.isFile()) {
            /* Already downloaded and verified: do not fetch it a second time. */
            if (manual) {
                say("v" + latest.version + " 已經下載好了，關閉遊戲後會安裝。",
                        "v" + latest.version + " is already downloaded; it installs when the game closes.");
            }
            return;
        }
        state = State.CHECKING;
        UpdateManifest m;
        try {
            m = UpdateManifest.parse(new String(download(manifestUrl(), MAX_MANIFEST_BYTES), StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail(e.getMessage(), manual);
            return;
        }
        if (!m.version.isNewerThan(current)) {
            state = State.UP_TO_DATE;
            latest = m;
            if (manual) {
                say("已經是最新版（v" + current + "）。", "You are on the latest version (v" + current + ").");
            }
            return;
        }
        latest = m;
        if (!UpdateSignature.verify(m, key)) {
            /* No key in this build, or a signature that does not verify:
               never install, but the player may still want to know. */
            state = State.AVAILABLE;
            lastError = key == null ? "this build has no update key" : "signature check failed";
            if (key == null) {
                say("發現新版 v" + m.version + "（目前 v" + current + "）。這個版本不支援自動安裝，請到 GitHub 下載。",
                        "Version v" + m.version + " is out (you have v" + current + "). This build cannot install it automatically; download it from GitHub.");
            } else {
                say("§c新版 v" + m.version + " 的簽章驗證失敗，已忽略。如果持續發生請回報。",
                        "§cThe signature of v" + m.version + " did not verify, so it was ignored. Please report this if it keeps happening.");
            }
            return;
        }
        state = State.AVAILABLE;
        UpdateVersion skip = skipped;
        if (skip != null && !m.version.isNewerThan(skip)) {
            /* Skipped with ".update cancel": not downloaded again; ".update download" still gets it. */
            if (manual) {
                say("v" + m.version + " 已設定為跳過。要安裝請輸入 .update download。",
                        "v" + m.version + " is skipped. Type .update download to install it anyway.");
            }
            return;
        }
        if (skip != null) {
            /* A newer version than the skipped one: the skip is over. */
            skipped = null;
            saveSettings();
        }
        if (mode == Mode.AUTO) {
            say("發現新版 v" + m.version + "（目前 v" + current + "），正在背景下載…",
                    "Version v" + m.version + " is out (you have v" + current + "). Downloading in the background...");
            download(m);
        } else {
            say("發現新版 v" + m.version + "（目前 v" + current + "）。輸入 .update download 下載，關閉遊戲後自動安裝。",
                    "Version v" + m.version + " is out (you have v" + current + "). Type .update download to get it; it installs when the game closes.");
        }
    }

    private void fail(String why, boolean manual) {
        state = State.FAILED;
        lastError = why;
        if (manual) {
            say("檢查更新失敗：" + why, "Update check failed: " + why);
        }
    }

    // --------------------------------------------------------------- download

    /** A download the player asked for (".update download", or switching to auto): it also undoes a skip. */
    public void downloadAsync() {
        startDownload(true);
    }

    private void startDownload(boolean undoSkip) {
        final UpdateManifest m = latest;
        if (m == null || state == State.READY || !working.compareAndSet(false, true)) {
            return;
        }
        if (undoSkip && skipped != null) {
            skipped = null;
            saveSettings();
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    download(m);
                } finally {
                    working.set(false);
                }
            }
        }, "Atlas-Updater-Download");
        t.setDaemon(true);
        t.start();
    }

    void download(UpdateManifest m) {
        if (!UpdateSignature.verify(m, key)) {
            fail("signature check failed", true);
            return;
        }
        File currentJar = currentJar();
        if (currentJar == null) {
            fail("cannot find the running jar (development environment?)", true);
            return;
        }
        state = State.DOWNLOADING;
        File pendingDir = new File(workDir, "pending");
        File part = new File(pendingDir, m.file + ".part");
        File done = new File(pendingDir, m.file);
        try {
            pendingDir.mkdirs();
            downloadToFile(jarUrl(m), part, m.size);
            if (!UpdateSignature.matches(m, part)) {
                part.delete();
                throw new IOException("downloaded file does not match the signed hash");
            }
            Files.move(part.toPath(), done.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            part.delete();
            state = State.FAILED;
            lastError = e.getMessage();
            say("§c下載更新失敗：" + e.getMessage() + "（下次啟動會再試）",
                    "§cUpdate download failed: " + e.getMessage() + " (it will retry next start)");
            return;
        }
        pendingJar = done;
        installCancelled = false;
        state = State.READY;
        say("§av" + m.version + " 已下載並驗證完成，關閉遊戲後會自動更新。§r輸入 .update cancel 可跳過這個版本。",
                "§av" + m.version + " is downloaded and verified; it installs when you close the game.§r Type .update cancel to skip this version.");
    }

    // ---------------------------------------------------------------- install

    /**
     * ".update cancel": not installed when the game closes, and this version is
     * skipped on later starts too (2026-10-26; it used to come back on the next
     * start and install then).
     */
    public void cancelInstall() {
        installCancelled = true;
        UpdateManifest m = latest;
        if (m != null && m.version.isNewerThan(current)) {
            skipped = m.version;
            saveSettings();
        }
    }

    /** ".update download" after a cancel in the same session: install it after all. */
    public void uncancelInstall() {
        installCancelled = false;
        if (skipped != null) {
            skipped = null;
            saveSettings();
        }
    }

    /** The version ".update cancel" skipped, or null. */
    public UpdateVersion getSkipped() {
        return skipped;
    }

    public boolean isInstallCancelled() {
        return installCancelled;
    }

    /** Runs while the game shuts down: starts UpdateInstaller and returns at once. */
    void launchInstallerIfReady() {
        File jar = pendingJar;
        File currentJar = currentJar();
        if (state != State.READY || installCancelled || jar == null || !jar.isFile() || currentJar == null) {
            return;
        }
        /* Check the file once more right before handing it over. */
        UpdateManifest m = latest;
        if (m == null || !UpdateSignature.matches(m, jar)) {
            return;
        }
        try {
            List<String> cmd = installerCommand(currentJar, jar);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(gameDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(workDir, "install.log")));
            pb.start();
        } catch (Throwable t) {
            System.err.println("[Atlas] could not start the updater: " + t);
        }
    }

    List<String> installerCommand(File currentJar, File newJar) throws IOException {
        File installerDir = new File(workDir, "installer");
        File classFile = new File(installerDir, "atlas/update/UpdateInstaller.class");
        classFile.getParentFile().mkdirs();
        InputStream in = Updater.class.getResourceAsStream("/atlas/update/UpdateInstaller.class");
        if (in == null) {
            throw new IOException("UpdateInstaller.class not found in the jar");
        }
        try {
            Files.copy(in, classFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(javaExecutable());
        cmd.add("-Xmx64m");
        cmd.add("-cp");
        cmd.add(installerDir.getAbsolutePath());
        cmd.add("atlas.update.UpdateInstaller");
        cmd.add(currentJar.getAbsolutePath());
        cmd.add(newJar.getAbsolutePath());
        cmd.add(new File(workDir, "backup").getAbsolutePath());
        cmd.add(new File(workDir, "install.log").getAbsolutePath());
        return cmd;
    }

    static String javaExecutable() {
        File bin = new File(System.getProperty("java.home"), "bin");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            File javaw = new File(bin, "javaw.exe"); // no console window
            return javaw.isFile() ? javaw.getAbsolutePath() : new File(bin, "java.exe").getAbsolutePath();
        }
        return new File(bin, "java").getAbsolutePath();
    }

    /** On start: drop a download left from an earlier session (it is fetched again if still needed). */
    void cleanStaleFiles() {
        File pendingDir = new File(workDir, "pending");
        File[] files = pendingDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            f.delete();
        }
    }

    // ---------------------------------------------------------- current jar

    /** The jar this class was loaded from, or null in a development run. */
    public File currentJar() {
        if (currentJarOverride != null) {
            return currentJarOverride;
        }
        try {
            java.security.CodeSource cs = Updater.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File f = toJarFile(cs.getLocation());
                if (f != null) {
                    return f;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            URL self = Updater.class.getResource("Updater.class");
            if (self != null) {
                return toJarFile(self);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static File toJarFile(URL url) {
        try {
            String s = url.toString();
            if (s.startsWith("jar:")) {
                int bang = s.indexOf("!/");
                s = s.substring(4, bang < 0 ? s.length() : bang);
            }
            if (!s.startsWith("file:")) {
                return null;
            }
            /* toURI, not URLDecoder: a "+" in the jar name must stay a "+". */
            File f = new File(new URL(s).toURI());
            return f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".jar") ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- network

    String manifestUrl() {
        return releasesBase + "/latest/download/" + MANIFEST_NAME;
    }

    String jarUrl(UpdateManifest m) {
        return releasesBase + "/download/v" + m.version + "/" + m.file;
    }

    /** A failure another attempt cannot fix: the file is not there, or is not the signed size. */
    static final class PermanentFailure extends IOException {
        PermanentFailure(String message) {
            super(message);
        }
    }

    /** {@code from} > 0: ask for the rest of the file from that byte on. */
    private static URLConnection open(String url, long from) throws IOException {
        URLConnection c = new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Atlas-Updater");
        /* 2026-10-22: GitHub answers 406 to Java's default Accept header (text/html, image/gif, image/jpeg and
           two wildcards) on release downloads; every "update check" failed with HTTP 406. Ask for the file. */
        c.setRequestProperty("Accept", "application/octet-stream");
        if (from > 0) {
            c.setRequestProperty("Range", "bytes=" + from + "-");
        }
        if (c instanceof HttpURLConnection) {
            HttpURLConnection h = (HttpURLConnection) c;
            h.setInstanceFollowRedirects(true);
            int code = h.getResponseCode();
            if (code == 404) {
                /* Usually a release just published: its files are attached by
                   the release Action a minute or two later (2026-10-13). */
                throw new PermanentFailure("not on the latest release yet (404); it may still be building,"
                        + " try again in a minute or two");
            }
            if (code != 200 && !(from > 0 && code == 206)) {
                if (code >= 400 && code < 500 && code != 408 && code != 429) {
                    throw new PermanentFailure("HTTP " + code);
                }
                throw new IOException("HTTP " + code);
            }
        }
        return c;
    }

    static byte[] download(String url, int maxBytes) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                InputStream in = open(url, 0L).getInputStream();
                try {
                    return UpdateSignature.readAll(in, maxBytes);
                } finally {
                    in.close();
                }
            } catch (PermanentFailure e) {
                throw e;
            } catch (IOException e) {
                waitBeforeRetry(attempt, e);
            }
        }
    }

    static void downloadToFile(String url, File out, long expectedSize) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                downloadOnce(url, out, expectedSize);
                return;
            } catch (PermanentFailure e) {
                throw e;
            } catch (IOException e) {
                waitBeforeRetry(attempt, e);
            }
        }
    }

    /** Rethrows {@code failure} when the attempts are used up; else waits this attempt's delay. */
    private static void waitBeforeRetry(int attempt, IOException failure) throws IOException {
        long[] delays = retryDelaysMs;
        if (attempt >= delays.length) {
            throw failure;
        }
        try {
            Thread.sleep(delays[attempt]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }

    /** One attempt, going on from what {@code out} already holds when the server allows it. */
    private static void downloadOnce(String url, File out, long expectedSize) throws IOException {
        long have = out.isFile() ? out.length() : 0L;
        if (have > expectedSize) {
            have = 0L;
        }
        if (have == expectedSize) {
            return;
        }
        URLConnection c = open(url, have);
        boolean resumed = have > 0 && c instanceof HttpURLConnection
                && ((HttpURLConnection) c).getResponseCode() == 206;
        if (!resumed) {
            have = 0L;
        }
        InputStream in = c.getInputStream();
        OutputStream os = new FileOutputStream(out, resumed);
        try {
            byte[] buf = new byte[65536];
            long total = have;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > expectedSize) {
                    throw new PermanentFailure("download is larger than the signed size");
                }
                os.write(buf, 0, r);
            }
            if (total != expectedSize) {
                throw new IOException("download is incomplete (" + total + " of " + expectedSize + " bytes)");
            }
        } finally {
            closeQuietly(os);
            closeQuietly(in);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ status

    public Mode getMode() {
        return mode;
    }

    public State getState() {
        return state;
    }

    public UpdateVersion getCurrent() {
        return current;
    }

    public UpdateManifest getLatest() {
        return latest;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean hasKey() {
        return key != null;
    }

    // ------------------------------------------------------------ test hooks

    void setCurrentJarForTests(File f) {
        currentJarOverride = f;
    }

    static void setReleasesBaseForTests(String base) {
        releasesBase = base;
    }

    File workDir() {
        return workDir;
    }
}
