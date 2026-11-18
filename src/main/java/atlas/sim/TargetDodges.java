package atlas.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TargetDodges {
    public static final class Way {
        public final String name;

        final int forward;
        final int strafe;

        final float turn;
        final boolean sprint;
        final boolean jump;

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

    public static List<TargetForecast> paths(TargetTrack track, PlayerSim.World world, int moves, int after) {
        PlayerSim start = track == null ? null : track.runStart();
        if (start == null || moves < 1) {
            return Collections.emptyList();
        }
        PlayerSim.Input before = track.runInput();

        PlayerSim.Input tap = track.runTapInput();
        List<TargetForecast> out = runAll(start, world, moves, after, before, track.runYaw(), track.yawRate());
        if (tap != null) {
            List<TargetForecast> tapped = runAll(start, world, moves, after, tap, track.runYaw(), track.yawRate());
            for (int w = 0; w < out.size(); w++) {
                TargetForecast path = out.get(w);
                for (int i = 1; i <= moves; i++) {
                    path.x[i] = (path.x[i] + tapped.get(w).x[i]) / 2.0;
                    path.z[i] = (path.z[i] + tapped.get(w).z[i]) / 2.0;
                }
            }
        }
        return out;
    }

    public static List<TargetForecast> pathsAtRest(TargetTrack track, PlayerSim.World world, int moves, int after,
                                                   float yaw) {
        PlayerSim start = track == null ? null : track.restStart(world);
        if (start == null || moves < 1) {
            return Collections.emptyList();
        }
        return runAll(start, world, moves, after, new PlayerSim.Input(), yaw, 0.0F);
    }

    static List<TargetForecast> runAll(PlayerSim start, PlayerSim.World world, int moves, int after,
                                       PlayerSim.Input before, float yaw, float yawRate) {
        after = Math.max(0, after);
        int shared = Math.min(after, moves);
        TargetForecast prefix = new TargetForecast(moves);
        PlayerSim sim = start.copy();
        set(prefix, 0, sim);
        PlayerSim.Input in = new PlayerSim.Input();
        for (int i = 1; i <= shared; i++) {
            hold(in, before, yaw + yawRate * (float) i);
            sim.tick(world, in);
            set(prefix, i, sim);
        }
        List<TargetForecast> out = new ArrayList<TargetForecast>();
        for (Way way : WAYS) {
            TargetForecast path = new TargetForecast(moves);
            path.simulated = true;
            path.confidence = 1.0;
            for (int i = 0; i <= shared; i++) {
                path.x[i] = prefix.x[i];
                path.y[i] = prefix.y[i];
                path.z[i] = prefix.z[i];
                path.onGround[i] = prefix.onGround[i];
            }
            PlayerSim branch = sim.copy();
            float changedYaw = yaw + yawRate * (float) shared + way.turn;
            for (int i = shared + 1; i <= moves; i++) {
                change(in, before, way, changedYaw);
                branch.tick(world, in);
                set(path, i, branch);
            }
            out.add(path);
        }
        return out;
    }

    private static void hold(PlayerSim.Input in, PlayerSim.Input before, float yaw) {
        in.forward = before.forward;
        in.strafe = before.strafe;
        in.jump = before.jump;
        in.sneak = before.sneak;
        in.sprint = before.sprint;
        in.yaw = yaw;
    }

    private static void change(PlayerSim.Input in, PlayerSim.Input before, Way way, float yaw) {
        if (way.keep) {
            hold(in, before, yaw);
            in.jump = before.jump || way.jump;
        } else {
            in.forward = (float) way.forward;
            in.strafe = (float) way.strafe;
            in.jump = way.jump;
            in.sneak = false;
            in.sprint = way.sprint;
            in.yaw = yaw;
        }
    }

    static TargetForecast run(PlayerSim start, PlayerSim.World world, int moves, int after, PlayerSim.Input before,
                              float yaw, float yawRate, Way way) {
        PlayerSim sim = start.copy();
        TargetForecast out = new TargetForecast(moves);
        out.simulated = true;
        out.confidence = 1.0;
        set(out, 0, sim);
        after = Math.max(0, after);

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
