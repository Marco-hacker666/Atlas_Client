package atlas.mommy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class PanicRemote {
    private static HttpServer server;
    private static int port;
    private static Supplier<Panic.Plan> plans;

    private PanicRemote() {
    }

    public static synchronized boolean running() {
        return server != null;
    }

    public static synchronized boolean start(Supplier<Panic.Plan> planSource) {
        plans = planSource;
        PanicSetup setup = PanicSetup.get();
        if (server != null && port == setup.remotePort) {
            return true;
        }
        stop();
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(setup.remotePort), 8);
            s.createContext("/", PanicRemote::handle);
            s.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Atlas mommy remote");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            port = setup.remotePort;
            return true;
        } catch (Exception e) {
            System.err.println("[Atlas] mommy remote: " + e);
            return false;
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public static String phoneUrl() {
        String ip = lanAddress();
        return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + PanicSetup.get().remotePort + "/?k="
                + PanicSetup.get().remoteKey;
    }

    public static String pairUrl() {
        return "http://127.0.0.1:" + PanicSetup.get().remotePort + "/pair?k=" + PanicSetup.get().remoteKey;
    }

    private static void handle(HttpExchange ex) {
        try {
            InetAddress from = ex.getRemoteAddress().getAddress();
            if (!local(from)) {
                send(ex, 403, "text/plain", "no");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            String key = PanicSetup.get().remoteKey;
            if (method.equals("GET") && path.equals("/")) {
                send(ex, 200, "text/html; charset=utf-8", PanicPages.phone());
                return;
            }
            if (method.equals("GET") && path.equals("/qrcode.js")) {
                send(ex, 200, "application/javascript", resource("/assets/atlas/mommy/qrcode.min.js"));
                return;
            }
            if (method.equals("GET") && path.equals("/pair")) {
                if (!from.isLoopbackAddress() || !sameKey(query(ex, "k"), key)) {
                    send(ex, 403, "text/plain", "Open this from .mommy phone in the game.");
                    return;
                }
                send(ex, 200, "text/html; charset=utf-8", PanicPages.pair(phoneUrl()));
                return;
            }
            if (path.startsWith("/api/")) {
                if (!sameKey(ex.getRequestHeaders().getFirst("X-Key"), key)) {
                    send(ex, 401, "application/json", "{\"error\":\"wrong key\"}");
                    return;
                }
                if (method.equals("POST") && path.equals("/api/fire")) {
                    Supplier<Panic.Plan> p = plans;
                    if (p != null) {
                        Panic.fireLater(p.get());
                    }
                } else if (method.equals("POST") && path.equals("/api/restore")) {
                    Panic.restoreLater();
                } else if (!(method.equals("GET") && path.equals("/api/state"))) {
                    send(ex, 404, "application/json", "{\"error\":\"not found\"}");
                    return;
                }
                if (method.equals("POST")) {
                    Thread.sleep(250L);
                }
                send(ex, 200, "application/json", "{\"active\":" + Panic.active() + ",\"countdown\":"
                        + Panic.countdown() + "}");
                return;
            }
            send(ex, 404, "text/plain", "not found");
        } catch (Exception e) {
            try {
                send(ex, 500, "text/plain", "error");
            } catch (Exception ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(code, b.length);
        OutputStream out = ex.getResponseBody();
        out.write(b);
        out.close();
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return "";
        }
        for (String part : q.split("&")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        return "";
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = PanicRemote.class.getResourceAsStream(path)) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static boolean sameKey(String given, String key) {
        if (given == null || key == null || key.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    static boolean local(InetAddress a) {
        if (a == null) {
            return false;
        }
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) {
            return true;
        }
        if (a instanceof Inet6Address) {
            byte b = a.getAddress()[0];
            return (b & 0xFE) == 0xFC;
        }
        return false;
    }

    static String lanAddress() {
        String best = null;
        int bestRank = 99;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String name = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                if (name.contains("virtual") || name.contains("vmware") || name.contains("vethernet")
                        || name.contains("hyper-v") || name.contains("wsl") || name.contains("vpn")
                        || name.contains("tap-") || name.contains("wintun") || name.contains("docker")) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    int rank = ip.startsWith("192.168.") ? 0 : ip.startsWith("10.") ? 1 : 2;
                    if (rank < bestRank) {
                        best = ip;
                        bestRank = rank;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return best;
    }
}
