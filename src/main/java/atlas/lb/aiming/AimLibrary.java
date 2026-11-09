package atlas.lb.aiming;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Your own aim, read back from the Recorder's aim-*.csv files (2026-11-10,
 * for AngleSmooth "Recorded"). Each usable tick becomes a sample: how far
 * your look was off the target (err_yaw, err_pitch) and how far your hand
 * turned by the next tick. Consecutive ticks on the same target keep their
 * order, so a turn can be replayed as the run it was.
 *
 * A tick is used only when the next row is the very next tick on the same
 * target and neither was marked as assisted (bit 4: the look moved by
 * something other than the mouse; bit 8: a module that changes aim, clicks or
 * movement was on). Turns over 60 degrees in a tick are dropped as camera
 * snaps, not aim.
 */
public final class AimLibrary {

    /** One recorded tick. */
    public static final class Sample {
        public final float errYaw;
        public final float errPitch;
        public final float turnYaw;
        public final float turnPitch;
        /** Index of the next tick of the same run, or -1. */
        public final int next;

        Sample(float errYaw, float errPitch, float turnYaw, float turnPitch, int next) {
            this.errYaw = errYaw;
            this.errPitch = errPitch;
            this.turnYaw = turnYaw;
            this.turnPitch = turnPitch;
            this.next = next;
        }
    }

    static final int ASSIST_NOT_OWN = 4 | 8;
    static final float SNAP = 60.0F;
    /** Bucket size of the lookup grid, degrees. */
    static final float CELL = 2.0F;
    public static final int MAX_SAMPLES = 400000;

    private final List<Sample> samples = new ArrayList<Sample>();
    /** Samples by grid cell of (|err_yaw|, err_pitch); yaw is mirrored so left and right share data. */
    private final Map<Long, List<Integer>> grid = new HashMap<Long, List<Integer>>();

    public int size() {
        return this.samples.size();
    }

    public Sample get(int index) {
        return this.samples.get(index);
    }

    /** Every aim-*.csv in {@code dir}, newest last, up to MAX_SAMPLES samples. */
    public static AimLibrary load(File dir) {
        AimLibrary library = new AimLibrary();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return library;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.startsWith("aim-") || !name.endsWith(".csv")) {
                continue;
            }
            List<String> lines = new ArrayList<String>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception e) {
                continue;
            }
            library.add(lines);
            if (library.size() >= MAX_SAMPLES) {
                break;
            }
        }
        return library;
    }

    /** Adds one recording's lines (header and comment lines are skipped). */
    public void add(List<String> lines) {
        Map<String, Integer> column = null;
        float[] last = null;
        String lastTarget = null;
        long lastTick = Long.MIN_VALUE;
        int lastAssist = 0;
        int previousIndex = -1;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cells = line.split(",", -1);
            if (cells.length > 0 && "tick".equals(cells[0])) {
                column = new HashMap<String, Integer>();
                for (int i = 0; i < cells.length; i++) {
                    column.put(cells[i], i);
                }
                last = null;
                continue;
            }
            if (column == null || !column.containsKey("err_yaw")) {
                continue;
            }
            long tick;
            String target;
            float yaw;
            float pitch;
            float errYaw;
            float errPitch;
            int assist;
            try {
                tick = Long.parseLong(cells[column.get("tick")]);
                target = cells[column.get("target")];
                yaw = Float.parseFloat(cells[column.get("yaw")]);
                pitch = Float.parseFloat(cells[column.get("pitch")]);
                errYaw = Float.parseFloat(cells[column.get("err_yaw")]);
                errPitch = Float.parseFloat(cells[column.get("err_pitch")]);
                Integer at = column.get("assist");
                assist = at == null || at >= cells.length ? 0 : Integer.parseInt(cells[at]);
            } catch (RuntimeException e) {
                last = null;
                continue;
            }
            boolean follows = last != null && tick == lastTick + 1 && target.equals(lastTarget);
            if (follows && (lastAssist & ASSIST_NOT_OWN) == 0 && (assist & ASSIST_NOT_OWN) == 0) {
                float turnYaw = Rotation.angleDifference(yaw, last[0]);
                float turnPitch = pitch - last[1];
                if (Math.abs(turnYaw) <= SNAP && Math.abs(turnPitch) <= SNAP && this.samples.size() < MAX_SAMPLES) {
                    int index = this.samples.size();
                    this.samples.add(new Sample(last[2], last[3], turnYaw, turnPitch, -1));
                    index(index, last[2], last[3]);
                    if (previousIndex >= 0 && previousIndex == index - 1) {
                        Sample before = this.samples.get(previousIndex);
                        this.samples.set(previousIndex, new Sample(before.errYaw, before.errPitch, before.turnYaw,
                                before.turnPitch, index));
                    }
                    previousIndex = index;
                } else {
                    previousIndex = -1;
                }
            } else {
                previousIndex = -1;
            }
            last = new float[]{yaw, pitch, errYaw, errPitch};
            lastTarget = target;
            lastTick = tick;
            lastAssist = assist;
        }
    }

    private static long cell(float errYaw, float errPitch) {
        long x = (long) Math.floor(Math.abs(errYaw) / CELL);
        long y = (long) Math.floor(errPitch / CELL);
        return (x << 32) ^ (y & 0xFFFFFFFFL);
    }

    private void index(int sample, float errYaw, float errPitch) {
        Long key = cell(errYaw, errPitch);
        List<Integer> list = this.grid.get(key);
        if (list == null) {
            list = new ArrayList<Integer>();
            this.grid.put(key, list);
        }
        list.add(sample);
    }

    /**
     * A recorded tick whose error looked like this one (yaw mirrored), picked
     * at random among the nearest few so the same error does not always
     * replay the same tick. -1 when the library has nothing within 12
     * degrees.
     */
    public int nearest(float errYaw, float errPitch, java.util.Random random) {
        if (this.samples.isEmpty()) {
            return -1;
        }
        long cx = (long) Math.floor(Math.abs(errYaw) / CELL);
        long cy = (long) Math.floor(errPitch / CELL);
        int[] best = {-1, -1, -1};
        float[] bestDistance = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        for (int ring = 0; ring <= 6; ring++) {
            for (long x = cx - ring; x <= cx + ring; x++) {
                for (long y = cy - ring; y <= cy + ring; y++) {
                    if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) != ring || x < 0) {
                        continue;
                    }
                    List<Integer> list = this.grid.get((x << 32) ^ (y & 0xFFFFFFFFL));
                    if (list == null) {
                        continue;
                    }
                    for (int index : list) {
                        Sample s = this.samples.get(index);
                        float dy = Math.abs(s.errYaw) - Math.abs(errYaw);
                        float dp = s.errPitch - errPitch;
                        float d = dy * dy + dp * dp;
                        for (int k = 0; k < best.length; k++) {
                            if (d < bestDistance[k]) {
                                for (int m = best.length - 1; m > k; m--) {
                                    best[m] = best[m - 1];
                                    bestDistance[m] = bestDistance[m - 1];
                                }
                                best[k] = index;
                                bestDistance[k] = d;
                                break;
                            }
                        }
                    }
                }
            }
            /* Anything found within this ring is as near as the next ring could give, near enough. */
            if (best[0] >= 0 && ring >= 1) {
                break;
            }
        }
        int found = 0;
        for (int index : best) {
            if (index >= 0) {
                found++;
            }
        }
        if (found == 0) {
            return -1;
        }
        return best[random == null ? 0 : random.nextInt(found)];
    }
}
