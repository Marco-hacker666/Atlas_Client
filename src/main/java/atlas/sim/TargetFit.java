package atlas.sim;

/**
 * Which keys moved another player (2026-11-13, owner: "predict the other players' movement", the first step of it).
 *
 * The server tells this client little about another player, and coarsely (EntityTrackerEntry, read on 2026-11-13):
 * its position about every second tick, floored to 1/32 of a block and only once it is 4/32 from the last one sent;
 * its yaw in 256ths of a turn and only once it is 4 of those (5.6 degrees) from the last one sent; whether it stands
 * on ground; and its sprint, sneak and item-use flags. Never its keys. But the keys are what a forecast needs, and
 * between two positions there are few things a 1.8 player can have done: one of eight walking directions or none,
 * with a jump or without, for one to four moves. This tries them, each with {@link PlayerSim} from the player's
 * state as it was at the last position, and says which ends in the cell the server now names.
 *
 * The yaw is not taken from the packet (5.6 degrees is 0.05 of a block over two sprinted moves, more than the cell):
 * it is solved. Without a wall in the way, what the keys add to a move turns rigidly with the yaw, so one run of the
 * keys at any yaw and one run with no keys give the yaw that points the keys' share at the cell; a last run at that
 * yaw checks it against the real collisions. The packet's yaw only bounds it, which is what tells W from W+A.
 *
 * Keys change when the player presses them, not when the server sends, so about every second change falls between
 * two positions and no one set of keys explains those moves. Only when none does, two more things are tried: the
 * keys of before for the first move or more and new ones after; and a tap -- other keys or none first, then the
 * keys of before again, which is the sprint reset of every 1.8 fight.
 *
 * What it cannot see: in the air the keys add so little (0.02 a move) that every direction fits; there the keys of
 * last time are kept for as long as they fit at all. And a player moved by something else -- a knockback this client
 * is not told of, a cheat -- fits nothing: {@link #solve} then finds the motion that must have replaced the
 * player's, and {@link TargetTrack} goes on from that.
 */
public final class TargetFit {

    /** The server's unit for another player's position, in blocks. */
    public static final double UNIT = 1.0 / 32.0;
    /** How far outside the named cell a move may end and still fit: the start is known no better than one cell. */
    public static final double SLACK = UNIT;
    /**
     * The yaw the server last sent is a 256th of a turn, floored, and a new one comes once the player's is 4 of
     * those away: the player's own is from 3 below the one sent to just under 4 above. That is the sent yaw plus
     * YAW_MID, give or take YAW_HALF (a little added for the float).
     */
    public static final float YAW_MID = 0.5F * 360.0F / 256.0F;
    public static final float YAW_HALF = 3.5F * 360.0F / 256.0F + 0.3F;

    /** Keys that add less than this to the move (blocks) say nothing about the yaw: in the air, mostly. */
    private static final double WEAK = 0.1;
    /** A share of the keys this much longer or shorter than the gap to fill cannot be turned to fit. */
    private static final double RADIAL = 0.1;
    private static final double PER_MOVE = 0.004;
    private static final double CHANGED_KEYS = 0.006;
    /** Where the keys add too little to tell them apart, the keys of last time stay for as long as they fit. */
    private static final double CHANGED_WEAK_KEYS = 0.05;
    /** A landing shows a held jump key and a free one alike: as it was. */
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

    /** What the server last told of another player. */
    public static final class Seen {
        /** Its position in 32nds of a block, as sent (Entity.serverPosX and the like): the cell it is in. */
        public int x;
        public int y;
        public int z;
        public boolean onGround;
        /** Its yaw as the last packet with one gave it, degrees. */
        public float yaw;
        public boolean sprinting;
        public boolean sneaking;
        /** Blocking, eating, drawing a bow. */
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

        /** The middle of the cell. */
        public double midX() {
            return ((double) this.x + 0.5) * UNIT;
        }

        public double midY() {
            return ((double) this.y + 0.5) * UNIT;
        }

        public double midZ() {
            return ((double) this.z + 0.5) * UNIT;
        }

        /** How far this point is outside the cell level, blocks; 0 inside. */
        public double outside(double px, double pz) {
            double dx = beyond(px, this.x);
            double dz = beyond(pz, this.z);
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** How far this height is under the cell (positive) or over it (negative); 0 inside. */
        public double under(double py) {
            return beyond(py, this.y);
        }

        /** How far {@code p} is from the cell on one axis: positive below it, negative above, 0 inside. */
        static double beyond(double p, int cell) {
            double min = (double) cell * UNIT;
            double max = min + UNIT;
            return p < min ? min - p : p > max ? max - p : 0.0;
        }
    }

    /** The keys of a move, as a keyboard has them, and the yaw they ran by. */
    public static final class Keys {
        /** 1 forward, -1 back, 0 neither. */
        public int forward;
        /** 1 left, -1 right, 0 neither. */
        public int strafe;
        public boolean jump;
        public float yaw;
        /** An item in use slowed the keys to a fifth. */
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

        /** The keys as the game hands them to the move: scaled by a sneak (0.3) and an item in use (0.2). */
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

    /** The best explanation found for a position. */
    public static final class Result {
        /** How far outside the cell it ends level, blocks; it fits when this is at most {@link #SLACK}... */
        public double offset = Double.MAX_VALUE;
        /** ...and this, how far under (positive) or over the cell it ends, is within the slack given for it. */
        public double under;
        boolean fits;
        /** How many moves the player made. */
        public int moves;
        public Keys keys;
        /** The player after those moves. */
        public PlayerSim end;
        /** The move (from 0) before which the expected impulse replaced the motion; -1 for none. */
        public int impulseAt = -1;
        /** How many of the first moves ran by other keys than {@link #keys}; 0 when all ran by them. */
        public int led;
        /** True when those first moves were a tap and {@link #keys} are the keys held before it and after. */
        public boolean tapped;
        double score = Double.MAX_VALUE;

        public boolean fits() {
            return this.fits;
        }
    }

    /** What happened on the way besides the keys looked for. */
    private static final class Way {
        static final Way PLAIN = new Way();
        /** A motion that replaced the player's before move {@code impulseAt}; null for none. */
        double[] impulse;
        int impulseAt = -1;
        /** Other keys for the first {@code led} moves, with the sprint they ran by; null for none. */
        PlayerSim.Input lead;
        boolean leadSprint;
        int led;
    }

    /**
     * @param start    the player after the last position it was seen at, as between ticks; not changed
     * @param seen     what the server now says
     * @param before   what it said at the last position, or null
     * @param yawMid   the middle of the yaws the moves may have run by, degrees
     * @param yawHalf  how far to either side of it they may lie
     * @param moves    how many moves it may have made since, likeliest first
     * @param hint     how many it likely made, from the clock; 0 or less when not known
     * @param last     the keys of the moves before, or null
     * @param impulse  a motion {x, y, z} that may have replaced the player's before one of the moves, or null
     * @param slackY   how far under or over the cell a move may end and fit: {@link #SLACK}, or more while the
     *                 start's height and fall are themselves only estimates
     * @return the best explanation; {@link Result#fits} says whether it is one
     */
    public static Result fit(PlayerSim start, PlayerSim.World world, Seen seen, Seen before, float yawMid,
                             float yawHalf, int[] moves, double hint, Keys last, double[] impulse, double slackY) {
        int most = 0;
        for (int n : moves) {
            most = Math.max(most, n);
        }
        /* As last time: the same keys by the same yaw. A player running straight is found here, in one run, and
           its yaw is left alone as long as it keeps landing in the cell -- over many moves that pins it down far
           more finely than any single pair of positions can. */
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
            /* One set of keys for all the moves explains nothing: they changed on the way. Keys change when the
               player presses them, not when the server sends, so about every second change falls between two
               positions. First: the old keys for a move or more, then new ones. */
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

    /** One search: what is the same for every candidate, and the best so far. */
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

        /** Every key and jump, for one thing that may have happened on the way (or nothing). */
        void all(Way way) {
            PlayerSim.Input idle = new PlayerSim.Input();
            idle.yaw = this.yawMid;
            idle.sneak = this.seen.sneaking;
            double[][] still = positions(this.start, this.world, idle, false, this.most, way);
            boolean ground = this.start.onGround;
            for (double[] at : still) {
                ground |= at[3] != 0.0;
            }
            if (!ground) {
                /* Nowhere near ground the jump key shows nothing: it stays as it was. */
                branch(way, this.last != null && this.last.jump, still);
                return;
            }
            /* The height tells a jump from none, whatever the keys: where only one of the two can reach the
               cell's height the other is not run. Where neither or both can (a step up, an edge), both are. */
            PlayerSim.Input hop = idle.copy();
            hop.jump = true;
            double[][] stillJumping = positions(this.start, this.world, hop, false, this.most, way);
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

        /** No keys, and each of the eight directions, with the jump key as given. */
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
                    /* A sprint needs the forward key. */
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

        /** These keys over {@code n} moves, by the yaw that points what they add at the cell. */
        private void turned(Way way, Keys keys, int n, double[] still, double[] pushed) {
            /* What the keys added, and the gap they have to fill, level. */
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
            if (weak) {
                turned.yaw = inBand(this.last != null ? this.last.yaw : this.seen.yaw, this.yawMid, this.yawHalf);
            } else {
                turned.yaw = inBand(this.yawMid + turn(addX, addZ, gapX, gapZ), this.yawMid, this.yawHalf);
            }
            PlayerSim end = end(this.start, this.world, turned.input(this.seen.sneaking), this.seen.sprinting, n,
                    way);
            double offset = this.seen.outside(end.x(), end.z());
            if (offset > 0.0 && !weak) {
                /* A wall or the sine table bent it: once more from where it did end. */
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

        /**
         * A tap: other keys (or none) for a move or more, then the keys of before again -- the sprint reset of a
         * 1.8 fight when it falls between two positions. By the yaw of before; the keys stay the ones of before.
         */
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
                    /* Off the forward key the sprint is off; back on it, it is on again in the same move. */
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

        /** Keeps the candidate when it is the best so far: one that fits before one that does not, then by score. */
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

    /**
     * The motion that, put in place of the player's before move {@code at} of {@code moves}, ends in the cell: what
     * a knockback this client was not told of must have been, or, with {@code at} 0, simply the motion the player
     * had when its position was right and its motion was not. The keys are taken to be the same. Each try runs the
     * moves and adds what was missing, divided by how far a unit of motion carries over the moves left (the air's
     * friction, close enough to converge on ground too).
     *
     * @param height false to leave the fall as it is and look for the level motion only: on ground at the end the
     *               height says nothing of the fall, and a fall that already ends at the right height is better
     *               than any worked back from two cells
     * @return the player after the moves, or null when no motion ends in the cell (a wall is in the way)
     */
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

    /** The turn of the yaw, degrees, that points the vector (fromX, fromZ) along (toX, toZ). */
    static float turn(double fromX, double fromZ, double toX, double toZ) {
        /* The yaw and the angle of a level vector differ by a quarter turn and run the same way. */
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

    /** {x, y, z, on ground (1 or 0)} after each of {@code ticks} moves by these keys. */
    static double[][] positions(PlayerSim start, PlayerSim.World world, PlayerSim.Input in, boolean sprinting,
                                int ticks) {
        return positions(start, world, in, sprinting, ticks, Way.PLAIN);
    }

    /** The player after {@code ticks} moves by these keys. */
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
        /* The wait between the jumps of a held key is the other client's own count, not known here; it is over
           by the time a jump lands, so a held key jumps on every landing. */
        sim.jumpTicks = 0;
        sim.move(world, leading ? way.lead : in);
    }
}
