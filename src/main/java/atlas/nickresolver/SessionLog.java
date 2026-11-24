package atlas.nickresolver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class SessionLog {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    static final int KEEP_FILES = 40;

    private final File dir;
    private final BooleanSupplier on;

    private final Supplier<String> state;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NicknameResolver-Log");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> tabSeen = ConcurrentHashMap.newKeySet();

    private final Set<UUID> seen = ConcurrentHashMap.newKeySet();

    private final Map<UUID, String> decided = new ConcurrentHashMap<UUID, String>();

    private BufferedWriter out;
    private volatile boolean open;

    public SessionLog(File dir, final NickSettings settings) {
        this(dir, () -> settings.sessionLog, () -> "denick=" + onOff(settings.enabled)
                + "\tcloud=" + onOff(settings.cloudEnabled) + "\tmojang=" + onOff(settings.useMojang)
                + "\tkey=" + (settings.cloudKey.isEmpty() ? "none" : "set"));
    }

    SessionLog(File dir, BooleanSupplier on, Supplier<String> state) {
        this.dir = dir;
        this.on = on;
        this.state = state;
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    public void connect(String server) {
        this.tabSeen.clear();
        this.decided.clear();
        this.seen.clear();
        if (!this.on.getAsBoolean()) {
            this.open = false;
            return;
        }
        this.open = true;
        final String header = "CONNECT\tserver=" + (server == null ? "local" : server) + "\t" + this.state.get();
        this.writer.execute(() -> {
            this.close();
            this.rotate();
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(new Date());
            File file = new File(this.dir, "denick-" + stamp + ".log");
            try {
                this.dir.mkdirs();
                this.out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            } catch (IOException e) {
                LOG.warn("Could not open the Denick log {}", file, e);
                this.out = null;
            }
            this.write(header);
            this.write("# columns: TAB shown uuid(version) tab-name textures(self|other:<id> <name>|none) profileName signed skin");
        });
    }

    public void tab(ResolvedIdentity identity, TextureDecoder.TextureInfo textures) {
        if (!this.open || identity == null) {
            return;
        }
        this.seen.add(identity.uuid);
        String key = identity.uuid + "|" + identity.displayName + "|" + (textures == null ? "" : textures.skinHash);
        if (!this.tabSeen.add(key)) {
            return;
        }
        String tex;
        if (textures == null) {
            tex = "none\t-\t-\t-";
        } else {
            String owner = textures.profileId == null ? "missing"
                    : textures.profileId.equals(identity.uuid) ? "self" : "other:" + textures.profileId;

            tex = owner + "\t" + nz(textures.profileName) + "\t"
                    + (textures.signed ? "signed" : textures.hasSignature ? "FORGED" : "unsigned")
                    + "\t" + shortHash(textures.skinHash);
        }
        this.queue("TAB\t" + nz(identity.displayName) + "\t" + identity.uuid + "(v" + identity.uuid.version() + ")\t"
                + nz(identity.tabDisplayName) + "\t" + tex);
    }

    public void sweep(List<ResolvedIdentity> online) {
        if (!this.open) {
            return;
        }
        for (ResolvedIdentity identity : online) {
            String now = identity.source + "|" + nz(identity.originalName);
            String before = this.decided.put(identity.uuid, now);
            if (now.equals(before)) {
                continue;
            }
            if (before == null && identity.source == ResolvedIdentity.Source.UNKNOWN) {
                continue;
            }
            String tag = identity.isNickedWithKnownName() ? "NICKED" : identity.isNickedUnknown() ? "NICK?" : "plain";
            this.queue("DECIDE\t" + nz(identity.displayName) + "\t" + tag + "\t" + identity.source + "\treal="
                    + nz(identity.originalName) + "\tuuid=" + identity.uuid + "(v" + identity.uuid.version() + ")"
                    + (identity.skinOwnerName != null ? "\tskin-owner=" + identity.skinOwnerName : "")
                    + (before != null ? "\twas=" + before : ""));
        }
    }

    public void disconnect(IdentityCache cache, List<CustomPayloadInspector.ChannelStats> channels, CloudClient cloud) {
        if (!this.open) {
            return;
        }
        List<ResolvedIdentity> seen = new ArrayList<ResolvedIdentity>();
        for (UUID uuid : this.seen) {
            ResolvedIdentity identity = cache.get(uuid);
            if (identity != null) {
                seen.add(identity);
            }
        }
        this.sweep(seen);
        this.open = false;
        Map<ResolvedIdentity.Source, Integer> counts = new EnumMap<ResolvedIdentity.Source, Integer>(ResolvedIdentity.Source.class);
        int nicked = 0;
        int named = 0;
        for (ResolvedIdentity identity : seen) {
            Integer n = counts.get(identity.source);
            counts.put(identity.source, n == null ? 1 : n + 1);
            if (identity.isNickedWithKnownName()) {
                nicked++;
                named++;
            } else if (identity.isNickedUnknown()) {
                nicked++;
            }
        }
        final List<String> lines = new ArrayList<String>();
        lines.add("END\tplayers=" + seen.size() + "\tnicked=" + nicked + "\tnamed=" + named + "\tsources=" + counts);
        List<CustomPayloadInspector.ChannelStats> sorted = new ArrayList<CustomPayloadInspector.ChannelStats>(channels);
        Collections.sort(sorted, new Comparator<CustomPayloadInspector.ChannelStats>() {
            @Override
            public int compare(CustomPayloadInspector.ChannelStats a, CustomPayloadInspector.ChannelStats b) {
                return Long.compare(b.packets.get(), a.packets.get());
            }
        });
        for (CustomPayloadInspector.ChannelStats c : sorted) {
            lines.add("CHANNEL\t(since game start)\t" + c.channel + "\tpackets=" + c.packets.get() + "\thints=" + c.hints.get()
                    + "\tuuid=" + c.sawUuid + "\tlast-size=" + c.lastSize);
        }
        lines.add("CLOUD\t" + this.state.get() + (cloud == null ? ""
                : "\tsent=" + cloud.sentTotal() + "\tqueued=" + cloud.queued() + "\tlast-error=" + nz(cloud.lastError())));
        this.writer.execute(() -> {
            for (String line : lines) {
                this.write(line);
            }
            this.close();
        });
    }

    public void shutdown() {
        this.writer.execute(this::close);
        this.writer.shutdown();
    }

    private void queue(final String line) {
        this.writer.execute(() -> this.write(line));
    }

    private void write(String line) {
        if (this.out == null) {
            return;
        }
        try {
            this.out.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + "\t" + line);
            this.out.newLine();
            this.out.flush();
        } catch (IOException e) {
            LOG.warn("Writing the Denick log failed", e);
            this.close();
        }
    }

    private void close() {
        if (this.out != null) {
            try {
                this.out.close();
            } catch (IOException ignored) {
            }
            this.out = null;
        }
    }

    private void rotate() {
        File[] files = this.dir.listFiles((d, name) -> name.startsWith("denick-") && name.endsWith(".log"));
        if (files == null || files.length < KEEP_FILES) {
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int i = 0; i <= files.length - KEEP_FILES; i++) {
            if (!files[i].delete()) {
                LOG.debug("Could not delete old Denick log {}", files[i]);
            }
        }
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String shortHash(String hash) {
        return hash == null ? "-" : hash.length() > 16 ? hash.substring(0, 16) : hash;
    }
}
