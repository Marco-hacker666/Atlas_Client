package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.KeyEvent;
import atlas.events.MoveInputEvent;
import atlas.events.TickEvent;
import atlas.events.UpdateEvent;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.util.*;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class AimAssist extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final TimerUtil timer = new TimerUtil();
    /* 2026-10-20 (plan item 6): how the view is turned. Smooth is the one before (a share of
       what is left each tick). Regular, Linear and Lock On turn every frame by the frame's time,
       after OpenSlinky's Aim Assist (AimAssistSlinky has the details). */
    public final ModeProperty style = new ModeProperty("style", 0, new String[]{"Smooth", "Regular", "Linear", "Lock On"});
    private final java.util.function.BooleanSupplier framed = () -> this.style.getValue() != 0;
    private final java.util.function.BooleanSupplier smooth = () -> this.style.getValue() == 0;
    public final FloatProperty speedH = new FloatProperty("speed-h", 5.5F, 0.0F, 30.0F,
            () -> this.style.getValue() == 1 || this.style.getValue() == 2);
    public final FloatProperty speedV = new FloatProperty("speed-v", 5.8F, 0.0F, 30.0F,
            () -> this.style.getValue() == 1 || this.style.getValue() == 2);
    public final BooleanProperty lockVertical = new BooleanProperty("lock-vertical", false, () -> this.style.getValue() == 3);
    public final FloatProperty predict = new FloatProperty("predict", 0.85F, 0.0F, 1.0F, framed);
    public final FloatProperty randomization = new FloatProperty("randomization", 0.36F, 0.0F, 1.0F, framed);
    public final FloatProperty hitboxH = new FloatProperty("hitbox-h", 1.0F, 0.0F, 1.0F, framed);
    public final FloatProperty hitboxV = new FloatProperty("hitbox-v", 1.0F, 0.0F, 1.0F, framed);
    public final FloatProperty deadZone = new FloatProperty("dead-zone", 2.0F, 0.0F, 30.0F, framed);
    public final ModeProperty targetMode = new ModeProperty("target-mode", 1, new String[]{"Single", "Switch"}, framed);
    public final ModeProperty sortBy = new ModeProperty("sort-by", 3, new String[]{"Distance", "Health", "Hurt time", "Aim angle"},
            () -> framed.getAsBoolean() && this.targetMode.getValue() == 1);
    public final BooleanProperty exceedFov = new BooleanProperty("exceed-fov", true, framed);
    public final BooleanProperty requireClick = new BooleanProperty("require-click", true, framed);
    public final BooleanProperty requireMouseMove = new BooleanProperty("require-mouse-move", false, framed);
    public final BooleanProperty stopOnBlockBreak = new BooleanProperty("stop-on-block-break", true, framed);
    public final BooleanProperty lineOfSight = new BooleanProperty("line-of-sight", true, framed);
    public final BooleanProperty ignoreInvisible = new BooleanProperty("ignore-invisible", true, framed);
    private final AimAssistSlinky framedAim = new AimAssistSlinky(this);
    public final FloatProperty hSpeed = new FloatProperty("horizontal-speed", 3.0F, 0.0F, 10.0F, smooth);
    public final FloatProperty vSpeed = new FloatProperty("vertical-speed", 0.0F, 0.0F, 10.0F, smooth);
    public final PercentProperty smoothing = new PercentProperty("smoothing", 50, smooth);
    public final FloatProperty range = new FloatProperty("range", 4.5F, 3.0F, 8.0F);
    public final IntProperty fov = new IntProperty("fov", 90, 30, 360);
    /* Pull toward the point of the hitbox nearest the crosshair instead of its
       middle (RotationEngine.nearestOnBox). With the crosshair already on the
       player there is nothing to correct and the view is left alone -- an
       assist that keeps dragging an on-target aim to the centre is the
       pattern aim checks look for. */
    public final BooleanProperty multipoint = new BooleanProperty("multipoint", true, smooth);
    /* After Vape's AimAssist (studied 2026-09-25), rewritten here.
       lead: aim where the target will be, its last tick's movement carried
       this many ticks ahead. A pull toward where they were trails a
       strafing target by a tick of their movement every tick.
       strafe-increase: pull 1.6 times harder while this player strafes away
       from the side the target is on -- the case where the gap opens
       fastest. Capped at a full step, so at speed 10 it changes nothing. */
    public final FloatProperty lead = new FloatProperty("lead", 1.0F, 0.0F, 3.0F, smooth);
    public final BooleanProperty strafeIncrease = new BooleanProperty("strafe-increase", true, smooth);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponOnly::getValue);
    public final BooleanProperty botChecks = new BooleanProperty("bot-check", true);
    public final BooleanProperty team = new BooleanProperty("teams", true);

    /* NORMAL moves the real camera through RotationManager, which writes
       mc.thePlayer.rotationYaw directly. SILENT leaves the camera alone and
       only overrides the yaw/pitch carried by the movement packet.

       Silent aim is not simply "the safer option": it makes the direction you
       report to the server differ from the one your movement is computed
       from, which is a mismatch a server can check on its own. The MoveFix
       module does not close it: it turns the keys toward the yaw movement is
       computed from, and a rotation that only goes on the packet leaves that
       yaw at the camera's, so MoveFix turns them by nothing. move-fix below
       is what closes it (ENGINEERING-NOTES 4.12). */
    /* Smooth only: the framed styles turn the camera, as a hand does. */
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"NORMAL", "SILENT"}, smooth);

    /* How far the silent aim may drift from where the player is actually
       looking. Without a ceiling an assist that nudges a little every tick
       accumulates into a full silent killaura pointing somewhere the player
       never looked, which is both a different feature and a far louder one. */
    public final FloatProperty maxOffset = new FloatProperty("max-offset", 180.0F, 1.0F, 180.0F,
            () -> smooth.getAsBoolean() && this.mode.getValue() == 1);
    /* 2026-10-31 (owner: "silent still does not work, look at Raven bS 16"): the silent turn is Raven bS's --
       from the look the server has, (speed / 30)^2 * 180 degrees a tick at most, randomised, slower near the target. */
    public final IntProperty silentSpeed = new IntProperty("silent-speed", 10, 1, 30,
            () -> smooth.getAsBoolean() && this.mode.getValue() == 1);
    public final PercentProperty silentRandomization = new PercentProperty("silent-randomization", 50,
            () -> smooth.getAsBoolean() && this.mode.getValue() == 1);
    /* 2026-10-31 (a player: "add more settings to silent aim, like hp based, distance based"): which enemy the
       silent aim picks. One in reach still comes before one out of it; among those, the lowest of this. Distance
       is what it always did. */
    public final ModeProperty silentSort = new ModeProperty("silent-sort", 0,
            new String[]{"Distance", "Health", "Hurt time", "Aim angle"},
            () -> smooth.getAsBoolean() && this.mode.getValue() == 1);

    /* SILENT makes the silent aim the yaw movement is computed from too, and
       turns the keys to match, so the player still goes where the camera
       points (to within the 22.5 degrees eight key combinations leave) and
       the server sees movement that agrees with the yaw it was sent. NONE
       keeps moving by the camera -- the mismatch described above. */
    public final ModeProperty moveFix = new ModeProperty("move-fix", 1, new String[]{"NONE", "SILENT"},
            () -> smooth.getAsBoolean() && this.mode.getValue() == 1);

    /*
     * 2026-10-30 (owner: "fix the silent mode"): the silent look is kept as an offset from the camera, so it follows
     * the mouse and builds up toward the target. Before, every tick stepped from the camera again, so with speed 3 it
     * never got past 30 % of the way and never reached the player the camera was not already on; clicks went by the
     * camera's crosshair, not the look the server got; the angle was not on the mouse grid; and with no target it
     * jumped back to the camera in one tick (up to max-offset).
     */
    /* 2026-10-31: SILENT asks the shared engine (management/RotationHelper) for its look; sending it, the
       mouse grid, the move fix, the clicks along it and the way back to the camera are the engine's. */

    private boolean isValidTarget(EntityPlayer entityPlayer) {
        return this.isValidTarget(entityPlayer, true);
    }

    private boolean isValidTarget(EntityPlayer entityPlayer, boolean cameraFov) {
        if (entityPlayer != mc.thePlayer && entityPlayer != mc.thePlayer.ridingEntity) {
            if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
                return false;
            } else if (entityPlayer.deathTime > 0) {
                return false;
            } else if (RotationUtil.distanceToEntity(entityPlayer) > (double) this.range.getValue()) {
                return false;
            } else if (cameraFov && RotationUtil.angleToEntity(entityPlayer) > (float) this.fov.getValue()) {
                return false;
            } else if (RotationUtil.rayTrace(entityPlayer) != null) {
                return false;
            } else if (TeamUtil.isFriend(entityPlayer)) {
                return false;
            } else {
                return (!this.team.getValue() || !TeamUtil.isSameTeam(entityPlayer)) && (!this.botChecks.getValue() || !TeamUtil.isBot(entityPlayer));
            }
        } else {
            return false;
        }
    }

    private boolean isInReach(EntityPlayer entityPlayer) {
        Reach reach = (Reach) Atlas.moduleManager.modules.get(Reach.class);
        double distance = reach.isEnabled() ? (double) reach.range.getValue() : 3.0;
        return RotationUtil.distanceToEntity(entityPlayer) <= distance;
    }

    private boolean isLookingAtBlock() {
        return mc.objectMouseOver != null && mc.objectMouseOver.typeOfHit == MovingObjectType.BLOCK;
    }

    @Override
    public void onEnabled() {
        this.framedAim.reset();
    }

    @Override
    public void onDisabled() {
        this.humanizer.reset();
        this.framedAim.reset();
    }

    /** At the start of the tick, so this tick's clicks follow the silent look (the engine does that). */
    @EventTarget
    public void onTickPre(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.silentAim();
    }

    /**
     * SILENT, the way Raven bS 16 does it (2026-10-31). Decided once at the start of the tick, before the clicks
     * and before the movement, so this tick's click, movement and movement packet all use the same look (it used to
     * be decided at the end of the tick before). The turn starts from the look the server has, not the camera; the
     * target is chosen within fov of that look; and there is no 30-degree leash any more (max-offset 180 by
     * default): with one, a target farther off than that was never reached. A running aura with a target wins.
     */
    private void silentAim() {
        if (this.style.getValue() != 0 || this.mode.getValue() != 1) {
            return;
        }
        if (mc.currentScreen != null || atlas.util.Auras.target() != null) {
            return;
        }
        if (this.weaponOnly.getValue() && !ItemUtil.hasRawUnbreakingEnchant()
                && !(this.allowTools.getValue() && ItemUtil.isHoldingTool())) {
            return;
        }
        if (!PlayerUtil.isAttacking() && this.timer.hasTimeElapsed(350L)) {
            return;
        }
        float cameraYaw = mc.thePlayer.rotationYaw;
        float cameraPitch = mc.thePlayer.rotationPitch;
        /* From the look the server has (Raven: serverRotations). */
        float baseYaw = cameraYaw + MathHelper.wrapAngleTo180_float(atlas.management.RotationHelper.serverYaw() - cameraYaw);
        float basePitch = MathHelper.clamp_float(atlas.management.RotationHelper.serverPitch(), -90.0F, 90.0F);
        EntityPlayer target = null;
        double best = Double.MAX_VALUE;
        boolean bestInReach = false;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!this.isValidTarget(player, false)) {
                continue;
            }
            double yawTo = Math.toDegrees(Math.atan2(player.posZ - mc.thePlayer.posZ, player.posX - mc.thePlayer.posX)) - 90.0;
            if (Math.abs(MathHelper.wrapAngleTo180_double(yawTo - baseYaw)) > this.fov.getValue()) {
                continue;
            }
            boolean inReach = this.isInReach(player);
            double score = this.silentScore(player, baseYaw, basePitch);
            if (inReach && !bestInReach || inReach == bestInReach && score < best) {
                target = player;
                best = score;
                bestInReach = inReach;
            }
        }
        if (target == null || !this.humanizer.reacted(target.getEntityId(), System.currentTimeMillis())) {
            return;
        }
        /* Raven's aim point: the middle of the box at eye height, or the eyes' own height from inside it. */
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double px = (box.minX + box.maxX) / 2.0;
        double pz = (box.minZ + box.maxZ) / 2.0;
        double py = box.isVecInside(eyes) ? eyes.yCoord : target.posY + target.getEyeHeight();
        double dx = px - eyes.xCoord;
        double dy = py - eyes.yCoord;
        double dz = pz - eyes.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-4) {
            return;
        }
        float wantYaw = baseYaw + MathHelper.wrapAngleTo180_float((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) - baseYaw);
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        float[] next = ravenSmooth(baseYaw, basePitch, wantYaw, wantPitch, this.silentSpeed.getValue(),
                this.silentRandomization.getValue());
        if (Float.isNaN(next[0]) || Float.isNaN(next[1])) {
            return;
        }
        float yaw = cameraYaw + limit(MathHelper.wrapAngleTo180_float(next[0] - cameraYaw));
        float pitch = cameraPitch + limit(MathHelper.clamp_float(next[1], -90.0F, 90.0F) - cameraPitch);
        /* Priority 1: below Scaffold (3) and Clutch (7), which need the look to place. */
        atlas.management.RotationHelper.request(this, yaw, pitch, 1, this.moveFix.getValue() != 0, true);
    }

    /** silent-sort: lower is picked first. */
    private double silentScore(EntityPlayer player, float baseYaw, float basePitch) {
        switch (this.silentSort.getValue()) {
            case 1:
                return player.getHealth() + player.getAbsorptionAmount();
            case 2:
                /* One that can be hurt now (hurt time 0) first, then the one nearest to it. */
                return player.hurtTime * 100.0 + RotationUtil.distanceToEntity(player);
            case 3: {
                double dx = player.posX - mc.thePlayer.posX;
                double dz = player.posZ - mc.thePlayer.posZ;
                double dy = player.posY + player.getEyeHeight() - (mc.thePlayer.posY + mc.thePlayer.getEyeHeight());
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                return atlas.util.RotationEngine.angle(yaw, pitch, baseYaw, basePitch);
            }
            default:
                return RotationUtil.distanceToEntity(player);
        }
    }

    /** Raven bS RotationUtils.smoothRotation: speed 1-30 (30 = there at once), randomization 0-100. */
    static float[] ravenSmooth(float baseYaw, float basePitch, float targetYaw, float targetPitch, int speed,
                               int randomization) {
        if (speed >= 30) {
            return new float[]{targetYaw, MathHelper.clamp_float(targetPitch, -90.0F, 90.0F)};
        }
        float deltaYaw = MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
        float deltaPitch = targetPitch - basePitch;
        float magnitude = (float) Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (speed <= 0 || magnitude < 0.001F) {
            return speed <= 0 ? new float[]{baseYaw, basePitch} : new float[]{targetYaw, targetPitch};
        }
        float t = speed / 30.0F;
        float step = t * t * 180.0F;
        float range = 0.6F * (randomization / 100.0F);
        step *= range <= 0.001F ? 1.0F : 1.0F - range / 2.0F + (float) Math.random() * range;
        float proximity = (float) Math.pow(Math.min(1.0F, magnitude / 180.0F), 0.7);
        step *= Math.max(0.8F, 1.0F - randomization / 100.0F * (1.0F - proximity));
        float scale = Math.min(step, magnitude) / magnitude;
        return new float[]{baseYaw + deltaYaw * scale, MathHelper.clamp_float(basePitch + deltaPitch * scale, -90.0F, 90.0F)};
    }

    /** Regular / Linear / Lock On: the view turns every frame. */
    @EventTarget
    public void onRender3D(atlas.events.Render3DEvent event) {
        if (this.isEnabled() && this.style.getValue() != 0) {
            this.framedAim.frame(event.getPartialTicks());
        }
    }

    /** Clamps an offset so the silent aim stays within max-offset of the camera. */
    private float limit(float offset) {
        float max = this.maxOffset.getValue();
        return MathHelper.clamp_float(offset, -max, max);
    }

    private final atlas.humanize.Humanizer humanizer = new atlas.humanize.Humanizer(atlas.humanize.Humanizer.CONSUMER_AIMASSIST);

    public AimAssist() {
        super("AimAssist", false , false, "Assists your aim by subtly adjusting your view towards nearby targets when attacking.");
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (this.isEnabled() && event.getType() == EventType.POST && this.style.getValue() != 0) {
            /* Regular / Linear / Lock On choose their target once a tick. */
            this.framedAim.tick();
            return;
        }
        if (this.isEnabled() && event.getType() == EventType.POST && mc.currentScreen == null
                && this.mode.getValue() != 1) {
            if (!(Boolean) this.weaponOnly.getValue()
                    || ItemUtil.hasRawUnbreakingEnchant()
                    || this.allowTools.getValue() && ItemUtil.isHoldingTool()) {
                boolean attacking = PlayerUtil.isAttacking();
                if (!attacking || !this.isLookingAtBlock()) {
                    if (attacking || !this.timer.hasTimeElapsed(350L)) {
                        List<EntityPlayer> inRange = mc.theWorld
                                .loadedEntityList
                                .stream()
                                .filter(entity -> entity instanceof EntityPlayer)
                                .map(entity -> (EntityPlayer) entity)
                                .filter(this::isValidTarget)
                                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                                .collect(Collectors.toList());
                        if (!inRange.isEmpty()) {
                            if (inRange.stream().anyMatch(this::isInReach)) {
                                inRange.removeIf(entityPlayer -> !this.isInReach(entityPlayer));
                            }
                            EntityPlayer player = inRange.get(0);
                            /* 2026-10-26 (review: apply-aimassist was never read): no turn toward a target until
                               Humanization's reaction to it is over. */
                            if (!this.humanizer.reacted(player.getEntityId(), System.currentTimeMillis())) {
                                return;
                            }
                            if (!(RotationUtil.distanceToEntity(player) <= 0.0)) {
                                AxisAlignedBB axisAlignedBB = player.getEntityBoundingBox();
                                double collisionBorderSize = player.getCollisionBorderSize();
                                AxisAlignedBB hitbox = axisAlignedBB.expand(collisionBorderSize, collisionBorderSize, collisionBorderSize);
                                float ahead = this.lead.getValue();
                                if (ahead > 0.0F) {
                                    hitbox = hitbox.offset((player.posX - player.lastTickPosX) * ahead, 0.0,
                                            (player.posZ - player.lastTickPosZ) * ahead);
                                }
                                /* The look this turns from: the camera, or in SILENT the silent look (camera + offset). */
                                float curYaw = mc.thePlayer.rotationYaw;
                                float curPitch = mc.thePlayer.rotationPitch;
                                float[] rotation;
                                if (this.multipoint.getValue()) {
                                    Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
                                    Vec3 point = RotationEngine.nearestOnBox(RotationEngine.aimBox(hitbox, 0.15, 0.05, 0.85),
                                            eyes, curYaw, curPitch);
                                    rotation = RotationUtil.getRotations(point.xCoord - eyes.xCoord,
                                            point.yCoord - eyes.yCoord, point.zCoord - eyes.zCoord,
                                            curYaw, curPitch, 180.0F,
                                            (float) this.smoothing.getValue() / 100.0F);
                                } else {
                                    rotation = RotationUtil.getRotationsToBox(
                                            hitbox,
                                            curYaw,
                                            curPitch,
                                            180.0F,
                                            (float) this.smoothing.getValue() / 100.0F
                                    );
                                }
                                float yaw = Math.min(Math.abs(this.hSpeed.getValue()), 10.0F);
                                float pitch = Math.min(Math.abs(this.vSpeed.getValue()), 10.0F);
                                float yawStep = 0.1F * yaw;
                                if (this.strafeIncrease.getValue()) {
                                    float side = MathHelper.wrapAngleTo180_float(rotation[0] - curYaw);
                                    float strafe = mc.thePlayer.movementInput.moveStrafe;
                                    /* moveStrafe > 0 is left; a target to the right has a positive side. */
                                    if (side > 0.0F && strafe > 0.0F || side < 0.0F && strafe < 0.0F) {
                                        yawStep = Math.min(1.0F, yawStep * 1.6F);
                                    }
                                }
                                float pitchStep = 0.1F * pitch;
                                float humanYaw = 0.0F;
                                float humanPitch = 0.0F;
                                atlas.humanize.AimError.Adjust adjust = this.humanizer.aim(
                                        MathHelper.wrapAngleTo180_float(rotation[0] - curYaw),
                                        rotation[1] - curPitch,
                                        (float) Math.toDegrees(Math.atan2(Math.hypot(player.posX - player.lastTickPosX,
                                                player.posZ - player.lastTickPosZ), Math.max(0.5, RotationUtil.distanceToEntity(player)))));
                                if (adjust != null) {
                                    if (adjust.lapsing) {
                                        /* A lapse: the hand stops following for a moment. */
                                        return;
                                    }
                                    yawStep = Math.min(1.0F, yawStep * adjust.speed);
                                    pitchStep = Math.min(1.0F, pitchStep * adjust.speed);
                                    humanYaw = adjust.yaw;
                                    humanPitch = adjust.pitch;
                                }
                                /* The tremor/overshoot moves the point aimed at, not the step (the camera keeps
                                   its turn, so a per-step offset would walk away). */
                                float nextYaw = curYaw
                                        + MathHelper.wrapAngleTo180_float(rotation[0] + humanYaw - curYaw) * yawStep;
                                float nextPitch = curPitch + (rotation[1] + humanPitch - curPitch) * pitchStep;

                                Atlas.rotationManager.setRotation(nextYaw, nextPitch, 0, false);
                            }
                        }
                    }
                }
            }
        }
    }



    @Override
    public String[] getSuffix() {
        return new String[]{this.style.getValue() == 0 ? this.mode.getModeString() : this.style.getModeString()};
    }

    @EventTarget(whenDisabled = true)
    public void onPress(KeyEvent event) {
        if (event.getKey() == mc.gameSettings.keyBindAttack.getKeyCode() && !Atlas.moduleManager.modules.get(AutoClicker.class).isEnabled()) {
            this.timer.reset();
        }
    }

    /* 2026-10-31 (audit): a world unload leaves its entities alive (isDead stays false), and one kept here held the
       whole old world in memory -- for good while this module was off. Dropped when a world loads. */
    @atlas.event.EventTarget(whenDisabled = true)
    public void onWorldDropEntities(atlas.events.LoadWorldEvent event) {
        this.framedAim.reset();
    }
}
