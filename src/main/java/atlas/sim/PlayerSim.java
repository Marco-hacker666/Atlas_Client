package atlas.sim;

import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

import java.util.List;

/**
 * The player's own movement, run ahead (2026-11-11, owner: "a prediction
 * engine"). One {@link #tick} is one tick of 1.8's walking, jumping and
 * falling as the game itself works it out: the sprint rule, the jump, the
 * input's push (moveFlying), the move against the world's collision boxes
 * with the sneak edge and the step up, then gravity and friction. Float where
 * the game uses a float, so the numbers come out the game's.
 *
 * Before this, every module that needed to know where the player would be
 * had its own few lines for it (Scaffold's fall guard, Eagle, BlockLadder,
 * MoveUtil.predictMovement, MoveModel), each covering a part: flat ground
 * only, no sprint, no collisions. This is the one place for it. A caller
 * takes the state now ({@link SimPlayer#state}), copies it, and ticks the
 * copy with the keys it wants to try.
 *
 * Pure: the world is an interface (collision boxes and slipperiness), so it
 * runs in a test with no game. Not covered: water, lava, ladders, cobwebs,
 * soul sand, slime, riding and flying -- {@link SimPlayer#supported} says
 * when the player is in one of those, and the caller keeps what it did
 * before.
 */
public final class PlayerSim {

    /** What the move collides with and slides on. */
    public interface World {
        /** The collision boxes that touch {@code area}, as World.getCollidingBoundingBoxes gives them. */
        List<AxisAlignedBB> boxes(AxisAlignedBB area);

        /** Block.slipperiness of the block at this cell (0.6 for most, air too; ice 0.98). */
        float slipperiness(int x, int y, int z);
    }

    /** The keys of one tick, as the move gets them. */
    public static final class Input {
        /** The yaw the move runs by: the look's when a module turns the move, else the camera's. */
        public float yaw;
        /** As movementInput holds them when the move runs: after the sneak (0.3) and the item (0.2) scaling. */
        public float forward;
        public float strafe;
        public boolean jump;
        public boolean sneak;
        /** The sprint key (or a module holding it). */
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

        /** The same tick with the walk keys and the jump let go. */
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

    public AxisAlignedBB box;
    public double motionX;
    public double motionY;
    public double motionZ;
    public boolean onGround;
    public boolean sprinting;
    public boolean collidedHorizontally;
    /** Ticks until the next jump of a held jump key (EntityLivingBase.jumpTicks). */
    public int jumpTicks;
    /** The ground speed this tick's move uses: getAIMoveSpeed, set at the end of the tick before. */
    public float moveSpeed = 0.1F;
    /** The same in the air (jumpMovementFactor). */
    public float airSpeed = AIR_SPEED;
    /** The movement-speed attribute without the sprint boost: walk speed and potions. */
    public double walkSpeed = (double) 0.1F;
    /** 0.1 a level of Jump Boost, added to the jump. */
    public float jumpBoost;
    public float stepHeight = 0.6F;
    /** Food above 6, or may fly: sprint is kept only with it. */
    public boolean canSprint = true;
    /** Not using an item and not blind: sprint starts only with it. */
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
        return s;
    }

    public double x() {
        return (this.box.minX + this.box.maxX) / 2.0;
    }

    /** The feet. */
    public double y() {
        return this.box.minY;
    }

    public double z() {
        return (this.box.minZ + this.box.maxZ) / 2.0;
    }

    /** Blocks a tick, level, as the last tick moved. */
    public double speed() {
        return Math.sqrt(this.motionX * this.motionX + this.motionZ * this.motionZ);
    }

    /** One tick: EntityPlayerSP.onLivingUpdate from the sprint rule on, down to the friction. */
    public void tick(World world, Input in) {
        sprintRule(in);
        move(world, in);
    }

    /** The client's sprint rule, on the keys as scaled (a sneak's 0.3 and an item's 0.2 are under 0.8). */
    public void sprintRule(Input in) {
        if (!this.sprinting && in.sprint && in.forward >= SPRINT_FORWARD && this.canSprint && this.canStartSprint) {
            this.sprinting = true;
        }
        if (this.sprinting && (in.forward < SPRINT_FORWARD || this.collidedHorizontally || !this.canSprint)) {
            this.sprinting = false;
        }
    }

    /**
     * The tick after the sprint rule, with {@link #sprinting} as it stands: the jump, the push, the move, gravity and
     * friction, and the speeds the next tick uses. A caller that knows the sprint state some other way (what the
     * server was told) sets it and runs only this.
     */
    public void move(World world, Input in) {
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
            if (this.onGround && this.jumpTicks == 0) {
                jump(in.yaw);
                this.jumpTicks = 10;
            }
        } else {
            this.jumpTicks = 0;
        }
        moveWithHeading(world, in.strafe * 0.98F, in.forward * 0.98F, in.yaw, in.sneak);
        /* EntityPlayer.onLivingUpdate's end: the speeds the next tick's move uses. */
        this.airSpeed = AIR_SPEED;
        if (this.sprinting) {
            this.airSpeed = (float) ((double) this.airSpeed + (double) AIR_SPEED * 0.3);
        }
        this.moveSpeed = (float) (this.sprinting ? this.walkSpeed * (1.0 + SPRINT_BOOST) : this.walkSpeed);
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

    /** EntityLivingBase.moveEntityWithHeading, on land. */
    private void moveWithHeading(World world, float strafe, float forward, float yaw, boolean sneak) {
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
        move(world, this.motionX, this.motionY, this.motionZ, sneak);
        this.motionY -= 0.08;
        this.motionY *= 0.9800000190734863;
        this.motionX *= (double) friction;
        this.motionZ *= (double) friction;
    }

    /**
     * Entity.moveFlying: what the keys add to the motion at this yaw, {x, z}.
     * {@code strafe} and {@code forward} already carry the 0.98.
     */
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

    /** Entity.moveEntity: the move against the world, with the sneak edge and the step up. */
    private void move(World world, double x, double y, double z, boolean sneak) {
        double wantedX = x;
        double wantedY = y;
        double wantedZ = z;
        if (this.onGround && sneak) {
            /* A sneaking player does not leave a block: the move is cut back in steps of 0.05 while there is
               nothing a block below where it would end. */
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
            /* Up as far as the whole swept box allows, then across... */
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
            /* ...or up as far as the box where it stands allows, then across: the further of the two. */
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
            /* Back down onto whatever was stepped on. */
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
        if (wantedX != x) {
            this.motionX = 0.0;
        }
        if (wantedZ != z) {
            this.motionZ = 0.0;
        }
        if (collidedVertically) {
            this.motionY = 0.0;
        }
    }

    private static double cutBack(double move, double cut) {
        if (move < cut && move >= -cut) {
            return 0.0;
        }
        return move > 0.0 ? move - cut : move + cut;
    }

    /**
     * The safe-walk question: walking this tick with {@code in} and then
     * letting the keys go, does the player come to rest on a floor? False
     * when it ends more than {@code drop} below where it started (0 for "must
     * not leave this floor"; a step down onto a floor that close still
     * counts), or stops with less than {@code margin} of its box (each side)
     * over a floor.
     *
     * The tick that crosses an edge still ends on the ground in the game (it
     * moves down first, from where the box still stands), so being on the
     * ground is not enough: the floor under where it stopped is looked for.
     */
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

    /** Whether a floor is right under the box, taken in by {@code margin} on each side. */
    public boolean hasFloor(World world, double margin) {
        return !world.boxes(this.box.expand(-margin, 0.0, -margin).offset(0.0, -0.05, 0.0)).isEmpty();
    }
}
