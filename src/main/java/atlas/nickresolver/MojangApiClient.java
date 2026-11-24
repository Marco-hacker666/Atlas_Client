package atlas.nickresolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

public final class MojangApiClient {
    private static final Logger LOG = LogManager.getLogger("NicknameResolver");
    private static final int QUEUE_SIZE = 128;
    private static final int TIMEOUT_MS = 6_000;
    private static final String USER_AGENT = "NicknameResolver/1.0";

    public static final class Result {
        public enum Kind { FOUND, NOT_FOUND, ERROR }

        public final Kind kind;
        public final String name;

        public final String skinHash;

        private Result(Kind kind, String name, String skinHash) {
            this.kind = kind;
            this.name = name;
            this.skinHash = skinHash;
        }

        static Result found(String name, String skinHash) {
            return new Result(Kind.FOUND, name, skinHash);
        }

        static final Result NOT_FOUND = new Result(Kind.NOT_FOUND, null, null);
        static final Result ERROR = new Result(Kind.ERROR, null, null);
    }

    public interface Callback {
        void onResult(UUID uuid, Result result);
    }

    private final ThreadPoolExecutor pool;
    private final RateLimiter limiter;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final AtomicInteger dropped = new AtomicInteger();

    public MojangApiClient(IntSupplier maxPer10Min) {
        this.limiter = new RateLimiter(maxPer10Min, 10 * 60 * 1000L, 120L);
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "NicknameResolver-Mojang-" + this.n.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            }
        };
        this.pool = new ThreadPoolExecutor(2, 3, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(QUEUE_SIZE), factory, new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
    }

    public boolean lookup(final UUID uuid, final Callback callback) {
        if (uuid == null || this.pool.isShutdown() || !this.pending.add(uuid)) {
            return false;
        }
        try {
            this.pool.execute(new Runnable() {
                @Override
                public void run() {
                    Result result;
                    try {
                        result = MojangApiClient.this.fetch(uuid);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } finally {
                        MojangApiClient.this.pending.remove(uuid);
                    }
                    try {
                        callback.onResult(uuid, result);
                    } catch (RuntimeException e) {
                        LOG.warn("Lookup callback failed for {}", uuid, e);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException full) {
            this.pending.remove(uuid);
            this.dropped.incrementAndGet();
            return false;
        }
    }

    public boolean isPending(UUID uuid) {
        return this.pending.contains(uuid);
    }

    public int queued() {
        return this.pool.getQueue().size();
    }

    public int usedInWindow() {
        return this.limiter.used();
    }

    public int droppedCount() {
        return this.dropped.get();
    }

    public boolean isThrottled() {
        return this.limiter.isPaused();
    }

    public void shutdown() {
        this.pool.shutdownNow();
    }

    private Result fetch(UUID uuid) throws InterruptedException {
        String id = TextureDecoder.undashed(uuid);
        Result primary = this.request("https://sessionserver.mojang.com/session/minecraft/profile/" + id, true);
        if (primary.kind != Result.Kind.ERROR) {
            return primary;
        }
        return this.request("https://api.mojang.com/user/profile/" + id, false);
    }

    private Result request(String address, boolean sessionFormat) throws InterruptedException {
        this.limiter.acquire();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 204 || code == 404) {
                return Result.NOT_FOUND;
            }
            if (code == 429) {
                long retryAfter = parseRetryAfter(conn.getHeaderField("Retry-After"));
                this.limiter.backOff(retryAfter);
                LOG.info("Mojang rate limited us; pausing lookups for {} ms", retryAfter);
                return Result.ERROR;
            }
            if (code != 200) {
                LOG.debug("Mojang answered {} for {}", code, address);
                return Result.ERROR;
            }
            String body = readAll(conn.getInputStream());
            if (body.trim().isEmpty()) {
                return Result.NOT_FOUND;
            }
            JsonElement parsed = new JsonParser().parse(body);
            if (!parsed.isJsonObject()) {
                return Result.ERROR;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (json.has("errorMessage") || json.has("error")) {
                return Result.NOT_FOUND;
            }
            JsonElement name = json.get("name");
            if (name == null || !name.isJsonPrimitive()) {
                return Result.ERROR;
            }
            String skinHash = sessionFormat ? skinHashFromProperties(json) : null;
            return Result.found(name.getAsString(), skinHash);
        } catch (IOException | RuntimeException e) {
            LOG.debug("Mojang request failed: {}", address, e);
            return Result.ERROR;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String skinHashFromProperties(JsonObject json) {
        if (!json.has("properties") || !json.get("properties").isJsonArray()) {
            return null;
        }
        for (JsonElement e : json.getAsJsonArray("properties")) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject p = e.getAsJsonObject();
            if (p.has("name") && "textures".equals(p.get("name").getAsString()) && p.has("value")) {
                TextureDecoder.TextureInfo info = TextureDecoder.decode(p.get("value").getAsString(), p.has("signature"));
                return info == null ? null : info.skinHash;
            }
        }
        return null;
    }

    private static long parseRetryAfter(String header) {
        if (header != null) {
            try {
                return Math.max(1_000L, Math.min(10 * 60_000L, Long.parseLong(header.trim()) * 1000L));
            } catch (NumberFormatException ignored) {
            }
        }
        return 60_000L;
    }

    private static String readAll(InputStream in) throws IOException {
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
}
