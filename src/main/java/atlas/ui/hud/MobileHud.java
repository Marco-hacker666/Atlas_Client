package atlas.ui.hud;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class MobileHud {
    public static final float SCALE = 0.85F;

    static final float[][] ZONES = {
            {0.00F, 0.00F, 0.22F, 0.16F},
            {0.78F, 0.00F, 1.00F, 0.16F},
            {0.00F, 0.55F, 0.30F, 1.00F},
            {0.70F, 0.50F, 1.00F, 1.00F},
    };
    private static final float GAP = 2.0F;
    private static final File FILE = new File("./config/Atlas/mobile-hud.txt");
    private static final String KEYSTROKES = "module:KeyStrokes";

    private static Set<String> done;

    private static final Map<String, Boolean> scaled = new HashMap<String, Boolean>();

    private MobileHud() {
    }

    static void consider(HudLayout.Element element, int screenWidth, int screenHeight) {
        if (!atlas.util.render.DeviceProfile.isMobile()) {
            return;
        }
        Set<String> handled = done();
        if (!handled.contains(KEYSTROKES)) {
            switchOffKeyStrokes();
            handled.add(KEYSTROKES);
            save();
        }
        if (handled.contains(element.id)) {
            return;
        }
        if (!scaled.containsKey(element.id)) {
            HudLayout.Scaler scaler = element.scaler;

            boolean moved = element.mover == null || !element.mover.atDefault();
            boolean resized = scaler != null && !Float.isNaN(scaler.defaultValue())
                    && Math.abs(scaler.get() - scaler.defaultValue()) >= 0.005F;
            if (moved || resized) {
                handled.add(element.id);
                save();
                return;
            }
            boolean shrink = scaler != null && !Float.isNaN(scaler.defaultValue())
                    && Math.abs(scaler.get() - scaler.defaultValue()) < 0.005F;
            if (shrink) {
                scaler.set(scaler.get() * SCALE);
            }
            scaled.put(element.id, shrink);
            if (shrink) {
                return;
            }
        }
        if (element.mover != null && element.mover.atDefault()) {
            float[] move = nudge(element.x, element.y, element.width, element.height, screenWidth, screenHeight);
            if (move != null) {
                element.mover.moveBy(Math.round(move[0]), Math.round(move[1]));
            }
        }
        handled.add(element.id);
        scaled.remove(element.id);
        save();
    }

    static float[] nudge(float x, float y, float w, float h, float sw, float sh) {
        if (!covers(x, y, w, h, sw, sh)) {
            return null;
        }
        java.util.List<Float> xs = new java.util.ArrayList<Float>();
        java.util.List<Float> ys = new java.util.ArrayList<Float>();
        xs.add(x);
        ys.add(y);
        for (float[] zone : ZONES) {
            float left = zone[0] * sw;
            float top = zone[1] * sh;
            float right = zone[2] * sw;
            float bottom = zone[3] * sh;
            xs.add(right + GAP);
            xs.add(left - w - GAP);
            ys.add(bottom + GAP);
            ys.add(top - h - GAP);
        }
        float[] best = null;
        float bestCost = Float.MAX_VALUE;
        for (float nx : xs) {
            for (float ny : ys) {
                if (nx < 0.0F || ny < 0.0F || nx + w > sw || ny + h > sh) {
                    continue;
                }
                float dx = nx - x;
                float dy = ny - y;
                if (Math.abs(dx) > sw / 3.0F || Math.abs(dy) > sh / 3.0F || covers(nx, ny, w, h, sw, sh)) {
                    continue;
                }
                float cost = Math.abs(dx) + Math.abs(dy);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new float[]{dx, dy};
                }
            }
        }
        return best;
    }

    static boolean covers(float x, float y, float w, float h, float sw, float sh) {
        for (float[] zone : ZONES) {
            if (x < zone[2] * sw && x + w > zone[0] * sw && y < zone[3] * sh && y + h > zone[1] * sh) {
                return true;
            }
        }
        return false;
    }

    private static void switchOffKeyStrokes() {
        try {
            atlas.module.Module keys = atlas.Atlas.moduleManager == null ? null
                    : atlas.Atlas.moduleManager.getModule("KeyStrokes");
            if (keys != null && keys.isEnabled()) {
                keys.setEnabled(false);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static Set<String> done() {
        if (done == null) {
            done = new HashSet<String>();
            try {
                if (FILE.isFile()) {
                    for (String line : Files.readAllLines(FILE.toPath(), StandardCharsets.UTF_8)) {
                        if (!line.trim().isEmpty()) {
                            done.add(line.trim());
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return done;
    }

    private static void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            Files.write(FILE.toPath(), done.isEmpty() ? new byte[0]
                    : (String.join("\n", done) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
