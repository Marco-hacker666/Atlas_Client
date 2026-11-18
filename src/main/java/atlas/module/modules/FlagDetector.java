package atlas.module.modules;

import atlas.util.Ping;
import atlas.util.AsyncLog;
import atlas.util.Attribution;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.events.Render2DEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ActionLedger;
import atlas.util.ChatUtil;
import atlas.util.SoundUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.network.play.server.S39PacketPlayerAbilities;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.util.BlockPos;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class FlagDetector extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final double SPRINT_SPEED = 0.28;

    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    public final FloatProperty minDistance = new FloatProperty("min-distance", 0.05F, 0.0F, 2.0F);
    public final FloatProperty teleportDistance = new FloatProperty("teleport-distance", 8.0F, 2.0F, 64.0F);
    public final BooleanProperty showTeleports = new BooleanProperty("show-teleports", false);
    public final BooleanProperty showContext = new BooleanProperty("context", true);
    public final BooleanProperty blame = new BooleanProperty("blame", true);
    public final BooleanProperty groupBursts = new BooleanProperty("group-bursts", true);
    public final FloatProperty burstWindow = new FloatProperty("burst-window", 1.0F, 0.2F, 5.0F);

    public final BooleanProperty detectRejects = new BooleanProperty("detect-rejects", true);

    public final BooleanProperty placeLog = new BooleanProperty("place-log", true, this.detectRejects::getValue);
    public final BooleanProperty chat = new BooleanProperty("chat", true);
    public final BooleanProperty sound = new BooleanProperty("sound", false);

    public final ModeProperty hud = new ModeProperty("hud", 0, new String[]{"NONE", "COMPACT", "LIST"});

    public final ModeProperty hudAlign = new ModeProperty("hud-align", 0,
            new String[]{"AUTO", "LEFT", "RIGHT"}, () -> this.hud.getValue() != 0);
    public final IntProperty hudX = new IntProperty("hud-x", 4, 0, 800, () -> this.hud.getValue() != 0);
    public final IntProperty hudY = new IntProperty("hud-y", 60, 0, 600, () -> this.hud.getValue() != 0);
    public final IntProperty hudLines = new IntProperty("hud-lines", 5, 1, 12, () -> this.hud.getValue() == 2);
    public final IntProperty hudFade = new IntProperty("hud-fade", 20, 3, 120, () -> this.hud.getValue() == 2);

    public final FloatProperty hudScale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F, () -> this.hud.getValue() != 0);
    public final BooleanProperty hudBackground = new BooleanProperty("background", false, () -> this.hud.getValue() != 0);

    public final BooleanProperty logFile = new BooleanProperty("log-file", false);

    public final BooleanProperty detectLowDamage = new BooleanProperty("detect-low-damage", true);

    public final BooleanProperty damageChat = new BooleanProperty("damage-chat", false,
            this.detectLowDamage::getValue);

    public final IntProperty blameWindow = new IntProperty("blame-window", 2, 1, 10,
            this.blame::getValue);

    private int ticksSinceAttack = 999;
    private int ticksSincePlace = 999;
    private int ticksSinceDig = 999;
    private int ticksSinceKnockback = 999;

    private static final int HISTORY = 200;

    private static final double VISITED_TOLERANCE = 0.3;
    private final double[] histX = new double[HISTORY];
    private final double[] histY = new double[HISTORY];
    private final double[] histZ = new double[HISTORY];
    private int histIndex = 0;
    private int histFilled = 0;

    private static final int REJECT_WINDOW_TICKS = 20;
    private final Map<BlockPos, Placement> pendingPlacements = new HashMap<BlockPos, Placement>();

    private static final class Placement {
        final int tick;
        double closestOpponent = Double.MAX_VALUE;

        String context;

        String module;

        BlockPos clicked;
        net.minecraft.util.EnumFacing face;
        net.minecraft.util.Vec3 hit;
        boolean awaitingNext;

        net.minecraft.util.Vec3 eyes;

        Placement(int tick) {
            this.tick = tick;
        }
    }

    private static final double OCCUPIED_DISTANCE = 1.2;
    private int tickCounter = 0;

    private String burstLabel = null;
    private int burstCount = 0;
    private double burstMaxDistance = 0.0;
    private long burstStartedAt = 0L;
    private long burstLastAt = 0L;
    private String burstContext = "";

    private long sessionStart;
    private int countLagback;
    private int countVertical;
    private int countTeleport;
    private int countReject;

    private int countBlockedByBody;

    private int countRelocated;

    private int countLowDamage;

    private int countHitsDropped;
    private final atlas.util.DamageWatch damageWatch = new atlas.util.DamageWatch();

    private final atlas.util.BlockTrace blockTrace = new atlas.util.BlockTrace();
    private boolean hiddenHealthReported;

    private boolean mitigationSaid;

    private int countClientFault;

    private static final int ORPHAN_TICKS = 5;
    private int timerOffTicks;
    private boolean timerOrphanReported;

    private static final int STALL_TICKS = 20;
    private int drainSmallest = Integer.MAX_VALUE;
    private int drainStallTicks;
    private boolean drainStallReported;
    private int strandedTicks;
    private boolean strandedReported;

    private final ArrayDeque<HudEntry> recent = new ArrayDeque<HudEntry>();
    private File logTarget;

    private static final int RESPAWN_GRACE = 60;

    private static final int TELEPORT_GRACE = 60;
    private int lastRespawnTick = Integer.MIN_VALUE / 2;
    private int lastTeleportTick = Integer.MIN_VALUE / 2;

    private static final int RELOCATE_GRACE = 20;
    private int lastRelocateTick = Integer.MIN_VALUE / 2;

    private static final long STALL_MS = 300L;
    private static final int STALL_GRACE = 40;
    private static final int ROUND_GRACE = 160;
    private static final int REFUSED_ALL_AFTER = 3;
    private long lastTickAt;
    private int lastStallTick = Integer.MIN_VALUE / 2;
    private int lastRoundTick = Integer.MIN_VALUE / 2;
    private int rejectsInARow;
    private boolean refusedAllSaid;

    private int serverSlot = -1;
    private final Map<String, Integer> ignoredReasons = new LinkedHashMap<String, Integer>();
    private int ignoredTotal;

    private boolean lastExceededTravel;

    public FlagDetector() {
        super("FlagDetector", false, true, "Reports server position corrections with distance, context and blame");
    }

    @Override
    public void onEnabled() {
        resetBurst();
        resetSession();
        this.ticksSinceAttack = 999;
        this.ticksSincePlace = 999;
        this.ticksSinceDig = 999;
        this.ticksSinceKnockback = 999;
        this.logTarget = null;
    }

    @Override
    public void onDisabled() {
        flushBurst();
        this.recent.clear();
        this.pendingPlacements.clear();
    }

    private void resetSession() {
        this.sessionStart = System.currentTimeMillis();
        this.countLagback = 0;
        this.countVertical = 0;
        this.countTeleport = 0;
        this.countReject = 0;
        this.countBlockedByBody = 0;
        this.countRelocated = 0;
        this.countLowDamage = 0;
        this.countHitsDropped = 0;
        this.countClientFault = 0;
        this.timerOffTicks = 0;
        this.timerOrphanReported = false;
        this.drainSmallest = Integer.MAX_VALUE;
        this.drainStallTicks = 0;
        this.drainStallReported = false;
        this.strandedTicks = 0;
        this.strandedReported = false;
        this.recent.clear();
        this.pendingPlacements.clear();
        this.ignoredReasons.clear();
        this.ignoredTotal = 0;
        this.rejectsInARow = 0;
        this.refusedAllSaid = false;
        this.serverSlot = -1;
    }

    private void resetBurst() {
        this.burstLabel = null;
        this.burstCount = 0;
        this.burstMaxDistance = 0.0;
        this.burstStartedAt = 0L;
        this.burstLastAt = 0L;
        this.burstContext = "";
        this.burstAttribution = null;
    }

    private int total() {
        return this.countLagback + this.countVertical + this.countTeleport + this.countReject + this.countLowDamage
                + this.countHitsDropped;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        this.tickCounter++;
        long tickAt = System.currentTimeMillis();
        if (this.lastTickAt > 0L && tickAt - this.lastTickAt > STALL_MS) {
            this.lastStallTick = this.tickCounter;
        }
        this.lastTickAt = tickAt;
        if (this.ticksSinceAttack < 999) {
            this.ticksSinceAttack++;
        }
        if (this.ticksSincePlace < 999) {
            this.ticksSincePlace++;
        }
        if (this.ticksSinceDig < 999) {
            this.ticksSinceDig++;
        }
        if (this.ticksSinceKnockback < 999) {
            this.ticksSinceKnockback++;
        }
        watchDamage();
        if (mc.thePlayer != null) {
            histX[histIndex] = mc.thePlayer.posX;
            histY[histIndex] = mc.thePlayer.posY;
            histZ[histIndex] = mc.thePlayer.posZ;
            histIndex = (histIndex + 1) % HISTORY;
            if (histFilled < HISTORY) {
                histFilled++;
            }
        }

        checkTimerOrphan();
        checkDrainStall();

        Iterator<Map.Entry<BlockPos, Placement>> it = this.pendingPlacements.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Placement> entry = it.next();
            if (this.tickCounter - entry.getValue().tick > REJECT_WINDOW_TICKS) {
                logPlacement(entry.getValue(), "OK");
                this.rejectsInARow = 0;
                it.remove();
                continue;
            }

            trackOccupancy(entry.getKey(), entry.getValue());
        }

        if (burstCount > 0 && System.currentTimeMillis() - burstLastAt >= (long) (burstWindow.getValue() * 1000.0F)) {
            flushBurst();
        }
    }

    private void checkTimerOrphan() {
        float speed;
        try {
            speed = ((atlas.mixin.IAccessorMinecraft) mc).getTimer().timerSpeed;
        } catch (Exception ignored) {
            return;
        }
        if (Math.abs(speed - 1.0F) < 0.001F
                || anyEnabled(Timer.class, TimerRange.class, NoFall.class)) {
            this.timerOffTicks = 0;
            this.timerOrphanReported = false;
            return;
        }
        this.timerOffTicks++;
        if (this.timerOrphanReported || this.timerOffTicks < ORPHAN_TICKS) {
            return;
        }
        this.timerOrphanReported = true;
        emit("TIMER-ORPHAN", 1, 0.0, String.format(
                " &8| &7timer &f%.2f&7 for &f%d&7t with Timer, TimerRange and NoFall all off",
                speed, this.timerOffTicks));
    }

    private void checkDrainStall() {
        if (Atlas.blinkManager == null) {
            return;
        }
        int size = Atlas.blinkManager.blinkedPackets.size();

        Module module = Atlas.moduleManager.modules.get(Blink.class);
        boolean releasing = module instanceof Blink && module.isEnabled() && ((Blink) module).isReleasing();
        if (!releasing) {
            this.drainSmallest = Integer.MAX_VALUE;
            this.drainStallTicks = 0;
            this.drainStallReported = false;
        } else if (size < this.drainSmallest) {
            this.drainSmallest = size;
            this.drainStallTicks = 0;
        } else {
            this.drainStallTicks++;
            if (!this.drainStallReported && this.drainStallTicks >= STALL_TICKS) {
                this.drainStallReported = true;
                emit("DRAIN-STALL", 1, 0.0, String.format(
                        " &8| &7Blink releasing, queue &f%d&7 (low &f%d&7), no progress for &f%d&7t",
                        size, this.drainSmallest, this.drainStallTicks));
            }
        }

        if (size == 0 || Atlas.blinkManager.isBlinking()) {
            this.strandedTicks = 0;
            this.strandedReported = false;
            return;
        }
        this.strandedTicks++;
        if (!this.strandedReported && this.strandedTicks >= STALL_TICKS) {
            this.strandedReported = true;
            emit("DRAIN-STALL", 1, 0.0, String.format(
                    " &8| &f%d&7 packets stranded in the blink queue with nothing blinking &8(owner %s)",
                    size, Atlas.blinkManager.getBlinkingModule()));
        }
    }

    private static boolean anyEnabled(Class<?>... types) {
        for (Class<?> type : types) {
            Module module = Atlas.moduleManager.modules.get(type);
            if (module != null && module.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }

        if (event.getType() == EventType.SEND) {
            if (event.getPacket() instanceof net.minecraft.network.play.client.C03PacketPlayer) {
                net.minecraft.network.play.client.C03PacketPlayer c03 =
                        (net.minecraft.network.play.client.C03PacketPlayer) event.getPacket();
                if (c03.isMoving()) {
                    this.sentX = c03.getPositionX();
                    this.sentY = c03.getPositionY();
                    this.sentZ = c03.getPositionZ();
                }
                if (c03.getRotating()) {
                    this.sentYawBefore = this.sentYaw;
                    this.sentPitchBefore = this.sentPitch;
                    this.sentYaw = c03.getYaw();
                    this.sentPitch = c03.getPitch();
                }
                this.sentGround = c03.isOnGround();
                this.sentTick = this.tickCounter;

                for (Placement placement : this.pendingPlacements.values()) {
                    if (placement.awaitingNext && placement.context != null) {
                        placement.awaitingNext = false;
                        placement.context = placement.context.replace("ray-next ?",
                                "ray-next " + rayText(placement.eyes, placement.clicked, placement.face, placement.hit));
                    }
                }
                return;
            }
            if (event.getPacket() instanceof net.minecraft.network.play.client.C09PacketHeldItemChange) {
                if (!event.isCancelled()) {
                    this.serverSlot = ((net.minecraft.network.play.client.C09PacketHeldItemChange) event.getPacket())
                            .getSlotId();
                }
                return;
            }
            if (event.getPacket() instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
                this.ticksSinceAttack = 0;
                if (this.detectLowDamage.getValue() && !event.isCancelled() && mc.theWorld != null
                        && hitQuietReason() == null) {
                    net.minecraft.entity.Entity attacked =
                            ((C02PacketUseEntity) event.getPacket()).getEntityFromWorld(mc.theWorld);
                    if (attacked instanceof net.minecraft.entity.EntityLivingBase && attacked != mc.thePlayer) {
                        this.damageWatch.attack(attacked.getEntityId(), System.currentTimeMillis(),
                                leastDamage((net.minecraft.entity.EntityLivingBase) attacked), weaponBasis());
                    }
                }
            } else if (event.getPacket() instanceof C07PacketPlayerDigging) {
                this.ticksSinceDig = 0;

                C07PacketPlayerDigging dig = (C07PacketPlayerDigging) event.getPacket();
                if (dig.getPosition() != null) {
                    Placement dug = this.pendingPlacements.remove(dig.getPosition());
                    logPlacement(dug, "DUG");
                }
            } else if (event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
                C08PacketPlayerBlockPlacement place = (C08PacketPlayerBlockPlacement) event.getPacket();

                ItemStack held = place.getStack();
                boolean placingBlock = held != null && held.getItem() instanceof ItemBlock;
                if (place.getPlacedBlockDirection() != 255 && place.getPosition() != null
                        && placingBlock) {
                    this.ticksSincePlace = 0;
                    if (this.detectRejects.getValue()) {
                        BlockPos target = place.getPosition().offset(
                                net.minecraft.util.EnumFacing.getFront(place.getPlacedBlockDirection()));
                        Placement placement = new Placement(this.tickCounter);
                        placement.module = ActionLedger.callerModuleExcept("FlagDetector");
                        if (this.placeLog.getValue()) {
                            placement.context = placementContext(place, target);
                            placement.clicked = place.getPosition();
                            placement.face = net.minecraft.util.EnumFacing.getFront(place.getPlacedBlockDirection());
                            placement.hit = new net.minecraft.util.Vec3(
                                    place.getPosition().getX() + place.getPlacedBlockOffsetX(),
                                    place.getPosition().getY() + place.getPlacedBlockOffsetY(),
                                    place.getPosition().getZ() + place.getPlacedBlockOffsetZ());
                            placement.eyes = new net.minecraft.util.Vec3(this.sentX,
                                    this.sentY + (mc.thePlayer.isSneaking() ? 1.54 : 1.62), this.sentZ);
                            placement.awaitingNext = true;
                        }
                        this.lastPlaceTick = this.tickCounter;
                        this.pendingPlacements.put(target, placement);
                    }
                }
            }
            return;
        }

        // pendingPlacements (a HashMap the tick iterates and prunes) had entries removed underneath that iteration, implicated wrote the map FlagResponder reads on its...

        final net.minecraft.network.Packet<?> received = event.getPacket();
        if (!(received instanceof S01PacketJoinGame || received instanceof S07PacketRespawn
                || received instanceof S12PacketEntityVelocity || received instanceof S23PacketBlockChange
                || received instanceof S08PacketPlayerPosLook || received instanceof S45PacketTitle
                || received instanceof S2BPacketChangeGameState || received instanceof S39PacketPlayerAbilities)) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (isEnabled() && mc.thePlayer != null) {
                    handleReceived(received);
                }
            }
        });
    }

    private void handleReceived(net.minecraft.network.Packet<?> packet) {
        if (packet instanceof S01PacketJoinGame) {
            resetSession();
            this.lastRespawnTick = this.tickCounter;
            return;
        }

        if (packet instanceof S07PacketRespawn) {
            this.lastRespawnTick = this.tickCounter;
            flushBurst();
            return;
        }

        if (packet instanceof S45PacketTitle) {
            net.minecraft.util.IChatComponent message = ((S45PacketTitle) packet).getMessage();
            if (message != null && !message.getUnformattedText().trim().isEmpty()) {
                this.lastRoundTick = this.tickCounter;
            }
            return;
        }

        if (packet instanceof S2BPacketChangeGameState) {
            if (((S2BPacketChangeGameState) packet).getGameState() == 3) {
                this.lastRoundTick = this.tickCounter;
            }
            return;
        }

        if (packet instanceof S39PacketPlayerAbilities) {
            S39PacketPlayerAbilities abilities = (S39PacketPlayerAbilities) packet;
            if (abilities.isAllowFlying() && !abilities.isCreativeMode()) {
                this.lastRoundTick = this.tickCounter;
            }
            return;
        }

        if (packet instanceof S12PacketEntityVelocity) {
            if (((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId()) {
                this.ticksSinceKnockback = 0;
            }
            return;
        }

        if (packet instanceof S23PacketBlockChange) {
            handleBlockChange((S23PacketBlockChange) packet);
            return;
        }

        if (!(packet instanceof S08PacketPlayerPosLook)) {
            return;
        }
        handlePosLook((S08PacketPlayerPosLook) packet);
    }

    private void trackOccupancy(BlockPos pos, Placement placement) {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        double centreX = pos.getX() + 0.5;
        double centreY = pos.getY() + 0.5;
        double centreZ = pos.getZ() + 0.5;
        for (Object object : mc.theWorld.playerEntities) {
            if (!(object instanceof net.minecraft.entity.player.EntityPlayer)) {
                continue;
            }
            net.minecraft.entity.player.EntityPlayer player =
                    (net.minecraft.entity.player.EntityPlayer) object;
            if (player == mc.thePlayer) {
                continue;
            }

            double dx = player.posX - centreX;
            double dz = player.posZ - centreZ;
            double dy = 0.0;
            if (centreY < player.posY) {
                dy = player.posY - centreY;
            } else if (centreY > player.posY + player.height) {
                dy = centreY - (player.posY + player.height);
            }
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < placement.closestOpponent) {
                placement.closestOpponent = distance;
            }
        }
    }

    private void handleBlockChange(S23PacketBlockChange packet) {
        if (!this.detectRejects.getValue() || this.pendingPlacements.isEmpty()) {
            return;
        }
        BlockPos pos = packet.getBlockPosition();
        Placement placement = pos == null ? null : this.pendingPlacements.remove(pos);
        if (placement == null) {
            return;
        }
        if (packet.getBlockState() == null || packet.getBlockState().getBlock() != Blocks.air) {
            logPlacement(placement, "OK");
            this.rejectsInARow = 0;
            this.refusedAllSaid = false;
            return;
        }

        if (mc.theWorld == null || mc.theWorld.getBlockState(pos).getBlock() == Blocks.air) {
            logPlacement(placement, "NO-BLOCK");
            return;
        }

        String quiet = quietReason();
        if (quiet == null && this.tickCounter - this.lastRoundTick < ROUND_GRACE) {
            quiet = "round-change";
        }
        if (quiet == null && this.rejectsInARow >= REFUSED_ALL_AFTER) {
            quiet = "refused-all";
            if (!this.refusedAllSaid && this.chat.getValue()) {
                this.refusedAllSaid = true;
                ChatUtil.sendFormatted("&7[&cFlagDetector&7] &8the server is refusing every block here"
                        + " (round over or a protected area); not counted until one is accepted");
            }
        }
        if (quiet != null) {
            logPlacement(placement, "IGNORED " + quiet);
            noteIgnored(quiet);
            return;
        }

        boolean occupied = placement.closestOpponent <= OCCUPIED_DISTANCE;
        logPlacement(placement, occupied ? "BODY" : "REJECT");
        if (!occupied) {
            this.rejectsInARow++;
        }
        String where = " &8| &7at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        if (placement.closestOpponent < Double.MAX_VALUE) {
            where += String.format(" &8| &7body %.2f", placement.closestOpponent);
        }

        Attribution.Result attribution = placement.module != null
                ? Attribution.direct(placement.module, "placed-this-block")
                : attributeNow();
        String context = this.showContext.getValue() ? where + blameSuffix(attribution) : "";
        this.emitAttribution = attribution;
        emit(occupied ? "BLOCKED-BY-BODY" : "REJECT", 1, 0.0, context);
    }

    private String ignoreReason() {
        String quiet = quietReason();
        if (quiet != null) {
            return quiet;
        }
        if (mc.thePlayer.ridingEntity != null) {
            return "riding";
        }
        if (mc.thePlayer.capabilities != null && mc.thePlayer.capabilities.isFlying) {
            return "flying";
        }
        if (this.tickCounter - this.lastRelocateTick < RELOCATE_GRACE) {
            return "post-relocate";
        }
        return null;
    }

    private String hitQuietReason() {
        String quiet = quietReason();
        return "post-teleport".equals(quiet) ? null : quiet;
    }

    private String quietReason() {
        if (mc.thePlayer == null) {
            return null;
        }
        if (mc.thePlayer.isSpectator()) {
            return "spectator";
        }
        if (mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0.0F) {
            return "dead";
        }
        if (mc.thePlayer.capabilities != null && mc.thePlayer.capabilities.allowFlying
                && !mc.thePlayer.capabilities.isCreativeMode) {
            return "ghost";
        }
        if (this.tickCounter - this.lastRespawnTick < RESPAWN_GRACE) {
            return "respawn";
        }
        if (this.tickCounter - this.lastTeleportTick < TELEPORT_GRACE) {
            return "post-teleport";
        }
        if (this.tickCounter - this.lastStallTick < STALL_GRACE) {
            return "client-stall";
        }
        return null;
    }

    private void noteIgnored(String reason) {
        Integer seen = this.ignoredReasons.get(reason);
        this.ignoredReasons.put(reason, seen == null ? 1 : seen + 1);
        this.ignoredTotal++;
    }

    public int violationCount() {
        return this.countLagback + this.countVertical + this.countReject;
    }

    public long sessionMillis() {
        return System.currentTimeMillis() - this.sessionStart;
    }

    public void resetCounters() {
        resetSession();
    }

    public String ignoredSummary() {
        if (this.ignoredTotal <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : this.ignoredReasons.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    private void handlePosLook(S08PacketPlayerPosLook packet) {
        String ignored = this.ignoreReason();
        if (ignored != null) {
            noteIgnored(ignored);

            flushBurst();
            return;
        }

        Set<S08PacketPlayerPosLook.EnumFlags> flags = packet.func_179834_f();
        boolean relX = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.X);
        boolean relY = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Y);
        boolean relZ = flags != null && flags.contains(S08PacketPlayerPosLook.EnumFlags.Z);
        double dx = relX ? packet.getX() : packet.getX() - mc.thePlayer.posX;
        double dy = relY ? packet.getY() : packet.getY() - mc.thePlayer.posY;
        double dz = relZ ? packet.getZ() : packet.getZ() - mc.thePlayer.posZ;

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        String label;
        if (distance >= teleportDistance.getValue()) {
            this.lastTeleportTick = this.tickCounter;
            if (!showTeleports.getValue()) {
                return;
            }
            label = "TELEPORT";
        } else if (distance < minDistance.getValue()) {
            return;
        } else if (!visited(mc.thePlayer.posX + dx, mc.thePlayer.posY + dy, mc.thePlayer.posZ + dz)) {
            this.lastRelocateTick = this.tickCounter;
            label = "RELOCATE";
        } else if (Math.abs(dy) > horizontal * 2.0 && horizontal < 0.3) {
            label = "VERTICAL";
        } else {
            label = "LAGBACK";
        }

        Attribution.Result attribution = attributeNow();
        String context = showContext.getValue() ? buildContext(horizontal, Math.abs(dy), attribution) : "";

        if (!groupBursts.getValue()) {
            this.emitAttribution = attribution;
            emit(label, 1, distance, context);
            return;
        }

        long now = System.currentTimeMillis();
        if (burstCount > 0 && !label.equals(burstLabel)) {
            flushBurst();
        }
        if (burstCount == 0) {
            burstLabel = label;
            burstStartedAt = now;
            burstMaxDistance = 0.0;
            burstContext = context;
            burstAttribution = attribution;
        }
        burstCount++;
        burstLastAt = now;
        if (distance > burstMaxDistance) {
            burstMaxDistance = distance;
            burstContext = context;
            burstAttribution = attribution;
        }
    }

    private boolean visited(double x, double y, double z) {
        if (this.histFilled == 0) {
            return true;
        }
        double limit = VISITED_TOLERANCE * VISITED_TOLERANCE;
        if (squaredDistance(mc.thePlayer.posX - x, mc.thePlayer.posY - y, mc.thePlayer.posZ - z) <= limit) {
            return true;
        }

        for (int i = 0; i < this.histFilled; i++) {
            if (squaredDistance(histX[i] - x, histY[i] - y, histZ[i] - z) <= limit) {
                return true;
            }
        }
        return false;
    }

    private static double squaredDistance(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    private int ping() {
        return Ping.own();
    }

    private final Map<String, ArrayDeque<Long>> implicated = new HashMap<String, ArrayDeque<Long>>();

    public java.util.Set<String> implicatedNames() {
        return new java.util.HashSet<String>(this.implicated.keySet());
    }

    @EventTarget(whenDisabled = true)
    public void onSession(atlas.events.SessionEvent event) {
        if (event.getType() == atlas.events.SessionEvent.Type.START) {
            this.implicated.clear();
            atlas.management.ModuleHealth.resetSession();

            this.damageWatch.reset();
            this.blockTrace.reset();
            this.hiddenHealthReported = false;
        }
    }

    public int implicationsWithin(String module, long millis) {
        ArrayDeque<Long> stamps = this.implicated.get(module);
        if (stamps == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - millis;
        while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
            stamps.removeFirst();
        }
        return stamps.size();
    }

    public void clearImplications(String module) {
        this.implicated.remove(module);
    }

    private void implicate(String module) {
        ArrayDeque<Long> stamps = this.implicated.get(module);
        if (stamps == null) {
            stamps = new ArrayDeque<Long>();
            this.implicated.put(module, stamps);
        }
        stamps.addLast(System.currentTimeMillis());
        while (stamps.size() > 64) {
            stamps.removeFirst();
        }
    }

    private volatile Attribution.Result lastAttribution;

    private Attribution.Result emitAttribution;
    private Attribution.Result burstAttribution;

    private void watchDamage() {
        if (!this.detectLowDamage.getValue() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        long now = System.currentTimeMillis();

        long window = Math.max(0, Ping.own()) + 350L;
        for (Integer id : this.damageWatch.watched()) {
            net.minecraft.entity.Entity entity = mc.theWorld.getEntityByID(id);
            if (!(entity instanceof net.minecraft.entity.EntityLivingBase)) {
                continue;
            }
            net.minecraft.entity.EntityLivingBase living = (net.minecraft.entity.EntityLivingBase) entity;
            this.blockTrace.record(id, now, living instanceof net.minecraft.entity.player.EntityPlayer
                    && ((net.minecraft.entity.player.EntityPlayer) living).isBlocking());

            this.damageWatch.setPrecision(
                    atlas.util.HealthUtil.SOURCE_ENTITY.equals(atlas.util.HealthUtil.source(living)) ? 0.0F : 1.0F);
            atlas.util.DamageWatch.Hit hit = this.damageWatch.tick(id,
                    atlas.util.HealthUtil.resolve(living) + living.getAbsorptionAmount(), living.hurtTime, now, window);
            if (hit == null) {
                continue;
            }
            if (this.logFile.getValue()) {
                int[] blkWindow = this.blockTrace.count(id, hit.at - 100L, now);
                writeLog(String.format("HIT-DMG target=%s dealt=%.2f hits=%d total=%.2f expected=%s usual=%s health=%s weapon=%s"
                                + " armour=%d blocking=%s blk-hit=%s blk-window=%d/%d",
                        living.getName(), hit.dealt, hit.hits, hit.total,
                        Float.isNaN(hit.expected) ? "-" : String.format("%.2f", hit.expected),
                        Float.isNaN(hit.usual) ? "-" : String.format("%.2f", hit.usual),
                        atlas.util.HealthUtil.source(living), weaponBasis(), living.getTotalArmorValue(),
                        living instanceof net.minecraft.entity.player.EntityPlayer
                                && ((net.minecraft.entity.player.EntityPlayer) living).isBlocking(),
                        this.blockTrace.atHit(id, hit.at, 100L), blkWindow[0], blkWindow[1]));
            }
            if (this.damageChat.getValue()) {
                ChatUtil.sendFormatted(String.format("&7[&cFlagDetector&7] &7dealt &f%.1f&7 to &f%s%s%s",
                        hit.dealt, living.getName(),
                        Float.isNaN(hit.usual) ? "" : String.format(" &8(usual %.1f)", hit.usual),
                        Float.isNaN(hit.expected) ? "" : String.format(" &8(vanilla at least %.1f)", hit.expected)));
            }
            atlas.util.DamageWatch.Mitigation mitigation = this.damageWatch.mitigation(now, MITIGATION_SHOW_MS);
            if (mitigation.active && !this.mitigationSaid && this.chat.getValue()) {
                ChatUtil.sendFormatted(String.format(
                        "&7[&cFlagDetector&7] &cmitigation?&7 %d of the last %d hits took &f%.1f&7, vanilla at least &f%.1f&7 (target %s, health from %s)",
                        mitigation.low, mitigation.of, mitigation.dealt, mitigation.expected, living.getName(),
                        atlas.util.HealthUtil.source(living)));
            }
            this.mitigationSaid = mitigation.active;
            atlas.util.DamageWatch.Verdict verdict = this.damageWatch.verdict(hit);
            if (verdict != null) {
                Attribution.Result attribution = attributeNow();
                String context = String.format(" &8| &7dealt &f%.2f&7 a hit, usual &f%.2f&7 &8| &7target %s &8| &7health from %s &8| &7ping %dms",
                        verdict.dealt, verdict.usual, living.getName(), atlas.util.HealthUtil.source(living), Ping.own());
                if (this.showContext.getValue()) {
                    context += blameSuffix(attribution);
                }
                this.emitAttribution = attribution;
                emit("LOW-DAMAGE", verdict.hits, 0.0, context);
            }
        }
        if (this.damageWatch.healthHidden() && !this.hiddenHealthReported) {
            this.hiddenHealthReported = true;
            ChatUtil.sendFormatted("&7[&cFlagDetector&7] &8low-damage check off here: this server does not show"
                    + " other players' health");
        }
        this.damageWatch.forget(now, 5000L);
    }

    public void noteHitResult(boolean dropped, String target, double distance) {
        if (!this.isEnabled() || !this.detectLowDamage.getValue()) {
            return;
        }
        if (!dropped) {
            this.damageWatch.landed();
            return;
        }
        String quiet = hitQuietReason();
        if (quiet != null) {
            noteIgnored(quiet);
            return;
        }
        int run = this.damageWatch.dropped(System.currentTimeMillis());
        if (run > 0) {
            Attribution.Result attribution = attributeNow();
            String context = String.format(" &8| &f%d&7 hits in a row with no reaction &8| &7target %s &8| &7dist %.2f &8| &7ping %dms",
                    run, target, distance, Ping.own());
            if (this.showContext.getValue()) {
                context += blameSuffix(attribution);
            }
            this.emitAttribution = attribution;
            emit("HITS-DROPPED", run, 0.0, context);
        }
    }

    public static final long MITIGATION_SHOW_MS = 20000L;

    private float leastDamage(net.minecraft.entity.EntityLivingBase target) {
        if (!(target instanceof net.minecraft.entity.player.EntityPlayer) || mc.thePlayer == null) {
            return Float.NaN;
        }
        ItemStack held = serverHeld();
        float weapon = 0.0F;
        int sharpness = 0;
        if (held != null) {
            for (net.minecraft.entity.ai.attributes.AttributeModifier modifier : held.getAttributeModifiers()
                    .get(net.minecraft.entity.SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName())) {
                if (modifier.getOperation() == 0) {
                    weapon += (float) modifier.getAmount();
                }
            }
            sharpness = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                    net.minecraft.enchantment.Enchantment.sharpness.effectId, held);
        }
        net.minecraft.potion.PotionEffect strength = mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.damageBoost);
        net.minecraft.potion.PotionEffect weakness = mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.weakness);
        int protection = 0;
        for (ItemStack armour : ((net.minecraft.entity.player.EntityPlayer) target).inventory.armorInventory) {
            if (armour != null) {
                protection += atlas.util.DamageModel.protectionPoints(net.minecraft.enchantment.EnchantmentHelper
                        .getEnchantmentLevel(net.minecraft.enchantment.Enchantment.protection.effectId, armour));
            }
        }

        boolean blocking = ((net.minecraft.entity.player.EntityPlayer) target).isBlocking();
        net.minecraft.potion.PotionEffect resistance = target.getActivePotionEffect(net.minecraft.potion.Potion.resistance);
        return atlas.util.DamageModel.expected(weapon, sharpness,
                strength == null ? 0 : strength.getAmplifier() + 1, weakness == null ? 0 : weakness.getAmplifier() + 1,
                false, target.getTotalArmorValue(), protection, blocking,
                resistance == null ? 0 : resistance.getAmplifier() + 1)[0];
    }

    private ItemStack serverHeld() {
        if (this.serverSlot >= 0 && this.serverSlot < 9) {
            return mc.thePlayer.inventory.getStackInSlot(this.serverSlot);
        }
        return mc.thePlayer.getHeldItem();
    }

    private String weaponBasis() {
        ItemStack held = serverHeld();
        StringBuilder basis = new StringBuilder(held == null ? "hand"
                : String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(held.getItem())));
        if (held != null) {
            int sharpness = net.minecraft.enchantment.EnchantmentHelper.getEnchantmentLevel(
                    net.minecraft.enchantment.Enchantment.sharpness.effectId, held);
            if (sharpness > 0) {
                basis.append("/sharp").append(sharpness);
            }
        }
        net.minecraft.potion.PotionEffect strength = mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.damageBoost);
        net.minecraft.potion.PotionEffect weakness = mc.thePlayer.getActivePotionEffect(net.minecraft.potion.Potion.weakness);
        if (strength != null) {
            basis.append("/str").append(strength.getAmplifier() + 1);
        }
        if (weakness != null) {
            basis.append("/weak").append(weakness.getAmplifier() + 1);
        }
        return basis.toString();
    }

    public atlas.util.DamageWatch.Mitigation mitigation() {
        return this.damageWatch.mitigation(System.currentTimeMillis(), MITIGATION_SHOW_MS);
    }

    public int droppedRun() {
        return this.damageWatch.droppedRun(System.currentTimeMillis());
    }

    private Attribution.Result attributeNow() {
        long window = this.blameWindow.getValue() * 1000L;
        return Attribution.attribute(ActionLedger.records(window), System.currentTimeMillis(), window);
    }

    public Attribution.Result lastAttribution() {
        return this.lastAttribution;
    }

    private void recordSuspects(Attribution.Result attribution) {
        if (attribution == null) {
            return;
        }
        this.lastAttribution = attribution;
        String culprit = attribution.culprit();
        if (culprit != null) {
            implicate(culprit);
        }

        long now = System.currentTimeMillis();
        if (culprit != null) {
            atlas.management.ModuleHealth.named(culprit, now);
        } else if (attribution.verdict == Attribution.Verdict.MULTIPLE_CANDIDATES) {
            for (Attribution.Candidate candidate : attribution.candidates) {
                if (candidate.confidence >= 0.2) {
                    atlas.management.ModuleHealth.involved(candidate.module, now);
                }
            }
        }
    }

    private String blameSuffix(Attribution.Result attribution) {
        if (!this.blame.getValue()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        String holds = atlas.management.PacketHolds.describe();
        if (!holds.isEmpty()) {
            sb.append(" &8| &cholding ").append(holds).append("&7");
        }

        String hub = atlas.management.PacketHub.recent(3000L);
        if (hub != null) {
            sb.append(" &8| &chub ").append(hub).append("&7");
        }

        atlas.management.PacketHolds.Expiry expiry = atlas.management.PacketHolds.lastExpiry();
        if (expiry != null && System.currentTimeMillis() - expiry.at <= 3000L) {
            sb.append(" &8| &clease ended ").append(System.currentTimeMillis() - expiry.at)
                    .append("ms ago: ").append(expiry).append("&7");
        }
        if (this.ticksSincePlace <= 10) {
            sb.append(" &8| &7placed ").append(this.ticksSincePlace).append("t ago");
        }
        if (this.ticksSinceDig <= 10) {
            sb.append(" &8| &7dug ").append(this.ticksSinceDig).append("t ago");
        }
        if (this.ticksSinceKnockback <= 10) {
            sb.append(" &8| &7kb ").append(this.ticksSinceKnockback).append("t ago");
        }

        if (attribution != null && attribution.verdict != Attribution.Verdict.UNKNOWN) {
            sb.append(" &8| &e").append(attribution.describe(3)).append("&7");
        }
        return sb.toString();
    }

    private String buildContext(double horizontalPush, double verticalPush, Attribution.Result attribution) {
        this.lastExceededTravel = false;
        StringBuilder sb = new StringBuilder();
        double speed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX
                + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
        sb.append(" &8| &7speed ").append(round(speed)).append("/t");

        if (speed > SPRINT_SPEED && mc.thePlayer.onGround) {
            sb.append(" &c(over sprint)&7");
        }
        sb.append(mc.thePlayer.isSprinting() ? " &8| &7sprint" : " &8| &7walk");
        sb.append(mc.thePlayer.onGround ? " &8| &7ground" : " &8| &7air");
        sb.append(" &8| &7push h").append(round(horizontalPush)).append(" v").append(round(verticalPush));

        int ping = ping();
        if (ping > 0) {
            int ticks = (int) Math.ceil(ping / 50.0);
            if (ticks < 1) {
                ticks = 1;
            } else if (ticks > HISTORY - 1) {
                ticks = HISTORY - 1;
            }
            sb.append(" &8| &7ping ").append(ping).append("ms");

            if (histFilled > ticks) {
                double travelH = 0.0;
                double travelV = 0.0;
                for (int step = ticks; step > 0; step--) {
                    int from = ((histIndex - 1 - step) % HISTORY + HISTORY) % HISTORY;
                    int to = ((histIndex - step) % HISTORY + HISTORY) % HISTORY;
                    double stepX = histX[to] - histX[from];
                    double stepZ = histZ[to] - histZ[from];
                    travelH += Math.sqrt(stepX * stepX + stepZ * stepZ);
                    travelV += Math.abs(histY[to] - histY[from]);
                }
                sb.append(" &8| &7moved h").append(round(travelH))
                        .append(" v").append(round(travelV))
                        .append(" in ").append(ticks).append("t");

                boolean withinH = horizontalPush <= travelH * 1.2 + 0.2;
                boolean withinV = verticalPush <= travelV * 1.2 + 0.2;
                this.lastExceededTravel = !(withinH && withinV);
                sb.append(withinH && withinV ? " &a(latency)&7" : " &c(exceeds travel)&7");
            }
        }

        if (ticksSinceAttack <= 20) {
            sb.append(" &8| &c").append(ticksSinceAttack).append("t after hit");
        }
        return sb.append(blameSuffix(attribution)).toString();
    }

    private void flushBurst() {
        if (burstCount <= 0) {
            return;
        }

        String label = burstLabel;
        int count = burstCount;
        double distance = burstMaxDistance;
        String context = burstContext;
        Attribution.Result attribution = burstAttribution;
        long span = Math.max(1L, burstLastAt - burstStartedAt);
        resetBurst();
        this.emitAttribution = attribution;
        emit(label, count, distance, context, span);
    }

    private void emit(String label, int count, double distance, String context) {
        emit(label, count, distance, context, 1L);
    }

    private static boolean isViolation(String label) {
        return !"BLOCKED-BY-BODY".equals(label) && !"TELEPORT".equals(label)
                && !"RELOCATE".equals(label) && !isClientFault(label);
    }

    private static boolean isClientFault(String label) {
        return "TIMER-ORPHAN".equals(label) || "DRAIN-STALL".equals(label);
    }

    private void emit(String label, int count, double distance, String context, long span) {
        Attribution.Result attribution = this.emitAttribution;
        this.emitAttribution = null;
        if ("BLOCKED-BY-BODY".equals(label)) {
            this.countBlockedByBody += count;
        } else if (isClientFault(label)) {
            this.countClientFault += count;
        } else if ("LAGBACK".equals(label)) {
            this.countLagback += count;
        } else if ("VERTICAL".equals(label)) {
            this.countVertical += count;
        } else if ("TELEPORT".equals(label)) {
            this.countTeleport += count;
        } else if ("RELOCATE".equals(label)) {
            this.countRelocated += count;
        } else if ("LOW-DAMAGE".equals(label)) {
            this.countLowDamage += count;
        } else if ("HITS-DROPPED".equals(label)) {
            this.countHitsDropped += count;
        } else {
            this.countReject += count;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(colourOf(label)).append(label).append("&r&7");
        if (count > 1) {
            sb.append(" &fx").append(count).append("&7 in ").append(round(span / 1000.0)).append("s, max ");
        } else {
            sb.append(" ");
        }
        if (!"REJECT".equals(label) && !"BLOCKED-BY-BODY".equals(label) && !"LOW-DAMAGE".equals(label)
                && !"HITS-DROPPED".equals(label) && !isClientFault(label)) {
            sb.append("&f").append(round(distance)).append("&7 blocks");
        }
        sb.append(context);
        String body = sb.toString();

        if (isViolation(label)) {
            recordSuspects(attribution);

            Adaptive.observeFlag(signatureFor(label), count);
        }
        if (this.chat.getValue()) {
            ChatUtil.sendFormatted("&7[&cFlagDetector&7] " + body);
        }

        this.recent.addLast(new HudEntry(label, count, distance, System.currentTimeMillis()));
        while (this.recent.size() > 32) {
            this.recent.removeFirst();
        }

        if (this.sound.getValue() && isViolation(label)) {
            SoundUtil.playSound("note.pling");
        }
        if (this.logFile.getValue()) {
            writeLog(label + " x" + count + " " + round(distance) + " "
                    + body.replaceAll("&[0-9a-fklmnor]", ""));
        }
    }

    private String signatureFor(String label) {
        StringBuilder sb = new StringBuilder(label);
        if (mc.thePlayer == null) {
            return sb.toString();
        }
        sb.append(mc.thePlayer.onGround ? "|ground" : "|air");
        if (mc.thePlayer.isSprinting()) {
            sb.append("|sprint");
        }
        if (this.ticksSinceKnockback <= 10) {
            sb.append("|kb");
        }
        if (this.ticksSinceAttack <= 10) {
            sb.append("|hit");
        }
        if (this.ticksSincePlace <= 10) {
            sb.append("|place");
        }
        if (this.ticksSinceDig <= 10) {
            sb.append("|dig");
        }
        if (this.lastExceededTravel) {
            sb.append("|beyond-latency");
        }
        return sb.toString();
    }

    private static String colourOf(String label) {
        if ("LAGBACK".equals(label)) {
            return "&c";
        }
        if ("TELEPORT".equals(label)) {
            return "&b";
        }
        if ("RELOCATE".equals(label)) {
            return "&3";
        }
        if ("REJECT".equals(label)) {
            return "&6";
        }
        if ("BLOCKED-BY-BODY".equals(label)) {
            return "&8";
        }
        if ("LOW-DAMAGE".equals(label) || "HITS-DROPPED".equals(label)) {
            return "&5";
        }
        if (isClientFault(label)) {
            return "&d";
        }
        return "&e";
    }

    private void writeLog(String line) {
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "flags-" + FILE_STAMP.format(new Date()) + ".txt");
        }

        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    private double sentX, sentY, sentZ;
    private float sentYaw, sentPitch, sentYawBefore, sentPitchBefore;
    private boolean sentGround;
    private int sentTick;
    private int lastPlaceTick = -1000;
    private java.io.File placeTarget;

    private String rayText(BlockPos clicked, net.minecraft.util.EnumFacing face, net.minecraft.util.Vec3 hit) {
        double eyeY = this.sentY + (mc.thePlayer.isSneaking() ? 1.54 : 1.62);
        return rayText(new net.minecraft.util.Vec3(this.sentX, eyeY, this.sentZ), clicked, face, hit);
    }

    private String rayText(net.minecraft.util.Vec3 eyes, BlockPos clicked, net.minecraft.util.EnumFacing face,
                           net.minecraft.util.Vec3 hit) {
        float yawRad = -this.sentYaw * 0.017453292F - (float) Math.PI;
        float pitchRad = -this.sentPitch * 0.017453292F;
        float cosPitch = -net.minecraft.util.MathHelper.cos(pitchRad);
        net.minecraft.util.Vec3 look = new net.minecraft.util.Vec3(net.minecraft.util.MathHelper.sin(yawRad) * cosPitch,
                net.minecraft.util.MathHelper.sin(pitchRad), net.minecraft.util.MathHelper.cos(yawRad) * cosPitch);
        net.minecraft.util.Vec3 far = eyes.addVector(look.xCoord * 6.0, look.yCoord * 6.0, look.zCoord * 6.0);
        net.minecraft.util.AxisAlignedBB box = new net.minecraft.util.AxisAlignedBB(clicked, clicked.add(1, 1, 1));
        net.minecraft.util.MovingObjectPosition ray = box.calculateIntercept(eyes, far);
        return ray == null ? "miss"
                : ray.sideHit == face ? String.format("face %.2f", ray.hitVec.distanceTo(hit)) : "side:" + ray.sideHit.getName();
    }

    private String placementContext(C08PacketPlayerBlockPlacement place, BlockPos target) {
        BlockPos clicked = place.getPosition();
        net.minecraft.util.EnumFacing face = net.minecraft.util.EnumFacing.getFront(place.getPlacedBlockDirection());
        net.minecraft.util.Vec3 hit = new net.minecraft.util.Vec3(clicked.getX() + place.getPlacedBlockOffsetX(),
                clicked.getY() + place.getPlacedBlockOffsetY(), clicked.getZ() + place.getPlacedBlockOffsetZ());
        double eyeY = this.sentY + (mc.thePlayer.isSneaking() ? 1.54 : 1.62);
        net.minecraft.util.Vec3 eyes = new net.minecraft.util.Vec3(this.sentX, eyeY, this.sentZ);
        String rayText = rayText(clicked, face, hit) + " | ray-next ?";
        float turn = Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float(this.sentYaw - this.sentYawBefore))
                + Math.abs(this.sentPitch - this.sentPitchBefore);
        StringBuilder mods = new StringBuilder();
        for (Class<?> type : new Class<?>[]{FastPlace.class, Clutch.class, Scaffold.class, AutoBlockIn.class,
                SpeedBlocker.class,
                KillAura.class, FakeLag.class, BackTrack.class, Blink.class, AntiVoid.class, SafeWalk.class, Eagle.class}) {
            atlas.module.Module module = atlas.Atlas.moduleManager.modules.get(type);
            if (module != null && module.isEnabled()) {
                mods.append(mods.length() == 0 ? "" : ",").append(module.getName());
            }
        }

        double half = 0.3;
        boolean across = this.sentX + half > target.getX() && this.sentX - half < target.getX() + 1
                && this.sentZ + half > target.getZ() && this.sentZ - half < target.getZ() + 1;
        String self = across ? String.format("%+.2f", this.sentY - (target.getY() + 1)) : "-";
        FakeLag fakeLag = (FakeLag) atlas.Atlas.moduleManager.modules.get(FakeLag.class);
        int fakeHeld = fakeLag != null && fakeLag.isEnabled() ? fakeLag.heldCount() : 0;
        int blinkHeld = atlas.Atlas.blinkManager == null ? 0 : atlas.Atlas.blinkManager.blinkedPackets.size();
        String extra = String.format(" | self %s | feet %+.2f | held fl=%d bl=%d", self,
                mc.thePlayer.posY - (target.getY() + 1), fakeHeld, blinkHeld);
        return String.format("%s | at %d,%d,%d | face %s | ray %s | dist %.2f | rot %.1f | gap %s | sentAge %dt"
                        + " | ground %s | v %.2f %.2f %.2f | sprint %s | sneak %s | hurt %d | ping %d | catching %s | mods %s"
                        + extra.replace("%", "%%"),
                LINE_STAMP.format(new java.util.Date()), target.getX(), target.getY(), target.getZ(), face.getName(),
                rayText, eyes.distanceTo(hit), turn,
                this.tickCounter - this.lastPlaceTick > 200 ? "-" : String.valueOf(this.tickCounter - this.lastPlaceTick),
                this.tickCounter - this.sentTick, this.sentGround,
                mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ,
                mc.thePlayer.isSprinting(), mc.thePlayer.isSneaking(), mc.thePlayer.hurtTime, ping(),
                atlas.management.Arbiter.catching(), mods.length() == 0 ? "-" : mods.toString());
    }

    private void logPlacement(Placement placement, String outcome) {
        if (placement == null || placement.context == null) {
            return;
        }
        if (this.placeTarget == null) {
            this.placeTarget = new java.io.File(LOG_DIR, "places-" + FILE_STAMP.format(new java.util.Date()) + ".txt");
        }
        atlas.util.AsyncLog.append(this.placeTarget, placement.context + " | result " + outcome);
        placement.context = null;
    }

    private double perMinute() {
        double minutes = (System.currentTimeMillis() - this.sessionStart) / 60000.0;
        return minutes < 0.05 ? 0.0 : total() / minutes;
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.hud.getValue() == 0
                || mc.fontRendererObj == null || mc.thePlayer == null
                || (mc.currentScreen != null && !atlas.ui.hud.HudLayout.isEditing())) {
            return;
        }
        if (mc.gameSettings.showDebugInfo) {
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        boolean right = alignRight();
        int margin = Math.min(this.hudX.getValue(), Math.max(0, sr.getScaledWidth() - 120));
        int y = Math.min(this.hudY.getValue(), Math.max(0, sr.getScaledHeight() - 20));

        String header = String.format("Flags %d  §cL%d §eV%d §6R%d §bT%d  §7%.1f/min",
                total(), this.countLagback, this.countVertical, this.countReject,
                this.countTeleport, perMinute());
        if (this.countClientFault > 0) {
            header += String.format("  §dBUG%d", this.countClientFault);
        }
        java.util.List<String> lines = new java.util.ArrayList<String>();
        java.util.List<Integer> colours = new java.util.ArrayList<Integer>();
        if (this.hud.getValue() == 2) {
            long now = System.currentTimeMillis();
            long fade = this.hudFade.getValue() * 1000L;
            Iterator<HudEntry> it = this.recent.descendingIterator();
            while (it.hasNext() && lines.size() < this.hudLines.getValue()) {
                HudEntry entry = it.next();
                long age = now - entry.at;
                if (age > fade) {
                    break;
                }

                int alpha = (int) (255 - 195 * ((double) age / fade));
                if (alpha < 60) {
                    alpha = 60;
                }
                lines.add(String.format("%s%s%s  §7%.1fs",
                        sectionOf(entry.label), entry.label,
                        entry.count > 1 ? " x" + entry.count : "", age / 1000.0));
                colours.add((alpha << 24) | 0xFFFFFF);
            }
        }

        int widest = mc.fontRendererObj.getStringWidth(header);
        for (String line : lines) {
            widest = Math.max(widest, mc.fontRendererObj.getStringWidth(line) + 2);
        }
        float left = right ? sr.getScaledWidth() - margin - widest : margin;
        float height = lines.isEmpty() ? 9.0F : 11.0F + lines.size() * 10.0F - 1.0F;

        float s = this.hudScale.getValue();
        float pivotX = right ? sr.getScaledWidth() - margin : margin;
        float[] area = atlas.ui.hud.HudLayout.scaledRect(pivotX, y, s, left - 2.0F, y - 2.0F, widest + 4.0F,
                height + 4.0F);
        atlas.ui.hud.HudLayout.report("FlagDetector", "FlagDetector", area[0], area[1], area[2], area[3],
                atlas.ui.hud.HudLayout.ints(this.hudX, right ? -1 : 1, this.hudY, 1),
                atlas.ui.hud.HudLayout.scale(this.hudScale));

        atlas.ui.hud.HudLayout.beginScaled(pivotX, y, s);
        GL11.glPushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        try {
            if (this.hudBackground.getValue()) {
                if (atlas.ui.impl.clickgui.atlas.HudPanel.active()) {
                    atlas.ui.impl.clickgui.atlas.HudPanel.panel(left - 3.0F, y - 3.0F, left + widest + 3.0F,
                            y + height + 2.0F);
                } else {
                    net.minecraft.client.gui.Gui.drawRect((int) left - 3, y - 3, (int) (left + widest) + 3,
                            (int) (y + height) + 2, atlas.ui.UiMode.adapt(0x70000000));
                }
                GlStateManager.enableBlend();
            }
            mc.fontRendererObj.drawStringWithShadow(header, lineX(sr, header, margin, right), y, 0xFFFFFFFF);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                mc.fontRendererObj.drawStringWithShadow(line,
                        lineX(sr, line, margin + 2, right), y + 11 + i * 10, colours.get(i));
            }
        } finally {
            GlStateManager.disableBlend();
            GlStateManager.enableDepth();
            GL11.glPopMatrix();
            atlas.ui.hud.HudLayout.endScaled();
        }
    }

    private boolean alignRight() {
        int mode = this.hudAlign.getValue();
        if (mode == 1) {
            return false;
        }
        if (mode == 2) {
            return true;
        }
        HUD hud = (HUD) Atlas.moduleManager.modules.get(HUD.class);
        return hud != null && hud.isEnabled() && hud.posX.getValue() == 0;
    }

    private int lineX(ScaledResolution sr, String line, int margin, boolean right) {
        if (!right) {
            return margin;
        }
        return sr.getScaledWidth() - margin - mc.fontRendererObj.getStringWidth(line);
    }

    private static String sectionOf(String label) {
        return colourOf(label).replace('&', '§');
    }

    @Override
    public String[] getSuffix() {
        String bugs = this.countClientFault > 0 ? " BUG" + this.countClientFault : "";
        if (total() == 0) {
            return new String[]{"clean" + bugs};
        }
        return new String[]{String.format("L%d V%d R%d", this.countLagback,
                this.countVertical, this.countReject) + bugs};
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class HudEntry {
        final String label;
        final int count;
        final double distance;
        final long at;

        HudEntry(String label, int count, double distance, long at) {
            this.label = label;
            this.count = count;
            this.distance = distance;
            this.at = at;
        }
    }
}
