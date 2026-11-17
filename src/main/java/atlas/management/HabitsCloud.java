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

/**
 * Dodge habits shared through the relay (2026-11-16, owner: everyone's dodge data in one place, a record per player
 * created on first meeting, read by everyone after). The relay's /habits route (report-relay/src/habits.js) keeps
 * one record per lower-case name, cracked accounts apart, old observations halved every 30 days.
 *
 * - ask(): the first time a player is met this session, its record is read, on a thread of its own; until it comes
 *   the local habits (DodgeHabits) are used, and when the relay has none the player starts empty.
 * - tell(): one shot watched (a reaction and a way) goes to the relay, while Client Settings > Privacy >
 *   share-dodge-data is on (on unless turned off). What is sent: the player's name, whether it is cracked, the
 *   reaction in ticks and the way. Nothing about this client.
 *
 * Nothing here ever holds the game up: a slow or absent relay only means the local habits are used.
 */
public final class HabitsCloud {
    private static final SharedHabits SHARED = new SharedHabits();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Set<String> ASKED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /* One worker; at most a few hundred jobs waiting, the rest dropped (a busy lobby must not pile up requests). */
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

    /** share-dodge-data (Client Settings > Privacy). */
    public static void setSharing(boolean on) {
        sharing = on;
    }

    public static boolean sharing() {
        return sharing;
    }

    /** Reads this player's record once a session (in the background). */
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
                /* Asked again the next time it is met. */
                ASKED.remove(key);
            }
        });
    }

    /** One shot watched: counted here at once, sent to the relay while sharing. */
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
                // Not sent: the local habits still have it.
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
