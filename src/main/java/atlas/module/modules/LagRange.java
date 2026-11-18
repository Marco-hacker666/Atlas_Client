package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.PacketEvent;
import atlas.events.Render3DEvent;
import atlas.events.TickEvent;
import atlas.management.Arbiter;
import atlas.management.PacketHub;
import atlas.mixin.IAccessorPlayerControllerMP;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.util.AsyncLog;
import atlas.util.ItemUtil;
import atlas.util.RenderUtil;
import atlas.util.RotationUtil;
import atlas.util.TeamUtil;
import atlas.property.properties.*;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ModeProperty;
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

public class LagRange extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");
    private static final String NAME = "LagRange";

    private static final int RAVEN = 0;
    private static final int ATLAS = 1;
    public final ModeProperty mode = new ModeProperty("mode", RAVEN, new String[]{"RAVEN", "ATLAS"});

    public final FloatProperty range = new FloatProperty("range", 6.0F, 3.0F, 100.0F);
    public final FloatProperty meleeDistance = new FloatProperty("melee-distance", 3.0F, 0.0F, 6.0F,
            () -> this.mode.getValue() == ATLAS);
    public final BooleanProperty weaponsOnly = new BooleanProperty("weapons-only", true);
    public final BooleanProperty allowTools = new BooleanProperty("allow-tools", false, this.weaponsOnly::getValue);
    public final BooleanProperty botCheck = new BooleanProperty("bot-check", true);
    public final BooleanProperty teams = new BooleanProperty("teams", true);

    public final IntProperty delay = new IntProperty("delay", 200, 0, 1000);

    public final ModeProperty letOut = new ModeProperty("let-out", 0, new String[]{"Gradual", "At once"},
            () -> this.mode.getValue() == ATLAS);
    public final IntProperty recoilTime = new IntProperty("recoil-time", 750, 0, 2000,
            () -> this.mode.getValue() == ATLAS);
    public final IntProperty flagPause = new IntProperty("flag-pause", 2000, 0, 5000);

    public final BooleanProperty releaseOnSprintReset = new BooleanProperty("release-on-sprint-reset", false,
            () -> this.mode.getValue() == ATLAS);

    public final BooleanProperty flushOnSprint = new BooleanProperty("flush-on-sprint", true,
            () -> this.mode.getValue() == RAVEN);
    public final BooleanProperty flushOnBlock = new BooleanProperty("flush-on-block", true,
            () -> this.mode.getValue() == RAVEN);
    private final LagRangeRaven raven = new LagRangeRaven();

    public final ModeProperty showPosition = new ModeProperty("show-position", 0, new String[]{"NONE", "DEFAULT", "HUD"});
    public final ModeProperty log = new ModeProperty("log", 1, new String[]{"Off", "File"});

    private int applied;
    private long recoilUntil;
    private boolean hasTarget;
    private Vec3 lastPosition;
    private Vec3 currentPosition;
    private File logTarget;
    private long holdStart;
    private String holdTarget = "";

    private final java.util.Map<Integer, Double> lastDistance = new java.util.HashMap<Integer, Double>();

    private static final double ATTACK_REACH = 3.0;

    public LagRange() {
        super(NAME, false);

        PacketHub.register(NAME, PacketHub.MOVEMENT, () -> this.applied > 0 && Atlas.lagManager != null
                && NAME.equals(Atlas.lagManager.holder()) ? Atlas.lagManager.packetQueue.size() : 0, () -> {
            this.applied = 0;
            Atlas.lagManager.setDelay(0);
        });
    }

    static int nextDelay(int applied, int wanted, boolean gradual) {
        if (wanted >= applied) {
            return wanted;
        }
        return gradual ? Math.max(wanted, applied - 1) : wanted;
    }

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

    private String ruledOut() {
        BedNuker bedNuker = (BedNuker) Atlas.moduleManager.modules.get(BedNuker.class);
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
            Vec3 saved = Atlas.lagManager.getLastPosition();
            this.lastPosition = this.currentPosition == null ? saved : this.currentPosition;
            this.currentPosition = saved;
            return;
        }
        if (event.getType() != EventType.PRE) {
            return;
        }
        if (this.mode.getValue() == RAVEN) {
            ravenTick();
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
            Vec3 serverEye = Atlas.lagManager.getLastPosition().addVector(0.0, height, 0.0);
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
                    why = "melee";
                    break;
                }

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

    private void ravenTick() {
        String blocked = ravenRuledOut();
        EntityPlayer target = null;
        double best = Double.MAX_VALUE;
        double rangeSq = this.range.getValue() * this.range.getValue();
        Vec3 eye = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!this.isValidTarget(player)) {
                continue;
            }
            double distance = RotationUtil.distanceToBox(player, eye);
            if (distance * distance <= rangeSq && distance < best) {
                best = distance;
                target = player;
            }
        }
        boolean weaponOk = !this.weaponsOnly.getValue() || ItemUtil.hasRawUnbreakingEnchant()
                || (this.allowTools.getValue() && ItemUtil.isHoldingTool());
        boolean moving = mc.thePlayer.moveForward != 0.0F || mc.thePlayer.moveStrafing != 0.0F;
        LagRangeRaven.Step step = this.raven.step(blocked, target == null ? -1 : target.getEntityId(),
                target == null ? 0.0 : best * best, rangeSq, mc.thePlayer.hurtTime,
                target == null ? 0 : target.hurtTime, moving, weaponOk, mc.thePlayer.isSprinting(),
                mc.thePlayer.isBlocking(), this.flushOnSprint.getValue(), this.flushOnBlock.getValue());
        if (step == LagRangeRaven.Step.START) {
            this.holdTarget = target.getName() + " " + String.format("%.1f", best) + " RAVEN";
        }
        this.hasTarget = this.raven.lagging;
        if (step == LagRangeRaven.Step.FLUSH) {
            releaseNow(this.raven.reason, 0L);
        } else {
            apply(this.raven.lagging ? ticksFor(this.delay.getValue()) : 0, this.raven.reason);
        }
    }

    private String ravenRuledOut() {
        if (mc.currentScreen != null) {
            return "screen";
        }
        BedNuker bedNuker = (BedNuker) Atlas.moduleManager.modules.get(BedNuker.class);
        if (bedNuker.isEnabled() && bedNuker.isReady()) {
            return "bed";
        }
        if (((IAccessorPlayerControllerMP) mc.playerController).getIsHittingBlock()) {
            return "digging";
        }
        if (System.currentTimeMillis() < this.recoilUntil) {
            return "flag-pause";
        }
        if (Arbiter.catching()) {
            return "catch";
        }
        String other = PacketHub.holding();
        if (other != null && !NAME.equals(other)) {
            return other;
        }
        if (auraBlocking()) {
            return "autoblock";
        }
        return null;
    }

    private static boolean auraBlocking() {
        KillAura aura = (KillAura) Atlas.moduleManager.getModule(KillAura.class);
        if (aura != null && aura.isEnabled() && aura.isAuraBlocking()) {
            return true;
        }
        return false;
    }

    private void apply(int wanted, String why) {
        int before = this.applied;
        int next = nextDelay(before, wanted, this.letOut.getValue() == 0);
        if (next == before && next == 0) {
            return;
        }
        this.applied = next;
        Atlas.lagManager.setDelay(next);
        if (before == 0 && next > 0) {
            this.holdStart = System.currentTimeMillis();
            writeLog("HOLD " + next + "t | " + this.holdTarget);
        } else if (before > 0 && next == 0) {
            writeLog("LET-OUT " + why + " | held " + (System.currentTimeMillis() - this.holdStart) + "ms | "
                    + this.holdTarget);
        }
    }

    private void releaseNow(String why, long pauseMs) {
        this.recoilUntil = Math.max(this.recoilUntil, System.currentTimeMillis() + pauseMs);
        if (this.applied == 0) {
            return;
        }
        int held = Atlas.lagManager.packetQueue.size();
        this.applied = 0;
        Atlas.lagManager.setDelay(0);
        writeLog("RELEASE " + why + " " + held + "p | held " + (System.currentTimeMillis() - this.holdStart)
                + "ms | " + this.holdTarget);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        boolean ravenMode = this.mode.getValue() == RAVEN;
        if (event.getType() == EventType.SEND) {
            if (mustBeCurrent(packet)) {
                releaseNow(packet instanceof C02PacketUseEntity ? "attack" : "action",
                        ravenMode ? 0L : this.recoilTime.getValue());
                if (ravenMode) {
                    this.raven.released();
                }
            } else if (!ravenMode && this.releaseOnSprintReset.getValue()
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
            pause = ravenMode ? 0L : this.recoilTime.getValue();
        } else if (packet instanceof S27PacketExplosion) {
            cause = "explosion";
            pause = ravenMode ? 0L : this.recoilTime.getValue();
        } else {
            return;
        }

        mc.addScheduledTask(() -> {
            if (this.isEnabled()) {
                releaseNow(cause, pause);
                this.raven.released();
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
                color = ((HUD) Atlas.moduleManager.modules.get(HUD.class)).getColor(System.currentTimeMillis());
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
        this.raven.reset();
        if (this.applied > 0) {
            Atlas.lagManager.setDelay(0);
        }
        this.applied = 0;
        this.recoilUntil = 0L;
        this.hasTarget = false;
        this.lastPosition = null;
        this.currentPosition = null;
    }

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
