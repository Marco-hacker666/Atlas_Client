package atlas.management;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.TickEvent;
import atlas.module.Module;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class UsageReporter {
    private static final File CONSENT_FILE = new File("./config/Atlas/usage-consent.txt");

    private static final String YES = "yes4";
    public static final int CONSENT_VERSION = 3;
    private static final File NAME_FILE = new File("./config/Atlas/usage-name.txt");

    private static final java.util.regex.Pattern ALIAS = java.util.regex.Pattern.compile("[\\p{L}\\p{N}_ .-]{2,20}");
    private static volatile String alias;
    private static final long BEAT_MS = 45_000L;
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(8), r -> {
        Thread t = new Thread(r, "Atlas usage");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    private static volatile String state;
    private static volatile boolean stateRead;

    private static volatile String sid;
    private static volatile String sessionPlayer = "";
    private static String server;
    private static long lastBeat;

    private static long lastDiag;

    private static String gpu = "";
    private static String glVersion = "";

    private final Minecraft mc = Minecraft.getMinecraft();

    public UsageReporter() {
        Runtime.getRuntime().addShutdownHook(new Thread(UsageReporter::stopNow, "Atlas usage stop"));
    }

    public static Boolean consent() {
        String s = state();
        return YES.equals(s) ? Boolean.TRUE : "no".equals(s) ? Boolean.FALSE : null;
    }

    public static void setConsent(boolean yes) {
        synchronized (UsageReporter.class) {
            state = yes ? YES : "no";
            stateRead = true;
            try {
                File dir = CONSENT_FILE.getParentFile();
                if (dir != null && !dir.isDirectory()) {
                    dir.mkdirs();
                }
                Files.write(CONSENT_FILE.toPath(), (state + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("[Atlas] Could not save the usage choice: " + e);
            }
        }
        if (!yes) {
            stopNow();
        }
    }

    private static String state() {
        if (!stateRead) {
            synchronized (UsageReporter.class) {
                if (!stateRead) {
                    try {
                        if (CONSENT_FILE.isFile()) {
                            state = new String(Files.readAllBytes(CONSENT_FILE.toPath()), StandardCharsets.UTF_8).trim();
                        }
                    } catch (Exception ignored) {
                        state = null;
                    }
                    stateRead = true;
                }
            }
        }
        return state;
    }

    public static String alias() {
        if (alias == null) {
            synchronized (UsageReporter.class) {
                if (alias == null) {
                    String read = null;
                    try {
                        if (NAME_FILE.isFile()) {
                            read = new String(Files.readAllBytes(NAME_FILE.toPath()), StandardCharsets.UTF_8).trim();
                        }
                    } catch (Exception ignored) {
                        read = null;
                    }
                    alias = read != null && validAlias(read) ? read
                            : "Pilot-" + (1000 + new java.util.Random().nextInt(9000));
                }
            }
        }
        return alias;
    }

    public static boolean validAlias(String name) {
        if (name == null || !ALIAS.matcher(name.trim()).matches()) {
            return false;
        }

        return !name.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(admin|owner|staff|moder|develop|official|support|atlas\\s*team).*");
    }

    public static boolean setAlias(String name) {
        if (!validAlias(name)) {
            return false;
        }
        alias = name.trim().replaceAll("\\s+", " ");
        try {
            File dir = NAME_FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.write(NAME_FILE.toPath(), (alias + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Atlas] Could not save the public name: " + e);
        }
        return true;
    }

    public static JsonObject reportIdentity() {
        if (!Boolean.TRUE.equals(consent())) {
            return null;
        }
        Minecraft mc = Minecraft.getMinecraft();
        JsonObject o = new JsonObject();
        o.addProperty("consent", true);
        o.addProperty("consent_v", CONSENT_VERSION);
        String player = mc.getSession() != null ? mc.getSession().getUsername() : "";
        o.addProperty("player", player);
        if (sid != null) {
            o.addProperty("sid", sid);
        }
        o.addProperty("server", serverName(mc));
        o.addProperty("version", version());
        return o;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.POST) {
            return;
        }
        if (gpu.isEmpty()) {
            try {
                gpu = String.valueOf(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
                glVersion = String.valueOf(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION));
            } catch (Throwable t) {
                gpu = "?";
            }
        }
        boolean playing = this.mc.theWorld != null && this.mc.thePlayer != null;
        if (!Boolean.TRUE.equals(consent()) || !playing) {
            if (sid != null) {
                stopNow();
            }
            return;
        }
        String now = serverName(this.mc);
        long ms = System.currentTimeMillis();
        if (sid == null || !now.equals(server)) {
            if (sid != null) {
                stopNow();
            }
            sid = UUID.randomUUID().toString();
            server = now;
            lastBeat = ms;
            lastDiag = ms;
            final JsonObject start = this.payload("start");
            final String open = sid;
            final String player = sessionPlayer;
            final String environment = DiagReporter.environment(this.mc);

            WORKER.execute(() -> {
                JsonObject proof = this.prove(open, player);
                if (proof != null) {
                    start.add("proof", proof);
                }
                post(start);
                DiagReporter.sessionStart(open, player, environment);
            });
        } else if (ms - lastBeat >= BEAT_MS) {
            lastBeat = ms;
            send("beat", this.payload("beat"));

            if (ms - lastDiag >= DiagReporter.EVERY_MS) {
                lastDiag = ms;
                final String open = sid;
                final String player = sessionPlayer;
                WORKER.execute(() -> DiagReporter.logs(open, player));
            }
        }
        SupportLink.tick(sid, sessionPlayer);
    }

    static String currentSid() {
        return sid;
    }

    static String currentPlayer() {
        return sessionPlayer;
    }

    static String call(String path, JsonObject body) {
        String base = atlas.util.BugReport.relayBase();
        return base.isEmpty() ? null : request(base + path, path, body);
    }

    private JsonObject payload(String event) {
        JsonObject o = new JsonObject();
        o.addProperty("event", event);
        o.addProperty("consent_v", CONSENT_VERSION);
        o.addProperty("sid", sid);
        String player = this.mc.getSession() != null ? this.mc.getSession().getUsername() : "";
        sessionPlayer = player;
        o.addProperty("player", player);
        if (this.mc.thePlayer != null) {
            o.addProperty("uuid", this.mc.thePlayer.getUniqueID().toString());
        }
        o.addProperty("server", server);
        o.addProperty("alias", alias());
        o.addProperty("version", version());
        o.addProperty("fps", Minecraft.getDebugFPS());
        int ping = ping(this.mc);
        if (ping >= 0) {
            o.addProperty("ping", ping);
        }
        JsonArray modules = new JsonArray();
        if (Atlas.moduleManager != null) {
            for (Module m : Atlas.moduleManager.modules.values()) {
                if (m.isEnabled()) {
                    modules.add(new com.google.gson.JsonPrimitive(m.getName()));
                }
            }
        }
        o.add("modules", modules);

        o.add("details", this.details());
        return o;
    }

    private JsonObject details() {
        JsonObject d = new JsonObject();
        Runtime rt = Runtime.getRuntime();
        d.addProperty("os", System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""));
        d.addProperty("arch", System.getProperty("os.arch", "?"));
        d.addProperty("java", System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?") + ")");
        d.addProperty("cpu cores", rt.availableProcessors());
        d.addProperty("memory max MB", rt.maxMemory() / (1024L * 1024L));
        d.addProperty("memory used MB", (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L));
        d.addProperty("gpu", gpu);
        d.addProperty("opengl", glVersion);
        d.addProperty("screen", this.mc.displayWidth + "x" + this.mc.displayHeight);
        d.addProperty("gui scale", this.mc.gameSettings.guiScale);
        d.addProperty("render distance", this.mc.gameSettings.renderDistanceChunks);
        d.addProperty("language", this.mc.gameSettings.language);
        d.addProperty("minecraft", "1.8.9");
        try {
            d.addProperty("forge", net.minecraftforge.common.ForgeVersion.getVersion());
            d.addProperty("mods", net.minecraftforge.fml.common.Loader.instance().getModList().size());
        } catch (Throwable ignored) {
        }
        try {
            atlas.module.modules.ClickGUIModule gui = Atlas.moduleManager == null ? null
                    : (atlas.module.modules.ClickGUIModule) Atlas.moduleManager.modules.get(
                    atlas.module.modules.ClickGUIModule.class);
            if (gui != null) {
                d.addProperty("menu style", gui.style.getModeString());
            }
        } catch (Throwable ignored) {
        }
        d.addProperty("phone", atlas.util.render.MobileSafe.active());
        return d;
    }

    private JsonObject prove(String open, String player) {
        String base = atlas.util.BugReport.relayBase();
        if (base.isEmpty() || open == null) {
            return null;
        }
        try {
            JsonObject ask = new JsonObject();
            ask.addProperty("sid", open);
            ask.addProperty("player", player);
            String answer = request(base + "/usage/challenge", "/usage/challenge", ask);
            if (answer == null) {
                return null;
            }
            JsonObject d = new com.google.gson.JsonParser().parse(answer).getAsJsonObject();
            long t = d.get("t").getAsLong();
            BuildSignature.serverTime(t);
            if (this.mc.getSession() == null) {
                return null;
            }

            String serverId = proofId(open, player, t);
            this.mc.getSessionService().joinServer(this.mc.getSession().getProfile(), this.mc.getSession().getToken(),
                    serverId);
            JsonObject proof = new JsonObject();
            proof.addProperty("t", t);
            return proof;
        } catch (Throwable t) {
            return null;
        }
    }

    static String proofId(String sid, String player, long t) throws Exception {
        String text = "atlas-proof\n" + sid + "\n" + player.toLowerCase(java.util.Locale.ROOT) + "\n" + t;
        byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(40);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    private static void stopNow() {
        String open = sid;
        if (open == null) {
            return;
        }
        sid = null;
        server = null;
        JsonObject o = new JsonObject();
        o.addProperty("event", "stop");
        o.addProperty("sid", open);
        o.addProperty("player", sessionPlayer.isEmpty() ? "-" : sessionPlayer);
        if (Thread.currentThread().getName().equals("Atlas usage stop")) {
            post(o);
        } else {
            final String player = sessionPlayer;
            WORKER.execute(() -> DiagReporter.logs(open, player));
            send("stop", o);
        }
    }

    private static void send(String event, JsonObject body) {
        WORKER.execute(() -> post(body));
    }

    private static void post(JsonObject body) {
        String base = atlas.util.BugReport.relayBase();
        if (!base.isEmpty()) {
            request(base + "/usage", "/usage", body);
        }
    }

    static void postDiag(JsonObject body) {
        String base = atlas.util.BugReport.relayBase();
        if (!base.isEmpty()) {
            request(base + "/usage/diag", "/usage/diag", body);
        }
    }

    private static String request(String url, String path, JsonObject body) {
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setRequestProperty("X-Atlas-Report", "1");
            c.setRequestProperty("User-Agent", "Atlas");
            c.setRequestProperty("Content-Type", "application/json");
            BuildSignature.sign(c, path, bytes);
            OutputStream out = c.getOutputStream();
            out.write(bytes);
            out.close();
            int code = c.getResponseCode();
            String text = null;
            if (code == 200) {
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[1024];
                int n;
                while ((n = in.read(b)) > 0 && buf.size() < 8192) {
                    buf.write(b, 0, n);
                }
                in.close();
                text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            c.disconnect();
            return text;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String serverName(Minecraft mc) {
        if (mc.isSingleplayer()) {
            return "singleplayer";
        }
        ServerData data = mc.getCurrentServerData();
        return data == null || data.serverIP == null ? "unknown" : data.serverIP.trim().toLowerCase();
    }

    private static int ping(Minecraft mc) {
        try {
            if (mc.getNetHandler() == null || mc.thePlayer == null) {
                return -1;
            }
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            return info == null ? -1 : info.getResponseTime();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String version() {
        return Atlas.version == null || Atlas.version.isEmpty() ? "dev" : Atlas.version;
    }
}
