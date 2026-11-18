package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import java.util.function.Supplier;
import atlas.event.types.Priority;
import atlas.events.AttackEvent;
import atlas.events.PacketEvent;
import atlas.events.Render3DEvent;
import atlas.events.TickEvent;
import atlas.management.Arbiter;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.RandomUtil;
import atlas.util.RenderUtil;
import atlas.util.RotationUtil;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BackTrack extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int MAX_HELD = 400;

    private static final double BORDER = 0.1;

    public final FloatProperty range;

    public final FloatProperty minRange;

    public final IntProperty maxHurtTime;

    public final IntProperty cooldown;

    public final BooleanProperty weaponsOnly;
    public final BooleanProperty adaptive;
    public final IntProperty normalDelay;
    public final IntProperty adaptiveDelay;
    public final BooleanProperty releaseOnHit;

    public final IntProperty randomize;

    public final ModeProperty hold;
    public final BooleanProperty interruptLagRange;
    public final BooleanProperty players;
    public final BooleanProperty teams;
    public final BooleanProperty botCheck;
    public final BooleanProperty esp;

    public final BooleanProperty espHead;
    public final BooleanProperty hitStats;

    public final BooleanProperty onlyInCombat;
    public final BooleanProperty requireAttackKey;

    private volatile boolean attackKeyHeld;

    private final Object lock = new Object();
    private final ConcurrentLinkedQueue<Packet<?>> incomingQueue = new ConcurrentLinkedQueue<>();

    private volatile EntityLivingBase target;

    private volatile Vec3 realPosition;

    public EntityLivingBase getBacktrackTarget() {
        return this.target;
    }

    public Vec3 getBacktrackPosition() {
        return this.realPosition;
    }
    private volatile long backtrackStartTime = 0L;
    private volatile int rolledDelay = 0;

    private volatile int holds = 0;

    private volatile long lastReleaseAt = 0L;

    private EntityLivingBase lastAttacked;
    private long lastAttackTime = 0L;
    private static final long COMBAT_LOCK_MS = 3000L;
    private boolean lagRangeInterrupted = false;
    private int targetHurtTime = 0;
    private static BackTrack instance;

    private Vec3 espPrevious;
    private Vec3 espCurrent;
    private float espAlphaPrevious;
    private float espAlpha;

    private static final long HIT_WINDOW_MS = 600L;
    private int swings = 0;
    private int landed = 0;
    private long swingPendingAt = 0L;
    private int lastTargetHurtTime = 0;

    public BackTrack() {
        super("Backtrack", false);

        atlas.management.PacketHub.registerIncoming("Backtrack", () -> this.incomingQueue.size(), this::releaseIncoming);

        atlas.management.PacketHolds.register(() -> {
            int count = this.incomingQueue.size();
            return count == 0 ? null : new atlas.management.PacketHolds.Hold("Backtrack", atlas.management.PacketHolds.Direction.IN, count,
                    this.backtrackStartTime, "the target's older position",
                    "after the delay, out of range, or on a catch");
        }, new atlas.management.PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            @Override
            public long ceilingMs() {
                return Math.max(normalDelay.getValue(), adaptiveDelay.getValue()) + randomize.getValue()
                        + atlas.management.PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                releaseIncoming();
            }
        });

        this.range = new FloatProperty("range", 3.0F, 1.0F, 8.0F);
        this.minRange = new FloatProperty("min-range", 0.0F, 0.0F, 4.0F);
        this.maxHurtTime = new IntProperty("max-hurt-time", 10, 0, 10);

        this.cooldown = new IntProperty("cooldown", 250, 0, 2000);
        this.weaponsOnly = new BooleanProperty("weapons-only", false);
        this.adaptive = new BooleanProperty("adaptive", true);

        this.normalDelay = new IntProperty("normal-delay", 100, 50, 1000, () -> !this.adaptive.getValue());
        this.adaptiveDelay = new IntProperty("adaptive-delay", 100, 50, 1000, this.adaptive::getValue);
        this.releaseOnHit = new BooleanProperty("release-on-hit", true);
        this.randomize = new IntProperty("randomize", 25, 0, 200);
        this.hold = new ModeProperty("hold", 1, new String[]{"TARGET", "ALL"});
        this.interruptLagRange = new BooleanProperty("interrupt-lagrange", true);
        this.players = new BooleanProperty("players", true);
        this.teams = new BooleanProperty("teams", true);
        this.botCheck = new BooleanProperty("bot-check", true);
        this.esp = new BooleanProperty("esp", true);
        this.espHead = new BooleanProperty("esp-head", true, this.esp::getValue);
        this.hitStats = new BooleanProperty("hit-stats", false);
        this.onlyInCombat = new BooleanProperty("only-in-combat", true);
        this.requireAttackKey = new BooleanProperty("require-attack-key", false);
        instance = this;
    }

    @Override
    public void onEnabled() {
        incomingQueue.clear();
        realPosition = null;
        backtrackStartTime = 0L;
        rolledDelay = 0;
        target = null;
        lastAttacked = null;
        lastAttackTime = 0L;
        lagRangeInterrupted = false;
        targetHurtTime = 0;
        espPrevious = null;
        espCurrent = null;
        espAlpha = 0.0F;
        espAlphaPrevious = 0.0F;
        swings = 0;
        landed = 0;
        swingPendingAt = 0L;
        lastTargetHurtTime = 0;
        holds = 0;
    }

    @Override
    public void onDisabled() {
        setLagRangeEnabled(true);
        releaseIncoming();
        incomingQueue.clear();
        realPosition = null;
        target = null;
        lastAttacked = null;
        lastAttackTime = 0L;
        espPrevious = null;
        espCurrent = null;
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(atlas.events.LoadWorldEvent event) {
        synchronized (lock) {
            incomingQueue.clear();
            backtrackStartTime = 0L;
            rolledDelay = 0;
            realPosition = null;
        }
        target = null;
        espPrevious = null;
        espCurrent = null;
        espAlpha = 0.0F;
        espAlphaPrevious = 0.0F;
    }

    private int baseDelay() {
        return adaptive.getValue() ? adaptiveDelay.getValue() : normalDelay.getValue();
    }

    public int holdMs() {
        return this.isEnabled() ? currentMaxDelay() : 0;
    }

    public int heldCount() {
        return incomingQueue.size();
    }

    private int currentMaxDelay() {
        return rolledDelay > 0 ? rolledDelay : baseDelay();
    }

    private int rollDelay() {
        int base = baseDelay();
        int spread = randomize.getValue();
        if (spread <= 0) return base;
        return Math.max(1, base - spread + RandomUtil.nextInt(0, spread * 2));
    }

    private LagRange getLagRange() {
        Module m = Atlas.moduleManager.getModule(LagRange.class);
        return (m instanceof LagRange) ? (LagRange) m : null;
    }

    private void setLagRangeEnabled(boolean enabled) {
        if (!interruptLagRange.getValue()) return;
        LagRange lr = getLagRange();
        if (lr == null) return;
        if (enabled && lagRangeInterrupted) {
            lagRangeInterrupted = false;
            lr.setEnabled(true);
        } else if (!enabled && !lagRangeInterrupted && lr.isEnabled()) {
            lagRangeInterrupted = true;
            lr.setEnabled(false);
        }
    }

    private boolean isInCombat() {
        if (lastAttacked == null || lastAttacked != target || lastAttacked.isDead) return false;
        return System.currentTimeMillis() - lastAttackTime <= COMBAT_LOCK_MS;
    }

    private static Vec3 serverPosition(Entity entity) {
        return new Vec3(entity.serverPosX / 32.0, entity.serverPosY / 32.0, entity.serverPosZ / 32.0);
    }

    private static AxisAlignedBB boxAt(Entity entity, Vec3 pos) {
        double hw = entity.width / 2.0;
        return new AxisAlignedBB(pos.xCoord - hw, pos.yCoord, pos.zCoord - hw,
                pos.xCoord + hw, pos.yCoord + entity.height, pos.zCoord + hw);
    }

    private static double reachTo(Entity entity, Vec3 pos) {
        AxisAlignedBB box = boxAt(entity, pos).expand(BORDER, BORDER, BORDER);
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double x = Math.max(box.minX, Math.min(box.maxX, eyes.xCoord)) - eyes.xCoord;
        double y = Math.max(box.minY, Math.min(box.maxY, eyes.yCoord)) - eyes.yCoord;
        double z = Math.max(box.minZ, Math.min(box.maxZ, eyes.zCoord)) - eyes.zCoord;
        return Math.sqrt(x * x + y * y + z * z);
    }

    private Vec3 advance(Vec3 from, Packet<?> packet) {
        EntityLivingBase t = target;
        if (t == null || mc.theWorld == null) return null;
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            if (p.getEntity(mc.theWorld) != t) return null;
            return from.addVector(p.func_149062_c() / 32.0, p.func_149061_d() / 32.0, p.func_149064_e() / 32.0);
        }
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            if (p.getEntityId() != t.getEntityId()) return null;
            return new Vec3(p.getX() / 32.0, p.getY() / 32.0, p.getZ() / 32.0);
        }
        return null;
    }

    private boolean worthHolding(Vec3 real) {
        EntityLivingBase t = target;
        if (t == null || t.isDead || mc.thePlayer == null) return false;
        if (backtrackStartTime > 0 && System.currentTimeMillis() - backtrackStartTime > currentMaxDelay()) return false;
        double far = reachTo(t, real);
        if (far > range.getValue() || far < minRange.getValue()) return false;
        double near = reachTo(t, serverPosition(t));
        return adaptive.getValue() ? far > near : far > near + 0.1;
    }

    private boolean mayOpen(EntityLivingBase t) {
        if (onlyInCombat.getValue() && (lastAttacked != t || System.currentTimeMillis() - lastAttackTime > 1000L)) return false;
        if (requireAttackKey.getValue() && !attackKeyHeld) return false;
        if (cooldown.getValue() > 0 && System.currentTimeMillis() - lastReleaseAt < cooldown.getValue()) return false;
        if (maxHurtTime.getValue() < 10 && t.hurtTime > maxHurtTime.getValue()) return false;
        if (weaponsOnly.getValue() && !atlas.util.ItemUtil.hasRawUnbreakingEnchant()) return false;
        return true;
    }

    private boolean flushes(Packet<?> p) {
        if (p instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) p).getEntityID() == mc.thePlayer.getEntityId();
        }
        return p instanceof S08PacketPlayerPosLook || p instanceof S27PacketExplosion
                || p instanceof S07PacketRespawn || p instanceof S01PacketJoinGame
                || p instanceof S40PacketDisconnect;
    }

    private static boolean hitOn(EntityLivingBase t, Packet<?> p) {
        if (p instanceof net.minecraft.network.play.server.S19PacketEntityStatus) {
            net.minecraft.network.play.server.S19PacketEntityStatus status =
                    (net.minecraft.network.play.server.S19PacketEntityStatus) p;
            return status.getOpCode() == 2 && status.getEntity(mc.theWorld) == t;
        }
        if (p instanceof S12PacketEntityVelocity) {
            return ((S12PacketEntityVelocity) p).getEntityID() == t.getEntityId();
        }
        return false;
    }

    private boolean aboutTarget(Packet<?> p) {
        EntityLivingBase t = target;
        if (t == null) return false;
        if (p instanceof S19PacketEntityHeadLook) return ((S19PacketEntityHeadLook) p).getEntity(mc.theWorld) == t;
        if (p instanceof S12PacketEntityVelocity) return ((S12PacketEntityVelocity) p).getEntityID() == t.getEntityId();
        return false;
    }

    private void releaseIncoming() {
        synchronized (lock) {
            if (!incomingQueue.isEmpty()) {
                lastReleaseAt = System.currentTimeMillis();
            }
            if (mc.getNetHandler() == null) {
                incomingQueue.clear();
            } else {
                Packet<?> p;
                while ((p = incomingQueue.poll()) != null) processPacketUnchecked(p);
            }
            backtrackStartTime = 0L;
            rolledDelay = 0;
            realPosition = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.network.INetHandler> void processPacketUnchecked(Packet<T> packet) {
        try {
            packet.processPacket((T) Minecraft.getMinecraft().getNetHandler());
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
        }
    }

    @EventTarget
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || mc.thePlayer == null) return;
        if (event.getTarget() instanceof EntityLivingBase) {
            lastAttacked = (EntityLivingBase) event.getTarget();
            lastAttackTime = System.currentTimeMillis();
            if (hitStats.getValue()) {
                swings++;
                swingPendingAt = lastAttackTime;
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() != EventType.RECEIVE) return;
        Module scaffold = Atlas.moduleManager.getModule(Scaffold.class);
        if (scaffold != null && scaffold.isEnabled()) {
            if (!incomingQueue.isEmpty()) releaseIncoming();
            return;
        }
        handleIncoming(event);
    }

    private void handleIncoming(PacketEvent event) {
        Packet<?> packet = event.getPacket();
        synchronized (lock) {
            boolean holding = !incomingQueue.isEmpty();
            if (Arbiter.catching()) {
                if (holding) {
                    releaseIncoming();
                    Arbiter.yielded(getName());
                }
                return;
            }
            if (flushes(packet)) {
                if (holding) releaseIncoming();
                return;
            }
            EntityLivingBase t = target;
            if (t == null) {
                if (holding) releaseIncoming();
                return;
            }
            if (holding && releaseOnHit.getValue() && hitOn(t, packet)) {
                releaseIncoming();
                return;
            }
            Vec3 from = holding && realPosition != null ? realPosition : serverPosition(t);
            Vec3 next = advance(from, packet);
            if (next != null && !holding && !mayOpen(t)) {
                return;
            }
            if (next != null) {
                if (!holding) {
                    backtrackStartTime = System.currentTimeMillis();
                    rolledDelay = rollDelay();
                }
                if (worthHolding(next) && incomingQueue.size() < MAX_HELD
                        && atlas.management.PacketHub.take(event, "Backtrack")) {
                    if (!holding) holds++;
                    realPosition = next;
                    incomingQueue.add(packet);
                } else {
                    releaseIncoming();
                }
                return;
            }
            if (holding && (hold.getValue() == 1 || aboutTarget(packet))
                    && atlas.management.PacketHub.take(event, "Backtrack")) {
                incomingQueue.add(packet);
            }
        }
    }

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (!isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;
        if (event.getType() == EventType.PRE) tickPre();
    }

    private void tickPre() {
        attackKeyHeld = atlas.util.KeyBindUtil.isKeyDown(mc.gameSettings.keyBindAttack.getKeyCode());
        if (hitStats.getValue() && lastAttacked != null) {
            int hurt = lastAttacked.hurtTime;
            if (hurt > lastTargetHurtTime && swingPendingAt > 0L
                    && System.currentTimeMillis() - swingPendingAt <= HIT_WINDOW_MS) {
                landed++;
                swingPendingAt = 0L;
            }
            lastTargetHurtTime = hurt;
        }

        EntityLivingBase newTarget = resolveTarget();
        if (newTarget != target) {
            setLagRangeEnabled(true);
            releaseIncoming();
            targetHurtTime = newTarget != null ? newTarget.hurtTime : 0;
            espPrevious = null;
            espCurrent = null;
            espAlpha = 0.0F;
        }
        target = newTarget;

        if (target == null) {
            setLagRangeEnabled(true);
            updateEsp(null, false);
            return;
        }

        boolean hitLanded = target.hurtTime > targetHurtTime;
        targetHurtTime = target.hurtTime;
        if (!incomingQueue.isEmpty()) {
            Vec3 real = realPosition;
            boolean release = real == null || !worthHolding(real);
            if (mc.thePlayer.hurtTime == mc.thePlayer.maxHurtTime && mc.thePlayer.maxHurtTime > 0) release = true;

            if (releaseOnHit.getValue() && hitLanded) release = true;
            if (release) releaseIncoming();
        }

        boolean holding = !incomingQueue.isEmpty();
        setLagRangeEnabled(!(holding && isInCombat()));
        Vec3 real = realPosition;
        updateEsp(holding && real != null ? real : serverPosition(target), holding);
    }

    private void updateEsp(Vec3 real, boolean show) {
        espAlphaPrevious = espAlpha;

        espAlpha = show ? 1.0F : Math.max(0.0F, espAlpha - 0.12F);
        if (real == null) {
            return;
        }
        if (espCurrent == null) {
            espCurrent = target != null ? serverPosition(target) : real;
        }
        espPrevious = espCurrent;

        espCurrent = new Vec3(espCurrent.xCoord + (real.xCoord - espCurrent.xCoord) * 0.5,
                espCurrent.yCoord + (real.yCoord - espCurrent.yCoord) * 0.5,
                espCurrent.zCoord + (real.zCoord - espCurrent.zCoord) * 0.5);
    }

    @EventTarget(Priority.HIGH)
    public void onRender3D(Render3DEvent event) {
        if (!isEnabled() || !esp.getValue()) return;
        EntityLivingBase t = target;
        if (t == null || espCurrent == null || espPrevious == null) return;
        float partial = event.getPartialTicks();
        float alpha = espAlphaPrevious + (espAlpha - espAlphaPrevious) * partial;
        if (alpha <= 0.01F) return;
        double x = espPrevious.xCoord + (espCurrent.xCoord - espPrevious.xCoord) * partial;
        double y = espPrevious.yCoord + (espCurrent.yCoord - espPrevious.yCoord) * partial;
        double z = espPrevious.zCoord + (espCurrent.zCoord - espPrevious.zCoord) * partial;

        double rx = RenderUtil.lerpDouble(t.posX, t.lastTickPosX, partial);
        double ry = RenderUtil.lerpDouble(t.posY, t.lastTickPosY, partial);
        double rz = RenderUtil.lerpDouble(t.posZ, t.lastTickPosZ, partial);
        double apart = (x - rx) * (x - rx) + (y - ry) * (y - ry) + (z - rz) * (z - rz);
        if (apart < 0.0025) return;
        Color color = (t instanceof EntityPlayer)
                ? TeamUtil.getTeamColor((EntityPlayer) t, 1.0F)
                : new Color(255, 60, 60);
        int fill = 55;
        int line = 200;
        float width = 1.5F;
        ThemeStyle style = ThemeStyle.active(BacktrackColors.class);
        if (style != null) {
            color = style.color(color, t, -1);
            fill = style.fillAlpha255();
            line = style.lineAlpha255();
            width = style.width();
        }
        IAccessorRenderManager rm = (IAccessorRenderManager) mc.getRenderManager();
        AxisAlignedBB box = boxAt(t, new Vec3(x, y, z))
                .offset(-rm.getRenderPosX(), -rm.getRenderPosY(), -rm.getRenderPosZ());
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(box, color.getRed(), color.getGreen(), color.getBlue(), (int) (fill * alpha));
        RenderUtil.drawBoundingBox(box, color.getRed(), color.getGreen(), color.getBlue(), (int) (line * alpha), width);
        if (espHead.getValue()) {
            float yaw = t.prevRotationYawHead + MathHelper.wrapAngleTo180_float(t.rotationYawHead - t.prevRotationYawHead) * partial;
            float pitch = t.prevRotationPitch + (t.rotationPitch - t.prevRotationPitch) * partial;
            Vec3 look = atlas.util.RotationEngine.direction(yaw, pitch);
            double ex = (box.minX + box.maxX) / 2.0;
            double ey = box.minY + t.getEyeHeight();
            double ez = (box.minZ + box.maxZ) / 2.0;
            org.lwjgl.opengl.GL11.glLineWidth(width);
            net.minecraft.client.renderer.GlStateManager.color(color.getRed() / 255.0F, color.getGreen() / 255.0F,
                    color.getBlue() / 255.0F, line / 255.0F * alpha);
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_LINES);
            org.lwjgl.opengl.GL11.glVertex3d(ex, ey, ez);
            org.lwjgl.opengl.GL11.glVertex3d(ex + look.xCoord * 0.9, ey + look.yCoord * 0.9, ez + look.zCoord * 0.9);
            org.lwjgl.opengl.GL11.glEnd();
            org.lwjgl.opengl.GL11.glLineWidth(2.0F);
            net.minecraft.client.renderer.GlStateManager.resetColor();
        }
        RenderUtil.disableRenderState();
    }

    private EntityLivingBase resolveTarget() {
        EntityLivingBase auraTarget = atlas.util.Auras.target();
        if (auraTarget != null) return auraTarget;
        if (lastAttacked != null && !lastAttacked.isDead
                && System.currentTimeMillis() - lastAttackTime <= COMBAT_LOCK_MS
                && mc.thePlayer.getDistanceToEntity(lastAttacked) <= range.getValue() * 2.0F) {
            return lastAttacked;
        }
        ArrayList<EntityLivingBase> candidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) continue;
            EntityLivingBase e = (EntityLivingBase) entity;
            if (isValidTarget(e) && mc.thePlayer.getDistanceToEntity(e) <= range.getValue()) candidates.add(e);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Float.compare(RotationUtil.angleToEntity(a), RotationUtil.angleToEntity(b)));
        return candidates.get(0);
    }

    private boolean isValidTarget(EntityLivingBase e) {
        if (!mc.theWorld.loadedEntityList.contains(e)) return false;
        if (e == mc.thePlayer || e == mc.thePlayer.ridingEntity) return false;
        if (e == mc.getRenderViewEntity() || e == mc.getRenderViewEntity().ridingEntity) return false;
        if (e.deathTime > 0) return false;
        if (e instanceof EntityPlayer) {
            if (!players.getValue()) return false;
            EntityPlayer p = (EntityPlayer) e;
            if (TeamUtil.isFriend(p)) return false;
            if (teams.getValue() && TeamUtil.isSameTeam(p)) return false;
            if (botCheck.getValue() && TeamUtil.isBot(p)) return false;
            return true;
        }
        return false;
    }

    public static boolean runWithNearestTrackedDistance(net.minecraft.entity.Entity entity, Supplier<Boolean> action) {
        return action.get();
    }

    @Override
    public String[] getSuffix() {
        String held = holds + (holds == 1 ? " hold" : " holds");
        if (hitStats.getValue() && swings > 0) {
            return new String[]{ currentMaxDelay() + "ms", held, (landed * 100 / swings) + "% of " + swings };
        }
        return new String[]{ currentMaxDelay() + "ms", held };
    }
}
