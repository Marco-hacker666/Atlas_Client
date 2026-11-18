package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

import java.util.List;

public final class PlayerSim {
    public interface World {
        List<AxisAlignedBB> boxes(AxisAlignedBB area);

        float slipperiness(int x, int y, int z);

        default boolean water(AxisAlignedBB area, double[] flow) {
            return false;
        }

        default boolean lava(AxisAlignedBB area) {
            return false;
        }

        default boolean liquid(AxisAlignedBB area) {
            return false;
        }

        default boolean climbable(int x, int y, int z) {
            return false;
        }

        default boolean slime(int x, int y, int z) {
            return false;
        }

        default int touching(AxisAlignedBB area, Touch kind) {
            return 0;
        }

        default boolean solid(int x, int y, int z) {
            return false;
        }

        default boolean loaded(int x, int z) {
            return true;
        }
    }

    public enum Stage {
        START,

        INPUT,

        MOVE
    }

    public enum Touch {
        WEB,

        SOUL_SAND
    }

    public static final class Input {
        public float yaw;

        public float forward;
        public float strafe;
        public boolean jump;
        public boolean sneak;

        public boolean sprint;

        public Input copy() {
            Input in = new Input();
            in.yaw = this.yaw;
            in.forward = this.forward;
            in.strafe = this.strafe;
            in.jump = this.jump;
            in.sneak = this.sneak;
            in.sprint = this.sprint;
            return in;
        }

        public Input released() {
            Input in = copy();
            in.forward = 0.0F;
            in.strafe = 0.0F;
            in.jump = false;
            return in;
        }
    }

    private static final float SPRINT_FORWARD = 0.8F;
    private static final double SPRINT_BOOST = (double) 0.3F;
    private static final float AIR_SPEED = 0.02F;
    private static final int SPRINT_TICKS = 600;

    public AxisAlignedBB box;
    public double motionX;
    public double motionY;
    public double motionZ;
    public boolean onGround;
    public boolean sprinting;
    public boolean collidedHorizontally;

    public boolean inWater;

    public boolean inWeb;

    public float depthStrider;

    public Stage stage = Stage.START;

    public int jumpTicks;

    public float moveSpeed = 0.1F;

    public float airSpeed = AIR_SPEED;

    public double walkSpeed = (double) 0.1F;

    public float jumpBoost;
    public float stepHeight = 0.6F;

    public float width = 0.6F;
    public float height = 1.8F;

    public int sprintTicksLeft;

    public boolean canSprint = true;

    public boolean canStartSprint = true;

    public PlayerSim copy() {
        PlayerSim s = new PlayerSim();
        s.box = this.box;
        s.motionX = this.motionX;
        s.motionY = this.motionY;
        s.motionZ = this.motionZ;
        s.onGround = this.onGround;
        s.sprinting = this.sprinting;
        s.collidedHorizontally = this.collidedHorizontally;
        s.jumpTicks = this.jumpTicks;
        s.moveSpeed = this.moveSpeed;
        s.airSpeed = this.airSpeed;
        s.walkSpeed = this.walkSpeed;
        s.jumpBoost = this.jumpBoost;
        s.stepHeight = this.stepHeight;
        s.canSprint = this.canSprint;
        s.canStartSprint = this.canStartSprint;
        s.inWater = this.inWater;
        s.inWeb = this.inWeb;
        s.depthStrider = this.depthStrider;
        s.stage = this.stage;
        s.width = this.width;
        s.height = this.height;
        s.sprintTicksLeft = this.sprintTicksLeft;
        return s;
    }

    public double x() {
        return (this.box.minX + this.box.maxX) / 2.0;
    }

    public double y() {
        return this.box.minY;
    }

    public double z() {
        return (this.box.minZ + this.box.maxZ) / 2.0;
    }

    public double speed() {
        return Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
    }

    public void tick(World world, Input in) {
        run(world, in, true);
    }

    public void move(World world, Input in) {
        run(world, in, false);
    }

    private void run(World world, Input in, boolean sprintRule) {
        Stage from = this.stage;
        this.stage = Stage.START;
        if (from == Stage.START) {
            waterStep(world);
        }
        if (from != Stage.MOVE) {
            pushOutOfBlocks(world);
            if (sprintRule) {
                if (from == Stage.START) {
                    sprintRule(in);
                } else {
                    sprintKeys(in);
                }
            }
        }
        livingMove(world, in);
    }

    public void sprintRule(Input in) {
        if (this.sprintTicksLeft > 0 && --this.sprintTicksLeft == 0) {
            this.sprinting = false;
        }
        sprintKeys(in);
    }

    private void sprintKeys(Input in) {
        if (!this.sprinting && in.sprint && in.forward >= SPRINT_FORWARD && this.canSprint && this.canStartSprint) {
            this.sprinting = true;
            this.sprintTicksLeft = SPRINT_TICKS;
        }
        if (this.sprinting && (in.forward < SPRINT_FORWARD || this.collidedHorizontally || !this.canSprint)) {
            this.sprinting = false;
            this.sprintTicksLeft = 0;
        }
    }

    private void waterStep(World world) {
        double[] flow = new double[3];
        this.inWater = world.water(this.box.expand(0.0, -0.4000000059604645, 0.0).contract(0.001, 0.001, 0.001), flow);
        double length = (double) MathHelper.sqrt_double(flow[0] * flow[0] + flow[1] * flow[1] + flow[2] * flow[2]);
        if (this.inWater && length >= 1.0E-4) {
            this.motionX += flow[0] / length * 0.014;
            this.motionY += flow[1] / length * 0.014;
            this.motionZ += flow[2] / length * 0.014;
        }
    }

    private void pushOutOfBlocks(World world) {
        double out = (double) this.width * 0.35;
        double y = this.box.minY + 0.5;
        pushOut(world, x() - out, y, z() + out);
        pushOut(world, x() - out, y, z() - out);
        pushOut(world, x() + out, y, z() - out);
        pushOut(world, x() + out, y, z() + out);
    }

    private void pushOut(World world, double x, double y, double z) {
        int cellX = MathHelper.floor_double(x);
        int cellY = MathHelper.floor_double(y);
        int cellZ = MathHelper.floor_double(z);
        if (open(world, cellX, cellY, cellZ)) {
            return;
        }
        double inX = x - (double) cellX;
        double inZ = z - (double) cellZ;
        int side = -1;
        double nearest = 9999.0;
        if (open(world, cellX - 1, cellY, cellZ) && inX < nearest) {
            nearest = inX;
            side = 0;
        }
        if (open(world, cellX + 1, cellY, cellZ) && 1.0 - inX < nearest) {
            nearest = 1.0 - inX;
            side = 1;
        }
        if (open(world, cellX, cellY, cellZ - 1) && inZ < nearest) {
            nearest = inZ;
            side = 4;
        }
        if (open(world, cellX, cellY, cellZ + 1) && 1.0 - inZ < nearest) {
            side = 5;
        }
        float push = 0.1F;
        if (side == 0) {
            this.motionX = (double) (-push);
        } else if (side == 1) {
            this.motionX = (double) push;
        } else if (side == 4) {
            this.motionZ = (double) (-push);
        } else if (side == 5) {
            this.motionZ = (double) push;
        }
    }

    private boolean open(World world, int x, int y, int z) {
        int tall = Math.max((int) Math.ceil(this.height), 1);
        for (int up = 0; up < tall; up++) {
            if (world.solid(x, y + up, z)) {
                return false;
            }
        }
        return true;
    }

    private void livingMove(World world, Input in) {
        if (this.jumpTicks > 0) {
            this.jumpTicks--;
        }
        if (Math.abs(this.motionX) < 0.005) {
            this.motionX = 0.0;
        }
        if (Math.abs(this.motionY) < 0.005) {
            this.motionY = 0.0;
        }
        if (Math.abs(this.motionZ) < 0.005) {
            this.motionZ = 0.0;
        }
        if (in.jump) {
            if (this.inWater || inLava(world)) {
                this.motionY += 0.03999999910593033;
            } else if (this.onGround && this.jumpTicks == 0) {
                jump(in.yaw);
                this.jumpTicks = 10;
            }
        } else {
            this.jumpTicks = 0;
        }
        this.moveSpeed = groundSpeed();
        moveWithHeading(world, in.strafe * 0.98F, in.forward * 0.98F, in.yaw, in.sneak);

        this.airSpeed = AIR_SPEED;
        if (this.sprinting) {
            this.airSpeed = (float) ((double) this.airSpeed + (double) AIR_SPEED * 0.3);
        }
        this.moveSpeed = groundSpeed();
    }

    private float groundSpeed() {
        return (float) (this.sprinting ? this.walkSpeed * (1.0 + SPRINT_BOOST) : this.walkSpeed);
    }

    private void jump(float yaw) {
        this.motionY = (double) 0.42F;
        if (this.jumpBoost > 0.0F) {
            this.motionY += (double) this.jumpBoost;
        }
        if (this.sprinting) {
            float radians = yaw * 0.017453292F;
            this.motionX -= (double) (MathHelper.sin(radians) * 0.2F);
            this.motionZ += (double) (MathHelper.cos(radians) * 0.2F);
        }
    }

    private boolean inLava(World world) {
        return world.lava(this.box.expand(-0.10000000149011612, -0.4000000059604645, -0.10000000149011612));
    }

    private void moveWithHeading(World world, float strafe, float forward, float yaw, boolean sneak) {
        if (this.inWater) {
            double startY = this.box.minY;
            float drag = 0.8F;
            float speed = 0.02F;
            float strider = Math.min(3.0F, this.depthStrider);
            if (!this.onGround) {
                strider *= 0.5F;
            }
            if (strider > 0.0F) {
                drag += (0.54600006F - drag) * strider / 3.0F;
                speed += (this.moveSpeed - speed) * strider / 3.0F;
            }
            double[] push = push(strafe, forward, speed, yaw);
            this.motionX += push[0];
            this.motionZ += push[1];
            move(world, this.motionX, this.motionY, this.motionZ, sneak);
            this.motionX *= (double) drag;
            this.motionY *= 0.800000011920929;
            this.motionZ *= (double) drag;
            this.motionY -= 0.02;
            climbOut(world, startY);
            return;
        }
        if (inLava(world)) {
            double startY = this.box.minY;
            double[] push = push(strafe, forward, 0.02F, yaw);
            this.motionX += push[0];
            this.motionZ += push[1];
            move(world, this.motionX, this.motionY, this.motionZ, sneak);
            this.motionX *= 0.5;
            this.motionY *= 0.5;
            this.motionZ *= 0.5;
            this.motionY -= 0.02;
            climbOut(world, startY);
            return;
        }
        landMove(world, strafe, forward, yaw, sneak);
    }

    private void climbOut(World world, double startY) {
        if (!this.collidedHorizontally) {
            return;
        }
        AxisAlignedBB above = this.box.offset(this.motionX, this.motionY + 0.6000000238418579 - this.box.minY + startY,
                this.motionZ);
        if (world.boxes(above).isEmpty() && !world.liquid(above)) {
            this.motionY = 0.30000001192092896;
        }
    }

    private boolean onLadder(World world) {
        return world.climbable(MathHelper.floor_double(x()), MathHelper.floor_double(this.box.minY),
                MathHelper.floor_double(z()));
    }

    private void landMove(World world, float strafe, float forward, float yaw, boolean sneak) {
        float friction = 0.91F;
        if (this.onGround) {
            friction = world.slipperiness(MathHelper.floor_double(x()), MathHelper.floor_double(this.box.minY) - 1,
                    MathHelper.floor_double(z())) * 0.91F;
        }
        float grip = 0.16277136F / (friction * friction * friction);
        float speed = this.onGround ? this.moveSpeed * grip : this.airSpeed;
        double[] push = push(strafe, forward, speed, yaw);
        this.motionX += push[0];
        this.motionZ += push[1];
        if (onLadder(world)) {
            this.motionX = MathHelper.clamp_double(this.motionX, (double) -0.15F, (double) 0.15F);
            this.motionZ = MathHelper.clamp_double(this.motionZ, (double) -0.15F, (double) 0.15F);
            if (this.motionY < -0.15) {
                this.motionY = -0.15;
            }
            if (sneak && this.motionY < 0.0) {
                this.motionY = 0.0;
            }
        }
        move(world, this.motionX, this.motionY, this.motionZ, sneak);
        if (this.collidedHorizontally && onLadder(world)) {
            this.motionY = 0.2;
        }

        if (!world.loaded((int) x(), (int) z())) {
            this.motionY = this.box.minY > 0.0 ? -0.1 : 0.0;
        } else {
            this.motionY -= 0.08;
        }
        this.motionY *= 0.9800000190734863;
        this.motionX *= (double) friction;
        this.motionZ *= (double) friction;
    }

    public static double[] push(float strafe, float forward, float speed, float yaw) {
        float size = strafe * strafe + forward * forward;
        if (size < 1.0E-4F) {
            return new double[]{0.0, 0.0};
        }
        size = MathHelper.sqrt_float(size);
        if (size < 1.0F) {
            size = 1.0F;
        }
        size = speed / size;
        strafe *= size;
        forward *= size;
        float sin = MathHelper.sin(yaw * (float) Math.PI / 180.0F);
        float cos = MathHelper.cos(yaw * (float) Math.PI / 180.0F);
        return new double[]{(double) (strafe * cos - forward * sin), (double) (forward * cos + strafe * sin)};
    }

    private void move(World world, double x, double y, double z, boolean sneak) {
        if (this.inWeb) {
            this.inWeb = false;
            x *= 0.25;
            y *= 0.05000000074505806;
            z *= 0.25;
            this.motionX = 0.0;
            this.motionY = 0.0;
            this.motionZ = 0.0;
        }
        boolean sneakEdge = this.onGround && sneak;
        double wantedX = x;
        double wantedY = y;
        double wantedZ = z;
        if (this.onGround && sneak) {
            double cut = 0.05;
            while (x != 0.0 && world.boxes(this.box.offset(x, -1.0, 0.0)).isEmpty()) {
                x = cutBack(x, cut);
                wantedX = x;
            }
            while (z != 0.0 && world.boxes(this.box.offset(0.0, -1.0, z)).isEmpty()) {
                z = cutBack(z, cut);
                wantedZ = z;
            }
            while (x != 0.0 && z != 0.0 && world.boxes(this.box.offset(x, -1.0, z)).isEmpty()) {
                x = cutBack(x, cut);
                wantedX = x;
                z = cutBack(z, cut);
                wantedZ = z;
            }
        }
        List<AxisAlignedBB> boxes = world.boxes(this.box.addCoord(x, y, z));
        AxisAlignedBB start = this.box;
        for (AxisAlignedBB other : boxes) {
            y = other.calculateYOffset(this.box, y);
        }
        this.box = this.box.offset(0.0, y, 0.0);
        boolean mayStep = this.onGround || wantedY != y && wantedY < 0.0;
        for (AxisAlignedBB other : boxes) {
            x = other.calculateXOffset(this.box, x);
        }
        this.box = this.box.offset(x, 0.0, 0.0);
        for (AxisAlignedBB other : boxes) {
            z = other.calculateZOffset(this.box, z);
        }
        this.box = this.box.offset(0.0, 0.0, z);
        if (this.stepHeight > 0.0F && mayStep && (wantedX != x || wantedZ != z)) {
            double flatX = x;
            double flatY = y;
            double flatZ = z;
            AxisAlignedBB flat = this.box;
            this.box = start;
            y = (double) this.stepHeight;
            List<AxisAlignedBB> stepBoxes = world.boxes(this.box.addCoord(wantedX, y, wantedZ));

            AxisAlignedBB swept = this.box;
            AxisAlignedBB sweep = swept.addCoord(wantedX, 0.0, wantedZ);
            double sweptY = y;
            for (AxisAlignedBB other : stepBoxes) {
                sweptY = other.calculateYOffset(sweep, sweptY);
            }
            swept = swept.offset(0.0, sweptY, 0.0);
            double sweptX = wantedX;
            for (AxisAlignedBB other : stepBoxes) {
                sweptX = other.calculateXOffset(swept, sweptX);
            }
            swept = swept.offset(sweptX, 0.0, 0.0);
            double sweptZ = wantedZ;
            for (AxisAlignedBB other : stepBoxes) {
                sweptZ = other.calculateZOffset(swept, sweptZ);
            }
            swept = swept.offset(0.0, 0.0, sweptZ);

            AxisAlignedBB own = this.box;
            double ownY = y;
            for (AxisAlignedBB other : stepBoxes) {
                ownY = other.calculateYOffset(own, ownY);
            }
            own = own.offset(0.0, ownY, 0.0);
            double ownX = wantedX;
            for (AxisAlignedBB other : stepBoxes) {
                ownX = other.calculateXOffset(own, ownX);
            }
            own = own.offset(ownX, 0.0, 0.0);
            double ownZ = wantedZ;
            for (AxisAlignedBB other : stepBoxes) {
                ownZ = other.calculateZOffset(own, ownZ);
            }
            own = own.offset(0.0, 0.0, ownZ);
            if (sweptX * sweptX + sweptZ * sweptZ > ownX * ownX + ownZ * ownZ) {
                x = sweptX;
                z = sweptZ;
                y = -sweptY;
                this.box = swept;
            } else {
                x = ownX;
                z = ownZ;
                y = -ownY;
                this.box = own;
            }

            for (AxisAlignedBB other : stepBoxes) {
                y = other.calculateYOffset(this.box, y);
            }
            this.box = this.box.offset(0.0, y, 0.0);
            if (flatX * flatX + flatZ * flatZ >= x * x + z * z) {
                x = flatX;
                y = flatY;
                z = flatZ;
                this.box = flat;
            }
        }
        this.collidedHorizontally = wantedX != x || wantedZ != z;
        boolean collidedVertically = wantedY != y;
        this.onGround = collidedVertically && wantedY < 0.0;
        if (!this.inWater) {
            waterStep(world);
        }
        if (wantedX != x) {
            this.motionX = 0.0;
        }
        if (wantedZ != z) {
            this.motionZ = 0.0;
        }

        boolean slime = world.slime(MathHelper.floor_double(x()), MathHelper.floor_double(this.box.minY - 0.20000000298023224),
                MathHelper.floor_double(z()));
        if (collidedVertically) {
            if (slime && !sneak) {
                if (this.motionY < 0.0) {
                    this.motionY = -this.motionY;
                }
            } else {
                this.motionY = 0.0;
            }
        }
        if (!sneakEdge && this.onGround && slime && Math.abs(this.motionY) < 0.1 && !sneak) {
            double slow = 0.4 + Math.abs(this.motionY) * 0.2;
            this.motionX *= slow;
            this.motionZ *= slow;
        }

        AxisAlignedBB inner = this.box.contract(0.001, 0.001, 0.001);
        if (world.touching(inner, Touch.WEB) > 0) {
            this.inWeb = true;
        }
        for (int i = world.touching(inner, Touch.SOUL_SAND); i > 0; i--) {
            this.motionX *= 0.4;
            this.motionZ *= 0.4;
        }
    }

    private static double cutBack(double move, double cut) {
        if (move < cut && move >= -cut) {
            return 0.0;
        }
        return move > 0.0 ? move - cut : move + cut;
    }

    public static boolean stopsOnFloor(PlayerSim start, World world, Input in, double margin, double drop) {
        PlayerSim sim = start.copy();
        double lowest = sim.y() - drop - 1.0E-9;
        Input first = in.copy();
        first.jump = false;
        sim.tick(world, first);
        Input idle = first.released();
        for (int i = 0; i < 60; i++) {
            if (sim.y() < lowest) {
                return false;
            }
            if (sim.onGround && sim.speed() < 0.003) {
                break;
            }
            sim.tick(world, idle);
        }
        return sim.onGround && sim.y() >= lowest && sim.hasFloor(world, margin);
    }

    public boolean hasFloor(World world, double margin) {
        return !world.boxes(this.box.expand(-margin, 0.0, -margin).offset(0.0, -0.05, 0.0)).isEmpty();
    }
}
