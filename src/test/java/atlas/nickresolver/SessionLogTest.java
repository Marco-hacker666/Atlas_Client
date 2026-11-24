package atlas.nickresolver;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SessionLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void oneConnectionWritesTabDecideAndSummary() throws Exception {
        File root = this.tmp.newFolder();
        IdentityCache cache = new IdentityCache(new File(root, "cache.json"));
        File logs = new File(root, "logs");
        SessionLog log = new SessionLog(logs, () -> true, () -> "denick=on\tcloud=off");

        UUID uuid = UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f");
        ResolvedIdentity identity = cache.getOrCreate(uuid);
        cache.updateShownName(identity, "SomeNick");
        TextureDecoder.TextureInfo textures = new TextureDecoder.TextureInfo(
                uuid, "Notch", "http://textures.minecraft.net/texture/abc", "0123456789abcdef0123", null, 0L, true);

        log.connect("play.example.net");
        log.tab(identity, textures);
        log.tab(identity, textures);
        identity.originalName = "Notch";
        identity.source = ResolvedIdentity.Source.TEXTURE_SIGNED;
        log.sweep(Collections.singletonList(identity));
        log.sweep(Collections.singletonList(identity));
        log.disconnect(cache, Collections.<CustomPayloadInspector.ChannelStats>emptyList(), null);
        log.shutdown();

        String text = null;
        for (int i = 0; i < 100 && (text == null || !text.contains("CLOUD\t")); i++) {
            File[] files = logs.listFiles();
            if (files != null && files.length == 1) {
                text = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
            }
            Thread.sleep(20);
        }
        assertNotNull(text);
        assertTrue(text, text.contains("CONNECT\tserver=play.example.net\tdenick=on\tcloud=off"));
        assertEquals(1, count(text, "\tTAB\t"));
        assertTrue(text, text.contains("TAB\tSomeNick\t" + uuid + "(v3)\t-\tself\tNotch\tsigned\t0123456789abcdef"));
        assertEquals(1, count(text, "\tDECIDE\t"));
        assertTrue(text, text.contains("DECIDE\tSomeNick\tNICKED\tTEXTURE_SIGNED\treal=Notch"));
        assertTrue(text, text.contains("END\tplayers=1\tnicked=1\tnamed=1"));
    }

    @Test
    public void offWritesNothing() throws Exception {
        File logs = new File(this.tmp.newFolder(), "logs");
        SessionLog log = new SessionLog(logs, () -> false, () -> "");
        log.connect("play.example.net");
        log.shutdown();
        Thread.sleep(100);
        assertTrue(!logs.exists() || logs.list().length == 0);
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
