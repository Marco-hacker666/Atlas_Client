package myau.update;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The small text file each release carries next to its jar (update.properties):
 *
 * <pre>
 * format=1
 * version=2.1.2
 * file=Myau-Atlas-v2.1.2.jar
 * size=27561234
 * sha256=9f86d0...
 * signature=MEUCIQ...   (base64, ECDSA P-256 over payload())
 * </pre>
 *
 * The signature covers the version, file name, size and hash together, so a
 * signed manifest cannot be reused to make an old jar look like a new
 * version. The download address is not in the file at all: the client builds
 * it from the version and file name, so a manifest cannot send it anywhere
 * but this repository's releases.
 */
public final class UpdateManifest {

    public static final int FORMAT = 1;
    /** Plain file names only: no slashes, no "..", must end in .jar. */
    private static final Pattern SAFE_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\\.jar");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    /** Jars are ~26 MB today; anything past this is not ours. */
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

    /** The exact bytes that are signed. Field order and line endings are fixed. */
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
