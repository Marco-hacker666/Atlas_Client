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
 * takes the player as it is now ({@link SimPlayer#snapshot}) and ticks that
 * state with the keys it wants to try.
 *
 * Pure: the world is an interface (collision boxes, slipperiness, and since
 * 2026-11-12 the liquids, ladders and the blocks that act on a touch), so it
 * runs in a test with no game. Covered: land, ice, water (with its flow and
 * Depth Strider), lava, ladders and vines, cobwebs, soul sand and slime. Not
 * covered: riding and flying -- {@link SimPlayer#supported} says when the
 * player is doing one of those, and the caller keeps what it did before.
 *
 * 2026-11-12, after holding every step against the game's own code (1.8.9
 * with Forge's patches), four things it left out are in:
 *   - a player that was not in water asks again where its move ended
 *     (EntityLivingBase.updateFallState), so the tick it enters flowing water
 *     it is already pushed, a tick before the push at the start of a tick;
 *   - the push out of a block the player is inside (EntityPlayerSP
 *     .pushOutOfBlocks, with Forge's test of the whole height);
 *   - where the chunk is not loaded the player sinks at a fixed 0.1 instead
 *     of falling;
 *   - a sprint ends by itself after 600 ticks (sprintingTicksLeft).
 * Still not modelled: the double tap of the forward key that starts a sprint
 * without the sprint key (it needs the keys of the tick before).
 *
 * A state says how far into its tick it was taken ({@link Stage}), so the
 * first tick run from it does only what the game has not done yet.
 */
public final class PlayerSim {

    /** What the move collides with and slides on. */
    public interface World {
        /** The collision boxes that touch {@code area}, as World.getCollidingBoundingBoxes gives them. */
        List<AxisAlignedBB> boxes(AxisAlignedBB area);

        /** Block.slipperiness of the block at this cell (0.6 for most, air too; ice 0.98). */
        float slipperiness(int x, int y, int z);

        /**
         * World.handleMaterialAcceleration for water over {@code area}: whether the area is in water, with the flow
         * of the water blocks counted (their getFlowVector summed, not normalised) added into {@code flow}.
         */
        default boolean water(AxisAlignedBB area, double[] flow) {
            return false;
        }

        /** World.isMaterialInBB(area, lava). */
        default boolean lava(AxisAlignedBB area) {
            return false;
        }

        /** World.isAnyLiquid(area). */
        default boolean liquid(AxisAlignedBB area) {
            return false;
        }

        /** A ladder or a vine at this cell (EntityLivingBase.isOnLadder). */
        default boolean climbable(int x, int y, int z) {
            return false;
        }

        /** Slime at this cell. */
        default boolean slime(int x, int y, int z) {
            return false;
        }

        /** How many blocks of this kind {@code area} touches (Entity.doBlockCollisions, cell by cell). */
        default int touching(AxisAlignedBB area, Touch kind) {
            return 0;
        }

        /** A full cube at this cell (Block.isNormalCube): what a player inside it is pushed out of. */
        default boolean solid(int x, int y, int z) {
            return false;
        }

        /** Whether the chunk of this column is there (World.isBlockLoaded and Chunk.isLoaded). */
        default boolean loaded(int x, int z) {
            return true;
        }
    }

    /**
     * How far into the player's tick a state was taken: what the first tick run from it still has to do. The game's
     * tick, in order: the water's flow (Entity.onEntityUpdate), the keys, the push out of blocks and the sprint rule
     * (EntityPlayerSP.onLivingUpdate), then the move.
     */
    public enum Stage {
        /** Between ticks (TickEvent, UpdateEvent): the whole tick is still to come. */
        START,
        /** The water has pushed, the sprint's count has run and the keys are read (MoveInputEvent). */
        INPUT,
        /** The push out of blocks and the sprint rule are done (LivingUpdateEvent): only the move is left. */
        MOVE
    }

    /** Blocks that act on a player who touches them (onEntityCollidedWithBlock). */
    public enum Touch {
        /** Sets the player in the web: the next move is a quarter across and a twentieth down. */
        WEB,
        /** Each one touched takes the motion across to 0.4. */
        SOUL_SAND
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
    private static final int SPRINT_TICKS = 600;

    public AxisAlignedBB box;
    public double motionX;
    public double motionY;
    public double motionZ;
    public boolean onGround;
    public boolean sprinting;
    public boolean collidedHorizontally;
    /** In water (Entity.inWater), as handleWaterMovement found it at the start of the last tick. */
    public boolean inWater;
    /** In a cobweb: the next move is cut (Entity.isInWeb). */
    public boolean inWeb;
    /** The boots' Depth Strider level (EnchantmentHelper.getDepthStriderModifier). */
    public float depthStrider;
    /**
     * How far into its tick the state was taken (review, PR #37: a state taken after the water's push must not be
     * pushed again). The first {@link #tick} or {@link #move} runs the rest of that tick and sets it back to START.
     */
    public Stage stage = Stage.START;
    /** Ticks until the next jump of a held jump key (EntityLivingBase.jumpTicks). */
    public int jumpTicks;
    /**
     * The ground speed this tick's move uses: EntityPlayer.getAIMoveSpeed, which reads the movement-speed attribute
     * as it is, so a sprint started or stopped this tick counts at once (2026-11-12: it was taken from the tick before,
     * and the self check was 0.0294 off on every tick a sprint started). Set from walkSpeed and the sprint at each move.
     */
    public float moveSpeed = 0.1F;
    /** The same in the air (jumpMovementFactor). */
    public float airSpeed = AIR_SPEED;
    /** The movement-speed attribute without the sprint boost: walk speed and potions. */
    public double walkSpeed = (double) 0.1F;
    /** 0.1 a level of Jump Boost, added to the jump. */
    public float jumpBoost;
    public float stepHeight = 0.6F;
    /** Entity.width and height: where the push out of blocks looks. */
    public float width = 0.6F;
    public float height = 1.8F;
    /** Ticks until a sprint ends by itself (EntityPlayerSP.sprintingTicksLeft: 600 from each start); 0 for no end. */
    public int sprintTicksLeft;
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

    /** One tick of the player, or what is left of it from the state's {@link #stage}. */
    public void tick(World world, Input in) {
        run(world, in, true);
    }

    /**
     * The same without the sprint rule, {@link #sprinting} as it stands. A caller that knows the sprint state some
     * other way (what the server was told) sets it and runs this.
     */
    public void move(World world, Input in) {
        run(world, in, false);
    }

    private void run(World world, Input in, boolean sprintRule) {
        Stage from = this.stage;
        this.stage = Stage.START;
        if (from == Stage.START) {
            /* Entity.onEntityUpdate, before onLivingUpdate. */
            waterStep(world);
        }
        if (from != Stage.MOVE) {
            /* EntityPlayerSP.onLivingUpdate, once the keys are read. */
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

    /**
     * The client's sprint rule for one tick: the count that ends a sprint after 600 ticks (the first thing
     * EntityPlayerSP.onLivingUpdate does), then the keys. A held sprint key starts it again in the same tick, as in
     * the game.
     */
    public void sprintRule(Input in) {
        if (this.sprintTicksLeft > 0 && --this.sprintTicksLeft == 0) {
            this.sprinting = false;
        }
        sprintKeys(in);
    }

    /** The rule on the keys as scaled (a sneak's 0.3 and an item's 0.2 are under 0.8). */
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

    /**
     * Entity.handleWaterMovement: in water or not where the box is now, and the flow's push. The game divides the
     * summed flow by its length as a float (Vec3.normalize through MathHelper.sqrt_double).
     */
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

    /**
     * EntityPlayerSP.pushOutOfBlocks: at four points inside the box (0.35 of the width from the middle, half a block
     * up), in the game's order, a point inside a full cube sets the motion to 0.1 toward the nearest open side.
     */
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

    /** Forge's isHeadspaceFree: no full cube in this cell or above it, for as many cells as the player is tall. */
    private boolean open(World world, int x, int y, int z) {
        int tall = Math.max((int) Math.ceil(this.height), 1);
        for (int up = 0; up < tall; up++) {
            if (world.solid(x, y + up, z)) {
                return false;
            }
        }
        return true;
    }

    /** EntityLivingBase.onLivingUpdate: the jump, the push, the move, gravity and friction, and the next speeds. */
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
                /* Swimming up (updateAITick, handleJumpLava). */
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
        /* EntityPlayer.onLivingUpdate's end: the air speed the next tick's move uses (this one does lag a tick). */
        this.airSpeed = AIR_SPEED;
        if (this.sprinting) {
            this.airSpeed = (float) ((double) this.airSpeed + (double) AIR_SPEED * 0.3);
        }
        this.moveSpeed = groundSpeed();
    }

    /** The movement-speed attribute with the sprint as it stands: walkSpeed, times 1.3 while sprinting. */
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

    /** EntityLivingBase.moveEntityWithHeading. */
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

    /** Against a wall in a liquid, a hop up when the place a little higher is free (isOffsetPositionInLiquid). */
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

    /** moveEntityWithHeading on land, ladders included. */
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
        /* The game casts here where it floors everywhere else; kept, so a negative column is one off as in the game. */
        if (!world.loaded((int) x(), (int) z())) {
            this.motionY = this.box.minY > 0.0 ? -0.1 : 0.0;
        } else {
            this.motionY -= 0.08;
        }
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
        if (!this.inWater) {
            /* EntityLivingBase.updateFallState: not in water before the move, it asks again where the move ended --
               and water there pushes at once, before the motion a wall stopped is cleared. */
            waterStep(world);
        }
        if (wantedX != x) {
            this.motionX = 0.0;
        }
        if (wantedZ != z) {
            this.motionZ = 0.0;
        }
        /* The block the feet are on (0.2 down): slime bounces a landing back and slows a walk. */
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
        /* Entity.doBlockCollisions: what the box (in by 0.001) touches. */
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
