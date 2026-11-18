package atlas.ui.hud;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HudAnchors {
    private static final File FILE = new File("./config/Atlas/", "hud-anchors.txt");
    private static final long SAVE_EVERY_MS = 2000L;

    static final class Anchor {
        int col;
        int row;
        float offX;
        float offY;
        int screenW;
        int screenH;

        float lastX;
        float lastY;
    }

    private static final Map<String, Anchor> ANCHORS = new LinkedHashMap<String, Anchor>();
    private static boolean loaded;
    private static boolean dirty;
    private static long savedAt;

    private HudAnchors() {
    }

    static int third(float start, float size, int screen) {
        float centre = start + size / 2.0F;
        if (centre < screen / 3.0F) {
            return 0;
        }
        return centre > screen * 2.0F / 3.0F ? 2 : 1;
    }

    static float anchorAt(int index, int screen) {
        return index == 0 ? 0.0F : index == 1 ? screen / 2.0F : screen;
    }

    static float pointOf(int index, float start, float size) {
        return index == 0 ? start : index == 1 ? start + size / 2.0F : start + size;
    }

    static float startFor(int index, float offset, float size, int screen) {
        float point = anchorAt(index, screen) + offset;
        float start = index == 0 ? point : index == 1 ? point - size / 2.0F : point - size;
        return Math.max(0.0F, Math.min(Math.max(0.0F, screen - size), start));
    }

    static void track(HudLayout.Element element, int screenW, int screenH) {
        load();
        Anchor anchor = ANCHORS.get(element.id);
        if (anchor == null) {
            anchor = new Anchor();
            place(anchor, element, screenW, screenH);
            ANCHORS.put(element.id, anchor);
            dirty = true;
        } else if (anchor.screenW == screenW && anchor.screenH == screenH) {
            float px = pointOf(anchor.col, element.x, element.width);
            float py = pointOf(anchor.row, element.y, element.height);
            if (Float.isNaN(anchor.lastX) || Math.abs(px - anchor.lastX) > 1.5F || Math.abs(py - anchor.lastY) > 1.5F) {
                place(anchor, element, screenW, screenH);
                dirty = true;
            }
        } else {
            float wantX = startFor(anchor.col, anchor.offX, element.width, screenW);
            float wantY = startFor(anchor.row, anchor.offY, element.height, screenH);
            int dx = Math.round(wantX - element.x);
            int dy = Math.round(wantY - element.y);
            int[] moved = dx != 0 || dy != 0 ? element.mover.moveBy(dx, dy) : new int[]{0, 0};
            anchor.screenW = screenW;
            anchor.screenH = screenH;
            anchor.lastX = pointOf(anchor.col, element.x + moved[0], element.width);
            anchor.lastY = pointOf(anchor.row, element.y + moved[1], element.height);
            dirty = true;
        }
        saveSoon();
    }

    private static void place(Anchor anchor, HudLayout.Element element, int screenW, int screenH) {
        anchor.col = third(element.x, element.width, screenW);
        anchor.row = third(element.y, element.height, screenH);
        anchor.lastX = pointOf(anchor.col, element.x, element.width);
        anchor.lastY = pointOf(anchor.row, element.y, element.height);
        anchor.offX = anchor.lastX - anchorAt(anchor.col, screenW);
        anchor.offY = anchor.lastY - anchorAt(anchor.row, screenH);
        anchor.screenW = screenW;
        anchor.screenH = screenH;
    }

    static float[] marker(HudLayout.Element element, int screenW, int screenH) {
        Anchor anchor = ANCHORS.get(element.id);
        if (anchor == null) {
            return null;
        }
        return new float[]{anchorAt(anchor.col, screenW), anchorAt(anchor.row, screenH),
                pointOf(anchor.col, element.x, element.width), pointOf(anchor.row, element.y, element.height)};
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!FILE.exists()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(FILE));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length != 7) {
                    continue;
                }
                Anchor anchor = new Anchor();
                anchor.col = Integer.parseInt(parts[1]);
                anchor.row = Integer.parseInt(parts[2]);
                anchor.offX = Float.parseFloat(parts[3]);
                anchor.offY = Float.parseFloat(parts[4]);
                anchor.screenW = Integer.parseInt(parts[5]);
                anchor.screenH = Integer.parseInt(parts[6]);

                anchor.lastX = Float.NaN;
                anchor.lastY = Float.NaN;
                ANCHORS.put(parts[0], anchor);
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void saveSoon() {
        long now = System.currentTimeMillis();
        if (dirty && now - savedAt >= SAVE_EVERY_MS) {
            save();
        }
    }

    static void save() {
        if (!loaded) {
            return;
        }
        dirty = false;
        savedAt = System.currentTimeMillis();
        PrintWriter writer = null;
        try {
            FILE.getParentFile().mkdirs();
            writer = new PrintWriter(FILE);
            for (Map.Entry<String, Anchor> entry : ANCHORS.entrySet()) {
                Anchor a = entry.getValue();
                writer.println(entry.getKey() + "|" + a.col + "|" + a.row + "|" + a.offX + "|" + a.offY
                        + "|" + a.screenW + "|" + a.screenH);
            }
        } catch (Exception ignored) {
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
