package atlas.script.raven.model;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Raven script API: an HTTP request (2026-11-02). fetch() waits for the answer, so call it from client.async, not
 * from a hook (a hook on the client thread would freeze the game until the server answers).
 */
public class Request {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /** What Raven says it is when the script names nothing. */
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
            + " Chrome/120.0.0.0 Safari/537.36";

    public String method;
    public String url;
    public List<String[]> headers = new ArrayList<String[]>();
    public String userAgent = "";
    public int connectionTimeout = 5000;
    public int readTimeout = 5000;
    public String content = "";

    /** As in Raven: "POST" or "GET" (anything else is a GET). */
    public Request(String method, String URL) {
        this.method = "POST".equals(method) || "GET".equals(method) ? method : "GET";
        this.url = URL;
    }

    public void addHeader(final String header, final String value) {
        this.headers.add(new String[]{header, value});
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void setConnectTimeout(int timeout) {
        this.connectionTimeout = timeout;
    }

    public void setReadTimeout(int timeout) {
        this.readTimeout = timeout;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * The answer, or null when there was none (no connection, timeout, no url). As Raven sends it: a browser's
     * User-Agent unless one was set, a body only for a POST with content (as JSON), and the answer's lines joined
     * without their line breaks.
     */
    public Response fetch() {
        if (this.url == null || this.url.isEmpty()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(this.url).openConnection();
            connection.setRequestMethod(this.method);
            connection.setConnectTimeout(this.connectionTimeout);
            connection.setReadTimeout(this.readTimeout);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    this.userAgent == null || this.userAgent.isEmpty() ? BROWSER : this.userAgent);
            for (String[] header : this.headers) {
                connection.setRequestProperty(header[0], header[1]);
            }
            if ("POST".equals(this.method) && this.content != null && !this.content.isEmpty()) {
                connection.setDoOutput(true);
                byte[] body = this.content.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String contents = in == null ? "" : read(in).replace("\r", "").replace("\n", "");
            List<String[]> answerHeaders = new ArrayList<String[]>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    answerHeaders.add(new String[]{entry.getKey(), value});
                }
            }
            return new Response(code, contents, answerHeaders);
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    break;
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public String toString() {
        return "Request(" + this.method + "," + this.url + ")";
    }
}
