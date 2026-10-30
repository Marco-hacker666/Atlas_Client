package atlas.module.modules;

import atlas.events.PacketEvent;
import atlas.management.PacketHub;
import atlas.mixin.IAccessorMinecraft;
import atlas.util.ItemUtil;
import atlas.util.PacketUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Timer;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Mouse;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OpenSlinky's Timer Range, ported for TimerRange's SLINKY and LEGIT modes
 * (2026-10-19, owner: "bring in Slinky's legit TimerRange; ours is useful but
 * brings mitigation").
 *
 * Ours freezes on an attack and then runs at timer-boost for a while. Slinky
 * only acts when, walking forward on the ground at a target that can be hit
 * (hurt time 1 or less), a short prediction says the crosshair will be on the
 * target's box within 1-4 ticks (duration), at least min-dist away. Then:
 *   Regular (SLINKY): the next frame runs those ticks at once (ahead of
 *     time), and the game then stands still for as long, paying the time
 *     back; optionally blocking while it stands.
 *   Legit (LEGIT): the game stands still first, for those ticks, with every
 *     outgoing packet held; then the ticks are run at once and the held
 *     packets go first.
 * Either way no time is gained overall (no timer speed above 1), the jump is
 * at most four ticks, and an attack is clicked on each of those ticks while
 * the crosshair is on a player. A cooldown (seconds) follows each use.
 */
final class TimerRangeSlinky {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String HUB = "TimerRange/out";

    private final TimerRange module;
    private int remaining;
    private boolean frozen;
    private boolean eligible;
    private volatile boolean packetHold;
    private boolean useOwned;
    private float forward;
    private float lateral;
    private double freezeRemaining;
    private double cooldown;
    private long lastFrame;
    private EntityPlayer target;
    private double targetDistanceSq;
    private final ConcurrentLinkedQueue<Packet<?>> held = new ConcurrentLinkedQueue<Packet<?>>();
    /* The trigger log (2026-10-20, owner: LEGIT still seemed to bring mitigation; each use is written with the
       time to the millisecond, so it can be lined up with FlagDetector's HIT-DMG and mitigation lines). */
    private static final java.io.File LOG_DIR = new java.io.File("./config/Atlas/");
    private static final java.text.SimpleDateFormat FILE_STAMP = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final java.text.SimpleDateFormat LINE_STAMP = new java.text.SimpleDateFormat("HH:mm:ss.SSS");
    private java.io.File logTarget;
    private long triggeredAt;
    private int clicks;
    private String triggerTarget = "";

    TimerRangeSlinky(TimerRange module) {
        this.module = module;
        PacketHub.register(HUB, PacketHub.MOVEMENT, this.held::size, this::flushHeld);
    }

    private boolean legit() {
        return this.module.timerBoostMode.getValue() == TimerRange.MODE_LEGIT;
    }

    private static Timer timer() {
        return ((IAccessorMinecraft) mc).getTimer();
    }

    // ---- target, as Slinky's TargetCombatStatus picks it ----------------------

    private static boolean hostile(EntityPlayer p) {
        return p != mc.thePlayer && p.getHealth() > 0.0F && !p.isPlayerSleeping() && !TeamUtil.isFriend(p)
                && !TeamUtil.isSameTeam(p) && !TeamUtil.isBot(p) && TargetFilter.accepts(p);
    }

    private static double boxDistanceSq(Vec3 eye, EntityPlayer p) {
        AxisAlignedBB box = p.getEntityBoundingBox().expand(0.1, 0.1, 0.1);
        double x = clamp(box.minX, box.maxX, eye.xCoord) - eye.xCoord;
        double y = clamp(box.minY, box.maxY, eye.yCoord) - eye.yCoord;
        double z = clamp(box.minZ, box.maxZ, eye.zCoord) - eye.zCoord;
        return x * x + y * y + z * z;
    }

    private static double clamp(double min, double max, double v) {
        return v < min ? min : (v > max ? max : v);
    }

    private void pickTarget() {
        Vec3 eye = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + 1.62, mc.thePlayer.posZ);
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && hit.entityHit instanceof EntityOtherPlayerMP && hostile((EntityPlayer) hit.entityHit)) {
            this.target = (EntityPlayer) hit.entityHit;
            this.targetDistanceSq = boxDistanceSq(eye, this.target);
            return;
        }
        EntityPlayer chosen = null;
        double best = 225.0;
        for (EntityPlayer p : mc.theWorld.playerEntities) {
            if (!hostile(p)) {
                continue;
            }
            double d = boxDistanceSq(eye, p);
            if (d > best) {
                continue;
            }
            double dx = p.posX - eye.xCoord;
            double dz = p.posZ - eye.zCoord;
            double dy = p.posY - eye.yCoord;
            float yawTo = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitchTo = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            float yaw = net.minecraft.util.MathHelper.wrapAngleTo180_float(yawTo - mc.thePlayer.rotationYaw);
            float pitch = pitchTo - mc.thePlayer.rotationPitch;
            if (yaw * yaw + pitch + pitch > 4900.0F) {
                continue;
            }
            best = d;
            chosen = p;
        }
        this.target = chosen;
        this.targetDistanceSq = best;
    }

    // ---- once a tick (TickEvent PRE) ------------------------------------------------

    void tick() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        pickTarget();
        GameSettings keys = mc.gameSettings;
        boolean inputReady = mc.currentScreen == null;
        if (this.remaining > 0) {
            this.remaining--;
            if (this.remaining == 0 && !legit()) {
                beginFreeze();
            }
            if (inputReady && this.target != null) {
                MovingObjectPosition hit = mc.objectMouseOver;
                if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
                    KeyBinding.onTick(keys.keyBindAttack.getKeyCode());
                    this.clicks++;
                }
            }
            if (this.remaining == 0) {
                log("DONE " + this.clicks + " click(s) | " + (System.currentTimeMillis() - this.triggeredAt) + "ms after trigger | "
                        + this.triggerTarget + " hurt " + (this.target == null ? "-" : String.valueOf(this.target.hurtTime)));
            }
            return;
        }
        this.eligible = false;
        if (inputReady && this.target != null && this.targetDistanceSq >= 3.0 && mc.thePlayer.onGround
                && this.target.hurtTime <= 1
                && (!this.module.slRequireMouse.getValue() || keys.keyBindAttack.isKeyDown())
                && (!this.module.slRequireWeapon.getValue() || ItemUtil.isHoldingSword())
                && (!this.module.slRequireNotUsing.getValue() || !mc.thePlayer.isUsingItem())
                && mc.thePlayer.motionY <= 0.0 && !keys.keyBindJump.isKeyDown()) {
            Vec3 self = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + 1.62, mc.thePlayer.posZ);
            Vec3 remote = server(this.target).addVector(0.0, 1.62, 0.0);
            if (mc.theWorld.rayTraceBlocks(self, remote, false, true, true) == null) {
                this.forward = (keys.keyBindForward.isKeyDown() ? 1.0F : 0.0F) - (keys.keyBindBack.isKeyDown() ? 1.0F : 0.0F);
                this.lateral = (keys.keyBindLeft.isKeyDown() ? 1.0F : 0.0F) - (keys.keyBindRight.isKeyDown() ? 1.0F : 0.0F);
                this.eligible = this.forward > 0.0F;
            }
        }
    }

    /** Where the server last put this player (the end of the client's smoothing). */
    private static Vec3 server(EntityPlayer p) {
        if (!(p instanceof EntityOtherPlayerMP)) {
            return new Vec3(p.posX, p.posY, p.posZ);
        }
        return new Vec3(p.serverPosX / 32.0, p.serverPosY / 32.0, p.serverPosZ / 32.0);
    }

    // ---- once a frame (Render3DEvent) -------------------------------------------------

    void frame(float renderPartialTicks) {
        long now = System.nanoTime();
        double elapsed = this.lastFrame == 0L ? 0.0 : Math.min(0.25, (now - this.lastFrame) / 1.0E9);
        this.lastFrame = now;
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        Timer timer = timer();
        if (this.frozen) {
            this.freezeRemaining -= elapsed;
            if (this.freezeRemaining > 0.0) {
                atlas.util.TimerControl.set(this, 0.0F);
                return;
            }
            atlas.util.TimerControl.release(this);
            this.frozen = false;
            this.packetHold = false;
            int flushed = this.held.size();
            flushHeld();
            log("RESUME stood " + (System.currentTimeMillis() - this.triggeredAt) + "ms | let go " + flushed + "p | "
                    + (legit() ? "running " + this.remaining + "t now" : "paid back"));
            if (!legit() && this.useOwned) {
                releaseUse();
            }
            if (legit()) {
                /* The ticks the game stood still for, run at once on the next frame. */
                timer.elapsedPartialTicks = (float) this.remaining;
            }
        }
        if (this.cooldown > 0.0) {
            this.cooldown -= elapsed;
            return;
        }
        if (!this.eligible || mc.currentScreen != null || this.target == null) {
            return;
        }
        EntityPlayer t = this.target;
        Vec3 velocity = new Vec3(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
        Vec3 self = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + 1.62, mc.thePlayer.posZ);
        Vec3 position = new Vec3(t.posX, t.posY, t.posZ);
        Vec3 desired = server(t);
        int targetTicks = position.squareDistanceTo(desired) > 1.0E-4 ? 3 : 0;
        Vec3 reach = mc.thePlayer.getLookVec();
        reach = new Vec3(reach.xCoord * 3.0, reach.yCoord * 3.0, reach.zCoord * 3.0);
        boolean legit = legit();
        int steps = legit ? this.module.slDurationLegit.getValue() : this.module.slDuration.getValue();
        for (int step = 1; step <= steps; step++) {
            AxisAlignedBB box = new AxisAlignedBB(position.xCoord - 0.4, position.yCoord - 0.1, position.zCoord - 0.4,
                    position.xCoord + 0.4, position.yCoord + 1.9, position.zCoord + 0.4);
            Vec3 end = self.addVector(reach.xCoord, reach.yCoord, reach.zCoord);
            if (box.isVecInside(self) || box.calculateIntercept(self, end) != null) {
                Vec3 nearest = new Vec3(clamp(box.minX, box.maxX, self.xCoord), clamp(box.minY, box.maxY, self.yCoord),
                        clamp(box.minZ, box.maxZ, self.zCoord));
                double distance = self.squareDistanceTo(nearest);
                if (distance > 0.0) {
                    float radius = this.module.slMinDist.getValue();
                    if (radius * radius > distance) {
                        return;
                    }
                    if (legit) {
                        beginFreeze();
                    } else {
                        timer.elapsedPartialTicks = (float) step;
                    }
                    this.remaining = step;
                    this.freezeRemaining = (step - renderPartialTicks) * 0.05;
                    this.cooldown = this.module.slCooldown.getValue();
                    this.triggeredAt = System.currentTimeMillis();
                    this.clicks = 0;
                    this.triggerTarget = t.getName();
                    atlas.util.ActionLedger.note("TimerRange", legit ? "timer-freeze" : "timer-ahead");
                    log("TRIGGER " + (legit ? "LEGIT" : "SLINKY") + " " + step + "t | " + t.getName()
                            + String.format(" box %.2f", Math.sqrt(distance)) + " hurt " + t.hurtTime
                            + " | ping " + ping() + "ms | speed " + String.format("%.2f",
                            Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ)) + "/t");
                    return;
                }
            }
            double[] move = movement(mc.thePlayer.rotationYaw, this.forward, this.lateral, mc.thePlayer.getAIMoveSpeed());
            velocity = velocity.addVector(move[0], 0.0, move[1]);
            self = self.add(velocity);
            velocity = new Vec3(velocity.xCoord * 0.546, velocity.yCoord * 0.546, velocity.zCoord * 0.546);
            if (legit) {
                double length = Math.hypot(velocity.xCoord, velocity.zCoord);
                Vec3 difference = self.subtract(desired);
                double across = Math.hypot(difference.xCoord, difference.zCoord);
                if (across > 1.0E-6) {
                    double scale = length / across;
                    desired = desired.addVector(difference.xCoord * scale, 0.0, difference.zCoord * scale);
                }
            }
            if (targetTicks > 0) {
                Vec3 to = desired.subtract(position);
                position = position.addVector(to.xCoord / targetTicks, to.yCoord / targetTicks, to.zCoord / targetTicks);
                targetTicks--;
            }
        }
    }

    private static int ping() {
        try {
            return mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID()).getResponseTime();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private void log(String line) {
        if (this.logTarget == null) {
            this.logTarget = new java.io.File(LOG_DIR, "timerrange-" + FILE_STAMP.format(new java.util.Date()) + ".txt");
        }
        atlas.util.AsyncLog.append(this.logTarget, LINE_STAMP.format(new java.util.Date()) + "  " + line);
    }

    /** Vanilla's ground input step, as Slinky's RotationMath.movement. */
    static double[] movement(float yaw, float forward, float sideways, float speed) {
        float f = forward * 0.98F;
        float s = sideways * 0.98F;
        float squared = s * s + f * f;
        if (squared < 1.0E-4F) {
            return new double[]{0.0, 0.0};
        }
        float scale = speed / Math.max(1.0F, (float) Math.sqrt(squared));
        s *= scale;
        f *= scale;
        float angle = (float) (yaw * Math.PI / 180.0);
        float sin = net.minecraft.util.MathHelper.sin(angle);
        float cos = net.minecraft.util.MathHelper.cos(angle);
        return new double[]{s * cos - f * sin, f * cos + s * sin};
    }

    private void beginFreeze() {
        atlas.util.TimerControl.set(this, 0.0F);
        this.frozen = true;
        if (legit()) {
            this.packetHold = true;
        } else if (this.module.slBlockWhileFrozen.getValue() && ItemUtil.isHoldingSword()) {
            KeyBinding key = mc.gameSettings.keyBindUseItem;
            KeyBinding.setKeyBindState(key.getKeyCode(), true);
            KeyBinding.onTick(key.getKeyCode());
            this.useOwned = true;
        }
    }

    private void releaseUse() {
        int code = mc.gameSettings.keyBindUseItem.getKeyCode();
        boolean physical = code < 0 ? Mouse.isButtonDown(code + 100) : org.lwjgl.input.Keyboard.isKeyDown(code);
        KeyBinding.setKeyBindState(code, physical);
        this.useOwned = false;
    }

    // ---- packets -----------------------------------------------------------------------

    /** Legit: everything going out while the game stands still waits for it to move again. */
    void onPacket(PacketEvent event) {
        if (this.packetHold && PacketHub.take(event, HUB)) {
            this.held.add(event.getPacket());
        }
    }

    /** A correction or a world change: stop standing still at once. */
    void interrupt() {
        if (this.frozen || this.packetHold || this.useOwned) {
            reset();
        }
    }

    private void flushHeld() {
        Packet<?> packet;
        while ((packet = this.held.poll()) != null) {
            if (mc.getNetHandler() != null) {
                PacketUtil.sendPacketNoEvent(packet);
            }
        }
    }

    void reset() {
        if (this.frozen || ((atlas.mixin.IAccessorTimer) timer()).getTimerSpeed() == 0.0F) {
            atlas.util.TimerControl.release(this);
        }
        this.frozen = false;
        this.packetHold = false;
        flushHeld();
        if (this.useOwned) {
            releaseUse();
        }
        this.remaining = 0;
        this.eligible = false;
        this.freezeRemaining = 0.0;
        this.cooldown = 0.0;
        this.forward = 0.0F;
        this.lateral = 0.0F;
    }

    String status() {
        return this.frozen ? "frozen" : (this.remaining > 0 ? "ahead " + this.remaining : null);
    }
}
