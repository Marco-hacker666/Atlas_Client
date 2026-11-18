package atlas.update;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

public final class UpdateManifest {
    public static final int FORMAT = 1;

    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public static final long MAX_JAR_BYTES = 128L * 1024 * 1024;

    public final UpdateVersion version;
    public final String file;
    public final long size;
    public final String sha256;
    public final String signature;

    public UpdateManifest(UpdateVersion version, String file, long size, String sha256, String signature) {
        this.version = version;
        this.file = file;
        this.size = size;
        this.sha256 = sha256;
        this.signature = signature;
    }

    public static String payload(String version, String file, long size, String sha256) {
        return "myau-update/" + FORMAT + "\n"
                + "version=" + version + "\n"
                + "file=" + file + "\n"
                + "size=" + size + "\n"
                + "sha256=" + sha256 + "\n";
    }

    public byte[] payloadBytes() {
        return payload(version.toString(), file, size, sha256).getBytes(StandardCharsets.UTF_8);
    }

    public static UpdateManifest parse(String text) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(text));
        String format = req(p, "format");
        if (!String.valueOf(FORMAT).equals(format)) {
            throw new IOException("Unsupported manifest format " + format);
        }
        UpdateVersion version = UpdateVersion.parse(req(p, "version"));
        if (version == null) {
            throw new IOException("Bad version in manifest");
        }
        String file = req(p, "file");
        if (!SAFE_FILE.matcher(file).matches() || file.contains("..")) {
            throw new IOException("Bad file name in manifest");
        }
        long size;
        try {
            size = Long.parseLong(req(p, "size"));
        } catch (NumberFormatException e) {
            throw new IOException("Bad size in manifest");
        }
        if (size <= 0 || size > MAX_JAR_BYTES) {
            throw new IOException("Size out of range in manifest");
        }
        String sha = req(p, "sha256").toLowerCase(java.util.Locale.ROOT);
        if (!HEX64.matcher(sha).matches()) {
            throw new IOException("Bad sha256 in manifest");
        }
        String sig = req(p, "signature");
        return new UpdateManifest(version, file, size, sha, sig);
    }

    private static String req(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IOException("Manifest is missing '" + key + "'");
        }
        return v.trim();
    }
}
