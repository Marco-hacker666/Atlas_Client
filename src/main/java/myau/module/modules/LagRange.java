package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.management.Arbiter;
import myau.management.PacketHub;
import myau.mixin.IAccessorPlayerControllerMP;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.util.AsyncLog;
import myau.util.ItemUtil;
import myau.util.RenderUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import myau.property.properties.*;
import myau.property.properties.BooleanProperty;
import myau.property.properties.ModeProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Holds this player's packets (through LagManager) while closing on a
 * target, so the server's copy of this player arrives late.
 *
 * 2026-10-18 (plan item 4b, "optimise the release"). In the flag logs of
 * 10-15, 10-16 and 10-18, 17 lagbacks named LagRange first, 15 of them 0-6
 * ticks after a hit: every attack set the delay to 0 and LagManager sent the
 * whole queue in that tick -- the dump FakeLag learnt on 09-25 and 10-17 the
 * server answers with a pullback. So, as FakeLag does:
 *   - within melee-distance of the target it stops holding and lets the queue
 *     out, so by the time of the attack there is little or nothing left;
 *   - letting out is gradual (the delay drops a tick a tick, so about two held
 *     packets go each tick) unless let-out is At once;
 *   - an attack, a dig or a placement still sends what is held first (it is
 *     judged where the player really is), then holding pauses for
 *     recoil-time; knockback lets go at once and pauses (the server expects a
 *     reaction); a correction lets go and pauses for flag-pause;
 *   - it does not hold while another module holds movement (PacketHub) or a
 *     fall is being caught (Arbiter), and it no longer sets LagManager's
 *     delay to 0 every tick, which cut across BlockHit's use of it.
 * Each hold and each let-out, with why, goes to config/Atlas/lagrange-*.txt.
 *
 * Settings, in three groups: when to hold (range, melee-distance, weapons,
 * targets), how long (delay), when to let go (let-out, recoil-time,
 * flag-pause).
 */
public class LagRange extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final String NAME = "LagRange";

    // when to hold
    public final FloatProperty range = new FloatProperty("range", 10.0F, 3.0F, 100.0F);
    public final FloatProperty meleeDistance = new FloatProperty("melee-distance", 3.0F, 0.0F, 6.0F);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final BooleanProperty teams = new BooleanProperty("teams", true);
    // how long
    public final IntProperty delay = new IntProperty("delay", 150, 0, 1000);
    // when to let go
    public final ModeProperty letOut = new ModeProperty("let-out", 0, new String[]{"Gradual", "At once"});
    public final IntProperty recoilTime = new IntProperty("recoil-time", 750, 0, 2000);
    public final IntProperty flagPause = new IntProperty("flag-pause", 2000, 0, 5000);
    /* 2026-10-19 (from Slinky): a W-tap (sprint stopping) lets the queue out
       at once, so the hit that follows it is judged from where you are. */
    public final BooleanProperty releaseOnSprintReset = new BooleanProperty("release-on-sprint-reset", false);
    // display
    public final ModeProperty showPosition = new ModeProperty("show-position", 0, new String[]{"NONE", "DEFAULT", "HUD"});
    public final ModeProperty log = new ModeProperty("log", 1, new String[]{"Off", "File"});

    /** The delay, in ticks, this module has LagManager holding now (0: none). */
    private int applied;
    private long recoilUntil;
    private boolean hasTarget;
    private Vec3 lastPosition;
    private Vec3 currentPosition;
    private File logTarget;
    private long holdStart;
    private String holdTarget = "";
    /* Each player's box distance last tick, for how fast the gap is closing (2026-10-20). */
    private final java.util.Map<Integer, Double> lastDistance = new java.util.HashMap<Integer, Double>();
    /** Vanilla's attack reach, eyes to hitbox. */
    private static final double ATTACK_REACH = 3.0;

    public LagRange() {
        super(NAME, false);
        /* One movement holder at a time (PacketHub): its queue is LagManager's while this module set the delay. */
        PacketHub.register(NAME, PacketHub.MOVEMENT, () -> this.applied > 0 && Myau.lagManager != null
                && NAME.equals(Myau.lagManager.holder()) ? Myau.lagManager.packetQueue.size() : 0, () -> {
            /* Sent in order by LagManager at the next packet or tick. */
            this.applied = 0;
            Myau.lagManager.setDelay(0);
        });
    }

    /**
     * The next delay: straight up to what is wanted, down one tick a tick when
     * gradual (LagManager then sends about two held packets a tick), or down
     * at once.
     */
    static int nextDelay(int applied, int wanted, boolean gradual) {
        if (wanted >= applied) {
            return wanted;
        }
        return gradual ? Math.max(wanted, applied - 1) : wanted;
    }

    /** The delay setting in ticks, at least one. */
    /**
     * Where to stop holding (2026-10-20, Pika mitigation). The queue must be
     * empty before the first swing can land: letting out drops the delay a
     * tick a tick, so it takes about as many ticks as the delay, plus one;
     * in that time the gap closes by the closing speed each tick. Stop at
     * reach + that, plus a margin, and never closer than melee-distance.
     * Before, holding stopped at a fixed 3.0 -- inside reach -- so the hit
     * came while 17-36 packets were held and sent them all at once (and the
     * transaction replies queued with them made the server see a sudden ping
     * spike): every LOW-DAMAGE on 10-20 20:34 and 20:38 named LagRange.
     */
    static double stopDistance(double closingPerTick, int queuedTicks, float meleeDistance) {
        double drain = Math.max(0.0, closingPerTick) * (queuedTicks + 1);
        return Math.max(meleeDistance, ATTACK_REACH + drain + 0.3);
    }

    static int ticksFor(int millis) {
        return Math.max(1, Math.round(millis / 50.0F));
    }

    private boolean isValidTarget(EntityPlayer entityPlayer) {
        if (entityPlayer == mc.thePlayer || entityPlayer == mc.thePlayer.ridingEntity) {
            return false;
        }
        if (entityPlayer == mc.getRenderViewEntity() || entityPlayer == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        if (entityPlayer.deathTime > 0 || TeamUtil.isFriend(entityPlayer) || !TargetFilter.accepts(entityPlayer)) {
            return false;
        }
        return (!this.teams.getValue() || !TeamUtil.isSameTeam(entityPlayer))
                && (!this.botCheck.getValue() || !TeamUtil.isBot(entityPlayer));
    }

    /** Things the server judges where the player really is: what is held goes first. */
    private static boolean mustBeCurrent(Packet<?> packet) {
        if (packet instanceof C02PacketUseEntity) {
            return true;
        } else if (packet instanceof C07PacketPlayerDigging) {
            return ((C07PacketPlayerDigging) packet).getStatus() != Action.RELEASE_USE_ITEM;
        } else if (packet instanceof C08PacketPlayerBlockPlacement) {
            ItemStack item = ((C08PacketPlayerBlockPlacement) packet).getStack();
            return item == null || !(item.getItem() instanceof ItemSword);
        }
        return false;
    }

    /** Why not to hold this tick, or null to look for a target. */
    private String ruledOut() {
        BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
        if (bedNuker.isEnabled() && bedNuker.isReady()) {
            return "bed";
        }
        if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
            return "digging";
        }
        if (mc.thePlayer.isUsingItem() && !mc.thePlayer.isBlocking()) {
            return "using-item";
        }
        if (this.weaponsOnly.getValue() && !ItemUtil.hasRawUnbreakingEnchant()
                && !(this.allowTools.getValue() && ItemUtil.isHoldingTool())) {
            return "no-weapon";
        }
        if (System.currentTimeMillis() < this.recoilUntil) {
            return "recoil";
        }
        if (Arbiter.catching()) {
            return "catch";
        }
        String other = PacketHub.holding();
        if (other != null && !NAME.equals(other)) {
            return other;
        }
        return null;
    }

    @EventTarget(Priority.LOW)
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.POST) {
            Vec3 saved = Myau.lagManager.getLastPosition();
            this.lastPosition = this.currentPosition == null ? saved : this.currentPosition;
            this.currentPosition = saved;
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        this.hasTarget = false;
        int wanted = 0;
        String why = ruledOut();
        if (why == null) {
            why = "no-target";
            List<EntityPlayer> players = mc.theWorld.loadedEntityList.stream()
                    .filter(entity -> entity instanceof EntityPlayer)
                    .map(entity -> (EntityPlayer) entity)
                    .filter(this::isValidTarget)
                    .collect(Collectors.toList());
            double height = mc.thePlayer.getEyeHeight();
            Vec3 serverEye = Myau.lagManager.getLastPosition().addVector(0.0, height, 0.0);
            Vec3 lastEye = new Vec3(mc.thePlayer.lastTickPosX, mc.thePlayer.lastTickPosY + height, mc.thePlayer.lastTickPosZ);
            Vec3 eye = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + height, mc.thePlayer.posZ);
            for (EntityPlayer player : players) {
                double distance = RotationUtil.distanceToBox(player, eye);
                if (distance > this.range.getValue()) {
                    continue;
                }
                Double before = this.lastDistance.put(player.getEntityId(), distance);
                double closing = before == null ? 0.0 : before - distance;
                int queued = Math.max(this.applied, ticksFor(this.delay.getValue()));
                if (distance <= stopDistance(closing, queued, this.meleeDistance.getValue())) {
                    /* About to be in reach: let out now, so the queue is empty when the first swing lands. */
                    why = "melee";
                    break;
                }
                /* Closing in: nearer now than last tick, or than where the server has this player. */
                if (distance < RotationUtil.distanceToBox(player, lastEye)
                        || distance < RotationUtil.distanceToBox(player, serverEye)) {
                    wanted = ticksFor(this.delay.getValue());
                    this.hasTarget = true;
                    this.holdTarget = player.getName() + " " + String.format("%.1f", distance);
                    why = null;
                    break;
                }
                why = "not-closing";
            }
        }
        apply(wanted, why);
    }

    private void apply(int wanted, String why) {
        int before = this.applied;
        int next = nextDelay(before, wanted, this.letOut.getValue() == 0);
        if (next == before && next == 0) {
            /* Nothing of ours to set: leave LagManager to whoever else uses it (BlockHit). */
            return;
        }
        this.applied = next;
        Myau.lagManager.setDelay(next);
        if (before == 0 && next > 0) {
            this.holdStart = System.currentTimeMillis();
            writeLog("HOLD " + next + "t | " + this.holdTarget);
        } else if (before > 0 && next == 0) {
            writeLog("LET-OUT " + why + " | held " + (System.currentTimeMillis() - this.holdStart) + "ms | "
                    + this.holdTarget);
        }
    }

    /** Lets everything go at once, ahead of whatever is being sent, and pauses holding. */
    private void releaseNow(String why, long pauseMs) {
        this.recoilUntil = Math.max(this.recoilUntil, System.currentTimeMillis() + pauseMs);
        if (this.applied == 0) {
            return;
        }
        int held = Myau.lagManager.packetQueue.size();
        this.applied = 0;
        Myau.lagManager.setDelay(0);
        writeLog("RELEASE " + why + " " + held + "p | held " + (System.currentTimeMillis() - this.holdStart)
                + "ms | " + this.holdTarget);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (event.getType() == EventType.SEND) {
            if (mustBeCurrent(packet)) {
                /* We are at the head of the send: LagManager sends the queue before this packet. */
                releaseNow(packet instanceof C02PacketUseEntity ? "attack" : "action", this.recoilTime.getValue());
            } else if (this.releaseOnSprintReset.getValue()
                    && packet instanceof net.minecraft.network.play.client.C0BPacketEntityAction
                    && ((net.minecraft.network.play.client.C0BPacketEntityAction) packet).getAction()
                    == net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SPRINTING) {
                releaseNow("sprint-reset", this.recoilTime.getValue());
            }
            return;
        }
        final String cause;
        final long pause;
        if (packet instanceof S08PacketPlayerPosLook) {
            cause = "correction";
            pause = this.flagPause.getValue();
        } else if (packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId()) {
            cause = "knockback";
            pause = this.recoilTime.getValue();
        } else if (packet instanceof S27PacketExplosion) {
            cause = "explosion";
            pause = this.recoilTime.getValue();
        } else {
            return;
        }
        /* The network thread must not set the delay under the client thread: done before its next tick. */
        mc.addScheduledTask(() -> {
            if (this.isEnabled()) {
                releaseNow(cause, pause);
            }
        });
    }

    @EventTarget(Priority.HIGH)
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || this.showPosition.getValue() == 0 || mc.gameSettings.thirdPersonView == 0
                || !this.hasTarget || this.lastPosition == null || this.currentPosition == null) {
            return;
        }
        Color color = new Color(-1);
        switch (this.showPosition.getValue()) {
            case 1:
                color = TeamUtil.getTeamColor(mc.thePlayer, 1.0F);
                break;
            case 2:
                color = ((HUD) Myau.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
        }
        double x = RenderUtil.lerpDouble(this.currentPosition.xCoord, this.lastPosition.xCoord, event.getPartialTicks());
        double y = RenderUtil.lerpDouble(this.currentPosition.yCoord, this.lastPosition.yCoord, event.getPartialTicks());
        double z = RenderUtil.lerpDouble(this.currentPosition.zCoord, this.lastPosition.zCoord, event.getPartialTicks());
        float size = mc.thePlayer.getCollisionBorderSize();
        AxisAlignedBB aabb = new AxisAlignedBB(
                x - (double) mc.thePlayer.width / 2.0, y, z - (double) mc.thePlayer.width / 2.0,
                x + (double) mc.thePlayer.width / 2.0, y + (double) mc.thePlayer.height,
                z + (double) mc.thePlayer.width / 2.0)
                .expand(size, size, size)
                .offset(-((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX(),
                        -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY(),
                        -((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ());
        RenderUtil.enableRenderState();
        RenderUtil.drawFilledBox(aabb, color.getRed(), color.getGreen(), color.getBlue());
        RenderUtil.disableRenderState();
    }

    @Override
    public void onDisabled() {
        this.lastDistance.clear();
        if (this.applied > 0) {
            Myau.lagManager.setDelay(0);
        }
        this.applied = 0;
        this.recoilUntil = 0L;
        this.hasTarget = false;
        this.lastPosition = null;
        this.currentPosition = null;
    }

    /** One file per game session (Backtrack switches this module off and on in fights). */
    private void writeLog(String line) {
        if (this.log.getValue() == 0) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "lagrange-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    @Override
    public String[] getSuffix() {
        String ms = this.delay.getValue() + "ms";
        return this.applied > 0 ? new String[]{ms, this.applied + "t"} : new String[]{ms};
    }
}
