package myau.ui.hud;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps each HUD element where it was placed relative to the screen
 * (2026-10-18, owner: "HUD should use anchor positioning").
 *
 * The sixteen HUD modules store positions sixteen ways -- absolute pixels,
 * a margin from the right, a free drag -- so switching between windowed and
 * fullscreen, resizing, or changing the GUI scale left elements in the middle
 * of the screen or off it. Every element already reports its rectangle and a
 * mover to HudLayout each frame; this layer sits there, so no module's
 * settings change.
 *
 * The screen is split three by three and each element hangs from the nearest
 * of the nine anchors (left/centre/right x top/middle/bottom), by the matching
 * point of itself (its left edge, centre or right edge; top, middle or bottom)
 * at an offset. When the screen size changes the element is moved, through
 * its own mover, so that point is at the same offset from the same anchor on
 * the new screen, kept on screen. At the same screen size a move -- the HUD
 * editor, a profile, the module's own content growing -- is taken as the new
 * placement and re-anchored, so this never fights the player.
 *
 * Saved in config/Atlas/hud-anchors.txt.
 */
public final class HudAnchors {
    private static final File FILE = new File("./config/Atlas/", "hud-anchors.txt");
    private static final long SAVE_EVERY_MS = 2000L;

    /** One element's anchor: column and row 0-2, offset of its matching point, and the screen it was placed on. */
    static final class Anchor {
        int col;
        int row;
        float offX;
        float offY;
        int screenW;
        int screenH;
        /** Where the matching point was last seen, to tell a placement from a resize. */
        float lastX;
        float lastY;
    }

    private static final Map<String, Anchor> ANCHORS = new LinkedHashMap<String, Anchor>();
    private static boolean loaded;
    private static boolean dirty;
    private static long savedAt;

    private HudAnchors() {
    }

    /** Column (0 left, 1 centre, 2 right) of an element by its centre, in thirds of the screen. */
    static int third(float start, float size, int screen) {
        float centre = start + size / 2.0F;
        if (centre < screen / 3.0F) {
            return 0;
        }
        return centre > screen * 2.0F / 3.0F ? 2 : 1;
    }

    /** The anchor's coordinate on a screen axis. */
    static float anchorAt(int index, int screen) {
        return index == 0 ? 0.0F : index == 1 ? screen / 2.0F : screen;
    }

    /** The element's point that matches its anchor on one axis. */
    static float pointOf(int index, float start, float size) {
        return index == 0 ? start : index == 1 ? start + size / 2.0F : start + size;
    }

    /** Where the element's start must be on one axis so its point sits at the anchor plus the offset, kept on screen. */
    static float startFor(int index, float offset, float size, int screen) {
        float point = anchorAt(index, screen) + offset;
        float start = index == 0 ? point : index == 1 ? point - size / 2.0F : point - size;
        return Math.max(0.0F, Math.min(Math.max(0.0F, screen - size), start));
    }

    /**
     * Called by HudLayout.report for every element, every frame, with the
     * scaled screen size.
     */
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
                /* Moved at the same screen size: a new placement. */
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

    /** {anchorX, anchorY, pointX, pointY} for the editor's marker, or null. */
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
                /* Unknown until seen: the first report at this size re-anchors from where the module put it. */
                anchor.lastX = Float.NaN;
                anchor.lastY = Float.NaN;
                ANCHORS.put(parts[0], anchor);
            }
        } catch (Exception ignored) {
            /* A missing or unreadable file means elements anchor afresh where they are. */
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // Nothing further to do.
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

    /** Writes the anchors now (also when the HUD editor closes). */
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
            // Kept in memory; written again on the next change.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }
}
