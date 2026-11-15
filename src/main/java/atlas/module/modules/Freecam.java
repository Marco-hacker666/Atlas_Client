package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LeftClickMouseEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.RightClickMouseEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.util.KeyBindUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovementInput;

/**
 * Scout around while your body stays put (rewritten 2026-11-15, owner: "fix Freecam, look at how LiquidBounce does it").
 *
 * The old one moved the player itself with noClip and cancelled every movement packet. The game resets noClip at the
 * start of every player tick, so it never went through walls, and a player that sends no movement packets at all is
 * what an anticheat looks for. LiquidBounce's FreeCam does the opposite, and so does this: the body is left where it
 * is and only the camera moves.
 *
 * - A camera entity, never added to the world, is the render view: the world is drawn and the crosshair traced from
 *   it, and the mouse turns it (MixinEntity.setAngles).
 * - The body's movement input is cleared every tick, so it stands still and keeps sending the movement packets a
 *   still player sends. The game only sends them for the render view entity (EntityPlayerSP.isCurrentViewEntity),
 *   so MixinEntityPlayerSP keeps that true for the body while this is on.
 * - The body is drawn where it stands (it is no longer the render view); the hand is not drawn at the camera.
 * - Attacks, use, block breaking and the block outline are off unless camera-interact is on: from the camera they
 *   would reach through walls and from far away, which a server checks.
 */
public class Freecam extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Blocks a tick at speed 1. */
    private static final double UNIT = 0.25;

    public final FloatProperty speed = new FloatProperty("speed", 2.5F, 0.5F, 10.0F);
    /** Clicks from the camera: through walls and from anywhere. Off: no attack, use or breaking while flying. */
    public final BooleanProperty cameraInteract = new BooleanProperty("camera-interact", false);
    /** Back to the body when it takes damage. */
    public final BooleanProperty leaveOnDamage = new BooleanProperty("leave-on-damage", true);
    /** Sneaking when it starts: the body keeps sneaking, so it cannot slide off an edge (as Meteor's Freecam). */
    public final BooleanProperty staySneaking = new BooleanProperty("stay-sneaking", true);
    /**
     * Rebuild the drawn chunks when it starts and stops (Meteor's reload-chunks). Off by default since 2026-11-15:
     * with OptiFine every chunk is built again over the next seconds, so only the near ones show and it stutters.
     */
    public final BooleanProperty reloadChunks = new BooleanProperty("reload-chunks", false);

    private static Freecam instance;
    private EntityOtherPlayerMP camera;
    private float lastHealth;
    private boolean sneakingAtStart;

    public Freecam() {
        super("Freecam", false, false, "Scout around freely while your body stays put.");
        instance = this;
    }

    /** The camera while flying, else null. */
    public static EntityOtherPlayerMP camera() {
        Freecam freecam = instance;
        return freecam != null && freecam.isEnabled() ? freecam.camera : null;
    }

    /** The body keeps sending its movement packets while the camera is the render view. */
    public static boolean keepsBody(Object player) {
        EntityOtherPlayerMP camera = camera();
        return camera != null && player == mc.thePlayer && mc.getRenderViewEntity() == camera;
    }

    /** Whether clicks and the crosshair are taken away from the camera. */
    public static boolean blocksInteraction() {
        Freecam freecam = instance;
        return camera() != null && freecam != null && !freecam.cameraInteract.getValue();
    }

    /** The mouse turns the camera, as Entity.setAngles turns an entity. */
    public static void turn(float yawDelta, float pitchDelta) {
        EntityOtherPlayerMP camera = camera();
        if (camera != null) {
            camera.setAngles(yawDelta, pitchDelta);
            camera.rotationYawHead = camera.rotationYaw;
        }
    }

    @Override
    public void onEnabled() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            this.setEnabled(false);
            return;
        }
        EntityOtherPlayerMP cam = new EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.getGameProfile());
        cam.copyLocationAndAnglesFrom(mc.thePlayer);
        cam.rotationYawHead = mc.thePlayer.rotationYaw;
        cam.prevRotationYaw = cam.rotationYaw;
        cam.prevRotationPitch = cam.rotationPitch;
        cam.noClip = true;
        settle(cam);
        updateChunk(cam);
        this.camera = cam;
        this.lastHealth = mc.thePlayer.getHealth();
        this.sneakingAtStart = mc.thePlayer.movementInput != null && mc.thePlayer.movementInput.sneak;
        mc.setRenderViewEntity(cam);
        /* Cave culling stays on (owner, 2026-11-15: with it off everywhere "the distance is short and the view
           stutters": every chunk, underground ones too, had to be built and drawn). Inside a block the camera counts
           as a spectator (MixinEntityRenderer), which is where the game itself turns it off. */
        reload();
    }

    @Override
    public void onDisabled() {
        boolean wasFlying = this.camera != null;
        this.camera = null;
        if (wasFlying) {
            reload();
        }
        if (mc.thePlayer != null) {
            mc.setRenderViewEntity(mc.thePlayer);
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindSneak.getKeyCode());
            KeyBindUtil.updateKeyState(mc.gameSettings.keyBindJump.getKeyCode());
        }
    }

    @EventTarget
    public void onWorldLoad(LoadWorldEvent event) {
        this.setEnabled(false);
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        EntityOtherPlayerMP cam = this.camera;
        if (cam == null || mc.thePlayer == null || mc.theWorld == null || cam.worldObj != mc.theWorld
                || mc.getRenderViewEntity() != cam) {
            /* A respawn or a dimension change puts the render view back on the new player. */
            this.setEnabled(false);
            return;
        }
        float health = mc.thePlayer.getHealth();
        if (this.leaveOnDamage.getValue() && (health < this.lastHealth || mc.thePlayer.isDead)) {
            this.setEnabled(false);
            return;
        }
        this.lastHealth = health;

        settle(cam);
        if (mc.currentScreen == null) {
            double step = UNIT * this.speed.getValue()
                    * (KeyBindUtil.isKeyDown(mc.gameSettings.keyBindSprint.getKeyCode()) ? 2.0 : 1.0);
            float forward = axis(mc.gameSettings.keyBindForward.getKeyCode(), mc.gameSettings.keyBindBack.getKeyCode());
            float strafe = axis(mc.gameSettings.keyBindLeft.getKeyCode(), mc.gameSettings.keyBindRight.getKeyCode());
            float up = axis(mc.gameSettings.keyBindJump.getKeyCode(), mc.gameSettings.keyBindSneak.getKeyCode());
            double dx = 0.0;
            double dz = 0.0;
            if (forward != 0.0F || strafe != 0.0F) {
                double length = Math.sqrt(forward * forward + strafe * strafe);
                double f = forward / length;
                double s = strafe / length;
                float yaw = cam.rotationYaw * (float) Math.PI / 180.0F;
                double sin = MathHelper.sin(yaw);
                double cos = MathHelper.cos(yaw);
                dx = (s * cos - f * sin) * step;
                dz = (f * cos + s * sin) * step;
            }
            cam.setPosition(cam.posX + dx, cam.posY + up * step, cam.posZ + dz);
        }
        updateChunk(cam);
    }

    /**
     * The body stands still (owner: "with Freecam on the body itself must not move", as Meteor): no movement or jump
     * reaches it while flying, only the sneak it had when Freecam started (stay-sneaking).
     */
    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (camera() == null || mc.thePlayer == null) {
            return;
        }
        MovementInput input = mc.thePlayer.movementInput;
        input.moveForward = 0.0F;
        input.moveStrafe = 0.0F;
        input.jump = false;
        input.sneak = this.staySneaking.getValue() && this.sneakingAtStart;
    }

    @EventTarget
    public void onLeftClick(LeftClickMouseEvent event) {
        if (blocksInteraction()) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onRightClick(RightClickMouseEvent event) {
        if (blocksInteraction()) {
            event.setCancelled(true);
        }
    }

    /** Rebuilds the drawn chunks for the new view (reload-chunks), as F3+A does. */
    private void reload() {
        if (this.reloadChunks.getValue() && mc.renderGlobal != null && mc.theWorld != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    /** +1, -1 or 0 from two keys held. */
    private static float axis(int positive, int negative) {
        return (KeyBindUtil.isKeyDown(positive) ? 1.0F : 0.0F) - (KeyBindUtil.isKeyDown(negative) ? 1.0F : 0.0F);
    }

    /** Where the camera was last tick, for the frame-to-frame interpolation the renderer does. */
    private static void settle(EntityOtherPlayerMP cam) {
        cam.prevPosX = cam.lastTickPosX = cam.posX;
        cam.prevPosY = cam.lastTickPosY = cam.posY;
        cam.prevPosZ = cam.lastTickPosZ = cam.posZ;
        cam.prevRotationYaw = cam.rotationYaw;
        cam.prevRotationPitch = cam.rotationPitch;
        cam.prevRotationYawHead = cam.rotationYawHead;
    }

    /**
     * The chunk the camera is in. The world sets this for entities in it; the renderer moves its grid of chunks when
     * the view entity's chunk changes, so without it nothing past the starting render distance would be drawn.
     */
    private static void updateChunk(EntityOtherPlayerMP cam) {
        cam.chunkCoordX = MathHelper.floor_double(cam.posX) >> 4;
        cam.chunkCoordY = MathHelper.clamp_int(MathHelper.floor_double(cam.posY) >> 4, 0, 15);
        cam.chunkCoordZ = MathHelper.floor_double(cam.posZ) >> 4;
    }
}
