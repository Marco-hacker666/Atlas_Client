package atlas.management;

import atlas.sim.DodgeHabits;
import atlas.sim.SharedHabits;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class HabitsCloud {
    private static final SharedHabits SHARED = new SharedHabits();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Set<String> ASKED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(256), r -> {
        Thread t = new Thread(r, "Atlas habits");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private static volatile boolean sharing = true;

    private HabitsCloud() {
    }

    public static SharedHabits shared() {
        return SHARED;
    }

    public static void setSharing(boolean on) {
        sharing = on;
    }

    public static boolean sharing() {
        return sharing;
    }

    public static void ask(String name, boolean cracked) {
        if (name == null || !NAME.matcher(name).matches()) {
            return;
        }
        final String key = SharedHabits.key(name, cracked);
        if (!ASKED.add(key)) {
            return;
        }
        final String base = atlas.util.BugReport.relayBase();
        if (base.isEmpty()) {
            return;
        }
        WORKER.execute(() -> {
            try {
                String url = base + "/habits?name=" + URLEncoder.encode(name.toLowerCase(), "UTF-8")
                        + "&cracked=" + (cracked ? "1" : "0");
                HttpURLConnection c = open(url, "GET");
                int code = c.getResponseCode();
                if (code == 404) {
                    SHARED.fetched(key, new double[DodgeHabits.WAYS + 1], 0.0, 0.0);
                    return;
                }
                if (code != 200) {
                    ASKED.remove(key);
                    return;
                }
                JsonObject o = new JsonParser().parse(read(c.getInputStream())).getAsJsonObject();
                JsonArray a = o.getAsJsonArray("counts");
                double[] counts = new double[DodgeHabits.WAYS + 1];
                for (int i = 0; i < counts.length && i < a.size(); i++) {
                    counts[i] = a.get(i).getAsDouble();
                }
                SHARED.fetched(key, counts, o.get("reactionSum").getAsDouble(), o.get("reactionN").getAsDouble());
            } catch (Exception e) {
                ASKED.remove(key);
            }
        });
    }

    public static void tell(String name, boolean cracked, double reaction, int way) {
        if (name == null || !NAME.matcher(name).matches()) {
            return;
        }
        String key = SharedHabits.key(name, cracked);
        SHARED.saw(key, reaction, way);
        if (!sharing) {
            return;
        }
        final String base = atlas.util.BugReport.relayBase();
        if (base.isEmpty()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", name.toLowerCase());
        body.addProperty("cracked", cracked);
        if (Double.isNaN(reaction)) {
            body.add("reaction", com.google.gson.JsonNull.INSTANCE);
        } else {
            body.addProperty("reaction", Math.max(0.0, Math.min(20.0, reaction)));
        }
        body.addProperty("way", way < 0 || way >= DodgeHabits.WAYS ? -1 : way);
        final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        WORKER.execute(() -> {
            try {
                HttpURLConnection c = open(base + "/habits", "POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream out = c.getOutputStream();
                out.write(bytes);
                out.close();
                c.getResponseCode();
            } catch (Exception ignored) {
            }
        });
    }

    private static HttpURLConnection open(String url, String method) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestProperty("X-Atlas-Report", "1");
        c.setRequestProperty("User-Agent", "Atlas");
        return c;
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int n;
        while ((n = in.read(buffer)) > 0 && out.size() < 16384) {
            out.write(buffer, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
