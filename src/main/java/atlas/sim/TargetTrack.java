package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

import java.util.ArrayDeque;

public final class TargetTrack {
    private static final double RESTED = 6.0;

    private static final double TELEPORT = 1.5;
    private static final double RESTING_FALL = -0.0784000015258789;

    private static final int EXPECT_FOR = 4;
    private static final int REMEMBERED = 6;

    private static final int TRAIL_MARKS = 7;
    private static final int TRAIL_MOVES = 12;

    private static final float TRAIL_TURN = 8.0F;

    private static final double TRAIL_WEAK = 0.1;
    private static final float FASTEST_TURN = 30.0F;

    private static final int TAP_MOVES = 4;
    private static final double TAP_SPEED = 0.05;

    public double walkSpeed = (double) 0.1F;

    public float jumpBoost;

    public float depthStrider;

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

    private PlayerSim other;
    private TargetFit.Seen seen;
    private TargetFit.Seen before;
    private TargetFit.Keys keys;

    private TargetFit.Keys heldBefore;
    private boolean sprintBefore;
    private int releasedFor;
    private TargetFit.Result lastFit;

    private final ArrayDeque<Mark> trail = new ArrayDeque<Mark>();

    private double lineX;
    private double lineZ;

    private float yawRate;
    private int lastMoves = 2;

    private int explained;
    private int positions;
    private int streak;

    private boolean looseHeight;
    private double[] expected;
    private int expectedLeft;

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

    public void expect(double motionX, double motionY, double motionZ) {
        this.expected = new double[]{motionX, motionY, motionZ};
        this.expectedLeft = EXPECT_FOR;
    }

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
            start.motionX = 0.0;
            start.motionZ = 0.0;
            if (start.onGround) {
                start.motionY = RESTING_FALL;
            }
        }

        float turned = (float) TargetFit.wrap((double) now.yaw - (double) last.yaw);
        float yawMid = last.yaw + turned / 2.0F + TargetFit.YAW_MID;
        float yawHalf = Math.abs(turned) / 2.0F + TargetFit.YAW_HALF;
        double slackY = this.looseHeight ? 2.0 * TargetFit.UNIT : TargetFit.SLACK;
        TargetFit.Result fit = TargetFit.fit(start, world, now, last, yawMid, yawHalf, candidates(moves), moves,
                this.keys, this.expected, slackY);
        if (!fit.fits() && this.other != null) {
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

    private PlayerSim unexplained(PlayerSim start, TargetFit.Seen now, TargetFit.Seen last, int moves,
                                  PlayerSim.World world, TargetFit.Result fit) {
        double perMoveX = (now.midX() - last.midX()) / (double) moves;
        double perMoveZ = (now.midZ() - last.midZ()) / (double) moves;
        TargetFit.Keys held = this.keys;
        if (held != null && perMoveX * perMoveX + perMoveZ * perMoveZ <= TELEPORT * TELEPORT
                && (!now.sprinting || held.forward == 1)) {
            int count = Math.min(moves, 4);

            PlayerSim asIs = TargetFit.end(start, world, held.input(now.sneaking), now.sprinting, count);
            boolean height = !now.onGround && (asIs.onGround || now.under(asIs.y()) != 0.0);
            PlayerSim first = TargetFit.solve(start, world, now, held, count, 0, height);
            PlayerSim late = count > 1 ? TargetFit.solve(start, world, now, held, count, count - 1, height) : null;

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
            again.box = again.box.offset(0.0, fit.end.y() - again.y(), 0.0);
            again.motionY = fit.end.motionY;
        } else {
            this.looseHeight = true;
        }
        return again;
    }

    static int[] candidates(double moves) {
        if (moves <= 0.0) {
            return new int[]{2, 1, 3};
        }
        int likely = (int) Math.max(1L, Math.min(4L, Math.round(moves)));
        int[] out = new int[4];
        int count = 0;
        out[count++] = likely;
        for (int step = 1; step <= 2; step++) {
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
            end.box = end.box.offset(this.shadow.x() - end.x(), 0.0, this.shadow.z() - end.z());
            settle(end, now, moves);
            this.shadow = end;
            this.keys = better;
            this.trail.peekLast().state = end.copy();
        }
    }

    private void apply(PlayerSim sim, TargetFit.Seen now) {
        sim.sprinting = now.sprinting;
        sim.walkSpeed = this.walkSpeed;
        sim.jumpBoost = this.jumpBoost;
        sim.depthStrider = this.depthStrider;
        sim.stage = PlayerSim.Stage.START;
    }

    private PlayerSim placed(TargetFit.Seen now, TargetFit.Seen last, int moves, PlayerSim.World world) {
        PlayerSim sim = new PlayerSim();
        double x = now.midX();
        double z = now.midZ();

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

    static double fallAfter(double rise, int moves) {
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

    public boolean locked() {
        return this.shadow != null && (this.streak >= 2 || this.streak >= 1 && !this.shadow.onGround);
    }

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

    public TargetFit.Keys keys() {
        return this.keys == null ? null : this.keys.copy();
    }

    public float yawRate() {
        return this.yawRate;
    }

    public TargetFit.Result lastFit() {
        return this.lastFit;
    }

    public PlayerSim shadow() {
        return this.shadow == null ? null : this.shadow.copy();
    }

    PlayerSim runStart() {
        if (!locked() || this.keys == null || this.seen == null) {
            return null;
        }
        PlayerSim sim = this.shadow.copy();
        apply(sim, this.seen);
        return sim;
    }

    PlayerSim.Input runInput() {
        PlayerSim.Input in = this.keys.input(this.seen.sneaking);
        in.sprint = this.seen.sprinting;
        return in;
    }

    PlayerSim.Input runTapInput() {
        if (this.heldBefore == null || this.releasedFor > TAP_MOVES || !(this.shadow.speed() > TAP_SPEED)) {
            return null;
        }
        PlayerSim.Input held = this.heldBefore.input(this.seen.sneaking);
        held.jump = this.keys.jump;
        held.sprint = this.sprintBefore;
        return held;
    }

    float runYaw() {
        return this.keys.yaw + this.yawRate * (float) (this.lastMoves - 1) / 2.0F;
    }

    PlayerSim restStart(PlayerSim.World world) {
        if (this.seen == null || !this.seen.onGround) {
            return null;
        }
        PlayerSim sim = placed(this.seen, null, 0, world);
        sim.sprinting = false;
        sim.airSpeed = 0.02F;
        return sim;
    }

    public TargetForecast forecast(PlayerSim.World world, int moves) {
        if (this.seen == null || this.shadow == null) {
            return null;
        }
        TargetForecast out = new TargetForecast(moves);
        if (locked() && this.keys != null) {
            PlayerSim sim = this.shadow.copy();
            apply(sim, this.seen);
            PlayerSim.Input in = this.keys.input(this.seen.sneaking);

            in.sprint = this.seen.sprinting;

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
