package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

import java.util.ArrayDeque;

/**
 * One other player, followed (2026-11-13). It keeps a shadow of the player -- a {@link PlayerSim} state: position,
 * motion, on ground or not -- and with each position the server sends it asks {@link TargetFit} which keys took the
 * shadow there, moves the shadow on by them, and remembers them. {@link #forecast} runs the shadow on with the same
 * keys: through a turn, up and down a jump's arc, into a wall and not through it -- the cases where a straight line
 * from the last two positions, which is what every module used, is wrong.
 *
 * The shadow knows more than the positions do. A position is a cell 1/32 of a block wide; the shadow is a point in
 * it with an exact motion, and it is only pushed back into the cell when it leaves it. And while the player holds
 * the same keys without turning, its motion is that of the whole run since the keys last changed, by one yaw, not
 * of a yaw found anew for each two cells: a run of twelve moves holds the yaw six times tighter than two.
 *
 * What a forecast takes for granted is that the keys stay as they are. Two things it adds to that. A player whose
 * yaw has been turning one way keeps turning (circling a target). And keys let go a moment ago by a player still
 * sliding may be a stop or a tap, so the answer is the middle of both.
 *
 * When nothing fits -- a knockback this client was not told of (the server sends a melee hit's only to the player
 * hit), a teleport, a cheat that moves as no 1.8 player can -- the shadow keeps its place and takes the motion that
 * leads from there to the new cell. Until two positions are explained again (one, in the air, where a fall leaves
 * little to guess) the answer is the straight line, so it is never worse than what the modules did before, and it
 * says which of the two it is.
 *
 * Not thread safe: positions arrive on the network thread and must be handed over to the thread that asks.
 */
public final class TargetTrack {

    /** A gap this long (moves) with no position means the player stood still in it: slower than 4/32 in two moves. */
    private static final double RESTED = 6.0;
    /** Faster than this (blocks a move) between two positions is a teleport, not a run. */
    private static final double TELEPORT = 1.5;
    private static final double RESTING_FALL = -0.0784000015258789;
    /** An impulse the caller expects is tried for this many positions. */
    private static final int EXPECT_FOR = 4;
    private static final int REMEMBERED = 6;
    /** The run the yaw is taken from: at most this many positions and this many moves back. */
    private static final int TRAIL_MARKS = 7;
    private static final int TRAIL_MOVES = 12;
    /** A run whose yaw comes out this far (degrees) from the last moves' was not one straight run. */
    private static final float TRAIL_TURN = 8.0F;
    /** Keys that add less than this over the run (blocks) say nothing about its yaw. */
    private static final double TRAIL_WEAK = 0.1;
    private static final float FASTEST_TURN = 30.0F;
    /** Keys let go this few moves ago, by a player still sliding faster than TAP_SPEED, may be a tap. */
    private static final int TAP_MOVES = 4;
    private static final double TAP_SPEED = 0.05;

    /** The player's movement-speed attribute without the sprint's share (0.1 with no potion). */
    public double walkSpeed = (double) 0.1F;
    /** 0.1 for each level of Jump Boost, when known. */
    public float jumpBoost;
    /** Depth Strider's level on the boots, when known. */
    public float depthStrider;

    /** The shadow after an explained position, what the server said there, and the moves that led to it. */
    private static final class Mark {
        PlayerSim state;
        final TargetFit.Seen seen;
        final int moves;

        Mark(PlayerSim state, TargetFit.Seen seen, int moves) {
            this.state = state;
            this.seen = seen;
            this.moves = moves;
        }
    }

    private PlayerSim shadow;
    /** A second reading of the last position that nothing explained; the next position says which was right. */
    private PlayerSim other;
    private TargetFit.Seen seen;
    private TargetFit.Seen before;
    private TargetFit.Keys keys;
    /** The keys held before they were all let go, whether that was a sprint, and the moves since; null when held. */
    private TargetFit.Keys heldBefore;
    private boolean sprintBefore;
    private int releasedFor;
    private TargetFit.Result lastFit;
    /** The explained positions of the run now going, oldest first: same keys, same flags, no turn. */
    private final ArrayDeque<Mark> trail = new ArrayDeque<Mark>();
    /** The straight line's speed, blocks a move. */
    private double lineX;
    private double lineZ;
    /** Degrees a move the player is turning by, from the yaws sent; 0 unless two positions in a row turned one way. */
    private float yawRate;
    private int lastMoves = 2;
    /** One bit a position, newest lowest: explained or not. */
    private int explained;
    private int positions;
    private int streak;
    /** The shadow's height and fall are estimates (it was placed in the air), so they get more room to fit. */
    private boolean looseHeight;
    private double[] expected;
    private int expectedLeft;

    /** Forgets the player: the next position is the first. */
    public void reset() {
        this.shadow = null;
        this.other = null;
        this.seen = null;
        this.before = null;
        this.keys = null;
        this.heldBefore = null;
        this.releasedFor = 0;
        this.lastFit = null;
        this.trail.clear();
        this.lineX = 0.0;
        this.lineZ = 0.0;
        this.yawRate = 0.0F;
        this.lastMoves = 2;
        this.explained = 0;
        this.positions = 0;
        this.streak = 0;
        this.looseHeight = false;
        this.expected = null;
    }

    /**
     * The player's motion may be replaced by this before one of its next moves: a knockback the server did send
     * (an arrow's, an explosion's, a rod's -- those go to everyone) or one worked out ({@link #vanillaHit}). When it
     * reaches the player's own client is not known, so it is tried at each move of the next few positions.
     */
    public void expect(double motionX, double motionY, double motionZ) {
        this.expected = new double[]{motionX, motionY, motionZ};
        this.expectedLeft = EXPECT_FOR;
    }

    /**
     * The motion a melee hit gives a player on an unchanged 1.8 server (EntityLivingBase.knockBack and the extra of
     * a sprinting or Knockback hit). The server works it out from its own idea of the player's motion, which for a
     * player is nothing but the resting fall, so it does not depend on how the player was moving.
     *
     * @param fromX       the attacker's x minus the player's (and z)
     * @param attackerYaw the attacker's yaw, for the extra
     * @param extra       1 for a sprinting attacker, plus the Knockback level
     */
    public static double[] vanillaHit(double fromX, double fromZ, float attackerYaw, int extra) {
        float distance = MathHelper.sqrt_double(fromX * fromX + fromZ * fromZ);
        float strength = 0.4F;
        double x = 0.0;
        double y = RESTING_FALL / 2.0 + (double) strength;
        double z = 0.0;
        if (distance > 0.0F) {
            x -= fromX / (double) distance * (double) strength;
            z -= fromZ / (double) distance * (double) strength;
        }
        if (y > 0.4000000059604645) {
            y = 0.4000000059604645;
        }
        if (extra > 0) {
            x += (double) (-MathHelper.sin(attackerYaw * (float) Math.PI / 180.0F) * (float) extra * 0.5F);
            y += 0.1;
            z += (double) (MathHelper.cos(attackerYaw * (float) Math.PI / 180.0F) * (float) extra * 0.5F);
        }
        return new double[]{x, y, z};
    }

    /**
     * A position of the player arrived.
     *
     * @param now   what the server says; kept, so not to be changed afterwards
     * @param moves how many moves the player likely made since the last position, from the clock (ticks between the
     *              two arrivals); 0 or less when not known
     */
    public void observe(TargetFit.Seen now, PlayerSim.World world, double moves) {
        TargetFit.Seen last = this.seen;
        this.positions++;
        if (this.shadow == null || last == null) {
            this.shadow = placed(now, null, 0, world);
            this.looseHeight = !now.onGround;
            this.seen = now;
            this.explained = 0;
            this.streak = 0;
            return;
        }
        int likely = moves > 0.0 ? (int) Math.max(1L, Math.round(moves)) : 2;
        double dx = now.midX() - last.midX();
        double dz = now.midZ() - last.midZ();
        this.lineX = dx / (double) likely;
        this.lineZ = dz / (double) likely;
        if (this.lineX * this.lineX + this.lineZ * this.lineZ > TELEPORT * TELEPORT) {
            this.lineX = 0.0;
            this.lineZ = 0.0;
        }
        PlayerSim start = this.shadow.copy();
        apply(start, now);
        boolean rested = moves > RESTED;
        if (rested) {
            /* It stood still in between, or an update would have come: it starts from rest. */
            start.motionX = 0.0;
            start.motionZ = 0.0;
            if (start.onGround) {
                start.motionY = RESTING_FALL;
            }
        }
        /* The moves ran by yaws between the last one sent and this one, each known no better than the slack. */
        float turned = (float) TargetFit.wrap((double) now.yaw - (double) last.yaw);
        float yawMid = last.yaw + turned / 2.0F + TargetFit.YAW_MID;
        float yawHalf = Math.abs(turned) / 2.0F + TargetFit.YAW_HALF;
        double slackY = this.looseHeight ? 2.0 * TargetFit.UNIT : TargetFit.SLACK;
        TargetFit.Result fit = TargetFit.fit(start, world, now, last, yawMid, yawHalf, candidates(moves), moves,
                this.keys, this.expected, slackY);
        if (!fit.fits() && this.other != null) {
            /* The last position had two readings; this one may follow from the second. */
            PlayerSim second = this.other.copy();
            apply(second, now);
            TargetFit.Result otherFit = TargetFit.fit(second, world, now, last, yawMid, yawHalf, candidates(moves),
                    moves, this.keys, this.expected, slackY);
            if (otherFit.fits()) {
                fit = otherFit;
                start = second;
            }
        }
        this.other = null;
        this.lastFit = fit;
        int made;
        if (fit.fits()) {
            made = fit.moves;
            /* One set of keys by one yaw for all the moves: a stretch the yaw can be taken from. */
            boolean single = fit.impulseAt < 0 && fit.led == 0 && turned == 0.0F;
            boolean steady = single && !rested && this.keys != null
                    && fit.keys.sameDirection(this.keys) && fit.keys.jump == this.keys.jump
                    && now.sprinting == last.sprinting && now.sneaking == last.sneaking
                    && now.usingItem == last.usingItem;
            this.shadow = fit.end;
            settle(this.shadow, now, fit.moves);
            if (fit.keys.forward != 0 || fit.keys.strafe != 0) {
                this.heldBefore = null;
                this.releasedFor = 0;
            } else if (this.keys != null && (this.keys.forward != 0 || this.keys.strafe != 0)) {
                this.heldBefore = this.keys;
                this.sprintBefore = last.sprinting;
                this.releasedFor = fit.moves;
            } else {
                this.releasedFor += fit.moves;
            }
            this.keys = fit.keys;
            if (!steady) {
                this.trail.clear();
                if (single) {
                    /* The run begins where these moves began: they ran by the new keys from there. */
                    this.trail.addLast(new Mark(start, last, 0));
                }
            }
            this.trail.addLast(new Mark(this.shadow.copy(), now, fit.moves));
            while (this.trail.size() > TRAIL_MARKS || trailMoves() > TRAIL_MOVES) {
                this.trail.removeFirst();
            }
            if (this.trail.size() >= 3) {
                yawFromTheRun(world, now, fit.moves, slackY);
            }
            if (this.shadow.onGround) {
                this.looseHeight = false;
            }
            this.explained = this.explained << 1 | 1;
            this.streak++;
            if (fit.impulseAt >= 0) {
                this.expected = null;
            }
        } else {
            made = likely;
            this.shadow = unexplained(start, now, last, likely, world, fit);
            this.heldBefore = null;
            this.releasedFor = 0;
            this.trail.clear();
            this.explained = this.explained << 1;
            this.streak = 0;
        }
        /* Turning: two positions in a row turned the same way. The slower of the two rates, so that one flick
           is not run on as a spin. */
        float rate = turned / (float) made;
        float rateBefore = this.before == null ? 0.0F
                : (float) TargetFit.wrap((double) last.yaw - (double) this.before.yaw) / (float) this.lastMoves;
        if (rate * rateBefore > 0.0F) {
            float slower = Math.min(Math.abs(rate), Math.abs(rateBefore));
            this.yawRate = Math.signum(rate) * Math.min(slower, FASTEST_TURN);
        } else {
            this.yawRate = 0.0F;
        }
        this.lastMoves = made;
        if (this.expected != null && --this.expectedLeft <= 0) {
            this.expected = null;
        }
        this.before = last;
        this.seen = now;
    }

    /**
     * The shadow after a position nothing explained. Its position before was right (it had been following); what
     * was not is its motion: something replaced it, a knockback mostly. So the motion is looked for that takes the
     * old position to the new cell -- replaced before the first of the moves, and, as a second reading kept for the
     * next position to choose from, before the last. Where no motion does that (a teleport, a wall, the first
     * positions), it is placed in the cell with what the two positions alone give.
     */
    private PlayerSim unexplained(PlayerSim start, TargetFit.Seen now, TargetFit.Seen last, int moves,
                                  PlayerSim.World world, TargetFit.Result fit) {
        double perMoveX = (now.midX() - last.midX()) / (double) moves;
        double perMoveZ = (now.midZ() - last.midZ()) / (double) moves;
        TargetFit.Keys held = this.keys;
        if (held != null && perMoveX * perMoveX + perMoveZ * perMoveZ <= TELEPORT * TELEPORT
                && (!now.sprinting || held.forward == 1)) {
            int count = Math.min(moves, 4);
            /* The fall is the same whatever the keys: where it already ends at the right height it is kept, and
               only the level motion is looked for. */
            PlayerSim asIs = TargetFit.end(start, world, held.input(now.sneaking), now.sprinting, count);
            boolean height = !now.onGround && (asIs.onGround || now.under(asIs.y()) != 0.0);
            PlayerSim first = TargetFit.solve(start, world, now, held, count, 0, height);
            PlayerSim late = count > 1 ? TargetFit.solve(start, world, now, held, count, count - 1, height) : null;
            /* With the fall left as it is, nothing has held the height against the cell yet: a player moved to
               another floor is not on the old one, however well the level motion fits (review, PR #40). */
            if (first != null && Math.abs(now.under(first.y())) > TargetFit.SLACK) {
                first = null;
            }
            if (late != null && Math.abs(now.under(late.y())) > TargetFit.SLACK) {
                late = null;
            }
            if (first != null || late != null) {
                if (now.onGround) {
                    this.looseHeight = false;
                } else if (height) {
                    this.looseHeight = true;
                }
                this.other = first != null ? late : null;
                return first != null ? first : late;
            }
        }
        PlayerSim again = placed(now, last, moves, world);
        if (now.onGround) {
            this.looseHeight = false;
        } else if (fit.end != null && now.under(fit.end.y()) == 0.0 && !fit.end.onGround) {
            /* The fall is the same whatever the keys: where the best try ended at the right height, its height
               and fall are better than any worked back from two cells. */
            again.box = again.box.offset(0.0, fit.end.y() - again.y(), 0.0);
            again.motionY = fit.end.motionY;
        } else {
            this.looseHeight = true;
        }
        return again;
    }

    /** How many moves to try, the one the clock says first, then those nearest it, from 1 to 4. */
    static int[] candidates(double moves) {
        if (moves <= 0.0) {
            return new int[]{2, 1, 3};
        }
        int likely = (int) Math.max(1L, Math.min(4L, Math.round(moves)));
        int[] out = new int[4];
        int count = 0;
        out[count++] = likely;
        for (int step = 1; step <= 2; step++) {
            /* Of the two at the same distance, the one on the clock's side first. */
            int first = moves >= (double) likely ? likely + step : likely - step;
            int second = moves >= (double) likely ? likely - step : likely + step;
            if (first >= 1 && first <= 4) {
                out[count++] = first;
            }
            if (second >= 1 && second <= 4) {
                out[count++] = second;
            }
        }
        int[] exact = new int[count];
        System.arraycopy(out, 0, exact, 0, count);
        return exact;
    }

    private int trailMoves() {
        int total = 0;
        boolean first = true;
        for (Mark mark : this.trail) {
            if (!first) {
                total += mark.moves;
            }
            first = false;
        }
        return total;
    }

    /**
     * The yaw of the whole run since the keys last changed, in place of the last moves' own. The keys are run from
     * the oldest position of the run to this one, turned to end in this cell; if that one yaw also passes every
     * position in between, it is the run's, and the shadow takes the motion that run ends with: the motion of one
     * yaw held all the way, not of a yaw found anew for each pair of cells. If it does not pass them, the run was
     * not straight, and its oldest position is let go.
     */
    private void yawFromTheRun(PlayerSim.World world, TargetFit.Seen now, int moves, double slackY) {
        Mark oldest = this.trail.peekFirst();
        int total = trailMoves();
        PlayerSim.Input idle = this.keys.input(now.sneaking);
        idle.forward = 0.0F;
        idle.strafe = 0.0F;
        double[][] still = TargetFit.positions(oldest.state, world, idle, false, total);
        double[][] pushed = TargetFit.positions(oldest.state, world, this.keys.input(now.sneaking), now.sprinting,
                total);
        double addX = pushed[total - 1][0] - still[total - 1][0];
        double addZ = pushed[total - 1][2] - still[total - 1][2];
        if (Math.sqrt(addX * addX + addZ * addZ) < TRAIL_WEAK) {
            return;
        }
        float turn = TargetFit.turn(addX, addZ, now.midX() - still[total - 1][0], now.midZ() - still[total - 1][2]);
        if (Math.abs(turn) > TRAIL_TURN) {
            this.trail.removeFirst();
            return;
        }
        TargetFit.Keys better = this.keys.copy();
        better.yaw = this.keys.yaw + turn;
        double[][] path = TargetFit.positions(oldest.state, world, better.input(now.sneaking), now.sprinting, total);
        int at = 0;
        boolean first = true;
        for (Mark mark : this.trail) {
            if (first) {
                first = false;
                continue;
            }
            at += mark.moves;
            double[] there = path[at - 1];
            if (mark.seen.outside(there[0], there[2]) > TargetFit.SLACK
                    || Math.abs(mark.seen.under(there[1])) > slackY) {
                this.trail.removeFirst();
                return;
            }
        }
        PlayerSim end = TargetFit.end(oldest.state, world, better.input(now.sneaking), now.sprinting, total);
        if (end.onGround == this.shadow.onGround) {
            /* The run's motion, at the shadow's place: that has been held in every cell on the way, the run's
               end only in the first and the last. */
            end.box = end.box.offset(this.shadow.x() - end.x(), 0.0, this.shadow.z() - end.z());
            settle(end, now, moves);
            this.shadow = end;
            this.keys = better;
            this.trail.peekLast().state = end.copy();
        }
    }

    /** What the server says of the player now, onto its state. */
    private void apply(PlayerSim sim, TargetFit.Seen now) {
        sim.sprinting = now.sprinting;
        sim.walkSpeed = this.walkSpeed;
        sim.jumpBoost = this.jumpBoost;
        sim.depthStrider = this.depthStrider;
        sim.stage = PlayerSim.Stage.START;
    }

    /**
     * The player placed in the cell the server names, with the motion the way from the position before leaves
     * behind: a move covers the motion and then friction takes its share, so what is left is a move's length times
     * the friction (the ground's when it was on ground at both ends, else the air's); in the air the fall is worked
     * back from the height lost.
     */
    private PlayerSim placed(TargetFit.Seen now, TargetFit.Seen last, int moves, PlayerSim.World world) {
        PlayerSim sim = new PlayerSim();
        double x = now.midX();
        double z = now.midZ();
        /* Ground is at a 32nd or coarser, and the cell is named by its lower edge. */
        double y = now.onGround ? (double) now.y * TargetFit.UNIT : now.midY();
        double half = (double) (sim.width / 2.0F);
        sim.box = new AxisAlignedBB(x - half, y, z - half, x + half, y + (double) sim.height, z + half);
        sim.onGround = now.onGround;
        apply(sim, now);
        sim.airSpeed = 0.02F;
        if (now.sprinting) {
            sim.airSpeed = (float) ((double) sim.airSpeed + (double) 0.02F * 0.3);
        }
        sim.motionY = now.onGround ? RESTING_FALL : 0.0;
        if (last == null || moves <= 0) {
            return sim;
        }
        double perMoveX = (now.midX() - last.midX()) / (double) moves;
        double perMoveZ = (now.midZ() - last.midZ()) / (double) moves;
        if (perMoveX * perMoveX + perMoveZ * perMoveZ <= TELEPORT * TELEPORT) {
            float friction = 0.91F;
            if (now.onGround && last.onGround) {
                friction = world.slipperiness(MathHelper.floor_double(x), MathHelper.floor_double(y) - 1,
                        MathHelper.floor_double(z)) * 0.91F;
            }
            sim.motionX = perMoveX * (double) friction;
            sim.motionZ = perMoveZ * (double) friction;
        }
        if (!now.onGround) {
            sim.motionY = fallAfter(now.midY() - last.midY(), moves);
        }
        return sim;
    }

    /**
     * The vertical motion after {@code moves} moves through the air that changed the height by {@code rise}: each
     * move covers the motion, which then loses gravity's 0.08 and keeps 0.98.
     */
    static double fallAfter(double rise, int moves) {
        /* The height over the moves is linear in the first move's motion: rise = a * first + b. */
        double a = 0.0;
        double b = 0.0;
        double unit = 1.0;
        double zero = 0.0;
        for (int i = 0; i < moves; i++) {
            a += unit;
            b += zero;
            unit *= 0.9800000190734863;
            zero = (zero - 0.08) * 0.9800000190734863;
        }
        double motion = (rise - b) / a;
        for (int i = 0; i < moves; i++) {
            motion = (motion - 0.08) * 0.9800000190734863;
        }
        return motion;
    }

    /**
     * Moves the state into the cell where it is outside it. Level, always. In height only in the air, and then the
     * fall takes half of what was missing a move: the miss came from the height, from the fall, or from both.
     */
    private static void settle(PlayerSim sim, TargetFit.Seen now, int moves) {
        double x = sim.x();
        double z = sim.z();
        double toX = x + TargetFit.Seen.beyond(x, now.x);
        double toZ = z + TargetFit.Seen.beyond(z, now.z);
        double up = sim.onGround ? 0.0 : now.under(sim.y());
        if (toX != x || toZ != z || up != 0.0) {
            sim.box = sim.box.offset(toX - x, up, toZ - z);
            sim.motionY += 0.5 * up / (double) moves;
        }
    }

    /**
     * True when the shadow can be run on: the last two positions were explained, or the last one was and the player
     * is in the air (a fall leaves little to guess, so one is enough).
     */
    public boolean locked() {
        return this.shadow != null && (this.streak >= 2 || this.streak >= 1 && !this.shadow.onGround);
    }

    /** 0 to 1: the share of the last positions (up to six) that were explained; 0 when not {@link #locked}. */
    public double confidence() {
        if (!locked()) {
            return 0.0;
        }
        int counted = Math.min(REMEMBERED, this.positions - 1);
        if (counted <= 0) {
            return 0.0;
        }
        return (double) Integer.bitCount(this.explained & (1 << counted) - 1) / (double) counted;
    }

    /** The keys the player's last moves were found to run by; null before any were. */
    public TargetFit.Keys keys() {
        return this.keys == null ? null : this.keys.copy();
    }

    /** Degrees a move the player is taken to be turning by; 0 when it is not turning steadily. */
    public float yawRate() {
        return this.yawRate;
    }

    /** What the last position was explained by, or was nearest to; null before the second position. */
    public TargetFit.Result lastFit() {
        return this.lastFit;
    }

    /** The player as followed, at the last position; null before the first. A copy. */
    public PlayerSim shadow() {
        return this.shadow == null ? null : this.shadow.copy();
    }

    /**
     * Where the player will be over its next {@code moves} moves, counted from the last position the server sent:
     * the shadow run on with the keys it was found to hold (and turning on, when it has been turning), or, when its
     * moves were not explained, the straight line from the last two positions at the last height. Null before the
     * first position.
     */
    public TargetForecast forecast(PlayerSim.World world, int moves) {
        if (this.seen == null || this.shadow == null) {
            return null;
        }
        TargetForecast out = new TargetForecast(moves);
        if (locked() && this.keys != null) {
            PlayerSim sim = this.shadow.copy();
            apply(sim, this.seen);
            PlayerSim.Input in = this.keys.input(this.seen.sneaking);
            /* A sprint is taken as held: it ends at a wall and starts again after it, as the key does it. */
            in.sprint = this.seen.sprinting;
            /* The yaw found is the middle one of the last moves; the player has turned on since. */
            float yaw = this.keys.yaw + this.yawRate * (float) (this.lastMoves - 1) / 2.0F;
            out.simulated = true;
            out.confidence = confidence();
            set(out, 0, sim.x(), sim.y(), sim.z(), sim.onGround);
            for (int i = 1; i <= moves; i++) {
                in.yaw = yaw + this.yawRate * (float) i;
                sim.tick(world, in);
                set(out, i, sim.x(), sim.y(), sim.z(), sim.onGround);
            }
            if (this.heldBefore != null && this.releasedFor <= TAP_MOVES && this.shadow.speed() > TAP_SPEED) {
                /* The keys were let go a moment ago and the player is still sliding: a stop, or a tap (the
                   sprint reset of every 1.8 fight) with the keys back in a tick or two. Which is not known, so
                   the answer is the middle of the two: half the error either way, instead of all of it one way. */
                PlayerSim back = this.shadow.copy();
                apply(back, this.seen);
                PlayerSim.Input held = this.heldBefore.input(this.seen.sneaking);
                held.jump = in.jump;
                held.sprint = this.sprintBefore;
                for (int i = 1; i <= moves; i++) {
                    held.yaw = yaw + this.yawRate * (float) i;
                    back.tick(world, held);
                    out.x[i] = (out.x[i] + back.x()) / 2.0;
                    out.z[i] = (out.z[i] + back.z()) / 2.0;
                }
            }
            return out;
        }
        double x = this.seen.midX();
        double y = this.seen.onGround ? (double) this.seen.y * TargetFit.UNIT : this.seen.midY();
        double z = this.seen.midZ();
        for (int i = 0; i <= moves; i++) {
            set(out, i, x + this.lineX * (double) i, y, z + this.lineZ * (double) i, this.seen.onGround);
        }
        return out;
    }

    private static void set(TargetForecast out, int index, double x, double y, double z, boolean onGround) {
        out.x[index] = x;
        out.y[index] = y;
        out.z[index] = z;
        out.onGround[index] = onGround;
    }
}
