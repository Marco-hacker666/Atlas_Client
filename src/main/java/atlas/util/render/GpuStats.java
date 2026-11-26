package atlas.util.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GpuStats {
    public static final String FRAME = "frame";

    private final Map<String, long[]> totals = new LinkedHashMap<String, long[]>();
    private int frames;
    private int dropped;

    public void frame(Map<String, Long> nanos) {
        this.frames++;
        for (Map.Entry<String, Long> entry : nanos.entrySet()) {
            long[] total = this.totals.get(entry.getKey());
            if (total == null) {
                total = new long[2];
                this.totals.put(entry.getKey(), total);
            }
            total[0] += Math.max(0L, entry.getValue());
            total[1]++;
        }
    }

    public void dropped() {
        this.dropped++;
    }

    public int frames() {
        return this.frames;
    }

    public int droppedFrames() {
        return this.dropped;
    }

    public double msPerFrame(String name) {
        long[] total = this.totals.get(name);
        return total == null || this.frames == 0 ? 0.0 : total[0] / 1.0E6 / this.frames;
    }

    public List<String> sections() {
        List<String> names = new ArrayList<String>(this.totals.keySet());
        names.remove(FRAME);
        Collections.sort(names, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Double.compare(msPerFrame(b), msPerFrame(a));
            }
        });
        return names;
    }

    public double msPerFrameEndingWith(String... suffixes) {
        double sum = 0.0;
        for (String name : this.totals.keySet()) {
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    sum += msPerFrame(name);
                    break;
                }
            }
        }
        return sum;
    }

    public GpuStats copy() {
        GpuStats out = new GpuStats();
        out.frames = this.frames;
        out.dropped = this.dropped;
        for (Map.Entry<String, long[]> entry : this.totals.entrySet()) {
            out.totals.put(entry.getKey(), entry.getValue().clone());
        }
        return out;
    }

    public void reset() {
        this.totals.clear();
        this.frames = 0;
        this.dropped = 0;
    }

    public String line(int top) {
        if (this.frames == 0) {
            return "gpu: no frames measured" + (this.dropped > 0 ? " (" + this.dropped + " not ready in time)" : "");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "gpu ms per frame (%d frames measured%s): frame %.2f", this.frames,
                this.dropped > 0 ? ", " + this.dropped + " skipped" : "", msPerFrame(FRAME)));
        List<String> names = sections();
        for (int i = 0; i < Math.min(top, names.size()); i++) {
            sb.append(String.format(Locale.ROOT, " | %s %.2f", names.get(i), msPerFrame(names.get(i))));
        }
        return sb.toString();
    }
}
