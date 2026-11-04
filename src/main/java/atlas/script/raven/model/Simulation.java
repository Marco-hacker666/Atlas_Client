package atlas.script.raven.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.util.List;

/**
 * Raven script API: this player's movement played forward, tick by tick, without moving it (2026-11-02). create()
 * starts from where the player is, how it moves and the keys it holds; set the keys and look, tick(), read the
 * position. Vanilla's ground and air movement (EntityLivingBase.moveEntityWithHeading): friction from the block
 * underfoot, the sprint and walk push, the jump (with its sprint boost and Jump Boost), gravity and air drag,
 * collisions axis by axis with the 0.6 step, and sneaking not walking off edges. Water, lava, ladders and webs
 * are not played.
 */
public class Simulation {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double STEP = 0.6;

    private double x, y, z;
    private double motionX, motionY, motionZ;
    private boolean onGround;
    private boolean collidedHorizontally;
    private float forward, strafe;
    private boolean jump, sneak, sprinting;
    private float yaw, pitch;
    private int jumpTicks;
    /** Walking speed without the sprint's 30%. */
    private double baseSpeed = 0.1;
    private double jumpBoost;
    private float width = 0.6F;
    private float height = 1.8F;

    public static Simulation create() {
        Simulation sim = new Simulation();
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) {
            return sim;
        }
        sim.x = player.posX;
        sim.y = player.getEntityBoundingBox().minY;
        sim.z = player.posZ;
        sim.motionX = player.motionX;
        sim.motionY = player.motionY;
        sim.motionZ = player.motionZ;
        sim.onGround = player.onGround;
        sim.yaw = player.rotationYaw;
        sim.pitch = player.rotationPitch;
        sim.sprinting = player.isSprinting();
        sim.width = player.width;
        sim.height = player.height;
        if (player.movementInput != null) {
            sim.forward = Math.signum(player.movementInput.moveForward);
            sim.strafe = Math.signum(player.movementInput.moveStrafe);
            sim.jump = player.movementInput.jump;
            sim.sneak = player.movementInput.sneak;
        }
        double attribute = player.getEntityAttribute(SharedMonsterAttributes.movementSpeed).getAttributeValue();
        sim.baseSpeed = player.isSprinting() ? attribute / 1.3 : attribute;
        if (player.isPotionActive(Potion.jump)) {
            sim.jumpBoost = (player.getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1;
        }
        return sim;
    }

    /** -1 back, 0, 1 forward (the key, not its speed). */
    public void setForward(float forward) {
        this.forward = forward;
    }

    /** -1 right, 0, 1 left. */
    public void setStrafe(float strafe) {
        this.strafe = strafe;
    }

    public void setJump(boolean jump) {
        this.jump = jump;
    }

    public void setSneak(boolean sneak) {
        this.sneak = sneak;
    }

    public void setYaw(float yaw) {
        this.yaw = yaw;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    public void resetState(double x, double y, double z, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.onGround = onGround;
    }

    /** One tick. */
    public void tick() {
        if (Math.abs(this.motionX) < 0.005) {
            this.motionX = 0.0;
        }
        if (Math.abs(this.motionY) < 0.005) {
            this.motionY = 0.0;
        }
        if (Math.abs(this.motionZ) < 0.005) {
            this.motionZ = 0.0;
        }
        float moveForward = this.forward * (this.sneak ? 0.3F : 1.0F);
        float moveStrafe = this.strafe * (this.sneak ? 0.3F : 1.0F);
        if (this.sprinting && (moveForward < 0.8F || this.collidedHorizontally || this.sneak)) {
            this.sprinting = false;
        }
        if (this.jumpTicks > 0) {
            this.jumpTicks--;
        }
        if (this.jump && this.onGround && this.jumpTicks == 0) {
            this.motionY = 0.42 + this.jumpBoost;
            if (this.sprinting) {
                float radians = this.yaw * (float) Math.PI / 180.0F;
                this.motionX -= MathHelper.sin(radians) * 0.2F;
                this.motionZ += MathHelper.cos(radians) * 0.2F;
            }
            this.jumpTicks = 10;
        } else if (!this.jump) {
            this.jumpTicks = 0;
        }
        moveStrafe *= 0.98F;
        moveForward *= 0.98F;

        float drag = this.onGround ? this.slipperiness() * 0.91F : 0.91F;
        double speed = this.baseSpeed * (this.sprinting ? 1.3 : 1.0);
        float push = this.onGround ? (float) (speed * (0.16277136F / (drag * drag * drag)))
                : this.getAirAcceleration();
        this.moveFlying(moveStrafe, moveForward, push);
        drag = this.onGround ? this.slipperiness() * 0.91F : 0.91F;
        this.move(this.motionX, this.motionY, this.motionZ);
        this.motionY -= 0.08;
        this.motionY *= 0.98;
        this.motionX *= drag;
        this.motionZ *= drag;
    }

    private float slipperiness() {
        BlockPos below = new BlockPos(MathHelper.floor_double(this.x), MathHelper.floor_double(this.y) - 1,
                MathHelper.floor_double(this.z));
        return mc.theWorld == null ? 0.6F : mc.theWorld.getBlockState(below).getBlock().slipperiness;
    }

    private void moveFlying(float strafe, float forward, float friction) {
        float amount = strafe * strafe + forward * forward;
        if (amount < 1.0E-4F) {
            return;
        }
        amount = MathHelper.sqrt_float(amount);
        if (amount < 1.0F) {
            amount = 1.0F;
        }
        amount = friction / amount;
        strafe *= amount;
        forward *= amount;
        float sin = MathHelper.sin(this.yaw * (float) Math.PI / 180.0F);
        float cos = MathHelper.cos(this.yaw * (float) Math.PI / 180.0F);
        this.motionX += strafe * cos - forward * sin;
        this.motionZ += forward * cos + strafe * sin;
    }

    private AxisAlignedBB box() {
        double half = this.width / 2.0;
        return new AxisAlignedBB(this.x - half, this.y, this.z - half, this.x + half, this.y + this.height, this.z + half);
    }

    private List<AxisAlignedBB> obstacles(AxisAlignedBB area) {
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, area);
    }

    /** Entity.moveEntity, for the parts that matter on land. */
    private void move(double dx, double dy, double dz) {
        if (mc.theWorld == null) {
            this.x += dx;
            this.y += dy;
            this.z += dz;
            return;
        }
        AxisAlignedBB box = this.box();
        if (this.sneak && this.onGround) {
            double step = 0.05;
            while (dx != 0.0 && this.obstacles(box.offset(dx, -1.0, 0.0)).isEmpty()) {
                dx = dx < step && dx >= -step ? 0.0 : dx > 0.0 ? dx - step : dx + step;
            }
            while (dz != 0.0 && this.obstacles(box.offset(0.0, -1.0, dz)).isEmpty()) {
                dz = dz < step && dz >= -step ? 0.0 : dz > 0.0 ? dz - step : dz + step;
            }
            while (dx != 0.0 && dz != 0.0 && this.obstacles(box.offset(dx, -1.0, dz)).isEmpty()) {
                dx = dx < step && dx >= -step ? 0.0 : dx > 0.0 ? dx - step : dx + step;
                dz = dz < step && dz >= -step ? 0.0 : dz > 0.0 ? dz - step : dz + step;
            }
        }
        double wantX = dx;
        double wantY = dy;
        double wantZ = dz;
        List<AxisAlignedBB> hits = this.obstacles(box.addCoord(dx, dy, dz));
        AxisAlignedBB start = box;
        for (AxisAlignedBB hit : hits) {
            dy = hit.calculateYOffset(box, dy);
        }
        box = box.offset(0.0, dy, 0.0);
        boolean grounded = this.onGround || wantY != dy && wantY < 0.0;
        for (AxisAlignedBB hit : hits) {
            dx = hit.calculateXOffset(box, dx);
        }
        box = box.offset(dx, 0.0, 0.0);
        for (AxisAlignedBB hit : hits) {
            dz = hit.calculateZOffset(box, dz);
        }
        box = box.offset(0.0, 0.0, dz);

        if (grounded && (wantX != dx || wantZ != dz)) {
            /* The step: up 0.6, across, back down; kept when it gets further. */
            double sx = wantX;
            double sy = STEP;
            double sz = wantZ;
            List<AxisAlignedBB> stepHits = this.obstacles(start.addCoord(wantX, STEP, wantZ));
            AxisAlignedBB stepped = start;
            for (AxisAlignedBB hit : stepHits) {
                sy = hit.calculateYOffset(stepped, sy);
            }
            stepped = stepped.offset(0.0, sy, 0.0);
            for (AxisAlignedBB hit : stepHits) {
                sx = hit.calculateXOffset(stepped, sx);
            }
            stepped = stepped.offset(sx, 0.0, 0.0);
            for (AxisAlignedBB hit : stepHits) {
                sz = hit.calculateZOffset(stepped, sz);
            }
            stepped = stepped.offset(0.0, 0.0, sz);
            double down = -sy;
            for (AxisAlignedBB hit : stepHits) {
                down = hit.calculateYOffset(stepped, down);
            }
            stepped = stepped.offset(0.0, down, 0.0);
            if (sx * sx + sz * sz > dx * dx + dz * dz) {
                dx = sx;
                dz = sz;
                dy = sy + down;
                box = stepped;
            }
        }
        this.x = (box.minX + box.maxX) / 2.0;
        this.y = box.minY;
        this.z = (box.minZ + box.maxZ) / 2.0;
        this.collidedHorizontally = wantX != dx || wantZ != dz;
        this.onGround = wantY != dy && wantY < 0.0;
        if (wantX != dx) {
            this.motionX = 0.0;
        }
        if (wantZ != dz) {
            this.motionZ = 0.0;
        }
        if (wantY != dy) {
            this.motionY = 0.0;
        }
    }

    public Vec3 getPosition() {
        return new Vec3(this.x, this.y, this.z);
    }

    public Vec3 getMotion() {
        return new Vec3(this.motionX, this.motionY, this.motionZ);
    }

    public boolean onGround() {
        return this.onGround;
    }

    // ---- bS 17 ----

    /** What a key pushes in the air this tick (vanilla's jumpMovementFactor): 0.02, or 0.026 sprinting. */
    public float getAirAcceleration() {
        return this.sprinting ? 0.026F : 0.02F;
    }

    /** In water or lava where the simulation stands. */
    public boolean isInLiquid() {
        return mc.theWorld != null && mc.theWorld.isAnyLiquid(this.box().contract(0.001, 0.001, 0.001));
    }

    /** On a ladder or vine where the simulation stands. */
    public boolean isOnLadder() {
        if (mc.theWorld == null) {
            return false;
        }
        net.minecraft.block.Block block = mc.theWorld.getBlockState(new BlockPos(MathHelper.floor_double(this.x),
                MathHelper.floor_double(this.y), MathHelper.floor_double(this.z))).getBlock();
        return block instanceof net.minecraft.block.BlockLadder || block instanceof net.minecraft.block.BlockVine;
    }
}
