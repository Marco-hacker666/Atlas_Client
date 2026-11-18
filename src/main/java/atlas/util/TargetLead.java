package atlas.util;

public final class TargetLead {
    public static final int WINDOW = 20;

    public static final double HANDOVER = 4.0;

    private final double[] x = new double[WINDOW];
    private final double[] y = new double[WINDOW];
    private final double[] z = new double[WINDOW];
    private final boolean[] ground = new boolean[WINDOW];
    private int count;
    private int head = -1;

    private int run;
    private final java.util.ArrayDeque<Integer> bursts = new java.util.ArrayDeque<Integer>();

    public static final int BURSTS = 8;

    public static final double MOVING = 0.06;

    private static final double SLIDE = 0.546;

    public void add(double px, double py, double pz, boolean onGround) {
        this.head = (this.head + 1) % WINDOW;
        this.x[this.head] = px;
        this.y[this.head] = py;
        this.z[this.head] = pz;
        this.ground[this.head] = onGround;
        if (this.count < WINDOW) {
            this.count++;
        }
        boolean moving = this.movedOverTwo(0);
        if (moving) {
            this.run++;
        } else if (this.run > 0 && !this.movedOverTwo(1)) {
            this.bursts.addLast(this.run);
            if (this.bursts.size() > BURSTS) {
                this.bursts.removeFirst();
            }
            this.run = 0;
        }
    }

    private boolean movedOverTwo(int back) {
        if (this.count < back + 3) {
            return this.count >= back + 2 && dist(this.at(back), this.at(back + 1)) > MOVING;
        }
        return dist(this.at(back), this.at(back + 2)) > MOVING;
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    public boolean stillNow() {
        return this.count >= 3 && !this.movedOverTwo(0) && !this.movedOverTwo(1);
    }

    public int typicalBurst() {
        if (this.bursts.size() < 2) {
            return -1;
        }
        java.util.List<Integer> sorted = new java.util.ArrayList<Integer>(this.bursts);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    public int currentRun() {
        return this.run;
    }

    double movingTicks(double t) {
        if (this.stillNow()) {
            return 0.0;
        }
        int typical = this.typicalBurst();
        if (typical < 0) {
            return t;
        }
        double left = Math.max(1.0, typical - this.run);
        if (t <= left) {
            return t;
        }
        double after = t - left;
        return left + SLIDE * (1.0 - Math.pow(SLIDE, after)) / (1.0 - SLIDE);
    }

    public int size() {
        return this.count;
    }

    private double[] at(int back) {
        int i = ((this.head - back) % WINDOW + WINDOW) % WINDOW;
        return new double[]{this.x[i], this.y[i], this.z[i]};
    }

    double[] quickSpeed() {
        if (this.count < 2) {
            return new double[]{0.0, 0.0};
        }
        int span = Math.min(this.count - 1, 2);
        double[] now = this.at(0);
        double[] then = this.at(span);
        return new double[]{(now[0] - then[0]) / span, (now[2] - then[2]) / span};
    }

    double[] fit() {
        int n = this.count;
        if (n < 2) {
            double[] now = this.at(0);
            return new double[]{now[0], 0.0, now[2], 0.0};
        }
        double meanK = -(n - 1) / 2.0;
        double sx = 0.0;
        double sz = 0.0;
        for (int back = 0; back < n; back++) {
            double[] p = this.at(back);
            sx += p[0];
            sz += p[2];
        }
        double mx = sx / n;
        double mz = sz / n;
        double kk = 0.0;
        double kx = 0.0;
        double kz = 0.0;
        for (int back = 0; back < n; back++) {
            double k = -back - meanK;
            double[] p = this.at(back);
            kk += k * k;
            kx += k * (p[0] - mx);
            kz += k * (p[2] - mz);
        }
        double bx = kk > 0.0 ? kx / kk : 0.0;
        double bz = kk > 0.0 ? kz / kk : 0.0;
        return new double[]{mx - bx * meanK, bx, mz - bz * meanK, bz};
    }

    double verticalSpeed() {
        if (this.count < 2) {
            return 0.0;
        }
        int span = Math.min(this.count - 1, 2);
        return (this.at(0)[1] - this.at(span)[1]) / span;
    }

    public double[] predict(double ticks, double floor) {
        if (this.count == 0) {
            return null;
        }
        double[] now = this.at(0);
        double t = Math.max(0.0, ticks);
        double[] quick = this.quickSpeed();
        double[] line = this.fit();
        double w = Math.exp(-t / HANDOVER);
        double px;
        double pz;
        if (this.stillNow()) {
            px = now[0];
            pz = now[2];
        } else {
            double m = this.movingTicks(t);
            px = w * (now[0] + quick[0] * m) + (1.0 - w) * (line[0] + line[1] * m);
            pz = w * (now[2] + quick[1] * m) + (1.0 - w) * (line[2] + line[3] * m);
        }
        double py = now[1];
        boolean onGround = this.ground[this.head];
        double vy = this.verticalSpeed();
        if (!onGround || vy > 1.0E-3) {
            double land = Double.isNaN(floor) ? Double.NEGATIVE_INFINITY : floor;
            int whole = (int) Math.floor(t);
            for (int i = 0; i < whole; i++) {
                vy = (vy - 0.08) * 0.98;
                py += vy;
                if (py <= land) {
                    py = land;
                    break;
                }
            }
            if (py > land && t > whole) {
                double step = (vy - 0.08) * 0.98;
                py = Math.max(land, py + step * (t - whole));
            }
        }
        return new double[]{px, py, pz};
    }
}
