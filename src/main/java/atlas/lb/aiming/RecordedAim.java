package atlas.lb.aiming;

import java.util.Random;

public final class RecordedAim extends AngleSmooth {
    public float follow = 4.0F;

    public float strength = 1.0F;

    public boolean useAuraData = true;
    public AngleSmooth fallback = new Tracking();

    private volatile AimLibrary library = new AimLibrary();
    private int run = -1;
    private final Random random = new Random();

    public void setLibrary(AimLibrary library) {
        this.library = library == null ? new AimLibrary() : library;
        this.run = -1;
    }

    public void addLive(String line) {
        this.library.addLive(line);
    }

    public AimLibrary library() {
        return this.library;
    }

    @Override
    public Rotation process(SmoothContext context, Rotation current, Rotation target) {
        AimLibrary lib = this.library;
        lib.includeAura = this.useAuraData;
        float[] error = current.deltaTo(target);
        int pick = continued(lib, error);
        if (pick < 0) {
            pick = lib.nearest(error[0], error[1], this.random);
        }
        this.run = pick;
        if (pick < 0) {
            return this.fallback.process(context, current, target);
        }
        float[] turn = turnFor(lib.get(pick), error, this.strength);
        return new Rotation(current.yaw + turn[0], Rotation.clamp(current.pitch + turn[1], -90.0F, 90.0F));
    }

    private int continued(AimLibrary lib, float[] error) {
        if (this.run < 0 || this.run >= lib.size()) {
            return -1;
        }
        int next = lib.get(this.run).next;
        if (next < 0) {
            return -1;
        }
        AimLibrary.Sample s = lib.get(next);
        if (s.aura && !lib.includeAura) {
            return -1;
        }
        float dy = Math.abs(s.errYaw) - Math.abs(error[0]);
        float dp = s.errPitch - error[1];
        return dy * dy + dp * dp <= this.follow * this.follow ? next : -1;
    }

    static float[] turnFor(AimLibrary.Sample s, float[] error, float strength) {
        boolean mirror = Math.abs(s.errYaw) > 0.3F && Math.abs(error[0]) > 0.3F
                && Math.signum(s.errYaw) != Math.signum(error[0]);
        float yaw = (mirror ? -s.turnYaw : s.turnYaw) * scale(s.errYaw, error[0]);
        float pitch = s.turnPitch * scale(s.errPitch, error[1]);
        float k = Rotation.clamp(strength, 0.0F, 1.0F);
        return new float[]{yaw * k, pitch * k};
    }

    private static float scale(float recorded, float now) {
        if (Math.abs(recorded) < 1.0F) {
            return 1.0F;
        }
        return Rotation.clamp(Math.abs(now) / Math.abs(recorded), 0.6F, 1.6F);
    }

    @Override
    public int calculateTicks(SmoothContext context, Rotation current, Rotation target) {
        return this.fallback.calculateTicks(context, current, target);
    }
}
