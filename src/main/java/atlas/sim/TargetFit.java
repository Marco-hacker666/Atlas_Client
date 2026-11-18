package atlas.sim;

public final class TargetFit {
    public static final double UNIT = 1.0 / 32.0;

    public static final double SLACK = UNIT;

    public static final float YAW_MID = 0.5F * 360.0F / 256.0F;
    public static final float YAW_HALF = 3.5F * 360.0F / 256.0F + 0.3F;

    private static final double WEAK = 0.1;

    private static final double RADIAL = 0.1;
    private static final double PER_MOVE = 0.004;
    private static final double CHANGED_KEYS = 0.006;

    private static final double CHANGED_WEAK_KEYS = 0.05;

    private static final double CHANGED_JUMP = 0.002;
    private static final double GROUND_DIFFERS = 0.01;
    private static final double NOT_SLOWED = 0.004;
    private static final double WITH_IMPULSE = 0.01;
    private static final double WITH_LEAD = 0.008;
    private static final double WITH_TAP = 0.012;

    private static final int[][] DIRECTIONS = {
            {1, 0}, {1, 1}, {1, -1}, {0, 1}, {0, -1}, {-1, 0}, {-1, 1}, {-1, -1}};

    private TargetFit() {
    }

    public static final class Seen {
        public int x;
        public int y;
        public int z;
        public boolean onGround;

        public float yaw;
        public boolean sprinting;
        public boolean sneaking;

        public boolean usingItem;

        public Seen copy() {
            Seen s = new Seen();
            s.x = this.x;
            s.y = this.y;
            s.z = this.z;
            s.onGround = this.onGround;
            s.yaw = this.yaw;
            s.sprinting = this.sprinting;
            s.sneaking = this.sneaking;
            s.usingItem = this.usingItem;
            return s;
        }

        public double midX() {
            return ((double) this.x + 0.5) * UNIT;
        }

        public double midY() {
            return ((double) this.y + 0.5) * UNIT;
        }

        public double midZ() {
            return ((double) this.z + 0.5) * UNIT;
        }

        public double outside(double px, double pz) {
            double dx = beyond(px, this.x);
            double dz = beyond(pz, this.z);
            return Math.sqrt(dx * dx + dz * dz);
        }

        public double under(double py) {
            return beyond(py, this.y);
        }

        static double beyond(double p, int cell) {
            double min = (double) cell * UNIT;
            double max = min + UNIT;
            return p < min ? min - p : p > max ? max - p : 0.0;
        }
    }

    public static final class Keys {
        public int forward;

        public int strafe;
        public boolean jump;
        public float yaw;

        public boolean itemSlow;

        public Keys copy() {
            Keys k = new Keys();
            k.forward = this.forward;
            k.strafe = this.strafe;
            k.jump = this.jump;
            k.yaw = this.yaw;
            k.itemSlow = this.itemSlow;
            return k;
        }

        public boolean sameDirection(Keys other) {
            return other != null && this.forward == other.forward && this.strafe == other.strafe
                    && this.itemSlow == other.itemSlow;
        }

        public PlayerSim.Input input(boolean sneaking) {
            PlayerSim.Input in = new PlayerSim.Input();
            float forward = (float) this.forward;
            float strafe = (float) this.strafe;
            if (sneaking) {
                forward = (float) ((double) forward * 0.3);
                strafe = (float) ((double) strafe * 0.3);
            }
            if (this.itemSlow) {
                forward *= 0.2F;
                strafe *= 0.2F;
            }
            in.forward = forward;
            in.strafe = strafe;
            in.jump = this.jump;
            in.sneak = sneaking;
            in.yaw = this.yaw;
            return in;
        }
    }

    public static final class Result {
        public double offset = Double.MAX_VALUE;

        public double under;
        boolean fits;

        public int moves;
        public Keys keys;

        public PlayerSim end;

        public int impulseAt = -1;

        public int led;

        public boolean tapped;
        double score = Double.MAX_VALUE;

        public boolean fits() {
            return this.fits;
        }
    }

    private static final class Way {
        static final Way PLAIN = new Way();

        double[] impulse;
        int impulseAt = -1;

        PlayerSim.Input lead;
        boolean leadSprint;
        int led;
    }

    public static Result fit(PlayerSim start, PlayerSim.World world, Seen seen, Seen before, float yawMid,
                             float yawHalf, int[] moves, double hint, Keys last, double[] impulse, double slackY) {
        int most = 0;
        for (int n : moves) {
            most = Math.max(most, n);
        }

        if (last != null && (!seen.sprinting || last.forward == 1)) {
            for (int n : moves) {
                PlayerSim end = end(start, world, last.input(seen.sneaking), seen.sprinting, n, Way.PLAIN);
                if (seen.outside(end.x(), end.z()) == 0.0 && seen.under(end.y()) == 0.0
                        && end.onGround == seen.onGround) {
                    Result same = new Result();
                    same.offset = 0.0;
                    same.fits = true;
                    same.score = 0.0;
                    same.moves = n;
                    same.keys = last.copy();
                    same.end = end;
                    return same;
                }
            }
        }
        Search search = new Search(start, world, seen, yawMid, yawHalf, moves, most, hint, last, slackY);
        search.all(Way.PLAIN);
        if (impulse != null) {
            for (int at = 0; at < most; at++) {
                Way hit = new Way();
                hit.impulse = impulse;
                hit.impulseAt = at;
                search.all(hit);
            }
        }
        if (!search.best.fits() && last != null && before != null && most >= 2) {
            for (int led = 1; led < most; led++) {
                Way changed = new Way();
                changed.lead = last.input(before.sneaking);
                changed.leadSprint = before.sprinting;
                changed.led = led;
                search.all(changed);
            }
            if (!search.best.fits()) {
                search.taps();
            }
        }
        return search.best;
    }

    private static final class Search {
        final PlayerSim start;
        final PlayerSim.World world;
        final Seen seen;
        final float yawMid;
        final float yawHalf;
        final int[] moves;
        final int most;
        final double hint;
        final Keys last;
        final double slackY;
        final Result best = new Result();

        Search(PlayerSim start, PlayerSim.World world, Seen seen, float yawMid, float yawHalf, int[] moves, int most,
               double hint, Keys last, double slackY) {
            this.start = start;
            this.world = world;
            this.seen = seen;
            this.yawMid = yawMid;
            this.yawHalf = yawHalf;
            this.moves = moves;
            this.most = most;
            this.hint = hint;
            this.last = last;
            this.slackY = slackY;
        }

        void all(Way way) {
            PlayerSim.Input idle = new PlayerSim.Input();
            idle.yaw = this.yawMid;
            idle.sneak = this.seen.sneaking;
            double[][] still = positions(this.start, this.world, idle, false, this.most, way);
            boolean ground = this.start.onGround;
            for (double[] at : still) {
                ground |= at[3] != 0.0;
            }
            PlayerSim.Input hop = idle.copy();
            hop.jump = true;
            double[][] stillJumping = positions(this.start, this.world, hop, false, this.most, way);
            if (!ground) {
                boolean shows = false;
                for (int i = 0; i < this.most; i++) {
                    shows |= still[i][1] != stillJumping[i][1];
                }
                if (!shows) {
                    branch(way, this.last != null && this.last.jump, still);
                    return;
                }
            }

            boolean plainFits = reachesHeight(still, way);
            boolean jumpFits = reachesHeight(stillJumping, way);
            if (plainFits || !jumpFits) {
                branch(way, false, still);
            }
            if (jumpFits || !plainFits) {
                branch(way, true, stillJumping);
            }
        }

        private boolean reachesHeight(double[][] still, Way way) {
            for (int n : this.moves) {
                if (n > way.led && Math.abs(this.seen.under(still[n - 1][1])) <= this.slackY) {
                    return true;
                }
            }
            return false;
        }

        private void branch(Way way, boolean jump, double[][] still) {
            if (!this.seen.sprinting) {
                for (int n : this.moves) {
                    if (n <= way.led) {
                        continue;
                    }
                    Keys none = new Keys();
                    none.jump = jump;
                    none.yaw = this.yawMid;
                    offer(none, n, way, false, null, this.seen.outside(still[n - 1][0], still[n - 1][2]),
                            this.seen.under(still[n - 1][1]), false);
                }
            }
            for (int slowed = this.seen.usingItem ? 1 : 0; slowed >= 0; slowed--) {
                for (int[] direction : DIRECTIONS) {
                    if (this.seen.sprinting && direction[0] != 1) {
                        continue;
                    }
                    Keys keys = new Keys();
                    keys.forward = direction[0];
                    keys.strafe = direction[1];
                    keys.jump = jump;
                    keys.itemSlow = slowed == 1;
                    keys.yaw = this.yawMid;
                    double[][] pushed = positions(this.start, this.world, keys.input(this.seen.sneaking),
                            this.seen.sprinting, this.most, way);
                    for (int n : this.moves) {
                        if (n > way.led) {
                            turned(way, keys, n, still[n - 1], pushed[n - 1]);
                        }
                    }
                }
            }
        }

        private void turned(Way way, Keys keys, int n, double[] still, double[] pushed) {
            double addX = pushed[0] - still[0];
            double addZ = pushed[2] - still[2];
            double gapX = this.seen.midX() - still[0];
            double gapZ = this.seen.midZ() - still[2];
            double add = Math.sqrt(addX * addX + addZ * addZ);
            double gap = Math.sqrt(gapX * gapX + gapZ * gapZ);
            if (add < 1.0E-4 || Math.abs(gap - add) > RADIAL) {
                return;
            }
            boolean weak = add < WEAK;
            Keys turned = keys.copy();
            if (weak && this.start.inWater) {
                turned.yaw = this.yawMid;
            } else if (weak) {
                turned.yaw = inBand(this.last != null ? this.last.yaw : this.seen.yaw, this.yawMid, this.yawHalf);
            } else {
                turned.yaw = inBand(this.yawMid + turn(addX, addZ, gapX, gapZ), this.yawMid, this.yawHalf);
            }
            PlayerSim end = end(this.start, this.world, turned.input(this.seen.sneaking), this.seen.sprinting, n,
                    way);
            double offset = this.seen.outside(end.x(), end.z());
            if (offset > 0.0 && !weak) {
                Keys again = turned.copy();
                again.yaw = inBand(turned.yaw + turn(end.x() - still[0], end.z() - still[2], gapX, gapZ),
                        this.yawMid, this.yawHalf);
                if (again.yaw != turned.yaw) {
                    PlayerSim second = end(this.start, this.world, again.input(this.seen.sneaking),
                            this.seen.sprinting, n, way);
                    double secondOffset = this.seen.outside(second.x(), second.z());
                    if (secondOffset < offset) {
                        turned = again;
                        end = second;
                        offset = secondOffset;
                    }
                }
            }
            offer(turned, n, way, false, end, offset, this.seen.under(end.y()), weak);
        }

        void taps() {
            for (int index = -1; index < DIRECTIONS.length; index++) {
                int forward = index < 0 ? 0 : DIRECTIONS[index][0];
                int strafe = index < 0 ? 0 : DIRECTIONS[index][1];
                if (forward == this.last.forward && strafe == this.last.strafe
                        || this.seen.sprinting && this.last.forward != 1) {
                    continue;
                }
                Keys tap = this.last.copy();
                tap.forward = forward;
                tap.strafe = strafe;
                for (int led = 1; led < this.most; led++) {
                    Way way = new Way();
                    way.lead = tap.input(this.seen.sneaking);

                    way.leadSprint = this.seen.sprinting && forward == 1;
                    way.led = led;
                    double[][] path = positions(this.start, this.world, this.last.input(this.seen.sneaking),
                            this.seen.sprinting, this.most, way);
                    for (int n : this.moves) {
                        if (n > led) {
                            offer(this.last.copy(), n, way, true, null,
                                    this.seen.outside(path[n - 1][0], path[n - 1][2]),
                                    this.seen.under(path[n - 1][1]), false);
                        }
                    }
                }
            }
        }

        private void offer(Keys keys, int n, Way way, boolean tapped, PlayerSim end, double offset, double under,
                           boolean weak) {
            boolean fits = offset <= SLACK && Math.abs(under) <= this.slackY;
            if (this.best.fits() && !fits) {
                return;
            }
            double score = offset + Math.abs(under);
            if (this.hint > 0.0) {
                score += PER_MOVE * Math.abs((double) n - this.hint);
            }
            if (this.last != null && !keys.sameDirection(this.last)) {
                score += weak ? CHANGED_WEAK_KEYS : CHANGED_KEYS;
            }
            if (this.last != null && keys.jump != this.last.jump) {
                score += CHANGED_JUMP;
            }
            if (this.seen.usingItem && !keys.itemSlow) {
                score += NOT_SLOWED;
            }
            if (way.impulse != null) {
                score += WITH_IMPULSE;
            }
            if (way.led > 0) {
                score += tapped ? WITH_TAP : WITH_LEAD;
            }
            if (!(fits && !this.best.fits()) && score >= this.best.score) {
                return;
            }
            if (end == null) {
                end = end(this.start, this.world, keys.input(this.seen.sneaking), this.seen.sprinting, n, way);
            }
            if (end.onGround != this.seen.onGround) {
                score += GROUND_DIFFERS;
                if (!(fits && !this.best.fits()) && score >= this.best.score) {
                    return;
                }
            }
            this.best.offset = offset;
            this.best.under = under;
            this.best.fits = fits;
            this.best.score = score;
            this.best.moves = n;
            this.best.keys = keys;
            this.best.end = end;
            this.best.impulseAt = way.impulse == null ? -1 : way.impulseAt;
            this.best.led = way.led;
            this.best.tapped = tapped;
        }
    }

    static PlayerSim solve(PlayerSim start, PlayerSim.World world, Seen seen, Keys keys, int moves, int at,
                           boolean height) {
        PlayerSim.Input in = keys.input(seen.sneaking);
        PlayerSim before = at == 0 ? start : end(start, world, in, seen.sprinting, at, Way.PLAIN);
        int left = moves - at;
        double level = 0.0;
        double fall = 0.0;
        double unitLevel = 1.0;
        double unitFall = 1.0;
        for (int i = 0; i < left; i++) {
            level += unitLevel;
            fall += unitFall;
            unitLevel *= (double) 0.91F;
            unitFall *= 0.9800000190734863;
        }
        Way replaced = new Way();
        replaced.impulse = new double[]{before.motionX, before.motionY, before.motionZ};
        replaced.impulseAt = at;
        double[] motion = replaced.impulse;
        PlayerSim end = null;
        for (int i = 0; i < 6; i++) {
            end = end(start, world, in, seen.sprinting, moves, replaced);
            double missX = seen.midX() - end.x();
            double missY = height ? seen.midY() - end.y() : 0.0;
            double missZ = seen.midZ() - end.z();
            if (Math.abs(missX) < 1.0E-3 && Math.abs(missY) < 1.0E-3 && Math.abs(missZ) < 1.0E-3) {
                break;
            }
            motion[0] += missX / level;
            motion[1] += missY / fall;
            motion[2] += missZ / level;
        }
        if (seen.outside(end.x(), end.z()) > 0.0 || height && seen.under(end.y()) != 0.0) {
            return null;
        }
        return end;
    }

    static float turn(double fromX, double fromZ, double toX, double toZ) {
        double angle = Math.toDegrees(Math.atan2(toZ, toX) - Math.atan2(fromZ, fromX));
        return (float) wrap(angle);
    }

    private static float inBand(float yaw, float mid, float half) {
        double off = wrap((double) yaw - (double) mid);
        if (off > (double) half) {
            off = (double) half;
        } else if (off < (double) -half) {
            off = (double) -half;
        }
        return mid + (float) off;
    }

    static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    static double[][] positions(PlayerSim start, PlayerSim.World world, PlayerSim.Input in, boolean sprinting,
                                int ticks) {
        return positions(start, world, in, sprinting, ticks, Way.PLAIN);
    }

    static PlayerSim end(PlayerSim start, PlayerSim.World world, PlayerSim.Input in, boolean sprinting, int ticks) {
        return end(start, world, in, sprinting, ticks, Way.PLAIN);
    }

    private static double[][] positions(PlayerSim start, PlayerSim.World world, PlayerSim.Input in,
                                        boolean sprinting, int ticks, Way way) {
        double[][] out = new double[ticks][];
        PlayerSim sim = start.copy();
        for (int i = 0; i < ticks; i++) {
            step(sim, world, in, sprinting, i, way);
            out[i] = new double[]{sim.x(), sim.y(), sim.z(), sim.onGround ? 1.0 : 0.0};
        }
        return out;
    }

    private static PlayerSim end(PlayerSim start, PlayerSim.World world, PlayerSim.Input in, boolean sprinting,
                                 int ticks, Way way) {
        PlayerSim sim = start.copy();
        for (int i = 0; i < ticks; i++) {
            step(sim, world, in, sprinting, i, way);
        }
        return sim;
    }

    private static void step(PlayerSim sim, PlayerSim.World world, PlayerSim.Input in, boolean sprinting, int index,
                             Way way) {
        if (way.impulse != null && index == way.impulseAt) {
            sim.motionX = way.impulse[0];
            sim.motionY = way.impulse[1];
            sim.motionZ = way.impulse[2];
        }
        boolean leading = index < way.led;
        sim.sprinting = leading ? way.leadSprint : sprinting;

        sim.move(world, leading ? way.lead : in);
    }
}
