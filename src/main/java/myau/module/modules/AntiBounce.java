package myau.module.modules;

import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.LivingUpdateEvent;
import myau.events.LoadWorldEvent;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.util.AsyncLog;
import myau.util.Ping;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Out of a block this player is stuck in (2026-10-16).
 *
 * Owner, on a high ping: "I often end up inside blocks and cannot move" --
 * pulled back into a block just placed, a ghost block, or a block placed onto
 * the body; not sure which. All end the same way: the player's box overlaps a
 * solid block.
 *
 * A 1.8 server does not pull a player back for colliding when the move
 * STARTS inside a block (NetHandlerPlayServer.processPlayer only sets back a
 * move from free space into a block), and vanilla's own pushOutOfBlocks moves
 * a stuck player 0.1 a tick. So once the box has overlapped a block for two
 * ticks, this finds the shortest way out -- each horizontal direction and
 * up, the one being walked in first on a tie -- and moves along it at no
 * more than `speed` a tick. Nothing during a Clutch catch, in creative or
 * spectator, flying, or with noclip.
 *
 * `log` writes each time stuck (config/Atlas/stuck-*.txt): where, which
 * blocks, whether this client placed them and how long ago, ticks since the
 * last server correction, ping, the way out and how it ended.
 */
public class AntiBounce extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final double STEP = 0.05;
    private static final File LOG_DIR = new File("./config/Atlas/");

    public final FloatProperty speed = new FloatProperty("speed", 0.12F, 0.05F, 0.3F);
    public final FloatProperty maxEscape = new FloatProperty("max-escape", 1.0F, 0.3F, 1.5F);
    public final BooleanProperty log = new BooleanProperty("log", true);

    private int tick;
    private volatile boolean corrected;
    private int lastCorrection = -1000;
    private int insideTicks;
    private int stuckSince;
    private String stuckWhat = "";
    private String exitWay = "none";
    private File logFile;
    /** Blocks this client placed lately: position -> tick. */
    private final Map<BlockPos, Integer> placed = new LinkedHashMap<BlockPos, Integer>();

    public AntiBounce() {
        super("AntiBounce", false, false, "Moves you out of a block you are stuck in");
    }

    /**
     * The shortest way out of a box: {dx, dy, dz} of at most maxDistance, or
     * null. Directions tried at each distance in this order: the preferred
     * one (may be null), +x, -x, +z, -z, up.
     *
     * @param box     {minX, minY, minZ, maxX, maxY, maxZ}
     * @param blocked whether a box overlaps something solid
     */
    static double[] escape(double[] box, Predicate<double[]> blocked, double[] preferred, double maxDistance) {
        List<double[]> ways = new ArrayList<double[]>();
        if (preferred != null) {
            ways.add(preferred);
        }
        ways.add(new double[]{1, 0, 0});
        ways.add(new double[]{-1, 0, 0});
        ways.add(new double[]{0, 0, 1});
        ways.add(new double[]{0, 0, -1});
        ways.add(new double[]{0, 1, 0});
        for (double d = STEP; d <= maxDistance + 1e-9; d += STEP) {
            for (double[] way : ways) {
                double[] moved = {box[0] + way[0] * d, box[1] + way[1] * d, box[2] + way[2] * d,
                        box[3] + way[0] * d, box[4] + way[1] * d, box[5] + way[2] * d};
                if (!blocked.test(moved)) {
                    return new double[]{way[0] * d, way[1] * d, way[2] * d};
                }
            }
        }
        return null;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (event.getType() == EventType.RECEIVE && event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.corrected = true;
        } else if (event.getType() == EventType.SEND && event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement use = (C08PacketPlayerBlockPlacement) event.getPacket();
            if (use.getPlacedBlockDirection() != 255 && use.getPosition() != null) {
                BlockPos at = use.getPosition().offset(net.minecraft.util.EnumFacing.getFront(use.getPlacedBlockDirection()));
                this.placed.remove(at);
                this.placed.put(at, this.tick);
                if (this.placed.size() > 32) {
                    Iterator<BlockPos> oldest = this.placed.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
            }
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.placed.clear();
        this.insideTicks = 0;
    }

    @EventTarget
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tick++;
        if (this.corrected) {
            this.corrected = false;
            this.lastCorrection = this.tick;
        }
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.noClip || mc.thePlayer.isSpectator()
                || mc.playerController.isInCreativeMode() || myau.management.Arbiter.catching()) {
            this.end("not watched");
            return;
        }
        AxisAlignedBB bb = mc.thePlayer.getEntityBoundingBox();
        List<AxisAlignedBB> inside = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, bb.contract(0.02, 0.02, 0.02));
        if (inside.isEmpty()) {
            this.end(this.insideTicks > 0 ? "out" : null);
            return;
        }
        if (++this.insideTicks == 1) {
            this.stuckSince = this.tick;
            this.stuckWhat = this.describe(inside);
            this.exitWay = "none";
        }
        if (this.insideTicks < 2) {
            return;
        }
        double[] box = {bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
        double[] way = escape(box, b -> !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer,
                        new AxisAlignedBB(b[0], b[1], b[2], b[3], b[4], b[5]).contract(0.001, 0.001, 0.001)).isEmpty(),
                this.walkedWay(), this.maxEscape.getValue());
        if (way == null) {
            this.exitWay = "no way out within " + this.maxEscape.getValue();
            return;
        }
        this.exitWay = String.format("%+.2f %+.2f %+.2f", way[0], way[1], way[2]);
        double cap = this.speed.getValue();
        double length = Math.sqrt(way[0] * way[0] + way[2] * way[2]);
        if (length > 1e-6) {
            double scale = Math.min(cap, length + 0.02) / length;
            mc.thePlayer.motionX = way[0] * scale;
            mc.thePlayer.motionZ = way[2] * scale;
        }
        if (way[1] > 0 && mc.thePlayer.onGround) {
            // Up and out, no faster than a jump.
            mc.thePlayer.motionY = Math.min(0.42, way[1] + 0.05);
        }
    }

    /** The axis direction the player walks in most, or null when not walking. */
    private double[] walkedWay() {
        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) {
            return null;
        }
        float yaw = mc.thePlayer.rotationYaw * (float) Math.PI / 180.0F;
        double x = -MathHelper.sin(yaw) * forward + MathHelper.cos(yaw) * strafe;
        double z = MathHelper.cos(yaw) * forward + MathHelper.sin(yaw) * strafe;
        return Math.abs(x) >= Math.abs(z) ? new double[]{Math.signum(x), 0, 0} : new double[]{0, 0, Math.signum(z)};
    }

    private String describe(List<AxisAlignedBB> inside) {
        StringBuilder what = new StringBuilder();
        int shown = 0;
        for (AxisAlignedBB box : inside) {
            if (shown++ >= 3) {
                what.append(" ...");
                break;
            }
            BlockPos at = new BlockPos((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2);
            Block block = mc.theWorld.getBlockState(at).getBlock();
            Integer when = this.placed.get(at);
            what.append(String.format("%s%s@%d,%d,%d%s", what.length() == 0 ? "" : ", ",
                    String.valueOf(Block.blockRegistry.getNameForObject(block)).replace("minecraft:", ""),
                    at.getX(), at.getY(), at.getZ(),
                    when == null ? "" : String.format(" (placed by us %dt ago)", this.tick - when)));
        }
        return what.toString();
    }

    private void end(String how) {
        if (this.insideTicks >= 2 && how != null && this.log.getValue()) {
            if (this.logFile == null) {
                this.logFile = new File(LOG_DIR, "stuck-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt");
            }
            int sinceCorrection = this.stuckSince - this.lastCorrection;
            AsyncLog.append(this.logFile, String.format("%s  STUCK %dt in %s | correction %s before | ping %dms | way out %s | %s",
                    new SimpleDateFormat("HH:mm:ss").format(new Date()), this.insideTicks, this.stuckWhat,
                    sinceCorrection > 200 ? "none" : sinceCorrection + "t", Ping.own(), this.exitWay, how));
        }
        this.insideTicks = 0;
    }

    @Override
    public void onDisabled() {
        this.insideTicks = 0;
    }
}
