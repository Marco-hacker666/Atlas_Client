package atlas.script.raven.model;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Raven script API: a WebSocket client (2026-11-02). A script makes one as an anonymous subclass and overrides
 * onOpen / onMessage / onClose / onError, which are called on the socket's own thread. Text frames; ping is
 * answered. Written from RFC 6455 (Raven ships a library for this; Atlas has none).
 */
public class WebSocket {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final URI uri;
    private final Map<String, String> httpHeaders;
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean open;
    private volatile boolean closed;
    private final Object writeLock = new Object();
    private final Object openLock = new Object();
    private volatile boolean finished;

    public WebSocket(String serverURI) {
        this(serverURI, Collections.<String, String>emptyMap());
    }

    public WebSocket(String serverUri, Map<String, String> httpHeaders) {
        this.uri = URI.create(serverUri);
        this.httpHeaders = httpHeaders == null ? Collections.<String, String>emptyMap() : httpHeaders;
    }

    public void onOpen(short status, String message) {
    }

    public void onMessage(String message) {
    }

    public void onClose(int code, String reason, boolean remote) {
    }

    public void onError(Exception ex) {
    }

    /** Connects on a thread of its own; with block, waits (up to 10 s) and says whether it is open. */
    public boolean connect(boolean block) {
        if (this.open) {
            return true;
        }
        this.finished = false;
        this.closed = false;
        Thread thread = new Thread(this::run, "Atlas-ScriptWebSocket");
        thread.setDaemon(true);
        thread.start();
        if (!block) {
            /* As Raven: started; onOpen says when it is up. */
            return true;
        }
        synchronized (this.openLock) {
            long until = System.currentTimeMillis() + 10000L;
            while (!this.open && !this.finished && System.currentTimeMillis() < until) {
                try {
                    this.openLock.wait(Math.max(1L, until - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return this.open;
    }

    private void run() {
        try {
            boolean secure = "wss".equalsIgnoreCase(this.uri.getScheme());
            int port = this.uri.getPort() > 0 ? this.uri.getPort() : secure ? 443 : 80;
            Socket raw = secure ? SSLSocketFactory.getDefault().createSocket() : new Socket();
            raw.connect(new InetSocketAddress(this.uri.getHost(), port), 10000);
            this.socket = raw;
            this.out = raw.getOutputStream();
            DataInputStream in = new DataInputStream(raw.getInputStream());
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            String path = this.uri.getRawPath() == null || this.uri.getRawPath().isEmpty() ? "/" : this.uri.getRawPath();
            if (this.uri.getRawQuery() != null) {
                path += "?" + this.uri.getRawQuery();
            }
            StringBuilder request = new StringBuilder();
            request.append("GET ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: ").append(this.uri.getHost()).append(this.uri.getPort() > 0 ? ":" + port : "").append("\r\n");
            request.append("Upgrade: websocket\r\nConnection: Upgrade\r\n");
            request.append("Sec-WebSocket-Key: ").append(key).append("\r\nSec-WebSocket-Version: 13\r\n");
            for (Map.Entry<String, String> header : this.httpHeaders.entrySet()) {
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            request.append("\r\n");
            this.out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            this.out.flush();
            String status = readLine(in);
            if (status == null || !status.contains(" 101")) {
                throw new IOException("the server did not switch to WebSocket: " + status);
            }
            String accept = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().toLowerCase(Locale.ROOT).equals("sec-websocket-accept")) {
                    accept = line.substring(colon + 1).trim();
                }
            }
            String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
            if (!expected.equals(accept)) {
                throw new IOException("the server's handshake answer is wrong");
            }
            this.open = true;
            this.signal();
            this.onOpen((short) 101, "Switching Protocols");
            this.readFrames(in);
        } catch (Exception e) {
            if (!this.closed) {
                this.onError(e);
            }
            this.shut(1006, e.getMessage() == null ? "" : e.getMessage(), true);
        } finally {
            this.finished = true;
            this.signal();
        }
    }

    private void signal() {
        synchronized (this.openLock) {
            this.openLock.notifyAll();
        }
    }

    private void readFrames(DataInputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageType = -1;
        while (this.open) {
            int first = in.readUnsignedByte();
            int second = in.readUnsignedByte();
            boolean fin = (first & 0x80) != 0;
            int opcode = first & 0x0F;
            long length = second & 0x7F;
            if (length == 126) {
                length = in.readUnsignedShort();
            } else if (length == 127) {
                length = in.readLong();
            }
            if (length > 16L * 1024 * 1024) {
                throw new IOException("frame too large");
            }
            byte[] mask = null;
            if ((second & 0x80) != 0) {
                mask = new byte[4];
                in.readFully(mask);
            }
            byte[] payload = new byte[(int) length];
            in.readFully(payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            if (opcode == 0x8) {
                int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                String reason = payload.length > 2 ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                this.writeFrame(0x8, payload);
                this.shut(code, reason, true);
                return;
            }
            if (opcode == 0x9) {
                this.writeFrame(0xA, payload);
                continue;
            }
            if (opcode == 0xA) {
                continue;
            }
            if (opcode == 0x1 || opcode == 0x2) {
                messageType = opcode;
                message.reset();
            }
            message.write(payload);
            if (fin && messageType == 0x1) {
                this.onMessage(new String(message.toByteArray(), StandardCharsets.UTF_8));
                message.reset();
                messageType = -1;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int end = line.length();
                return end > 0 && line.charAt(end - 1) == '\r' ? line.substring(0, end - 1) : line.toString();
            }
            line.append((char) c);
            if (line.length() > 8192) {
                throw new IOException("header line too long");
            }
        }
        return line.length() == 0 ? null : line.toString();
    }

    /** A client frame: always masked. */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        OutputStream stream = this.out;
        if (stream == null) {
            throw new IOException("not connected");
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 14);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write(payload.length >>> 8);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (((long) payload.length >>> shift) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        synchronized (this.writeLock) {
            stream.write(frame.toByteArray());
            stream.flush();
        }
    }

    public void send(String message) {
        if (!this.open) {
            return;
        }
        try {
            this.writeFrame(0x1, message.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            this.onError(e);
            this.shut(1006, e.getMessage() == null ? "" : e.getMessage(), false);
        }
    }

    /** Closes (code 1000); with block, waits for the socket to finish. */
    public void close(boolean block) {
        if (this.open) {
            try {
                this.writeFrame(0x8, new byte[]{(byte) 0x03, (byte) 0xE8});
            } catch (IOException ignored) {
                // Closing anyway.
            }
        }
        this.shut(1000, "", false);
        if (block) {
            synchronized (this.openLock) {
                long until = System.currentTimeMillis() + 5000L;
                while (!this.finished && System.currentTimeMillis() < until) {
                    try {
                        this.openLock.wait(100L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void shut(int code, String reason, boolean remote) {
        boolean wasOpen = this.open;
        this.open = false;
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            Socket current = this.socket;
            if (current != null) {
                current.close();
            }
        } catch (IOException ignored) {
            // Gone.
        }
        if (wasOpen) {
            this.onClose(code, reason, remote);
        }
    }

    public boolean isOpen() {
        return this.open;
    }
}
