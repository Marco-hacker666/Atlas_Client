package atlas.module.modules;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.LoadWorldEvent;
import atlas.events.MoveInputEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.management.LagGuard;
import atlas.management.PacketHolds;
import atlas.management.PacketHub;
import atlas.management.RotationState;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.AsyncLog;
import atlas.util.Ping;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class EdgeGuard extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String NAME = "EdgeGuard";
    private static final int EDGE_RADIUS = 3;
    private static final int AIR_STEER_TICKS = 20;
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");

    private static volatile double[] pending;

    public final IntProperty maxHold = new IntProperty("max-hold", 4, 1, 6);
    public final IntProperty latencyBudget = new IntProperty("latency-budget", 300, 150, 500);
    public final FloatProperty maxDrop = new FloatProperty("max-drop", 6.0F, 3.0F, 20.0F);
    public final FloatProperty range = new FloatProperty("range", 6.0F, 3.0F, 12.0F);
    public final BooleanProperty blockRescue = new BooleanProperty("block-rescue", true);
    public final BooleanProperty airSteer = new BooleanProperty("air-steer", true);
    public final ModeProperty log = new ModeProperty("log", 1, new String[]{"OFF", "FILE"});

    private final Object lock = new Object();
    private final List<Packet<?>> line = new ArrayList<Packet<?>>();

    private volatile boolean armed;
    private volatile int selfId = Integer.MIN_VALUE;

    private volatile boolean holding;
    private volatile long holdStartMs;
    private volatile double kx;
    private volatile double ky;
    private volatile double kz;

    private EdgeGuardPlan.Decision decision;
    private EdgeGuardPlan.Settings settings;
    private int heldTicks;
    private int budget;
    private float steerYaw = Float.NaN;
    private int steerLeft;
    private boolean leftGround;
    private File logFile;

    public EdgeGuard() {
        super(NAME, false);
        PacketHub.registerIncoming(NAME, this::heldCount, this::letGo);
        PacketHolds.register(() -> {
            int count = this.heldCount();
            if (count == 0) {
                return null;
            }
            return new PacketHolds.Hold(NAME, PacketHolds.Direction.IN, count, this.holdStartMs,
                    "a knockback toward the void, and what came after it", "once it lands, or the budget ends");
        }, new PacketHolds.Lease() {
            @Override
            public String owner() {
                return getName();
            }

            @Override
            public long ceilingMs() {
                return maxHold.getValue() * 50L + PacketHolds.LEASE_MARGIN_MS;
            }

            @Override
            public void expire() {
                letGo();
            }
        });
    }

    public static double[] pendingKnockback() {
        return pending;
    }

    public static boolean claimsPush() {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(EdgeGuard.class);
        return module instanceof EdgeGuard && module.isEnabled() && ((EdgeGuard) module).armed;
    }

    private int heldCount() {
        synchronized (this.lock) {
            return this.line.size();
        }
    }

    @Override
    public void onEnabled() {
        this.armed = false;
        this.steerLeft = 0;
    }

    @Override
    public void onDisabled() {
        this.armed = false;
        this.letGo();
        LagGuard.wholeStream(NAME, false);
    }

    @EventTarget(whenDisabled = true)
    public void onLoadWorld(LoadWorldEvent event) {
        synchronized (this.lock) {
            this.line.clear();
            this.holding = false;
        }
        this.endHold();
        this.steerLeft = 0;
    }

    @Override
    public String[] getSuffix() {
        return this.holding ? new String[]{"&cholding"} : new String[0];
    }

    @EventTarget(Priority.HIGH)
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.selfId = mc.thePlayer.getEntityId();
        LagGuard.wholeStream(NAME, this.isEnabled());
        if (!this.isEnabled()) {
            this.armed = false;
            return;
        }
        this.budget = this.budgetNow();
        this.armed = this.budget > 0 && this.canArm();
        if (this.holding && this.decision != null) {
            this.heldTick();
        }
        if (this.steerLeft > 0) {
            if (!mc.thePlayer.onGround) {
                this.leftGround = true;
            }
            if (this.leftGround && mc.thePlayer.onGround) {
                this.steerLeft = 0;
            } else {
                this.steerLeft--;
            }
        }
    }

    private int budgetNow() {
        int ping = Ping.own();
        int ticks = EdgeGuardPlan.budgetTicks(ping < 0 ? 150 : ping, this.latencyBudget.getValue(),
                this.maxHold.getValue());
        long allowance = LagGuard.streamAllowanceMs(NAME);
        return (int) Math.min(ticks, allowance / 50L);
    }

    private boolean canArm() {
        if (mc.isSingleplayer() || mc.thePlayer.ticksExisted < 20 || mc.thePlayer.isDead || !mc.thePlayer.onGround
                || mc.thePlayer.isInWater() || mc.thePlayer.isInLava() || mc.thePlayer.isOnLadder()
                || mc.thePlayer.capabilities.isFlying || mc.thePlayer.isRiding() || mc.currentScreen != null) {
            return false;
        }
        return this.enemyNear() && this.dropNear();
    }

    private boolean enemyNear() {
        double reach = this.range.getValue();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || player.getHealth() <= 0.0F || TeamUtil.isFriend(player)
                    || TeamUtil.isSameTeam(player) || TeamUtil.isBot(player)) {
                continue;
            }
            if (mc.thePlayer.getDistanceToEntity(player) <= reach) {
                return true;
            }
        }
        return false;
    }

    private boolean dropNear() {
        int depth = (int) Math.ceil(this.maxDrop.getValue());
        int px = MathHelper.floor_double(mc.thePlayer.posX);
        int py = MathHelper.floor_double(mc.thePlayer.posY);
        int pz = MathHelper.floor_double(mc.thePlayer.posZ);
        for (int dx = -EDGE_RADIUS; dx <= EDGE_RADIUS; dx++) {
            for (int dz = -EDGE_RADIUS; dz <= EDGE_RADIUS; dz++) {
                boolean empty = true;
                for (int dy = 1; dy <= depth && empty; dy++) {
                    if (!mc.theWorld.isAirBlock(new BlockPos(px + dx, py - dy, pz + dz))) {
                        empty = false;
                    }
                }
                if (empty) {
                    return true;
                }
            }
        }
        return false;
    }

    private EdgeGuardPlan.World world() {
        return region -> mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, region);
    }

    private EdgeGuardPlan.Settings settingsNow() {
        EdgeGuardPlan.Settings s = new EdgeGuardPlan.Settings();
        s.maxTicks = this.budget;
        s.maxDrop = this.maxDrop.getValue();
        s.sprint = true;
        s.blockRescue = this.blockRescue.getValue() && this.clutchCanPlace();
        return s;
    }

    private boolean clutchCanPlace() {
        Module clutch = Atlas.moduleManager.getModule(Clutch.class);
        if (!(clutch instanceof Clutch) || !clutch.isEnabled() || ((Clutch) clutch).trigger.getValue() == 1) {
            return false;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock
                    && ((ItemBlock) stack.getItem()).getBlock().isFullCube()
                    && ((ItemBlock) stack.getItem()).getBlock() != net.minecraft.init.Blocks.sand
                    && ((ItemBlock) stack.getItem()).getBlock() != net.minecraft.init.Blocks.gravel) {
                return true;
            }
        }
        return false;
    }

    private void decide() {
        if (!this.holding || mc.thePlayer == null || mc.theWorld == null || !this.isEnabled()) {
            this.letGo();
            return;
        }
        this.settings = this.settingsNow();
        if (this.settings.maxTicks <= 0) {
            note("push " + push() + " | no budget now: let go");
            this.letGo();
            return;
        }
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox();
        float yaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        EdgeGuardPlan.Decision d = EdgeGuardPlan.decide(world(), box, this.kx, this.ky, this.kz, yaw, this.settings);
        note("push " + push() + " | budget " + this.settings.maxTicks + "t | " + d.kind + ": " + d.why);
        switch (d.kind) {
            case HOLD:
                this.decision = d;

                this.heldTicks = -1;
                if (d.standStill()) {
                    pending = new double[]{this.kx, this.ky, this.kz};
                }
                break;
            case RELEASE:
                this.startAirSteer(d.yaw);
                this.letGo();
                break;
            default:
                this.letGo();
                break;
        }
    }

    private void heldTick() {
        this.heldTicks++;
        EdgeGuardPlan.Decision d = this.decision;
        long heldMs = System.currentTimeMillis() - this.holdStartMs;
        String why = null;
        if (!mc.thePlayer.onGround) {
            why = "left the ground";
        } else if (EdgeGuardPlan.safeNow(world(), mc.thePlayer.getEntityBoundingBox(), this.kx, this.ky, this.kz,
                d.yaw, this.settings)) {
            why = d.standStill() ? "a block is down: lands" : "walked in far enough: lands";
        } else if (this.heldTicks >= (d.standStill() ? this.settings.maxTicks : d.ticks)
                || heldMs > this.settings.maxTicks * 50L + 60L) {
            why = "time is up";
        }
        if (why != null) {
            note("  let go after " + this.heldTicks + "t (" + heldMs + "ms): " + why);
            this.startAirSteer(d.yaw);
            this.letGo();
        }
    }

    private void startAirSteer(float yaw) {
        if (this.airSteer.getValue() && !Float.isNaN(yaw)) {
            this.steerYaw = yaw;
            this.steerLeft = AIR_STEER_TICKS;
            this.leftGround = false;
        }
    }

    @EventTarget(Priority.LOW)
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        EdgeGuardPlan.Decision d = this.decision;
        if (this.holding && d != null) {
            mc.thePlayer.movementInput.jump = false;
            if (d.standStill()) {
                mc.thePlayer.movementInput.moveForward = 0.0F;
                mc.thePlayer.movementInput.moveStrafe = 0.0F;
            } else {
                press(d.yaw);
            }
            return;
        }
        if (this.steerLeft > 0 && !Float.isNaN(this.steerYaw)) {
            press(this.steerYaw);
        }
    }

    private static void press(float worldYaw) {
        float moveYaw = RotationState.isActived() ? RotationState.getSmoothedYaw() : mc.thePlayer.rotationYaw;
        float relative = MathHelper.wrapAngleTo180_float(worldYaw - moveYaw) * 0.017453292F;
        float forward = MathHelper.cos(relative);
        float strafe = -MathHelper.sin(relative);
        mc.thePlayer.movementInput.moveForward = forward > 0.38F ? 1.0F : forward < -0.38F ? -1.0F : 0.0F;
        mc.thePlayer.movementInput.moveStrafe = strafe > 0.38F ? 1.0F : strafe < -0.38F ? -1.0F : 0.0F;
        if (mc.thePlayer.movementInput.moveForward <= 0.0F) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.RECEIVE || event.isCancelled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S07PacketRespawn
                || packet instanceof S01PacketJoinGame || packet instanceof S40PacketDisconnect) {
            if (this.holding) {
                this.letGo();
            }
            return;
        }
        synchronized (this.lock) {
            if (this.holding) {
                boolean push = packet instanceof S12PacketEntityVelocity
                        && ((S12PacketEntityVelocity) packet).getEntityID() == this.selfId;
                this.hold(event, packet);
                if (push) {
                    this.letGo();
                }
                return;
            }
            if (!this.armed || !(packet instanceof S12PacketEntityVelocity)
                    || ((S12PacketEntityVelocity) packet).getEntityID() != this.selfId) {
                return;
            }
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) packet;
            String other = PacketHub.holdingIncoming();
            if (other != null && !NAME.equals(other)) {
                PacketHub.letGoGuarded(false, true, true);
            }
            if (!PacketHub.take(event, NAME)) {
                return;
            }
            this.kx = velocity.getMotionX() / 8000.0;
            this.ky = velocity.getMotionY() / 8000.0;
            this.kz = velocity.getMotionZ() / 8000.0;
            this.line.add(packet);
            this.holdStartMs = System.currentTimeMillis();
            this.holding = true;
            atlas.util.ActionLedger.note(NAME, "held-self");
        }
        mc.addScheduledTask(this::decide);
    }

    private void hold(PacketEvent event, Packet<?> packet) {
        if (PacketHub.take(event, NAME)) {
            this.line.add(packet);
            return;
        }
        if (PingFix.exempt(packet)) {
            return;
        }
        if (!event.isCancelled()) {
            this.letGo();
        }
    }

    private void letGo() {
        List<Packet<?>> out;
        synchronized (this.lock) {
            out = new ArrayList<Packet<?>>(this.line);
            this.line.clear();
            this.holding = false;
            if (!out.isEmpty()) {
                atlas.util.ActionLedger.note(NAME, "release-self", out.size());
            }
            for (Packet<?> p : out) {
                process(p);
            }
        }
        if (mc.isCallingFromMinecraftThread()) {
            this.endHold();
        } else {
            mc.addScheduledTask(this::endHold);
        }
    }

    private void endHold() {
        if (!this.holding) {
            this.decision = null;
            pending = null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void process(Packet<?> packet) {
        try {
            if (mc.getNetHandler() != null) {
                ((Packet) packet).processPacket(mc.getNetHandler());
            }
        } catch (net.minecraft.network.ThreadQuickExitException ignored) {
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String push() {
        return String.format("%.2f/%.2f/%.2f", this.kx, this.ky, this.kz);
    }

    private void note(String line) {
        if (this.log.getValue() != 1) {
            return;
        }
        if (this.logFile == null) {
            this.logFile = new File(LOG_DIR, "edgeguard-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logFile, LINE_STAMP.format(new Date()) + "  " + line);
    }
}
