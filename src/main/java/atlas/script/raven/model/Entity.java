package atlas.script.raven.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.block.material.Material;
import net.minecraft.init.Items;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemSword;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raven script API: an entity (2026-11-02). One wrapper per entity while the world lasts, so scripts may compare
 * them with == and key maps on them; {@link #entity} is the game's.
 */
public class Entity {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final Map<Integer, Entity> CACHE = new ConcurrentHashMap<Integer, Entity>();

    public net.minecraft.entity.Entity entity;
    public String type;
    public int entityId;
    public boolean isLiving;
    public boolean isPlayer;
    public boolean isUser;

    public Entity(net.minecraft.entity.Entity entity) {
        this.entity = entity;
        if (entity != null) {
            this.type = entity.getClass().getSimpleName();
            this.entityId = entity.getEntityId();
            this.isLiving = entity instanceof EntityLivingBase;
            this.isPlayer = entity instanceof EntityPlayer;
            this.isUser = entity == mc.thePlayer;
        }
    }

    public static Entity convert(net.minecraft.entity.Entity entity) {
        if (entity == null) {
            return null;
        }
        Entity cached = CACHE.get(entity.getEntityId());
        if (cached == null || cached.entity != entity) {
            cached = new Entity(entity);
            CACHE.put(entity.getEntityId(), cached);
        }
        return cached;
    }

    /** Forgets the entities that have left the world, so a long stay in one world does not keep every arrow and
     *  dropped item (2026-11-03). A wrapper a script still holds keeps working. */
    public static void prune() {
        CACHE.values().removeIf(wrapper -> wrapper.entity == null || wrapper.entity.isDead);
    }

    /** A new world: entity ids start over. */
    public static void clearCache() {
        CACHE.clear();
    }

    private EntityLivingBase living() {
        return this.isLiving ? (EntityLivingBase) this.entity : null;
    }

    private EntityPlayer player() {
        return this.isPlayer ? (EntityPlayer) this.entity : null;
    }

    public boolean allowEditing() {
        return this.isPlayer && this.player().capabilities.allowEdit;
    }

    public double distanceTo(Vec3 position) {
        return Math.sqrt(this.distanceToSq(position));
    }

    public double distanceToSq(Vec3 position) {
        return this.entity.getDistanceSq(position.x, position.y, position.z);
    }

    /** As Raven measures it (2026-11-03): down the column under its centre to the first block that is neither
     *  replaceable nor a liquid, from the feet, less one; 0 on the ground, -2 with nothing under it. */
    public double distanceToGround() {
        if (this.entity.onGround) {
            return 0.0;
        }
        double y = this.entity.posY;
        if (y % 1 == 0) {
            y--;
        }
        for (int i = (int) Math.floor(y); i > -1; i--) {
            BlockPos pos = new BlockPos(this.entity.posX, i, this.entity.posZ);
            net.minecraft.block.Block block = mc.theWorld.getBlockState(pos).getBlock();
            if (!block.isReplaceable(mc.theWorld, pos) && block.getMaterial() != Material.water
                    && block.getMaterial() != Material.lava) {
                return y - i - 1;
            }
        }
        return -2.0;
    }

    public boolean isHoldingBlock() {
        net.minecraft.item.ItemStack held = this.heldStack();
        return held != null && held.getItem() instanceof ItemBlock;
    }

    /** A sword or a stick: what Raven counts as a weapon with its settings as they come. */
    public boolean isHoldingWeapon() {
        net.minecraft.item.ItemStack held = this.heldStack();
        return held != null && (held.getItem() instanceof ItemSword || held.getItem() == Items.stick);
    }

    private net.minecraft.item.ItemStack heldStack() {
        return this.isLiving ? this.living().getHeldItem() : null;
    }

    /** -1 for what does not live (here and below, as in Raven). */
    public float getAbsorption() {
        return this.isLiving ? this.living().getAbsorptionAmount() : -1.0F;
    }

    public Vec3 getBlockPosition() {
        return new Vec3(new BlockPos(this.entity));
    }

    /** Without colour codes; a dropped item's own name. */
    public String getDisplayName() {
        if (this.entity instanceof net.minecraft.entity.item.EntityItem) {
            return ((net.minecraft.entity.item.EntityItem) this.entity).getEntityItem().getDisplayName();
        }
        return this.entity.getDisplayName().getUnformattedText();
    }

    public Entity getRidingEntity() {
        return convert(this.entity.ridingEntity);
    }

    public Entity getRiddenByEntity() {
        return convert(this.entity.riddenByEntity);
    }

    /** Where the server last put it, in the packets' own units (32 to a block), as Raven gives it. */
    public Vec3 getServerPosition() {
        return new Vec3(this.entity.serverPosX, this.entity.serverPosY, this.entity.serverPosZ);
    }

    public int getExperienceLevel() {
        return this.isPlayer ? this.player().experienceLevel : 0;
    }

    public float getExperience() {
        return this.isPlayer ? this.player().experience : 0.0F;
    }

    public float getFallDistance() {
        return this.entity.fallDistance;
    }

    public String getUUID() {
        return this.entity.getUniqueID().toString();
    }

    public String getCustomNameTag() {
        return this.entity.getCustomNameTag();
    }

    /** Blocks a second, from the last tick's move. */
    public double getBPS() {
        if (!this.isLiving) {
            return 0.0;
        }
        double dx = this.entity.posX - this.entity.prevPosX;
        double dz = this.entity.posZ - this.entity.prevPosZ;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }

    public String getFacing() {
        return this.entity.getHorizontalFacing().name();
    }

    public float getHealth() {
        return this.isLiving ? this.living().getHealth() : -1.0F;
    }

    public boolean isSleeping() {
        return this.isPlayer && this.player().isPlayerSleeping();
    }

    public float getEyeHeight() {
        return this.entity.getEyeHeight();
    }

    public float getHeight() {
        return this.entity.height;
    }

    public float getWidth() {
        return this.entity.width;
    }

    public boolean isBurning() {
        return this.entity.isBurning();
    }

    /** What it holds; for a dropped item, the item itself (as in Raven). */
    public ItemStack getHeldItem() {
        if (this.entity instanceof net.minecraft.entity.item.EntityItem) {
            return ItemStack.convert(((net.minecraft.entity.item.EntityItem) this.entity).getEntityItem());
        }
        return ItemStack.convert(this.heldStack());
    }

    public int getHurtTime() {
        return this.isLiving ? this.living().hurtTime : -1;
    }

    /** Using an item with food in hand (as Raven: a potion is not food). */
    public boolean isConsuming() {
        if (!this.isPlayer || !this.player().isUsingItem()) {
            return false;
        }
        net.minecraft.item.ItemStack held = this.player().getHeldItem();
        return held != null && held.getItem() instanceof ItemFood;
    }

    public Vec3 getLastPosition() {
        return new Vec3(this.entity.lastTickPosX, this.entity.lastTickPosY, this.entity.lastTickPosZ);
    }

    public float getMaxHealth() {
        return this.isLiving ? this.living().getMaxHealth() : -1.0F;
    }

    public int getMaxHurtTime() {
        return this.isLiving ? this.living().maxHurtTime : -1;
    }

    /** A dropped item's registry name without "minecraft:" (as Raven gives it), else the entity's name. */
    public String getName() {
        if (this.entity instanceof net.minecraft.entity.item.EntityItem) {
            net.minecraft.item.ItemStack item = ((net.minecraft.entity.item.EntityItem) this.entity).getEntityItem();
            String id = item == null ? null : item.getItem().getRegistryName();
            if (id != null) {
                return id.startsWith("minecraft:") ? id.substring(10) : id;
            }
        }
        return this.entity.getName();
    }

    public NetworkPlayer getNetworkPlayer() {
        if (!this.isPlayer || mc.getNetHandler() == null) {
            return null;
        }
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(this.entity.getUniqueID());
        return NetworkPlayer.convert(info);
    }

    public float getPitch() {
        return this.entity.rotationPitch;
    }

    public Vec3 getPosition() {
        return new Vec3(this.entity.posX, this.entity.posY, this.entity.posZ);
    }

    /** {id, name, amplifier, duration} for each active effect. */
    public List<Object[]> getPotionEffects() {
        List<Object[]> out = new ArrayList<Object[]>();
        if (!this.isLiving) {
            return out;
        }
        for (PotionEffect effect : this.living().getActivePotionEffects()) {
            out.add(new Object[]{effect.getPotionID(), effect.getEffectName(), effect.getAmplifier(), effect.getDuration()});
        }
        return out;
    }

    /** 0 boots .. 3 helmet. */
    public ItemStack getArmorInSlot(final int slot) {
        return this.isPlayer && slot >= 0 && slot <= 3 ? ItemStack.convert(this.player().inventory.armorInventory[slot])
                : null;
    }

    /** Horizontal speed, blocks a tick (its motion). */
    public double getSpeed() {
        return Math.sqrt(this.entity.motionX * this.entity.motionX + this.entity.motionZ * this.entity.motionZ);
    }

    public int getSwingProgress() {
        return this.isLiving ? this.living().swingProgressInt : -1;
    }

    public float getPrevSwingProgress() {
        return this.isLiving ? this.living().prevSwingProgress : -1.0F;
    }

    public int getTicksExisted() {
        return this.entity.ticksExisted;
    }

    public float getYaw() {
        return this.entity.rotationYaw;
    }

    public int getFireResistance() {
        return this.entity.fireResistance;
    }

    public float getPrevYaw() {
        return this.entity.prevRotationYaw;
    }

    public float getPrevPitch() {
        return this.entity.prevRotationPitch;
    }

    public boolean isCreative() {
        return this.isPlayer && this.player().capabilities.isCreativeMode;
    }

    /** A player: as the game set it this tick. Anything else: touching a block at its sides (Raven's test). */
    public boolean isCollided() {
        if (!this.isPlayer) {
            return mc.theWorld.checkBlockCollision(this.entity.getEntityBoundingBox().expand(0.05, 0.0, 0.05));
        }
        return this.entity.isCollided;
    }

    public boolean isCollidedHorizontally() {
        return this.entity.isCollidedHorizontally;
    }

    public boolean isCollidedVertically() {
        return this.entity.isCollidedVertically;
    }

    /** Removed, or in its death animation (as Raven has it). */
    public boolean isDead() {
        return this.entity.isDead || this.isLiving && this.living().deathTime > 0;
    }

    // ---- bS 17 ----

    /** An item entity's stack size, else the held stack's; -1 for none. */
    public int getStackSize() {
        if (this.entity instanceof net.minecraft.entity.item.EntityItem) {
            net.minecraft.item.ItemStack item = ((net.minecraft.entity.item.EntityItem) this.entity).getEntityItem();
            return item == null ? -1 : item.stackSize;
        }
        net.minecraft.item.ItemStack held = this.heldStack();
        return held == null ? -1 : held.stackSize;
    }

    public void moveTo(Vec3 position) {
        if (position != null) {
            this.moveTo(position.x, position.y, position.z);
        }
    }

    public void moveTo(double x, double y, double z) {
        this.entity.setPositionAndRotation(x, y, z, this.entity.rotationYaw, this.entity.rotationPitch);
    }

    public void moveTo(Vec3 position, float yaw, float pitch) {
        if (position != null) {
            this.moveTo(position.x, position.y, position.z, yaw, pitch);
        }
    }

    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        this.entity.setPositionAndRotation(x, y, z, yaw, pitch);
    }

    public void moveTo(Vec3 position, float yaw, float pitch, int interpolationTicks) {
        if (position != null) {
            this.moveTo(position.x, position.y, position.z, yaw, pitch, interpolationTicks);
        }
    }

    /** There over that many ticks, as the game moves entities the server moves. */
    public void moveTo(double x, double y, double z, float yaw, float pitch, int interpolationTicks) {
        if (interpolationTicks <= 0) {
            this.moveTo(x, y, z, yaw, pitch);
            return;
        }
        this.entity.setPositionAndRotation2(x, y, z, yaw, pitch, interpolationTicks, false);
    }

    public void setPrevYaw(float prevYaw) {
        this.entity.prevRotationYaw = prevYaw;
    }

    public void setPrevPitch(float prevPitch) {
        this.entity.prevRotationPitch = prevPitch;
    }

    public int getHunger() {
        return this.isPlayer ? this.player().getFoodStats().getFoodLevel() : 0;
    }

    public float getSaturation() {
        return this.isPlayer ? this.player().getFoodStats().getSaturationLevel() : 0.0F;
    }

    public float getAir() {
        return this.entity.getAir();
    }

    public boolean isInvisible() {
        return this.entity.isInvisible();
    }

    public boolean isInWater() {
        return this.entity.isInWater();
    }

    public boolean isInLava() {
        return this.entity.isInLava();
    }

    /** For a fishing hook: the player holding the rod. */
    public Entity getFisher() {
        return this.entity instanceof EntityFishHook ? convert(((EntityFishHook) this.entity).angler) : null;
    }

    /** Raven's test: its box overlaps a liquid or a block (Entity.isOffsetPositionInLiquid, which says the
     *  opposite of its name). */
    public boolean isInLiquid() {
        return !this.entity.isOffsetPositionInLiquid(0.0, 0.0, 0.0);
    }

    public boolean isOnLadder() {
        return this.isLiving && this.living().isOnLadder();
    }

    /** Standing where a little more of its own motion takes it off the block. */
    public boolean isOnEdge() {
        return mc.theWorld.getCollidingBoundingBoxes(this.entity, this.entity.getEntityBoundingBox()
                .offset(this.entity.motionX / 3.0, -1.0, this.entity.motionZ / 3.0)).isEmpty();
    }

    public boolean isSprinting() {
        return this.entity.isSprinting();
    }

    public boolean isSneaking() {
        return this.entity.isSneaking();
    }

    public boolean isUsingItem() {
        return this.isPlayer && this.player().isUsingItem();
    }

    public boolean onGround() {
        return this.entity.onGround;
    }

    public void setMotion(double x, double y, double z) {
        this.entity.motionX = x;
        this.entity.motionY = y;
        this.entity.motionZ = z;
    }

    public Vec3 getMotion() {
        return new Vec3(this.entity.motionX, this.entity.motionY, this.entity.motionZ);
    }

    public void setPitch(float pitch) {
        this.entity.rotationPitch = pitch;
    }

    public void setYaw(float yaw) {
        this.entity.rotationYaw = yaw;
    }

    public void setPosition(Vec3 position) {
        this.setPosition(position.x, position.y, position.z);
    }

    public void setPosition(double x, double y, double z) {
        this.entity.setPosition(x, y, z);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Entity && ((Entity) other).entityId == this.entityId;
    }

    @Override
    public int hashCode() {
        return this.entityId;
    }

    @Override
    public String toString() {
        return "Entity{" + this.type + " " + this.entityId + (this.entity == null ? "" : " " + this.entity.getName()) + "}";
    }
}
