package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.Render3DEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.CombatTargeting;
import atlas.util.RenderUtil;
import atlas.util.RotationUtil;
import atlas.util.TrajectoryAim;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemEgg;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemSnowball;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;

/**
 * Bow aimbot (2026-10-31, ported from LiquidBounce legacy's ProjectileAimbot,
 * GPL-3; it replaces the earlier OpenSkid auto-thrower).
 *
 * While a bow is being drawn -- or an egg, snowball or pearl is in hand -- the
 * look the server gets turns onto the best target, leading it along the arc
 * the projectile will fly (gravity-type Projectile, TrajectoryAim) or straight
 * at its body (None). The camera is not moved and nothing is thrown or shot
 * for the player: they still draw and let go themselves. The arrow leaves
 * along the look the server has, so the silent look is the one that counts.
 *
 * Differences from the original, each a fix: an unreachable target gets no
 * aim (the original's formula gave a NaN pitch there, which a server rejects);
 * egg, snowball and pearl are aimed by their own switches (in the original
 * they only were with other-items on); the look is turned at turn-speed and
 * eased back to the camera when the aim ends instead of snapping, and stays on
 * the mouse grid.
 */
public class ProjectileAimBot extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] BODY = new String[]{"Head", "Body", "Feet"};
    /** Below this from the camera the eased-back look is let go. */
    private static final float SETTLED = 1.0F;

    public final BooleanProperty bow = new BooleanProperty("bow", true);
    public final BooleanProperty egg = new BooleanProperty("egg", true);
    public final BooleanProperty snowball = new BooleanProperty("snowball", true);
    public final BooleanProperty enderPearl = new BooleanProperty("ender-pearl", false);
    public final BooleanProperty otherItems = new BooleanProperty("other-items", false);
    public final FloatProperty range = new FloatProperty("range", 10.0F, 0.0F, 30.0F);
    public final BooleanProperty throughWalls = new BooleanProperty("through-walls", false);
    public final FloatProperty throughWallsRange = new FloatProperty("through-walls-range", 10.0F, 0.0F, 30.0F,
            this.throughWalls::getValue);
    public final ModeProperty priority = new ModeProperty("priority", 2, new String[]{"Health", "Distance", "Direction"});
    public final ModeProperty gravityType = new ModeProperty("gravity-type", 1, new String[]{"None", "Projectile"});
    public final BooleanProperty predict = new BooleanProperty("predict", true, () -> this.gravityType.getValue() == 1);
    public final FloatProperty predictSize = new FloatProperty("predict-size", 2.0F, 0.1F, 5.0F,
            () -> this.predict.getValue() && this.gravityType.getValue() == 1);
    public final FloatProperty turnSpeed = new FloatProperty("turn-speed", 120.0F, 10.0F, 180.0F);
    public final ModeProperty highestBodyPoint = new ModeProperty("highest-body-point", 0, BODY,
            () -> this.gravityType.getValue() == 0);
    public final ModeProperty lowestBodyPoint = new ModeProperty("lowest-body-point", 2, BODY,
            () -> this.gravityType.getValue() == 0);
    public final BooleanProperty mark = new BooleanProperty("mark", true);

    private EntityPlayer target;
    /** The look this module has the server on, or NaN when it has none. */
    private float sentYaw = Float.NaN;
    private float sentPitch = Float.NaN;

    public ProjectileAimBot() {
        super("ProjectileAimBot", false, false, "Aims your bow, eggs, snowballs and pearls at the best target while you draw or hold them.");
    }

    @Override
    public void onEnabled() {
        this.clear();
    }

    @Override
    public void onDisabled() {
        this.clear();
    }

    @EventTarget(whenDisabled = true)
    public void onWorld(LoadWorldEvent event) {
        this.clear();
    }

    private void clear() {
        this.target = null;
        this.sentYaw = Float.NaN;
        this.sentPitch = Float.NaN;
    }

    /** Priority 2: above AimAssist (1), below Scaffold (3) and Clutch (7), which need theirs to place. */
    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.clear();
            return;
        }
        this.target = null;
        float[] want = this.aim();
        float cameraYaw = mc.thePlayer.rotationYaw;
        float cameraPitch = mc.thePlayer.rotationPitch;
        if (want == null) {
            if (Float.isNaN(this.sentYaw)) {
                return;
            }
            /* No aim now: back to the camera at the same pace, then the real look goes through again. */
            if (Math.abs(MathHelper.wrapAngleTo180_float(cameraYaw - event.getYaw())) < SETTLED
                    && Math.abs(cameraPitch - event.getPitch()) < SETTLED) {
                this.sentYaw = Float.NaN;
                this.sentPitch = Float.NaN;
                return;
            }
            want = new float[]{cameraYaw, cameraPitch};
        }
        /* From the look the server last got (another module may have had the packet since). */
        float fromYaw = event.getYaw();
        float fromPitch = event.getPitch();
        float max = this.turnSpeed.getValue() * (0.9F + (float) Math.random() * 0.2F);
        float dYaw = MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(want[0] - fromYaw), -max, max);
        float dPitch = MathHelper.clamp_float(want[1] - fromPitch, -max, max);
        float[] look = RotationUtil.gcd(fromYaw + dYaw, MathHelper.clamp_float(fromPitch + dPitch, -90.0F, 90.0F),
                event.getYaw(), event.getPitch());
        if (Float.isNaN(look[0]) || Float.isNaN(look[1])) {
            this.clear();
            return;
        }
        this.sentYaw = look[0];
        this.sentPitch = look[1];
        event.setRotation(look[0], look[1], 2);
    }

    /** The look wanted this tick, or null: nothing to aim with, no target, or out of reach. */
    private float[] aim() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || held.stackSize <= 0) {
            return null;
        }
        Item item = held.getItem();
        float velocity;
        float gravity;
        if (item instanceof ItemBow) {
            if (!this.bow.getValue() || !mc.thePlayer.isUsingItem()) {
                return null;
            }
            /* FastBow lets go at full draw. */
            Module fastBow = atlas.Atlas.moduleManager == null ? null : atlas.Atlas.moduleManager.modules.get(FastBow.class);
            velocity = fastBow != null && fastBow.isEnabled() ? 1.0F
                    : TrajectoryAim.bowVelocity(mc.thePlayer.getItemInUseDuration());
            gravity = TrajectoryAim.ARROW_GRAVITY;
        } else {
            boolean allowed = item instanceof ItemEgg ? this.egg.getValue()
                    : item instanceof ItemSnowball ? this.snowball.getValue()
                    : item instanceof ItemEnderPearl ? this.enderPearl.getValue()
                    : this.otherItems.getValue();
            if (!allowed) {
                return null;
            }
            velocity = TrajectoryAim.THROWN_VELOCITY;
            gravity = TrajectoryAim.THROWN_GRAVITY;
        }
        EntityPlayer chosen = this.pickTarget();
        if (chosen == null) {
            return null;
        }
        float[] look = this.gravityType.getValue() == 1 ? this.faceTrajectory(chosen, velocity, gravity) : this.faceBody(chosen);
        if (look == null) {
            return null;
        }
        this.target = chosen;
        return look;
    }

    /** LiquidBounce's faceTrajectory: both positions carried one step (theirs predict-size steps) ahead. */
    private float[] faceTrajectory(EntityPlayer t, float velocity, float gravity) {
        boolean ahead = this.predict.getValue();
        float size = this.predictSize.getValue();
        double dx = t.posX + (ahead ? (t.posX - t.prevPosX) * size : 0.0)
                - (mc.thePlayer.posX + (ahead ? mc.thePlayer.posX - mc.thePlayer.prevPosX : 0.0));
        double dy = t.getEntityBoundingBox().minY + (ahead ? (t.getEntityBoundingBox().minY - t.prevPosY) * size : 0.0)
                + t.getEyeHeight() - 0.15
                - (mc.thePlayer.getEntityBoundingBox().minY + (ahead ? mc.thePlayer.posY - mc.thePlayer.prevPosY : 0.0))
                - mc.thePlayer.getEyeHeight();
        double dz = t.posZ + (ahead ? (t.posZ - t.prevPosZ) * size : 0.0)
                - (mc.thePlayer.posZ + (ahead ? mc.thePlayer.posZ - mc.thePlayer.prevPosZ : 0.0));
        return TrajectoryAim.solve(dx, dy, dz, velocity, gravity);
    }

    /** Gravity-type None: straight at the middle of the chosen part of the body. */
    private float[] faceBody(EntityPlayer t) {
        AxisAlignedBB box = t.getEntityBoundingBox();
        double height = box.maxY - box.minY;
        double top = bodyY(this.highestBodyPoint.getValue());
        double bottom = bodyY(this.lowestBodyPoint.getValue());
        double y = box.minY + height * (Math.min(top, bottom) + Math.max(top, bottom)) / 2.0;
        double dx = (box.minX + box.maxX) / 2.0 - mc.thePlayer.posX;
        double dy = y - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
        double dz = (box.minZ + box.maxZ) / 2.0 - mc.thePlayer.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return null;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return Float.isNaN(yaw) || Float.isNaN(pitch) ? null : new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    /** Head, Body, Feet as a share of the box height from its bottom. */
    private static double bodyY(int point) {
        return point == 0 ? 0.9 : point == 1 ? 0.5 : 0.1;
    }

    /** In range, seen (or through-walls within its range), and the best by priority. */
    private EntityPlayer pickTarget() {
        EntityPlayer best = null;
        double bestScore = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.getHealth() <= 0.0F || !CombatTargeting.isTrackablePlayer(player, true)
                    || !TargetFilter.accepts(player)) {
                continue;
            }
            double distance = RotationUtil.distanceToEntity(player);
            if (distance > this.range.getValue()) {
                continue;
            }
            if (!mc.thePlayer.canEntityBeSeen(player)
                    && (!this.throughWalls.getValue() || distance > this.throughWallsRange.getValue())) {
                continue;
            }
            double score;
            switch (this.priority.getValue()) {
                case 0:
                    score = player.getHealth();
                    break;
                case 1:
                    score = distance;
                    break;
                default:
                    score = RotationUtil.angleToEntity(player);
                    break;
            }
            if (score < bestScore) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    @EventTarget
    public void onRender(Render3DEvent event) {
        EntityPlayer shown = this.target;
        if (!this.isEnabled() || !this.mark.getValue() || shown == null || shown.isDead || mc.theWorld == null
                || !mc.theWorld.playerEntities.contains(shown)) {
            return;
        }
        RenderUtil.enableRenderState();
        RenderUtil.drawEntityBoundingBox(shown, 37, 126, 255, 70, 2.0F, 0.05);
        RenderUtil.disableRenderState();
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.gravityType.getModeString()};
    }
}
