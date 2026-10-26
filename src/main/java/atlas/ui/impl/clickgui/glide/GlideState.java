package atlas.ui.impl.clickgui.glide;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the Glide windows were left: config/Atlas/glide-gui.txt, one line per
 * window ("title TAB x TAB y TAB folded TAB hidden"). The first line names the
 * window width, so a file saved for another width is ignored and the windows
 * lay out afresh.
 */
final class GlideState {
    private static final File FILE = new File("./config/Atlas/glide-gui.txt");

    private GlideState() {
    }

    private static String header() {
        return "#w" + (int) GlideTheme.WIDTH;
    }

    static Map<String, float[]> read() {
        Map<String, float[]> out = new HashMap<String, float[]>();
        try {
            if (!FILE.isFile()) {
                return out;
            }
            List<String> lines = Files.readAllLines(FILE.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(header())) {
                return out;
            }
            for (String line : lines) {
                String[] parts = line.split("\t");
                if (parts.length == 5) {
                    out.put(parts[0], new float[]{Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3]), Float.parseFloat(parts[4])});
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void write(List<GlideFrame> frames) {
        try (PrintWriter writer = new PrintWriter(FILE, "UTF-8")) {
            writer.println(header());
            for (GlideFrame frame : frames) {
                writer.println(frame.title + "\t" + frame.x + "\t" + frame.y + "\t" + (frame.folded ? 1 : 0)
                        + "\t" + (frame.hidden ? 1 : 0));
            }
        } catch (Exception ignored) {
        }
    }

    static void place(GlideFrame frame, Map<String, float[]> saved) {
        float[] s = saved.get(frame.title);
        if (s != null) {
            frame.x = s[0];
            frame.y = s[1];
            frame.folded = s[2] != 0.0F;
            frame.hidden = s[3] != 0.0F && !frame.main;
        }
    }
}
