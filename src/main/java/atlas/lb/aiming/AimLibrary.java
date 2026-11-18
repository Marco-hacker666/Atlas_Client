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

public final class AimLibrary {
    public static final class Sample {
        public final float errYaw;
        public final float errPitch;
        public final float turnYaw;
        public final float turnPitch;

        public final int next;

        public final boolean aura;

        Sample(float errYaw, float errPitch, float turnYaw, float turnPitch, int next) {
            this(errYaw, errPitch, turnYaw, turnPitch, next, false);
        }

        Sample(float errYaw, float errPitch, float turnYaw, float turnPitch, int next, boolean aura) {
            this.errYaw = errYaw;
            this.errPitch = errPitch;
            this.turnYaw = turnYaw;
            this.turnPitch = turnPitch;
            this.next = next;
            this.aura = aura;
        }

        Sample withNext(int next) {
            return new Sample(this.errYaw, this.errPitch, this.turnYaw, this.turnPitch, next, this.aura);
        }
    }

    static final int ASSIST_NOT_OWN = 4 | 8;

    public static final int ASSIST_AURA = 16;

    public volatile boolean includeAura = true;
    private Reader live;
    static final float SNAP = 60.0F;

    static final float CELL = 2.0F;

    public static final int MAX_SAMPLES = 200000;

    public static final String AIM_HEADER = "tick,target,yaw,pitch,err_yaw,err_pitch,dist,tx,ty,tz,px,py,pz,"
            + "t_ground,p_ground,t_hurt,p_hurt,swings,attacks,attack_key,ping,assist";

    private final List<Sample> samples = new ArrayList<Sample>();

    private final Map<Long, List<Integer>> grid = new HashMap<Long, List<Integer>>();

    public int size() {
        return this.samples.size();
    }

    public Sample get(int index) {
        return this.samples.get(index);
    }

    public static AimLibrary load(File dir) {
        AimLibrary library = new AimLibrary();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return library;
        }

        Arrays.sort(files, java.util.Collections.reverseOrder());
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

    public void add(List<String> lines) {
        Reader reader = new Reader();
        for (String line : lines) {
            reader.line(line);
        }
    }

    public void addLive(String line) {
        if (this.live == null) {
            this.live = new Reader();
            this.live.line(AIM_HEADER);
        }
        this.live.line(line);
    }

    private final class Reader {
        private Map<String, Integer> column;
        private float[] last;
        private String lastTarget;
        private long lastTick = Long.MIN_VALUE;
        private int lastAssist;
        private int previousIndex = -1;

        void line(String line) {
            if (line == null || line.isEmpty() || line.startsWith("#")) {
                return;
            }
            String[] cells = line.split(",", -1);
            if (cells.length > 0 && "tick".equals(cells[0])) {
                this.column = new HashMap<String, Integer>();
                for (int i = 0; i < cells.length; i++) {
                    this.column.put(cells[i], i);
                }
                this.last = null;
                this.previousIndex = -1;
                return;
            }
            if (this.column == null || !this.column.containsKey("err_yaw")) {
                return;
            }
            long tick;
            String target;
            float yaw;
            float pitch;
            float errYaw;
            float errPitch;
            int assist;
            try {
                tick = Long.parseLong(cells[this.column.get("tick")]);
                target = cells[this.column.get("target")];
                yaw = Float.parseFloat(cells[this.column.get("yaw")]);
                pitch = Float.parseFloat(cells[this.column.get("pitch")]);
                errYaw = Float.parseFloat(cells[this.column.get("err_yaw")]);
                errPitch = Float.parseFloat(cells[this.column.get("err_pitch")]);
                Integer at = this.column.get("assist");
                assist = at == null || at >= cells.length ? 0 : Integer.parseInt(cells[at]);
            } catch (RuntimeException e) {
                this.last = null;
                return;
            }
            boolean follows = this.last != null && tick == this.lastTick + 1 && target.equals(this.lastTarget);
            if (follows && (this.lastAssist & ASSIST_NOT_OWN) == 0 && (assist & ASSIST_NOT_OWN) == 0) {
                float turnYaw = Rotation.angleDifference(yaw, this.last[0]);
                float turnPitch = pitch - this.last[1];
                if (Math.abs(turnYaw) <= SNAP && Math.abs(turnPitch) <= SNAP && samples.size() < MAX_SAMPLES) {
                    int index = samples.size();
                    boolean aura = ((this.lastAssist | assist) & ASSIST_AURA) != 0;
                    samples.add(new Sample(this.last[2], this.last[3], turnYaw, turnPitch, -1, aura));
                    index(index, this.last[2], this.last[3]);
                    if (this.previousIndex >= 0 && this.previousIndex == index - 1) {
                        samples.set(this.previousIndex, samples.get(this.previousIndex).withNext(index));
                    }
                    this.previousIndex = index;
                } else {
                    this.previousIndex = -1;
                }
            } else {
                this.previousIndex = -1;
            }
            this.last = new float[]{yaw, pitch, errYaw, errPitch};
            this.lastTarget = target;
            this.lastTick = tick;
            this.lastAssist = assist;
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
                        if (s.aura && !this.includeAura) {
                            continue;
                        }
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
