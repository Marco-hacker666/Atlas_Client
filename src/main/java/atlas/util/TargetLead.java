package atlas.util;

/**
 * Where a player will be, from the positions the server sent for it, when the movement simulation has no answer of
 * its own (2026-11-15, owner: "can the prediction be better?"). No game types; tested.
 *
 * What the straight line it replaces got wrong:
 * - It read the position the client draws, which eases toward the server's over three ticks, so it ran one to three
 *   ticks behind and its speed was smoothed. This takes the server's positions (Entity.serverPosX / 32).
 * - It carried the speed of the last tick all the way, so a player strafing left and right (A-D) was led to the far
 *   end of a swing it would never reach. Here the near ticks follow the latest speed, and further out the lead hands
 *   over (e^(-t / {@link #HANDOVER})) to a straight line fitted to the whole window by least squares: a runner's
 *   fit is its run, a strafer's is the middle of its swing. (The window's two ends were tried first: the strafe's
 *   phase at either end biased them.)
 * - (2026-11-16, owner: "some move a little and stop, and the aim leads far ahead") A player standing still now is
 *   led nowhere: the fitted line still carried the steps it had just taken. And a player that moves in short bursts
 *   is led only to where its burst usually ends: the length of each burst it made is kept, and the lead runs for
 *   the median of them less what this one has run, then for the slide the ground's friction leaves (0.546 a tick).
 * - It carried a rise straight up. A player in the air here falls as the game makes it, (vy - 0.08) * 0.98 a tick,
 *   down to the ground it stood on (or that is under it).
 */
public final class TargetLead {
    /** Positions kept, one a tick. */
    public static final int WINDOW = 20;
    /** Ticks over which the latest speed hands over to the fitted line. */
    public static final double HANDOVER = 4.0;

    private final double[] x = new double[WINDOW];
    private final double[] y = new double[WINDOW];
    private final double[] z = new double[WINDOW];
    private final boolean[] ground = new boolean[WINDOW];
    private int count;
    private int head = -1;
    /* Stop-and-go: how long the burst now running has lasted (ticks), and the last bursts' lengths. */
    private int run;
    private final java.util.ArrayDeque<Integer> bursts = new java.util.ArrayDeque<Integer>();
    /** Bursts kept. */
    public static final int BURSTS = 8;
    /** Moving: more than this across the ground over two ticks (positions come every other tick). */
    public static final double MOVING = 0.06;
    /** Ground friction a tick with no key held (0.91 x 0.6): what is left of the speed. */
    private static final double SLIDE = 0.546;

    /** The server's position for this tick (call once a tick). */
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
            /* Two still ticks in a row end a burst (one alone is the gap between two position updates). */
            this.bursts.addLast(this.run);
            if (this.bursts.size() > BURSTS) {
                this.bursts.removeFirst();
            }
            this.run = 0;
        }
    }

    /** Whether it moved more than MOVING over the two ticks ending {@code back} ticks ago. */
    private boolean movedOverTwo(int back) {
        if (this.count < back + 3) {
            return this.count >= back + 2 && dist(this.at(back), this.at(back + 1)) > MOVING;
        }
        return dist(this.at(back), this.at(back + 2)) > MOVING;
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    /** Standing still now: no move over the last two ticks, nor the two before. */
    public boolean stillNow() {
        return this.count >= 3 && !this.movedOverTwo(0) && !this.movedOverTwo(1);
    }

    /** The median length of its last bursts, ticks; -1 until two are known. */
    public int typicalBurst() {
        if (this.bursts.size() < 2) {
            return -1;
        }
        java.util.List<Integer> sorted = new java.util.ArrayList<Integer>(this.bursts);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    /** Ticks the burst now running has lasted. */
    public int currentRun() {
        return this.run;
    }

    /**
     * The ticks of movement to lead by over {@code t} ticks: all of them for a player that keeps going, none for one
     * standing, and for one that moves in bursts the rest of its usual burst plus the slide after it.
     */
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

    /** {x, y, z} {@code back} ticks ago (0 = the latest). */
    private double[] at(int back) {
        int i = ((this.head - back) % WINDOW + WINDOW) % WINDOW;
        return new double[]{this.x[i], this.y[i], this.z[i]};
    }

    /**
     * The speed across the ground over the last moves, {vx, vz} a tick. Players' positions come every other tick, so
     * it is taken over the latest change and the tick before it.
     */
    double[] quickSpeed() {
        if (this.count < 2) {
            return new double[]{0.0, 0.0};
        }
        int span = Math.min(this.count - 1, 2);
        double[] now = this.at(0);
        double[] then = this.at(span);
        return new double[]{(now[0] - then[0]) / span, (now[2] - then[2]) / span};
    }

    /**
     * The least-squares line through the window across the ground: {ax, bx, az, bz}, where the fitted position
     * {@code t} ticks from the latest is a + b t.
     */
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

    /** The vertical speed now, blocks a tick (over the last two ticks). */
    double verticalSpeed() {
        if (this.count < 2) {
            return 0.0;
        }
        int span = Math.min(this.count - 1, 2);
        return (this.at(0)[1] - this.at(span)[1]) / span;
    }

    /**
     * Where it will be {@code ticks} after its latest position (fractional), {x, y, z} of the feet. {@code floor}:
     * the height it lands on when in the air (NaN for "where it stands").
     */
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
            /* Standing: where it stands, not where the steps it took before would carry it. */
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
