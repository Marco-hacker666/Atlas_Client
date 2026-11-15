package atlas.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where another player can get to if it changes what it is doing (2026-11-16, owner: "some only move once they see
 * the arrow leave, and then it misses"). A forecast says where a player goes if it carries on; nobody can know what
 * it will do instead, but the simulation knows how far each thing it could do takes it: the ways out of an arrow's
 * path are few, and each is run here as the game would run it, walls, friction and the jump's push included.
 *
 * Every path goes on as the forecast does for {@code after} moves (the time the player needs to see the shot and
 * react) and then by one of {@link #WAYS}. A way never sneaks or holds an item back: the player is taken to let go
 * of both to get away, which is the faster of the two.
 */
public final class TargetDodges {

    /** One way to get out of the way. */
    public static final class Way {
        public final String name;
        /** The keys, -1 to 1; ignored with {@link #keep}. */
        final int forward;
        final int strafe;
        /** Degrees the player turns before it goes; negative is to its left. */
        final float turn;
        final boolean sprint;
        final boolean jump;
        /** The keys and the sprint stay as they are; only the jump is added. */
        final boolean keep;

        Way(String name, int forward, int strafe, float turn, boolean sprint, boolean jump, boolean keep) {
            this.name = name;
            this.forward = forward;
            this.strafe = strafe;
            this.turn = turn;
            this.sprint = sprint;
            this.jump = jump;
            this.keep = keep;
        }
    }

    /**
     * Letting go of the keys, a step to either side, back, a sprint forward, a turn to either side and a sprint, a
     * jump as it goes, and the sprint-jumps, which are the farthest a player gets in a few ticks.
     */
    public static final Way[] WAYS = {
            new Way("stop", 0, 0, 0.0F, false, false, false),
            new Way("left", 0, 1, 0.0F, false, false, false),
            new Way("right", 0, -1, 0.0F, false, false, false),
            new Way("back", -1, 0, 0.0F, false, false, false),
            new Way("forward", 1, 0, 0.0F, true, false, false),
            new Way("turn left", 1, 0, -90.0F, true, false, false),
            new Way("turn right", 1, 0, 90.0F, true, false, false),
            new Way("jump", 0, 0, 0.0F, false, true, true),
            new Way("forward jump", 1, 0, 0.0F, true, true, false),
            new Way("turn left jump", 1, 0, -90.0F, true, true, false),
            new Way("turn right jump", 1, 0, 90.0F, true, true, false)};

    private TargetDodges() {
    }

    /**
     * A path for each of {@link #WAYS}, in that order, each {@code moves} moves from the last position the server
     * sent, as {@link TargetTrack#forecast} counts them. Empty when the track cannot be run on (not locked).
     *
     * @param after the moves the player goes on as forecast before it changes
     */
    public static List<TargetForecast> paths(TargetTrack track, PlayerSim.World world, int moves, int after) {
        PlayerSim start = track == null ? null : track.runStart();
        if (start == null || moves < 1) {
            return Collections.emptyList();
        }
        PlayerSim.Input before = track.runInput();
        /* Keys let go a moment ago: the forecast is the middle of a stop and a tap, and so is each way here, so
           that every path is the forecast until the player reacts (review, PR #43). */
        PlayerSim.Input tap = track.runTapInput();
        List<TargetForecast> out = new ArrayList<TargetForecast>();
        for (Way way : WAYS) {
            TargetForecast path = run(start, world, moves, after, before, track.runYaw(), track.yawRate(), way);
            if (tap != null) {
                TargetForecast tapped = run(start, world, moves, after, tap, track.runYaw(), track.yawRate(), way);
                for (int i = 1; i <= moves; i++) {
                    path.x[i] = (path.x[i] + tapped.x[i]) / 2.0;
                    path.z[i] = (path.z[i] + tapped.z[i]) / 2.0;
                }
            }
            out.add(path);
        }
        return out;
    }

    /**
     * The same for a player that has sent no position for a while, which means it stands still: from rest in the
     * cell the server last named, facing {@code yaw}; index 0 is now. Empty before the first position, or when the
     * player was not on ground.
     */
    public static List<TargetForecast> pathsAtRest(TargetTrack track, PlayerSim.World world, int moves, int after,
                                                   float yaw) {
        PlayerSim start = track == null ? null : track.restStart(world);
        if (start == null || moves < 1) {
            return Collections.emptyList();
        }
        PlayerSim.Input before = new PlayerSim.Input();
        List<TargetForecast> out = new ArrayList<TargetForecast>();
        for (Way way : WAYS) {
            out.add(run(start, world, moves, after, before, yaw, 0.0F, way));
        }
        return out;
    }

    /**
     * One path: {@code before} held, turning on by {@code yawRate} a move, for {@code after} moves, then the way.
     */
    static TargetForecast run(PlayerSim start, PlayerSim.World world, int moves, int after, PlayerSim.Input before,
                              float yaw, float yawRate, Way way) {
        PlayerSim sim = start.copy();
        TargetForecast out = new TargetForecast(moves);
        out.simulated = true;
        out.confidence = 1.0;
        set(out, 0, sim);
        after = Math.max(0, after);
        /* The yaw it has when it changes; it turns once and then goes straight. */
        float changedYaw = yaw + yawRate * (float) Math.min(after, moves) + way.turn;
        PlayerSim.Input in = new PlayerSim.Input();
        for (int i = 1; i <= moves; i++) {
            if (i <= after) {
                in.forward = before.forward;
                in.strafe = before.strafe;
                in.jump = before.jump;
                in.sneak = before.sneak;
                in.sprint = before.sprint;
                in.yaw = yaw + yawRate * (float) i;
            } else if (way.keep) {
                in.forward = before.forward;
                in.strafe = before.strafe;
                in.jump = before.jump || way.jump;
                in.sneak = before.sneak;
                in.sprint = before.sprint;
                in.yaw = changedYaw;
            } else {
                in.forward = (float) way.forward;
                in.strafe = (float) way.strafe;
                in.jump = way.jump;
                in.sneak = false;
                in.sprint = way.sprint;
                in.yaw = changedYaw;
            }
            sim.tick(world, in);
            set(out, i, sim);
        }
        return out;
    }

    private static void set(TargetForecast out, int index, PlayerSim sim) {
        out.x[index] = sim.x();
        out.y[index] = sim.y();
        out.z[index] = sim.z();
        out.onGround[index] = sim.onGround;
    }
}
