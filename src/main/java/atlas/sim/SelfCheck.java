package atlas.sim;

/**
 * The question a re-simulating anticheat (GrimAC's way) asks of each move, asked by the client of itself
 * (2026-11-11). From where the last tick left the player, with the yaw the movement packet carried and the sprint and
 * sneak state the server was told, does any keyboard input explain where the player is now? Every combination is
 * tried: forward, none or back, left, none or right, a jump or not, an item's slowdown or not (a sneak's 0.3 on top
 * when sneaking). The smallest distance left over is the offset; 0 means some real input explains the move.
 *
 * Pure: no game. It answers for the position the client really reached, not for what the packets carried (a client
 * leaves out a move under 0.03, a server then has to allow for it), so it is the strict form of the check.
 *
 * 2026-11-12, after reading how GrimAC builds its guesses (its ideas, not its code; Grim is GPL):
 *   - sprinting, the only keys tried are forward ones (W, W A, W D): a sprint with no forward key cannot happen in
 *     1.8 (the client drops the sprint first), and a re-simulating server takes it as cheating ("omni-sprint");
 *   - a hit while sprinting takes the motion across to 0.6 before the move (EntityPlayer.attackTargetEntityWithCurrent
 *     Item), so with attacks sent, 0 up to that many slowdowns (at most 5) are tried;
 *   - a velocity packet for this player (knockback) sets the motion, an explosion adds to it; the client cannot tell
 *     which tick the server meant, so the move is tried with and without it, and the result says which fitted;
 *   - it stops at the first input that fits to a millionth of a block.
 */
public final class SelfCheck {

    /** Offsets above this are reported. */
    public static final double OFF = 0.001;

    private static final float[] AXIS = {-1.0F, 0.0F, 1.0F};
    private static final float SNEAK_SCALE = 0.3F;
    private static final float ITEM_SCALE = 0.2F;

    private SelfCheck() {
    }

    /** The input that came closest, and how close. */
    public static final class Result {
        public double offset = Double.MAX_VALUE;
        /** -1, 0 or 1 before scaling. */
        public float forward;
        public float strafe;
        public boolean jump;
        public boolean usingItem;
        /** How many hit slowdowns (0.6 across) that input needed. */
        public int attackSlows;
        /** Whether it took the knockback offered, or the explosion. */
        public boolean velocity;
        public boolean explosion;
        /** Where that input ends the tick. */
        public PlayerSim end;

        /** The keys as a short word: "W", "S A", "D jump", "none item"... */
        public String keys() {
            StringBuilder out = new StringBuilder();
            if (this.forward > 0.0F) {
                out.append("W ");
            } else if (this.forward < 0.0F) {
                out.append("S ");
            }
            if (this.strafe > 0.0F) {
                out.append("A ");
            } else if (this.strafe < 0.0F) {
                out.append("D ");
            }
            if (out.length() == 0) {
                out.append("none ");
            }
            if (this.jump) {
                out.append("jump ");
            }
            if (this.usingItem) {
                out.append("item ");
            }
            if (this.attackSlows > 0) {
                out.append("hit").append(this.attackSlows > 1 ? "x" + this.attackSlows : "").append(' ');
            }
            if (this.velocity) {
                out.append("kb ");
            }
            if (this.explosion) {
                out.append("boom ");
            }
            return out.toString().trim();
        }
    }

    /**
     * @param before   the state at the end of the last tick (copied, not changed)
     * @param yaw      the yaw this tick's movement packet carried
     * @param sprinting the sprint state the server has for this tick
     * @param sneaking the sneak state the server has for this tick
     * @param x        where the player is now: the box's middle
     * @param y        the feet
     * @param z        the box's middle
     */
    public static Result check(PlayerSim before, PlayerSim.World world, float yaw, boolean sprinting,
                               boolean sneaking, double x, double y, double z) {
        return check(before, world, yaw, sprinting, sneaking, x, y, z, null);
    }

    /** What else may have acted on the motion before this move. */
    public static final class Extra {
        /** Attacks sent since the start state was taken. */
        public int attacks;
        /** A knockback for this player received since then, blocks a tick; null for none. */
        public double[] velocity;
        /** An explosion's push received since then; null for none. */
        public double[] explosion;
        /**
         * The explosions that came after the knockback (a knockback replaces the motion, so an explosion before it is
         * lost when it is taken): used with the knockback instead of {@link #explosion} when {@link #ordered}; null
         * for none after it.
         */
        public double[] explosionAfterVelocity;
        public boolean ordered;
        /** Try only the starts that take the knockback. */
        boolean velocityOnly;
    }

    /** Whether some input with the knockback taken explains the move (whatever explains it without). */
    public static boolean fitsWithVelocity(PlayerSim before, PlayerSim.World world, float yaw, boolean sprinting,
                                           boolean sneaking, double x, double y, double z, Extra extra) {
        if (extra == null || extra.velocity == null) {
            return false;
        }
        Extra only = new Extra();
        only.attacks = extra.attacks;
        only.velocity = extra.velocity;
        only.explosion = extra.explosion;
        only.explosionAfterVelocity = extra.explosionAfterVelocity;
        only.ordered = extra.ordered;
        only.velocityOnly = true;
        return check(before, world, yaw, sprinting, sneaking, x, y, z, only).offset <= OFF;
    }

    private static final int MAX_ATTACK_SLOWS = 5;
    private static final double FITS = 1.0E-6;
    private static final float[] FORWARD_ONLY = {1.0F};

    public static Result check(PlayerSim before, PlayerSim.World world, float yaw, boolean sprinting,
                               boolean sneaking, double x, double y, double z, Extra extra) {
        Result best = new Result();
        int maxSlows = extra == null ? 0 : Math.min(MAX_ATTACK_SLOWS, Math.max(0, extra.attacks));
        double[] velocity = extra == null ? null : extra.velocity;
        double[] explosion = extra == null ? null : extra.explosion;
        float[] forwards = sprinting ? FORWARD_ONLY : AXIS;
        for (int start = 0; start < 4; start++) {
            boolean useVelocity = (start & 1) != 0;
            boolean useExplosion = (start & 2) != 0;
            if (useVelocity && velocity == null || useExplosion && explosion == null
                    || extra != null && extra.velocityOnly && !useVelocity) {
                continue;
            }
            /* With the knockback, only the explosions after it count (review, PR #38). */
            double[] push = !useExplosion ? null
                    : useVelocity && extra.ordered ? extra.explosionAfterVelocity : explosion;
            if (useExplosion && push == null) {
                continue;
            }
            for (int slows = 0; slows <= maxSlows; slows++) {
                if (tryInputs(best, before, world, yaw, sprinting, sneaking, x, y, z, forwards, useVelocity ? velocity : null,
                        push, slows)) {
                    return best;
                }
            }
        }
        return best;
    }

    /** Every key, jump and item state from one start; true once one fits (best then holds it). */
    private static boolean tryInputs(Result best, PlayerSim before, PlayerSim.World world, float yaw, boolean sprinting,
                                     boolean sneaking, double x, double y, double z, float[] forwards, double[] velocity,
                                     double[] explosion, int slows) {
        for (int jump = 0; jump < 2; jump++) {
            for (int item = 0; item < 2; item++) {
                float scale = (sneaking ? SNEAK_SCALE : 1.0F) * (item == 1 ? ITEM_SCALE : 1.0F);
                for (float forward : forwards) {
                    for (float strafe : AXIS) {
                        PlayerSim sim = before.copy();
                        sim.sprinting = sprinting;
                        if (velocity != null) {
                            sim.motionX = velocity[0];
                            sim.motionY = velocity[1];
                            sim.motionZ = velocity[2];
                        }
                        if (explosion != null) {
                            sim.motionX += explosion[0];
                            sim.motionY += explosion[1];
                            sim.motionZ += explosion[2];
                        }
                        for (int i = 0; i < slows; i++) {
                            sim.motionX *= 0.6;
                            sim.motionZ *= 0.6;
                        }
                        PlayerSim.Input in = new PlayerSim.Input();
                        in.yaw = yaw;
                        in.forward = forward * scale;
                        in.strafe = strafe * scale;
                        in.jump = jump == 1;
                        in.sneak = sneaking;
                        in.sprint = sprinting;
                        sim.move(world, in);
                        double dx = sim.x() - x;
                        double dy = sim.y() - y;
                        double dz = sim.z() - z;
                        double offset = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (offset < best.offset) {
                            best.offset = offset;
                            best.forward = forward;
                            best.strafe = strafe;
                            best.jump = jump == 1;
                            best.usingItem = item == 1;
                            best.attackSlows = slows;
                            best.velocity = velocity != null;
                            best.explosion = explosion != null;
                            best.end = sim;
                            if (offset < FITS) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
