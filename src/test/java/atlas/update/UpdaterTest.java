package atlas.update;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Online updates (2026-10-13): versions, manifest, signature, download, install. */
public class UpdaterTest {

    private File dir;
    private HttpServer server;
    private final Map<String, byte[]> served = new HashMap<String, byte[]>();
    private final Map<String, Integer> hits = new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private volatile long jarDelayMs;
    private KeyPair keys;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("atlas-update-test").toFile();
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        keys = g.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.merge(path, 1, Integer::sum);
            if (path.endsWith(".jar") && jarDelayMs > 0) {
                try {
                    Thread.sleep(jarDelayMs);
                } catch (InterruptedException ignored) {
                }
            }
            byte[] body = served.get(path);
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                OutputStream os = exchange.getResponseBody();
                os.write(body);
                os.close();
            }
            exchange.close();
        });
        server.start();
        Updater.setReleasesBaseForTests("http://127.0.0.1:" + server.getAddress().getPort() + "/r");
    }

    @After
    public void tearDown() {
        server.stop(0);
        Updater.setReleasesBaseForTests("https://github.com/" + Updater.REPO + "/releases");
        deleteTree(dir);
    }

    // ---------------------------------------------------------------- version

    @Test
    public void versionsCompareNumerically() {
        assertTrue(v("2.1.10").isNewerThan(v("2.1.9")));
        assertTrue(v("v2.2.0").isNewerThan(v("2.1.99")));
        assertTrue(v("3").isNewerThan(v("2.9.9")));
        assertFalse(v("2.1.1").isNewerThan(v("v2.1.1")));
        assertEquals(v("2.1"), v("2.1.0"));
        assertTrue(v("2.2.0").isNewerThan(v("2.2.0-beta")));
        assertTrue(v("2.2.0-beta").isNewerThan(v("2.1.9")));
    }

    @Test
    public void nonVersionsAreRejected() {
        assertNull(UpdateVersion.parse("dev"));
        assertNull(UpdateVersion.parse(""));
        assertNull(UpdateVersion.parse("1..2"));
        assertNull(UpdateVersion.parse("${version}"));
        assertNull(UpdateVersion.parse(null));
    }

    // --------------------------------------------------------------- manifest

    @Test
    public void manifestRejectsPathsInFileName() throws Exception {
        for (String bad : new String[]{"../evil.jar", "a/b.jar", "x.exe", "..jar", "C:\\x.jar"}) {
            try {
                UpdateManifest.parse(manifestText("2.2.0", bad, 10, sha("x"), "AA=="));
                fail("accepted " + bad);
            } catch (IOException expected) {
            }
        }
    }

    @Test
    public void signatureCoversEveryField() throws Exception {
        byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        UpdateManifest good = UpdateManifest.parse(signed("2.2.0", "Atlas-v2.2.0.jar", jar, keys.getPrivate()));
        assertTrue(UpdateSignature.verify(good, keys.getPublic()));

        // Same signature, different version: must fail (no replaying an old jar as new).
        UpdateManifest relabeled = new UpdateManifest(v("9.9.9"), good.file, good.size, good.sha256, good.signature);
        assertFalse(UpdateSignature.verify(relabeled, keys.getPublic()));
        UpdateManifest otherHash = new UpdateManifest(good.version, good.file, good.size, sha("other"), good.signature);
        assertFalse(UpdateSignature.verify(otherHash, keys.getPublic()));

        // Signed with some other key: must fail.
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        UpdateManifest stranger = UpdateManifest.parse(signed("2.2.0", "Atlas-v2.2.0.jar", jar, g.generateKeyPair().getPrivate()));
        assertFalse(UpdateSignature.verify(stranger, keys.getPublic()));
        assertFalse(UpdateSignature.verify(good, null));
    }

    @Test
    public void publicKeyParsesFromPemOrBareBase64() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
        assertEquals(keys.getPublic(), UpdateSignature.parsePublicKey("# comment\n" + b64 + "\n"));
        assertEquals(keys.getPublic(), UpdateSignature.parsePublicKey("-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n"));
    }

    // ------------------------------------------------------- check + download

    @Test
    public void newerSignedReleaseIsDownloadedAndVerified() throws Exception {
        Fixture f = publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        List<String> said = listen(u);
        u.check(true);
        assertEquals(Updater.State.READY, u.getState());
        File pending = new File(u.workDir(), "pending/" + f.file);
        assertArrayEquals(f.jar, Files.readAllBytes(pending.toPath()));
        assertTrue(said.get(said.size() - 1).contains("v2.2.0"));
    }

    @Test
    public void secondCheckDoesNotDownloadAgain() throws Exception {
        Fixture f = publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        assertEquals(Updater.State.READY, u.getState());
        served.remove("/r/download/v2.2.0/" + f.file); // a second download would now fail
        List<String> said = listen(u);
        u.check(true);
        assertEquals(Updater.State.READY, u.getState());
        assertTrue(said.get(0).contains("already downloaded"));
    }

    @Test
    public void sameOrOlderReleaseIsIgnored() throws Exception {
        publish("2.1.1", "same".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        assertEquals(Updater.State.UP_TO_DATE, u.getState());
        assertFalse(new File(u.workDir(), "pending").exists());
    }

    @Test
    public void tamperedJarIsDiscarded() throws Exception {
        Fixture f = publish("2.2.0", "real".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        served.put("/r/download/v2.2.0/" + f.file, "fake".getBytes(StandardCharsets.UTF_8)); // same length, other bytes
        Updater u = updater("2.1.1");
        u.check(false);
        assertEquals(Updater.State.FAILED, u.getState());
        File[] left = new File(u.workDir(), "pending").listFiles();
        assertTrue(left == null || left.length == 0);
    }

    @Test
    public void foreignSignatureIsNeverDownloaded() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        publish("2.2.0", "evil".getBytes(StandardCharsets.UTF_8), g.generateKeyPair().getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        assertEquals(Updater.State.AVAILABLE, u.getState());
        assertFalse(new File(u.workDir(), "pending").exists());
    }

    @Test
    public void notifyModeOnlyTells() throws Exception {
        publish("2.2.0", "new".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.setMode(Updater.Mode.NOTIFY);
        List<String> said = listen(u);
        u.check(false);
        assertEquals(Updater.State.AVAILABLE, u.getState());
        assertTrue(said.get(0).contains(".update download"));
        // The setting is remembered.
        assertEquals(Updater.Mode.NOTIFY, updater("2.1.1").getMode());
    }

    @Test
    public void missingManifestIsAQuietFailure() {
        Updater u = updater("2.1.1");
        List<String> said = listen(u);
        u.check(false);
        assertEquals(Updater.State.FAILED, u.getState());
        assertTrue(said.isEmpty());
    }

    // ---------------------------------------------------------------- install

    @Test
    public void installerSwapsJarAndKeepsBackup() throws Exception {
        File mods = new File(dir, "mods");
        mods.mkdirs();
        File current = write(new File(mods, "Atlas+.jar"), "old");
        File update = write(new File(dir, "pending/Atlas-v2.2.0.jar"), "new");
        File backups = new File(dir, "backup");
        int code = UpdateInstaller.install(current, update, backups, new PrintWriter(new StringWriter()));
        assertEquals(0, code);
        assertFalse(current.exists());
        assertEquals("new", read(new File(mods, "Atlas-v2.2.0.jar")));
        assertEquals(1, backups.listFiles().length);
        assertEquals("old", read(backups.listFiles()[0]));
    }

    @Test
    public void installerKeepsOldJarWhenItCannotProceed() throws Exception {
        File mods = new File(dir, "mods");
        mods.mkdirs();
        File current = write(new File(mods, "Atlas+.jar"), "old");
        int code = UpdateInstaller.install(current, new File(dir, "missing.jar"), new File(dir, "backup"),
                new PrintWriter(new StringWriter()));
        assertEquals(1, code);
        assertEquals("old", read(current));
    }

    @Test
    public void backupsArePruned() throws Exception {
        File b = new File(dir, "backup");
        b.mkdirs();
        for (int i = 1; i <= 5; i++) {
            write(new File(b, "2026010" + i + "-000000-Atlas.jar"), "x");
        }
        UpdateInstaller.pruneBackups(b, 3, new PrintWriter(new StringWriter()));
        assertEquals(3, b.listFiles().length);
        assertFalse(new File(b, "20260101-000000-Atlas.jar").exists());
    }

    /** The whole path: download, then the installer as its own process, from the copied class file. */
    @Test
    public void endToEndInstallerProcess() throws Exception {
        Fixture f = publish("2.2.0", "brand new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        File mods = new File(dir, "mods");
        mods.mkdirs();
        File current = write(new File(mods, "Atlas+.jar"), "old client");
        Updater u = updater("2.1.1");
        u.setCurrentJarForTests(current);
        u.check(false);
        assertEquals(Updater.State.READY, u.getState());

        List<String> cmd = u.installerCommand(current, new File(u.workDir(), "pending/" + f.file));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        assertEquals(0, p.waitFor());
        assertFalse(current.exists());
        assertEquals("brand new client", read(new File(mods, f.file)));
        assertTrue(read(new File(u.workDir(), "install.log")).contains("installed"));
    }

    @Test
    public void cancelledInstallDoesNothing() throws Exception {
        publish("2.2.0", "new".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        File mods = new File(dir, "mods");
        mods.mkdirs();
        File current = write(new File(mods, "Atlas+.jar"), "old");
        Updater u = updater("2.1.1");
        u.setCurrentJarForTests(current);
        u.check(false);
        u.cancelInstall();
        u.launchInstallerIfReady();
        assertFalse(new File(u.workDir(), "installer").exists());
        assertEquals("old", read(current));
    }

    @Test
    public void jarUrlHandlesPlusInName() throws Exception {
        File plus = write(new File(dir, "Atlas+.jar"), "x");
        assertEquals(plus.getCanonicalFile(), Updater.toJarFile(plus.toURI().toURL()).getCanonicalFile());
        assertEquals(plus.getCanonicalFile(),
                Updater.toJarFile(new java.net.URL("jar:" + plus.toURI().toURL() + "!/atlas/update/Updater.class")).getCanonicalFile());
        assertNull(Updater.toJarFile(dir.toURI().toURL())); // a class folder (development) is not a jar
    }

    // ------------------------------------------------- 2026-10-26 fixes

    private static void waitFor(Updater u, Updater.State want) throws InterruptedException {
        long end = System.currentTimeMillis() + 10000L;
        while (u.getState() != want && System.currentTimeMillis() < end) {
            Thread.sleep(20L);
        }
        assertEquals(want, u.getState());
    }

    @Test
    public void overlappingChecksDownloadOnce() throws Exception {
        Fixture f = publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        jarDelayMs = 400L;
        Updater u = updater("2.1.1");
        List<String> said = listen(u);
        u.checkAsync(false);
        u.checkAsync(true);
        u.downloadAsync();
        waitFor(u, Updater.State.READY);
        Thread.sleep(200L);
        assertEquals(Integer.valueOf(1), hits.get("/r/download/v2.2.0/" + f.file));
        assertTrue(said.toString(), said.toString().contains("already running"));
    }

    @Test
    public void downloadLeavesOnlyTheJar() throws Exception {
        Fixture f = publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        String[] left = new File(u.workDir(), "pending").list();
        assertArrayEquals(new String[]{f.file}, left);
    }

    @Test
    public void cancelSkipsTheVersionOnLaterStarts() throws Exception {
        publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        u.cancelInstall();
        assertEquals("2.2.0", u.getSkipped().toString());
        Updater next = updater("2.1.1");
        next.cleanStaleFiles();
        hits.clear();
        List<String> said = listen(next);
        next.check(true);
        assertEquals(Updater.State.AVAILABLE, next.getState());
        assertNull(hits.get("/r/download/v2.2.0/Atlas-v2.2.0.jar"));
        assertTrue(said.toString(), said.toString().contains("skipped"));
    }

    @Test
    public void aNewerVersionEndsTheSkip() throws Exception {
        publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        u.cancelInstall();
        publish("2.3.0", "newer client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater next = updater("2.1.1");
        next.check(false);
        assertEquals(Updater.State.READY, next.getState());
        assertNull(next.getSkipped());
    }

    @Test
    public void downloadByHandInstallsASkippedVersion() throws Exception {
        publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        u.cancelInstall();
        Updater next = updater("2.1.1");
        next.cleanStaleFiles();
        next.check(false);
        assertEquals(Updater.State.AVAILABLE, next.getState());
        next.downloadAsync();
        waitFor(next, Updater.State.READY);
        assertNull(next.getSkipped());
        assertNull(updater("2.1.1").getSkipped());
    }

    @Test
    public void switchingToAutoDoesNotFetchASkippedVersion() throws Exception {
        publish("2.2.0", "new client".getBytes(StandardCharsets.UTF_8), keys.getPrivate());
        Updater u = updater("2.1.1");
        u.check(false);
        u.cancelInstall();
        Updater next = updater("2.1.1");
        next.setMode(Updater.Mode.NOTIFY);
        next.cleanStaleFiles();
        next.check(false);
        hits.clear();
        next.setMode(Updater.Mode.AUTO);
        Thread.sleep(300L);
        assertEquals(Updater.State.AVAILABLE, next.getState());
        assertNull(hits.get("/r/download/v2.2.0/Atlas-v2.2.0.jar"));
    }

    // ---------------------------------------------------------------- helpers

    private static final class Fixture {
        String file;
        byte[] jar;
    }

    private Fixture publish(String version, byte[] jar, PrivateKey key) throws Exception {
        Fixture f = new Fixture();
        f.file = "Atlas-v" + version + ".jar";
        f.jar = jar;
        served.put("/r/latest/download/update.properties", signed(version, f.file, jar, key).getBytes(StandardCharsets.UTF_8));
        served.put("/r/download/v" + version + "/" + f.file, jar);
        return f;
    }

    private Updater updater(String version) {
        Updater u = new Updater(dir, version, keys.getPublic());
        u.setCurrentJarForTests(new File(dir, "placeholder.jar"));
        return u;
    }

    private static List<String> listen(Updater u) {
        final List<String> out = new ArrayList<String>();
        u.addListener((zh, en) -> out.add(en));
        return out;
    }

    static String signed(String version, String file, byte[] jar, PrivateKey key) throws Exception {
        String sha = UpdateSignature.hex(MessageDigest.getInstance("SHA-256").digest(jar));
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(key);
        s.update(UpdateManifest.payload(version, file, jar.length, sha).getBytes(StandardCharsets.UTF_8));
        return manifestText(version, file, jar.length, sha, Base64.getEncoder().encodeToString(s.sign()));
    }

    private static String manifestText(String version, String file, long size, String sha, String sig) {
        return "format=1\nversion=" + version + "\nfile=" + file + "\nsize=" + size + "\nsha256=" + sha + "\nsignature=" + sig + "\n";
    }

    private static String sha(String s) throws Exception {
        return UpdateSignature.hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static UpdateVersion v(String s) {
        return UpdateVersion.parse(s);
    }

    private static File write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
