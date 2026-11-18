package atlas.util;

import atlas.property.properties.BooleanProperty;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFalling;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlaceUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static final double INSET = 0.1;

    public static final int GRID = 4;

    public static final int MAX_RAYS = 96;

    private static final int MAX_FACE_MISSES = 4;

    private PlaceUtil() {
    }

    public static MovingObjectPosition ray(Vec3 eye, float yaw, float pitch, double reach) {
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float cosPitch = -MathHelper.cos(-pitch * 0.017453292F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F);
        Vec3 end = eye.addVector(sinYaw * cosPitch * reach, sinPitch * reach, cosYaw * cosPitch * reach);
        return mc.theWorld.rayTraceBlocks(eye, end, false, false, false);
    }

    public static float[] rotationsTo(Vec3 eye, Vec3 target, float refYaw) {
        double dx = target.xCoord - eye.xCoord;
        double dy = target.yCoord - eye.yCoord;
        double dz = target.zCoord - eye.zCoord;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        yaw = refYaw + MathHelper.wrapAngleTo180_float(yaw - refYaw);
        return new float[]{yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F)};
    }

    public static double angle(float yaw, float pitch, float toYaw, float toPitch) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - yaw);
        float dPitch = toPitch - pitch;
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    public static float[] step(float yaw, float pitch, float toYaw, float toPitch, float maxStep) {
        float dYaw = MathHelper.wrapAngleTo180_float(toYaw - yaw);
        float dPitch = toPitch - pitch;
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        if (dist < 1.0E-4F) {
            return new float[]{yaw, pitch};
        }
        float scale = dist <= maxStep ? 1.0F : maxStep / dist;
        return quantize(yaw, pitch, yaw + dYaw * scale, pitch + dPitch * scale);
    }

    public static float[] quantize(float fromYaw, float fromPitch, float yaw, float pitch) {
        double gcd = RotationUtil.gcd();
        float outYaw = yaw;
        float outPitch = pitch;
        if (gcd > 0.0) {
            outYaw = fromYaw + (float) (Math.round(MathHelper.wrapAngleTo180_float(yaw - fromYaw) / gcd) * gcd);
            outPitch = fromPitch + (float) (Math.round((pitch - fromPitch) / gcd) * gcd);
        }
        return new float[]{outYaw, MathHelper.clamp_float(outPitch, -90.0F, 90.0F)};
    }

    public static boolean boxIntersects(AxisAlignedBB box, BlockPos pos) {
        return box.intersectsWith(new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
    }

    public static boolean clickable(BlockPos pos) {
        return !BlockUtil.isReplaceable(pos) && !BlockUtil.isInteractable(pos);
    }

    public static boolean usableBlock(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();

        return block.isFullCube() && block.getMaterial().isSolid() && block != Blocks.tnt
                && !(block instanceof BlockFalling) && !(block instanceof ITileEntityProvider)
                && !BlockUtil.isInteractable(block);
    }

    public static boolean canPlace(ItemStack stack, BlockPos support, EnumFacing face) {
        return usableBlock(stack)
                && ((ItemBlock) stack.getItem()).canPlaceBlockOnSide(mc.theWorld, support, face, mc.thePlayer, stack);
    }

    private static final int FALLBACK_SCORE = 100;

    private static int blockScore(Block block) {
        if (block == Blocks.obsidian) {
            return 0;
        }
        if (block == Blocks.end_stone) {
            return 1;
        }
        if (block == Blocks.planks || block == Blocks.log || block == Blocks.log2) {
            return 2;
        }
        if (block == Blocks.glass || block == Blocks.stained_glass) {
            return 3;
        }
        if (block == Blocks.hardened_clay || block == Blocks.stained_hardened_clay) {
            return 4;
        }
        if (block == Blocks.wool) {
            return 5;
        }
        return FALLBACK_SCORE;
    }

    public static int bestBlockSlot() {
        if (mc.thePlayer == null) {
            return -1;
        }
        int current = mc.thePlayer.inventory.currentItem;
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!usableBlock(stack)) {
                continue;
            }
            int score = blockScore(((ItemBlock) stack.getItem()).getBlock());
            if (score < bestScore || score == bestScore && i == current) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    public static final class Aim {
        public final BlockPos support;
        public final EnumFacing face;

        public final BlockPos cell;
        public final float yaw;
        public final float pitch;

        public final double cost;

        public final boolean current;

        public final Vec3 hitVec;

        Aim(BlockPos support, EnumFacing face, float yaw, float pitch, double cost, boolean current, Vec3 hitVec) {
            this.support = support;
            this.face = face;
            this.cell = support.offset(face);
            this.yaw = yaw;
            this.pitch = pitch;
            this.cost = cost;
            this.current = current;
            this.hitVec = hitVec;
        }

        public Aim verified(Vec3 hit, boolean current) {
            return new Aim(this.support, this.face, this.yaw, this.pitch, current ? 0.0 : this.cost, current, hit);
        }

        public FaceKey key() {
            return new FaceKey(this.support, this.face);
        }

        @Override
        public String toString() {
            return String.format("%d,%d,%d %s -> %d,%d,%d @%.1f/%.1f cost %.1f%s",
                    support.getX(), support.getY(), support.getZ(), face,
                    cell.getX(), cell.getY(), cell.getZ(), yaw, pitch, cost, current ? " (current)" : "");
        }
    }

    public static final class FaceKey {
        public final BlockPos support;
        public final EnumFacing face;

        public FaceKey(BlockPos support, EnumFacing face) {
            this.support = support;
            this.face = face;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof FaceKey)) {
                return false;
            }
            FaceKey k = (FaceKey) o;
            return k.face == this.face && k.support.equals(this.support);
        }

        @Override
        public int hashCode() {
            return this.support.hashCode() * 31 + this.face.ordinal();
        }
    }

    public static final class Stats {
        public int candidates;
        public int rays;
        public String source = "none";

        public void reset() {
            this.candidates = 0;
            this.rays = 0;
            this.source = "none";
        }
    }

    public static Aim fromRotation(Collection<BlockPos> goals, Vec3 eye, double reach, float yaw, float pitch,
                                   ItemStack stack, Set<FaceKey> excluded) {
        MovingObjectPosition hit = ray(eye, yaw, pitch, reach);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        BlockPos support = hit.getBlockPos();
        EnumFacing face = hit.sideHit;
        if (!goals.contains(support.offset(face)) || !clickable(support)
                || excluded.contains(new FaceKey(support, face)) || !canPlace(stack, support, face)) {
            return null;
        }
        return new Aim(support, face, yaw, pitch, 0.0, true, hit.hitVec);
    }

    public static MovingObjectPosition verify(Vec3 eye, double reach, float yaw, float pitch, BlockPos support, EnumFacing face) {
        MovingObjectPosition hit = ray(eye, yaw, pitch, reach);
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hit.getBlockPos().equals(support) && hit.sideHit == face) {
            return hit;
        }
        return null;
    }

    public static Aim bestAim(Collection<BlockPos> goalList, Vec3 eye, double reach, float yaw, float pitch,
                              Aim locked, ItemStack stack, Set<FaceKey> excluded, Stats stats) {
        if (goalList.isEmpty()) {
            return null;
        }
        Set<BlockPos> goals = goalList instanceof Set ? (Set<BlockPos>) goalList : new HashSet<BlockPos>(goalList);

        stats.rays++;
        Aim now = fromRotation(goals, eye, reach, yaw, pitch, stack, excluded);
        if (now != null) {
            stats.source = "current";
            return now;
        }

        if (locked != null && goals.contains(locked.cell) && clickable(locked.support)
                && !excluded.contains(locked.key()) && canPlace(stack, locked.support, locked.face)) {
            stats.rays++;
            MovingObjectPosition hit = verify(eye, reach, locked.yaw, locked.pitch, locked.support, locked.face);
            if (hit != null) {
                stats.source = "locked";
                return new Aim(locked.support, locked.face, locked.yaw, locked.pitch,
                        angle(yaw, pitch, locked.yaw, locked.pitch), false, hit.hitVec);
            }
        }

        List<Aim> candidates = new ArrayList<Aim>();
        for (BlockPos goal : goals) {
            for (EnumFacing toSupport : EnumFacing.values()) {
                BlockPos support = goal.offset(toSupport);
                EnumFacing face = toSupport.getOpposite();
                if (!clickable(support) || excluded.contains(new FaceKey(support, face))
                        || !canPlace(stack, support, face)) {
                    continue;
                }
                addFace(candidates, support, face, eye, reach, yaw, pitch);
            }
        }
        stats.candidates = candidates.size();
        Collections.sort(candidates, new Comparator<Aim>() {
            @Override
            public int compare(Aim a, Aim b) {
                return Double.compare(a.cost, b.cost);
            }
        });

        Map<FaceKey, Integer> misses = new HashMap<FaceKey, Integer>();
        int rays = 0;
        for (Aim candidate : candidates) {
            if (rays >= MAX_RAYS) {
                break;
            }
            FaceKey key = candidate.key();
            Integer missed = misses.get(key);
            if (missed != null && missed >= MAX_FACE_MISSES) {
                continue;
            }
            rays++;
            MovingObjectPosition hit = verify(eye, reach, candidate.yaw, candidate.pitch, candidate.support, candidate.face);
            if (hit != null) {
                stats.rays += rays;
                stats.source = "search";
                return candidate.verified(hit.hitVec, false);
            }
            misses.put(key, missed == null ? 1 : missed + 1);
        }
        stats.rays += rays;
        stats.source = "none";
        return null;
    }

    private static void addFace(List<Aim> out, BlockPos support, EnumFacing face, Vec3 eye, double reach,
                                float yaw, float pitch) {
        int fx = face.getFrontOffsetX();
        int fy = face.getFrontOffsetY();
        int fz = face.getFrontOffsetZ();
        double planeX = fx > 0 ? support.getX() + 1 : support.getX();
        double planeY = fy > 0 ? support.getY() + 1 : support.getY();
        double planeZ = fz > 0 ? support.getZ() + 1 : support.getZ();

        if (fx != 0 && (eye.xCoord - planeX) * fx <= 0.0
                || fy != 0 && (eye.yCoord - planeY) * fy <= 0.0
                || fz != 0 && (eye.zCoord - planeZ) * fz <= 0.0) {
            return;
        }
        double reachSq = reach * reach;
        for (int i = 0; i <= GRID; i++) {
            double u = INSET + (1.0 - 2.0 * INSET) * i / GRID;
            for (int j = 0; j <= GRID; j++) {
                double v = INSET + (1.0 - 2.0 * INSET) * j / GRID;
                double x = fx != 0 ? planeX : support.getX() + u;
                double y = fy != 0 ? planeY : support.getY() + (fx != 0 ? u : v);
                double z = fz != 0 ? planeZ : support.getZ() + v;
                Vec3 point = new Vec3(x, y, z);
                if (eye.squareDistanceTo(point) > reachSq) {
                    continue;
                }
                float[] rot = rotationsTo(eye, point, yaw);
                out.add(new Aim(support, face, rot[0], rot[1], angle(yaw, pitch, rot[0], rot[1]), false, null));
            }
        }
    }

    public static boolean click(Aim aim) {
        ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (aim.hitVec == null || !canPlace(stack, aim.support, aim.face)) {
            return false;
        }
        boolean placed = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack,
                aim.support, aim.face, aim.hitVec);
        if (placed) {
            mc.thePlayer.swingItem();
        }
        return placed;
    }

    public static final class Trace {
        private static final File LOG_DIR = new File("./config/Atlas/");
        private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
        private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
        private static final int REPEAT_TICKS = 40;
        private static final int CHAT_PER_SECOND = 4;

        private final String tag;
        private final BooleanProperty enabled;

        private final BooleanProperty chat;
        private final Map<String, String> lastMessage = new HashMap<String, String>();
        private final Map<String, Integer> lastTick = new HashMap<String, Integer>();
        private File target;
        private int chatWindow = Integer.MIN_VALUE;
        private int chatLines;

        public Trace(String tag, BooleanProperty enabled) {
            this(tag, enabled, null);
        }

        public Trace(String tag, BooleanProperty enabled, BooleanProperty chat) {
            this.tag = tag;
            this.enabled = enabled;
            this.chat = chat;
        }

        public boolean on() {
            return this.enabled.getValue();
        }

        public void log(int tick, String stage, String message) {
            if (!this.on()) {
                return;
            }
            String previous = this.lastMessage.get(stage);
            Integer at = this.lastTick.get(stage);
            if (message.equals(previous) && at != null && tick - at < REPEAT_TICKS) {
                return;
            }
            this.lastMessage.put(stage, message);
            this.lastTick.put(stage, tick);
            String line = this.tag + " " + stage + (message.isEmpty() ? "" : ": " + message);
            if (this.target == null) {
                this.target = new File(LOG_DIR, "placedebug-" + FILE_STAMP.format(new Date()) + ".txt");
            }
            AsyncLog.append(this.target, LINE_STAMP.format(new Date()) + "  t" + tick + "  " + line);
            if (this.chat == null || !this.chat.getValue()) {
                return;
            }
            if (tick - this.chatWindow >= 20) {
                this.chatWindow = tick;
                this.chatLines = 0;
            }
            if (this.chatLines < CHAT_PER_SECOND) {
                this.chatLines++;
                ChatUtil.sendFormatted("&8" + line.replace("&", ""));
            }
        }

        public void reset() {
            this.lastMessage.clear();
            this.lastTick.clear();
        }
    }
}
