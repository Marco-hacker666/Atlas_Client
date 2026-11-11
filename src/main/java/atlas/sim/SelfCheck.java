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
        Result best = new Result();
        for (int jump = 0; jump < 2; jump++) {
            for (int item = 0; item < 2; item++) {
                float scale = (sneaking ? SNEAK_SCALE : 1.0F) * (item == 1 ? ITEM_SCALE : 1.0F);
                for (float forward : AXIS) {
                    for (float strafe : AXIS) {
                        PlayerSim sim = before.copy();
                        sim.sprinting = sprinting;
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
                            best.end = sim;
                        }
                    }
                }
            }
        }
        return best;
    }
}
