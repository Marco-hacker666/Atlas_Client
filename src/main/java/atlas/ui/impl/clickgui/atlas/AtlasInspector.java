package atlas.ui.impl.clickgui.atlas;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

final class AtlasInspector {
    private static final File DIR = new File("./config/Atlas/ui/");
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private AtlasInspector() {
    }

    private static boolean recording;
    private static final List<Rect> DRAWN = new ArrayList<Rect>();
    private static final List<String> FINDINGS = new ArrayList<String>();

    private static final class Rect {
        final String purpose;
        final String group;
        final float x;
        final float y;
        final float x2;
        final float y2;

        Rect(String group, String purpose, float x, float y, float x2, float y2) {
            this.group = group;
            this.purpose = purpose;
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
        }

        boolean overlaps(Rect other) {
            return this.x < other.x2 && other.x < this.x2
                    && this.y < other.y2 && other.y < this.y2;
        }

        @Override
        public String toString() {
            return String.format("%s/%s [%.1f,%.1f %.1f,%.1f]",
                    this.group, this.purpose, this.x, this.y, this.x2, this.y2);
        }
    }

    private static boolean live;
    private static long lastCapture;
    private static String lastFindings = "";

    private static final int KEEP = 12;
    private static final long INTERVAL_MS = 2000L;

    static boolean isLive() {
        return live;
    }

    static String toggleLive() {
        live = !live;
        lastCapture = 0L;
        lastFindings = "";
        if (live) {
            recording = true;
            return "live inspect ON -- a frame every 2s into config/Atlas/ui/";
        }
        recording = false;
        return "live inspect OFF";
    }

    static void tick() {
        if (!live) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCapture < INTERVAL_MS) {
            return;
        }
        lastCapture = now;

        List<String> findings = audit();
        String joined = join(findings);

        if (!joined.equals(lastFindings)) {
            lastFindings = joined;
            appendLog(findings);
        }
        capture();
        prune();
    }

    private static String join(List<String> findings) {
        if (findings.isEmpty()) {
            return "clean";
        }
        StringBuilder sb = new StringBuilder();
        for (String finding : findings) {
            sb.append(finding).append((char) 10);
        }
        return sb.toString();
    }

    private static void appendLog(List<String> findings) {
        PrintWriter writer = null;
        try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                return;
            }
            writer = new PrintWriter(new FileWriter(new File(DIR, "layout.txt"), true));
            writer.println("==== " + new Date() + " ==== " + DRAWN.size() + " rects");
            if (findings.isEmpty()) {
                writer.println("clean");
            } else {
                for (String finding : findings) {
                    writer.println("  " + finding);
                }
            }
            writer.println();
            writer.flush();
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private static void prune() {
        try {
            File[] files = DIR.listFiles(new java.io.FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith("atlas-") && name.endsWith(".png");
                }
            });
            if (files == null || files.length <= KEEP) {
                return;
            }
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
            });
            for (int i = KEEP; i < files.length; i++) {
                if (!files[i].delete()) {
                    files[i].deleteOnExit();
                }
            }
        } catch (Exception ignored) {
        }
    }

    static boolean isRecording() {
        return recording;
    }

    static void beginFrame() {
        if (recording) {
            DRAWN.clear();
        }
    }

    static void note(String group, String purpose, float x, float y, float x2, float y2) {
        if (recording) {
            DRAWN.add(new Rect(group, purpose, x, y, x2, y2));
        }
    }

    static void noteHit(String group, String purpose, float x, float y, float x2, float y2) {
        note(group, "hit:" + purpose, x, y, x2, y2);
    }

    static List<String> audit() {
        FINDINGS.clear();
        for (int i = 0; i < DRAWN.size(); i++) {
            Rect a = DRAWN.get(i);
            if (a.x2 - a.x < 0.5F || a.y2 - a.y < 0.5F) {
                FINDINGS.add("degenerate: " + a);
                continue;
            }
            if (a.purpose.startsWith("hit:")) {
                Rect drawn = find(a.group, a.purpose.substring(4));
                if (drawn == null) {
                    FINDINGS.add("hit target with nothing drawn for it: " + a);
                } else if (Math.abs(drawn.y - a.y) > 1.0F || Math.abs(drawn.y2 - a.y2) > 1.0F) {
                    FINDINGS.add(String.format(
                            "hit/draw mismatch on %s/%s: drawn y %.1f..%.1f, tested y %.1f..%.1f",
                            a.group, a.purpose.substring(4), drawn.y, drawn.y2, a.y, a.y2));
                }
                continue;
            }
            for (int j = i + 1; j < DRAWN.size(); j++) {
                Rect b = DRAWN.get(j);
                if (!a.group.equals(b.group) || b.purpose.startsWith("hit:")) {
                    continue;
                }
                if (a.overlaps(b)) {
                    FINDINGS.add("overlap in " + a.group + ": " + a + "  vs  " + b);
                }
            }
        }
        return FINDINGS;
    }

    private static Rect find(String group, String purpose) {
        for (Rect rect : DRAWN) {
            if (rect.group.equals(group) && rect.purpose.equals(purpose)) {
                return rect;
            }
        }
        return null;
    }

    static String check() {
        recording = true;

        return "recording next frame";
    }

    static String report() {
        List<String> findings = audit();
        recording = false;
        PrintWriter writer = null;
        try {
            if (!DIR.exists() && !DIR.mkdirs()) {
                return "could not create " + DIR;
            }
            File target = new File(DIR, "layout.txt");
            writer = new PrintWriter(new FileWriter(target, true));
            writer.println("==== " + new Date() + " ====");
            writer.println(DRAWN.size() + " rectangles checked");
            if (findings.isEmpty()) {
                writer.println("clean");
            } else {
                for (String finding : findings) {
                    writer.println("  " + finding);
                }
            }
            writer.println();
            writer.flush();
            return findings.isEmpty()
                    ? "layout clean (" + DRAWN.size() + " rects)"
                    : findings.size() + " layout problems, see config/Atlas/ui/layout.txt";
        } catch (Exception e) {
            return "check failed: " + e;
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    static String capture() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (!DIR.exists() && !DIR.mkdirs()) {
                return "could not create " + DIR;
            }
            String name = "atlas-" + STAMP.format(new Date()) + ".png";

            File shots = new File(DIR, "screenshots");
            if (!shots.exists() && !shots.mkdirs()) {
                return "could not create " + shots;
            }
            ScreenShotHelper.saveScreenshot(DIR, name,
                    mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            return "saved config/Atlas/ui/screenshots/" + name;
        } catch (Throwable t) {
            return "capture failed: " + t;
        }
    }
}
