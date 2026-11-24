package atlas.nickresolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudClient {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    private static final int FLUSH_SECONDS = 60;

    private static final int MAX_BATCH = 150;
    private static final int MAX_QUEUE = 5_000;
    private static final int DEDUPE_MINUTES = 10;
    private static final int LOOKUPS_PER_MINUTE = 30;
    private static final int TIMEOUT_MS = 8_000;
    private static final String USER_AGENT = "NicknameResolver/1.0";

    public static final class Suggestion {
        public final String name;
        public final String confidence;
        public final int reporters;

        Suggestion(String name, String confidence, int reporters) {
            this.name = name;
            this.confidence = confidence;
            this.reporters = reporters;
        }
    }

    public interface LookupCallback {
        void onResult(ResolvedIdentity identity, Suggestion suggestion);
    }

    private static final class Observation {
        final String displayName;
        final String uuid;
        final String skinHash;
        final String resolvedName;
        final String source;
        final String server;
        final long timestamp;

        Observation(ResolvedIdentity identity, String server) {
            this.displayName = identity.displayName;
            this.uuid = identity.uuid.toString();
            this.skinHash = identity.skinHash;
            ResolvedIdentity.Source s = identity.source;
            this.resolvedName = s == ResolvedIdentity.Source.NOT_NICKED ? identity.displayName
                    : s.confident ? identity.originalName : null;
            this.source = s.name();
            this.server = server;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final NickSettings settings;
    private final ConcurrentLinkedQueue<Observation> queue = new ConcurrentLinkedQueue<Observation>();
    private final AtomicInteger size = new AtomicInteger();
    private final Map<String, Long> recent = new ConcurrentHashMap<String, Long>();
    private final Set<UUID> lookupPending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService reporter;
    private final ThreadPoolExecutor lookups;
    private final RateLimiter lookupLimiter = new RateLimiter(() -> LOOKUPS_PER_MINUTE, 60_000L, 250L);
    private volatile String server;
    private volatile String lastError;
    private volatile long sentTotal;

    public CloudClient(NickSettings settings) {
        this.settings = settings;
        this.reporter = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "NicknameResolver-CloudReport"));
        this.reporter.scheduleWithFixedDelay(this::flushSafely, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
        this.lookups = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(64),
                r -> daemon(r, "NicknameResolver-CloudLookup"), new ThreadPoolExecutor.AbortPolicy());
        this.lookups.allowCoreThreadTimeOut(true);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    }

    private boolean wanted() {
        return this.settings.cloudEnabled && !this.settings.cloudUrl.isEmpty();
    }

    private boolean configured() {
        return this.wanted() && !this.settings.cloudKey.isEmpty();
    }

    private volatile long nextRegisterAt;

    private synchronized boolean ensureKey() {
        if (this.configured()) {
            return true;
        }
        if (!this.wanted() || !this.settings.cloudAutoKey || System.currentTimeMillis() < this.nextRegisterAt) {
            return false;
        }
        this.nextRegisterAt = System.currentTimeMillis() + 30 * 60_000L;
        HttpURLConnection conn = null;
        try {
            byte[] body = "{\"client\":\"nickresolver\"}".getBytes(StandardCharsets.UTF_8);
            conn = this.open("/api/v1/keys/register");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            if (code != 201) {
                readAll(conn.getErrorStream());
                this.lastError = "register HTTP " + code;
                return false;
            }
            JsonElement parsed = new JsonParser().parse(readAll(conn.getInputStream()));
            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("key")) {
                this.settings.setCloudEndpoint(null, parsed.getAsJsonObject().get("key").getAsString());
                this.lastError = null;
                LOG.info("Registered a cloud API key");
                return true;
            }
            this.lastError = "register: no key in answer";
            return false;
        } catch (IOException | RuntimeException e) {
            this.lastError = "register " + e.getClass().getSimpleName();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public void setServer(String server) {
        this.server = server == null ? null : server.toLowerCase(Locale.ROOT);
    }

    public void record(ResolvedIdentity identity) {
        if (!this.wanted() || !this.settings.cloudReport || identity == null || identity.displayName == null) {
            return;
        }
        String key = identity.uuid + "|" + identity.displayName + "|" + identity.skinHash + "|" + identity.source;
        long now = System.currentTimeMillis();
        Long last = this.recent.get(key);
        if (last != null && now - last < DEDUPE_MINUTES * 60_000L) {
            return;
        }
        if (this.size.get() >= MAX_QUEUE) {
            return;
        }
        this.recent.put(key, now);
        this.queue.add(new Observation(identity, this.server));
        this.size.incrementAndGet();
    }

    public void flushAsync() {
        this.reporter.execute(this::flushSafely);
    }

    private void flushSafely() {
        try {
            this.pruneRecent();
            if (!this.queue.isEmpty() && !this.ensureKey()) {
                return;
            }
            while (this.configured() && !this.queue.isEmpty()) {
                List<Observation> batch = new ArrayList<Observation>(MAX_BATCH);
                Observation o;
                while (batch.size() < MAX_BATCH && (o = this.queue.poll()) != null) {
                    this.size.decrementAndGet();
                    batch.add(o);
                }
                if (!this.send(batch)) {
                    for (Observation back : batch) {
                        if (this.size.get() < MAX_QUEUE) {
                            this.queue.add(back);
                            this.size.incrementAndGet();
                        }
                    }
                    return;
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Cloud report flush failed", e);
        }
    }

    private void pruneRecent() {
        long cutoff = System.currentTimeMillis() - DEDUPE_MINUTES * 60_000L;
        for (Iterator<Map.Entry<String, Long>> it = this.recent.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < cutoff) {
                it.remove();
            }
        }
    }

    private boolean send(List<Observation> batch) {
        JsonArray entries = new JsonArray();
        for (Observation o : batch) {
            JsonObject e = new JsonObject();
            e.addProperty("displayName", o.displayName);
            e.addProperty("uuid", o.uuid);
            if (o.skinHash != null) {
                e.addProperty("skinTextureHash", o.skinHash);
            }
            if (o.resolvedName != null) {
                e.addProperty("resolvedName", o.resolvedName);
            }
            e.addProperty("source", o.source);
            if (o.server != null) {
                e.addProperty("server", o.server);
            }
            e.addProperty("timestamp", o.timestamp);
            entries.add(e);
        }
        JsonObject body = new JsonObject();
        body.add("entries", entries);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            conn = this.open("/api/v1/report");
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            readAll(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            if (code == 401) {
                this.settings.setCloudEndpoint(null, "");
                this.lastError = "HTTP 401";
                return false;
            }
            if (code >= 200 && code < 300) {
                this.sentTotal += batch.size();
                this.lastError = null;
                return true;
            }
            this.lastError = "HTTP " + code;

            return code >= 400 && code < 500 && code != 429;
        } catch (IOException e) {
            this.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public boolean lookup(final ResolvedIdentity identity, final LookupCallback callback) {
        if (!this.wanted() || !this.settings.cloudLookup || identity == null
                || !this.lookupPending.add(identity.uuid)) {
            return false;
        }
        try {
            this.lookups.execute(() -> {
                Suggestion suggestion = null;
                try {
                    this.lookupLimiter.acquire();
                    suggestion = this.ensureKey() ? this.fetch(identity) : null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    this.lookupPending.remove(identity.uuid);
                }
                try {
                    callback.onResult(identity, suggestion);
                } catch (RuntimeException e) {
                    LOG.warn("Cloud lookup callback failed", e);
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            this.lookupPending.remove(identity.uuid);
            return false;
        }
    }

    private Suggestion fetch(ResolvedIdentity identity) {
        StringBuilder query = new StringBuilder("/api/v1/lookup?uuid=").append(identity.uuid);
        try {
            if (identity.skinHash != null) {
                query.append("&hash=").append(URLEncoder.encode(identity.skinHash, "UTF-8"));
            }
            if (identity.displayName != null) {
                query.append("&name=").append(URLEncoder.encode(identity.displayName, "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException impossible) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = this.open(query.toString());
            int code = conn.getResponseCode();
            if (code != 200) {
                readAll(conn.getErrorStream());
                this.lastError = "lookup HTTP " + code;
                return null;
            }
            JsonElement parsed = new JsonParser().parse(readAll(conn.getInputStream()));
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (json.has("ambiguous") && json.get("ambiguous").getAsBoolean()) {
                return null;
            }
            JsonElement best = json.get("best");
            if (best == null || !best.isJsonObject()) {
                return null;
            }
            JsonObject b = best.getAsJsonObject();
            if (!b.has("name") || !b.get("name").isJsonPrimitive()) {
                return null;
            }
            String name = b.get("name").getAsString();
            if (name.equalsIgnoreCase(identity.displayName)) {
                return null;
            }
            String confidence = b.has("confidence") ? b.get("confidence").getAsString() : "low";
            int reporters = b.has("reporters") ? b.get("reporters").getAsInt() : 0;
            return new Suggestion(name, confidence, reporters);
        } catch (IOException | RuntimeException e) {
            this.lastError = "lookup " + e.getClass().getSimpleName();
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        String base = this.settings.cloudUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setUseCaches(false);
        if (!this.settings.cloudKey.isEmpty()) {
            conn.setRequestProperty("X-API-Key", this.settings.cloudKey);
        }
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = input.read(buf)) != -1) {
                total += n;
                if (total > 1 << 20) {
                    throw new IOException("Response too large");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public String lastError() {
        return this.lastError;
    }

    public long sentTotal() {
        return this.sentTotal;
    }

    public int queued() {
        return this.size.get();
    }

    public void shutdown() {
        this.lookups.shutdownNow();
        this.flushAsync();
        this.reporter.shutdown();
        try {
            this.reporter.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
