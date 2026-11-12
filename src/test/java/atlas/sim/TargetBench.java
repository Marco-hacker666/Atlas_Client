package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import java.util.ArrayList;
import java.util.List;

/** WORK IN PROGRESS, not a test: prints how well TargetTrack forecasts scripted players seen through an emulated server tracker, against the straight line. Run its main. To be turned into TargetTrackTest with fixed numbers, then removed. */
public class TargetBench {
    static final double REST = -0.0784000015258789;

    static final class Course implements PlayerSim.World {
        final List<AxisAlignedBB> solid = new ArrayList<AxisAlignedBB>();
        Course() { solid.add(new AxisAlignedBB(-500, -1, -500, 500, 0, 500)); }
        Course box(double a, double b, double c, double d, double e, double f) { solid.add(new AxisAlignedBB(a,b,c,d,e,f)); return this; }
        public List<AxisAlignedBB> boxes(AxisAlignedBB area) {
            List<AxisAlignedBB> out = new ArrayList<AxisAlignedBB>();
            for (AxisAlignedBB b : solid) if (b.intersectsWith(area)) out.add(b);
            return out;
        }
        public float slipperiness(int x, int y, int z) { return 0.6F; }
    }

    interface Script { void keys(int t, PlayerSim.Input in, PlayerSim game); }

    static PlayerSim standing(double x, double z) {
        PlayerSim sim = new PlayerSim();
        sim.box = new AxisAlignedBB(x - 0.3, 0.0, z - 0.3, x + 0.3, 1.8, z + 0.3);
        sim.onGround = true; sim.motionY = REST;
        return sim;
    }

    /** EntityTrackerEntry for one player and what the watching client holds. */
    static final class Tracker {
        /* From 1: the entry was made (and the player spawned for the watcher) a tick ago, not this tick. */
        int counter = 1, encX, encY, encZ, encYaw, sinceTeleport; boolean encGround, lastSprint, lastSneak;
        int cx, cy, cz; float cyaw; boolean cground;
        Tracker(PlayerSim s, float yaw) {
            encX = cx = MathHelper.floor_double(s.x() * 32.0); encY = cy = MathHelper.floor_double(s.y() * 32.0); encZ = cz = MathHelper.floor_double(s.z() * 32.0);
            encYaw = (int) Math.floor(yaw * 256.0F / 360.0F); cyaw = (float) ((byte) encYaw * 360) / 256.0F; encGround = cground = s.onGround;
        }
        /** True when a position reached the client this server tick. */
        boolean tick(PlayerSim s, float yaw, boolean airborne, boolean sneak) {
            boolean changed = s.sprinting != lastSprint || sneak != lastSneak;
            lastSprint = s.sprinting; lastSneak = sneak;
            boolean sent = false;
            if (counter % 2 == 0 || airborne || changed) {
                sinceTeleport++;
                int k = MathHelper.floor_double(s.x() * 32.0), j1 = MathHelper.floor_double(s.y() * 32.0), k1 = MathHelper.floor_double(s.z() * 32.0);
                int l1 = (int) Math.floor(yaw * 256.0F / 360.0F);
                int j2 = k - encX, k2 = j1 - encY, i = k1 - encZ;
                boolean flag = Math.abs(j2) >= 4 || Math.abs(k2) >= 4 || Math.abs(i) >= 4 || counter % 60 == 0;
                boolean flag1 = Math.abs(l1 - encYaw) >= 4;
                if (counter > 0) {
                    if (j2 >= -128 && j2 < 128 && k2 >= -128 && k2 < 128 && i >= -128 && i < 128 && sinceTeleport <= 400 && encGround == s.onGround) {
                        if (flag) { cx += j2; cy += k2; cz += i; sent = true; }
                        if (flag1) cyaw = (float) ((byte) l1 * 360) / 256.0F;
                        if (flag || flag1) cground = s.onGround;
                    } else {
                        encGround = s.onGround; sinceTeleport = 0;
                        cx = k; cy = j1; cz = k1; cyaw = (float) ((byte) l1 * 360) / 256.0F; cground = s.onGround; sent = true;
                    }
                }
                if (flag) { encX = k; encY = j1; encZ = k1; }
                if (flag1) encYaw = l1;
            }
            counter++;
            return sent;
        }
    }

    static final int[] H = {1, 2, 3, 5};
    static String VERBOSE;

    static void run(String name, PlayerSim.World world, PlayerSim start, int ticks, Script script, int[] movesPerTick) {
        // the truth
        List<PlayerSim> truth = new ArrayList<PlayerSim>();
        List<PlayerSim.Input> ins = new ArrayList<PlayerSim.Input>();
        PlayerSim game = start.copy();
        truth.add(game.copy());
        for (int t = 0; t < ticks + 8; t++) {
            PlayerSim.Input in = new PlayerSim.Input();
            script.keys(t, in, game);
            ins.add(in);
            game.tick(world, in);
            truth.add(game.copy());
        }
        Tracker tracker = new Tracker(truth.get(0), ins.get(0).yaw);
        TargetTrack track = new TargetTrack();
        double[] eng = new double[H.length], line = new double[H.length], engV = new double[H.length], lineV = new double[H.length];
        double[] engMax = new double[H.length], lineMax = new double[H.length];
        int n = 0, simulated = 0, fitsN = 0, obs = 0;
        TargetFit.Seen prev = null; int prevTick = 0, lastObsServerTick = -1;
        int move = 0; // moves the target has made
        int lastMove = 0;
        int serverTick = 0;
        while (move < ticks) {
            int k = movesPerTick == null ? 1 : movesPerTick[serverTick % movesPerTick.length];
            boolean airborne = false;
            for (int j = 0; j < k && move < ticks; j++) {
                move++;
                PlayerSim before = truth.get(move - 1), after = truth.get(move);
                airborne |= before.onGround && ins.get(move - 1).jump && after.motionY > 0.0 && !after.onGround;
            }
            PlayerSim s = truth.get(move);
            PlayerSim.Input in = ins.get(Math.max(0, move - 1));
            boolean sent = tracker.tick(s, in.yaw, airborne, in.sneak);
            serverTick++;
            if (!sent) continue;
            TargetFit.Seen seen = new TargetFit.Seen();
            seen.x = tracker.cx; seen.y = tracker.cy; seen.z = tracker.cz; seen.onGround = tracker.cground; seen.yaw = tracker.cyaw;
            seen.sprinting = s.sprinting; seen.sneaking = in.sneak;
            double hint = lastObsServerTick < 0 ? 0 : serverTick - lastObsServerTick;
            track.observe(seen, world, hint);
            obs++;
            if (track.lastFit() != null && track.lastFit().fits()) fitsN++;
            if (VERBOSE != null && name.equals(VERBOSE)) {
                TargetFit.Result r = track.lastFit();
                PlayerSim tr = truth.get(move);
                PlayerSim sh = track.shadow();
                System.out.println(String.format("  move %3d hint %.0f real %d | %s | truth %.3f %.3f %.3f g%d m(%.3f %.3f %.3f) | shadow %.3f %.3f %.3f g%d m(%.3f %.3f %.3f) | seenYaw %.1f g%d",
                        move, hint, move - lastMove,
                        r == null ? "-" : String.format("%s off %.3f n %d keys %d,%d j%d yaw %.1f imp %d", r.fits() ? "FIT " : "MISS", r.offset, r.moves, r.keys == null ? 9 : r.keys.forward, r.keys == null ? 9 : r.keys.strafe, r.keys != null && r.keys.jump ? 1 : 0, r.keys == null ? 0 : r.keys.yaw, r.impulseAt),
                        tr.x(), tr.y(), tr.z(), tr.onGround ? 1 : 0, tr.motionX, tr.motionY, tr.motionZ,
                        sh.x(), sh.y(), sh.z(), sh.onGround ? 1 : 0, sh.motionX, sh.motionY, sh.motionZ, seen.yaw, seen.onGround ? 1 : 0));
            }
            lastMove = move;
            TargetForecast f = track.forecast(world, 5);
            if (prev != null && f != null && obs > 4) {
                n++;
                if (f.simulated) simulated++;
                double dt = Math.max(1, serverTick - lastObsServerTick);
                double vx = (seen.midX() - prev.midX()) / dt, vz = (seen.midZ() - prev.midZ()) / dt;
                for (int h = 0; h < H.length; h++) {
                    PlayerSim tr = truth.get(Math.min(truth.size() - 1, move + H[h]));
                    double ex = f.x[H[h]] - tr.x(), ez = f.z[H[h]] - tr.z(), ey = f.y[H[h]] - tr.y();
                    double lx = seen.midX() + vx * H[h] - tr.x(), lz = seen.midZ() + vz * H[h] - tr.z(), ly = seen.midY() - tr.y();
                    double e = Math.sqrt(ex * ex + ez * ez), l = Math.sqrt(lx * lx + lz * lz);
                    eng[h] += e; line[h] += l; engV[h] += Math.abs(ey); lineV[h] += Math.abs(ly);
                    engMax[h] = Math.max(engMax[h], e); lineMax[h] = Math.max(lineMax[h], l);
                }
            }
            prev = seen; lastObsServerTick = serverTick;
        }
        StringBuilder sb = new StringBuilder(String.format("%-22s obs %3d fit %3d sim %3d/%3d |", name, obs, fitsN, simulated, n));
        for (int h = 0; h < H.length; h++) {
            sb.append(String.format(" h%d E %.3f L %.3f (max %.2f/%.2f) v %.3f/%.3f |", H[h], eng[h] / n, line[h] / n, engMax[h], lineMax[h], engV[h] / n, lineV[h] / n));
        }
        System.out.println(sb);
    }

    public static void main(String[] a) {
        if (a.length > 0) VERBOSE = a[0];
        Course flat = new Course();
        final float Y = -37.0F;
        run("straight sprint", flat, standing(0.3, 0.2), 120, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; }, null);
        run("straight walk", flat, standing(0.3, 0.2), 120, (t, in, g) -> { in.yaw = Y; in.forward = 1; }, null);
        run("sprint jump", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; in.jump = true; }, null);
        run("strafe A/D", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; in.strafe = (t / 8) % 2 == 0 ? 1 : -1; }, null);
        run("turning 6/tick", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y + 6.0F * t; in.forward = 1; in.sprint = true; }, null);
        run("turning 12/tick", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y + 12.0F * t; in.forward = 1; in.sprint = true; }, null);
        run("circle strafe", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y + 9.0F * t; in.forward = 0; in.strafe = 1; }, null);
        run("w-tap", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y; in.forward = t % 10 < 2 ? 0 : 1; in.sprint = true; }, null);
        run("into a wall", new Course().box(8, 0, -100, 9, 4, 100), standing(0.3, 0.2), 80, (t, in, g) -> { in.yaw = -70.0F; in.forward = 1; in.sprint = true; }, null);
        run("up a slab", new Course().box(4, 0, -100, 30, 0.5, 100), standing(0.3, 0.2), 60, (t, in, g) -> { in.yaw = -80.0F; in.forward = 1; in.sprint = true; }, null);
        run("knockback", flat, standing(0.3, 0.2), 120, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; if (t == 40 || t == 80) { g.motionX = -0.35; g.motionY = 0.36; g.motionZ = -0.2; } }, null);
        run("stop and go", flat, standing(0.3, 0.2), 200, (t, in, g) -> { in.yaw = Y + (t / 40) * 70.0F; in.forward = (t / 20) % 2 == 0 ? 1 : 0; in.sprint = true; }, null);
        run("sneak", flat, standing(0.3, 0.2), 200, (t, in, g) -> { in.yaw = Y; in.forward = 0.3F; in.sneak = true; }, null);
        run("speed cheat", flat, standing(0.3, 0.2), 120, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; g.motionX *= 1.6; g.motionZ *= 1.6; }, null);
        int[] jitter = {1, 1, 2, 0, 1, 1, 0, 2, 1, 1, 1, 2, 0, 1};
        run("sprint, jitter", flat, standing(0.3, 0.2), 120, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; }, jitter);
        run("sprint jump, jitter", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; in.jump = true; }, jitter);
        run("strafe, jitter", flat, standing(0.3, 0.2), 160, (t, in, g) -> { in.yaw = Y; in.forward = 1; in.sprint = true; in.strafe = (t / 8) % 2 == 0 ? 1 : -1; }, jitter);
    }
}
