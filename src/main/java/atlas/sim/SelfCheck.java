package atlas.sim;

// Grim is GPL)

public final class SelfCheck {
    public static final double OFF = 0.001;

    private static final float[] AXIS = {-1.0F, 0.0F, 1.0F};
    private static final float SNEAK_SCALE = 0.3F;
    private static final float ITEM_SCALE = 0.2F;

    private SelfCheck() {
    }

    public static final class Result {
        public double offset = Double.MAX_VALUE;

        public float forward;
        public float strafe;
        public boolean jump;
        public boolean usingItem;

        public int attackSlows;

        public boolean velocity;
        public boolean explosion;

        public PlayerSim end;

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

    public static Result check(PlayerSim before, PlayerSim.World world, float yaw, boolean sprinting,
                               boolean sneaking, double x, double y, double z) {
        return check(before, world, yaw, sprinting, sneaking, x, y, z, null);
    }

    public static final class Extra {
        public int attacks;

        public double[] velocity;

        public double[] explosion;

        public double[] explosionAfterVelocity;
        public boolean ordered;

        boolean velocityOnly;
    }

    public static double[] lookErrors(PlayerSim before, PlayerSim.World world, Result atSent, float sentYaw,
                                      float cameraYaw, boolean sprinting, boolean sneaking, double x, double y,
                                      double z, Extra extra) {
        float apart = sentYaw - cameraYaw;
        apart -= 360.0F * (float) Math.floor((apart + 180.0F) / 360.0F);
        double camera = Math.abs(apart) < 0.01F ? atSent.offset
                : check(before, world, cameraYaw, sprinting, sneaking, x, y, z, extra).offset;
        return new double[]{atSent.offset, camera};
    }

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

                        sim.jumpTicks = 0;
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
