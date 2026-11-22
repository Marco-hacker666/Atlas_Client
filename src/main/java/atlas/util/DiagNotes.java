package atlas.util;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public final class DiagNotes {
    private static final File DIR = new File("./config/Atlas/");
    private static final long REPEAT_MS = 2000L;
    private static final Map<String, Long> LAST = new HashMap<String, Long>();
    private static File file;

    private DiagNotes() {
    }

    public static void note(String module, String line) {
        note(module, line, false);
    }

    public static synchronized void note(String module, String line, boolean throttle) {
        if (line == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String clean = strip(line);
        if (throttle) {
            String key = module + "|" + clean;
            Long last = LAST.get(key);
            if (last != null && now - last < REPEAT_MS) {
                return;
            }
            if (LAST.size() > 512) {
                LAST.clear();
            }
            LAST.put(key, now);
        }
        if (file == null) {
            file = new File(DIR, "notes-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
        }
        AsyncLog.append(file, new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(now)) + " [" + module + "] "
                + clean);
    }

    static String strip(String line) {
        return line.replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }
}
