package atlas.sim;

/**
 * The self check as it runs tick after tick (2026-11-12): which state a move is checked from, at which point, and
 * with which packet's look. Pure, so the order of a tick can be tested -- that order is what MoveCheck had wrong
 * while this lived there, and nothing could test it.
 *
 * {@link #endOfTick} is called once a tick at the point where a server's tick ends too: the move is made and its
 * movement packet is out. The move it checks is the one made since the call before: from the state given then, to the
 * state given now, with the yaw, the sprint and the sneak the packet just sent gave the server, and with the attacks,
 * knockback and explosions that came in between ({@link #impulses}). The state given now is then kept as the start
 * of the next move; it also holds the speeds the game has just set for that move.
 *
 * A move is checked only from the tick right before it (a tick that was skipped leaves nothing to check from), only
 * between two states the simulation covers, and not when the caller says to leave it out (right after a correction
 * the start is not where the server has the player).
 */
public final class MoveAudit {

    /** What one call found. */
    public static final class Verdict {
        /** The check's answer for the move just made; null when it was not checked. */
        public SelfCheck.Result result;
        /** What became of the knockback on offer; NONE when the move was not checked. */
        public Impulses.Knockback knockback = Impulses.Knockback.NONE;
        /** The knockback that was on offer to the move, for the line about it; null for none. */
        public double[] velocity;
        /** The state the move was checked from: its speeds are the ones the move ran with. Null with no check. */
        public PlayerSim from;
    }

    private final Impulses impulses = new Impulses();
    private PlayerSim start;
    private int startTick;

    /** Where attacks sent and knockback or explosions received are told, from whatever thread sees them. */
    public Impulses impulses() {
        return this.impulses;
    }

    /** A new world, or the check starting again: no start, nothing waiting. Client thread. */
    public void reset() {
        this.start = null;
        this.impulses.clear();
    }

    /** The check is not running this tick: the next move has no start to be checked from. Client thread. */
    public void pause() {
        this.start = null;
    }

    /**
     * @param tick     the player's tick count; a move is checked only when it is one more than at the last call
     * @param now      the player as it is now, as between ticks; null where the simulation does not cover it. Kept
     *                 as the next move's start, so it must not be changed afterwards
     * @param world    the world as it is now
     * @param sentYaw  the yaw the movement packet just sent carried
     * @param sprint   the sprint state the server has, as the packets sent so far told it
     * @param sneak    the same for sneaking
     * @param leaveOut true to not check this move (and not hold what came in with it against the next)
     */
    public Verdict endOfTick(int tick, PlayerSim now, final PlayerSim.World world, final float sentYaw,
                             final boolean sprint, final boolean sneak, boolean leaveOut) {
        Verdict verdict = new Verdict();
        /* Closed every tick, checked or not, so nothing waits past the move it belongs to. */
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
        } else {
            this.impulses.skip();
        }
        this.start = now;
        this.startTick = tick;
        return verdict;
    }
}
