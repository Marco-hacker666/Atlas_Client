package atlas.sim;

public final class MoveAudit {
    public static final class Verdict {
        public SelfCheck.Result result;

        public Impulses.Knockback knockback = Impulses.Knockback.NONE;

        public double[] velocity;

        public PlayerSim from;

        public SelfCheck.Extra extra;
    }

    private final Impulses impulses = new Impulses();
    private PlayerSim start;
    private int startTick;

    public Impulses impulses() {
        return this.impulses;
    }

    public void reset() {
        this.start = null;
        this.impulses.clear();
    }

    public void pause() {
        this.start = null;
    }

    public Verdict endOfTick(int tick, PlayerSim now, final PlayerSim.World world, final float sentYaw,
                             final boolean sprint, final boolean sneak, boolean leaveOut) {
        Verdict verdict = new Verdict();

        final SelfCheck.Extra extra = this.impulses.close();
        final PlayerSim before = this.start;
        if (before != null && now != null && tick - this.startTick == 1 && !leaveOut) {
            final double x = now.x();
            final double y = now.y();
            final double z = now.z();
            verdict.result = SelfCheck.check(before, world, sentYaw, sprint, sneak, x, y, z, extra);
            verdict.knockback = this.impulses.settle(verdict.result,
                    () -> SelfCheck.fitsWithVelocity(before, world, sentYaw, sprint, sneak, x, y, z, extra));
            verdict.velocity = extra.velocity;
            verdict.from = before;
            verdict.extra = extra;
        } else {
            this.impulses.skip();
        }
        this.start = now;
        this.startTick = tick;
        return verdict;
    }
}
