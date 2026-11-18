package atlas.module.modules;

import atlas.util.Ping;
import atlas.util.AsyncLog;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LeftClickMouseEvent;
import atlas.events.PacketEvent;
import atlas.events.Render2DEvent;
import atlas.events.TickEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.ChatUtil;
import atlas.util.HealthUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class HitCheck extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int HISTORY = 64;
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss");

    public final FloatProperty rangeThreshold = new FloatProperty("range-threshold", 3.0F, 2.5F, 6.0F);
    public final IntProperty windowPad = new IntProperty("window-pad", 6, 0, 20);

    public final IntProperty invulnHurtTime = new IntProperty("invuln-hurt-time", 1, 0, 10);

    public final ModeProperty hud = new ModeProperty("hud", 0, new String[]{"NONE", "COMPACT", "LIST"});
    public final IntProperty hudX = new IntProperty("hud-x", 4, 0, 800, () -> this.hud.getValue() != 0);
    public final IntProperty hudY = new IntProperty("hud-y", 120, 0, 600, () -> this.hud.getValue() != 0);
    public final IntProperty hudLines = new IntProperty("hud-lines", 4, 1, 10, () -> this.hud.getValue() == 2);
    public final IntProperty hudFade = new IntProperty("hud-fade", 15, 3, 120, () -> this.hud.getValue() == 2);

    public final BooleanProperty explainMisses = new BooleanProperty("explain-misses", true);
    public final BooleanProperty chatSummary = new BooleanProperty("chat-summary", false);
    public final IntProperty summarySeconds = new IntProperty("summary-seconds", 30, 5, 300);

    public final BooleanProperty logFile = new BooleanProperty("log-file", true);

    public final BooleanProperty logEvents = new BooleanProperty("log-events", true,
            this.logFile::getValue);

    private final double[] selfX = new double[HISTORY];
    private final double[] selfY = new double[HISTORY];
    private final double[] selfZ = new double[HISTORY];
    private int selfIndex;
    private int selfFilled;

    private final Map<Integer, Integer> hurtAnimations = new HashMap<Integer, Integer>();

    private final Map<Integer, Track> tracks = new HashMap<Integer, Track>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<Pending>();

    private static final int EXPECT_TICKS = 2;
    private final ArrayDeque<Expectation> awaiting = new ArrayDeque<Expectation>();
    private final ArrayDeque<int[]> attacks = new ArrayDeque<int[]>();
    private final ArrayDeque<Miss> recent = new ArrayDeque<Miss>();
    private final Map<String, Integer> reasons = new LinkedHashMap<String, Integer>();

    private int clicks;
    private int expected;
    private int suppressed;
    private int swings;
    private int hits;
    private int misses;

    private int undetermined;
    private int tickCounter;
    private long nextSummaryAt;

    private File logTarget;
    private long sessionStart;

    private double lastClosest = -1.0;

    public HitCheck() {
        super("HitCheck", false, false,
                "Counts which swings landed and explains the ones that did not");
    }

    @Override
    public void onEnabled() {
        reset();
    }

    @Override
    public void onDisabled() {
        if (this.swings > 0 || this.expected > 0) {
            writeLog("TOTAL  " + plainSummary());
        }
        this.tracks.clear();
        this.pending.clear();
    }

    private void reset() {
        if (this.swings > 0 || this.expected > 0) {
            writeLog("TOTAL  " + plainSummary());
        }
        this.sessionStart = System.currentTimeMillis();
        writeLog("---- round start ----");
        this.tracks.clear();
        this.pending.clear();
        this.recent.clear();
        this.reasons.clear();
        this.hurtAnimations.clear();
        this.clicks = 0;
        this.expected = 0;
        this.suppressed = 0;
        this.swings = 0;
        this.awaiting.clear();
        this.attacks.clear();
        this.hits = 0;
        this.misses = 0;
        this.undetermined = 0;
        this.selfIndex = 0;
        this.selfFilled = 0;
        this.nextSummaryAt = System.currentTimeMillis() + this.summarySeconds.getValue() * 1000L;
    }

    @EventTarget(atlas.event.types.Priority.LOWEST)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.currentScreen != null
                || event.isCancelled()) {
            return;
        }
        this.clicks++;

        MovingObjectPosition over = mc.objectMouseOver;
        if (over == null || over.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                || over.entityHit == null) {
            return;
        }
        this.expected++;
        Expectation expectation = new Expectation();
        expectation.entityId = over.entityHit.getEntityId();
        expectation.tick = this.tickCounter;
        this.awaiting.addLast(expectation);
    }

    private void noteAttack(int entityId) {
        this.attacks.addLast(new int[]{entityId, this.tickCounter});
        while (this.attacks.size() > 64) {
            this.attacks.removeFirst();
        }
    }

    private void resolveExpectations() {
        while (!this.awaiting.isEmpty()
                && this.tickCounter - this.awaiting.peekFirst().tick > EXPECT_TICKS) {
            Expectation expectation = this.awaiting.pollFirst();
            boolean satisfied = false;
            for (int[] attack : this.attacks) {
                if (attack[0] == expectation.entityId
                        && attack[1] >= expectation.tick
                        && attack[1] <= expectation.tick + EXPECT_TICKS) {
                    satisfied = true;
                    break;
                }
            }
            if (!satisfied) {
                this.suppressed++;
                if (this.logEvents.getValue()) {
                    writeLog("SUPPRESSED  target=" + nameOf(expectation.entityId)
                            + "  held=" + heldItem());
                }
            }
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof S01PacketJoinGame) {
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        reset();
                    }
                });
                return;
            }
            if (event.getPacket() instanceof S19PacketEntityStatus) {
                final S19PacketEntityStatus status = (S19PacketEntityStatus) event.getPacket();
                if (status.getOpCode() == 2) {
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            if (mc.theWorld == null) {
                                return;
                            }
                            Entity entity = status.getEntity(mc.theWorld);
                            if (entity != null) {
                                hurtAnimations.put(entity.getEntityId(), tickCounter);
                            }
                        }
                    });
                }
            }
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        Entity target = packet.getEntityFromWorld(mc.theWorld);
        if (!(target instanceof EntityLivingBase)) {
            return;
        }
        this.swings++;
        noteAttack(target.getEntityId());

        EntityLivingBase living = (EntityLivingBase) target;
        Pending shot = new Pending();
        shot.entityId = living.getEntityId();
        shot.tick = this.tickCounter;
        shot.deadline = this.tickCounter + this.window();
        shot.targetHurtTime = living.hurtTime;
        shot.usingItem = mc.thePlayer.isUsingItem();
        shot.healthBefore = HealthUtil.resolve(living);
        this.pending.addLast(shot);
    }

    private int window() {
        int ping = this.ping();
        int ticks = ping > 0 ? (int) Math.ceil(ping / 50.0) : 4;
        ticks += this.windowPad.getValue();

        atlas.module.Module backtrack = atlas.Atlas.moduleManager == null ? null
                : atlas.Atlas.moduleManager.modules.get(BackTrack.class);
        if (backtrack instanceof BackTrack) {
            ticks += (int) Math.ceil(((BackTrack) backtrack).holdMs() / 50.0) + 1;
        }
        return Math.min(Math.max(ticks, 2), HISTORY - 1);
    }

    private int ping() {
        return Ping.own();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE
                || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.tickCounter++;

        this.selfX[this.selfIndex] = mc.thePlayer.posX;
        this.selfY[this.selfIndex] = mc.thePlayer.posY;
        this.selfZ[this.selfIndex] = mc.thePlayer.posZ;
        this.selfIndex = (this.selfIndex + 1) % HISTORY;
        if (this.selfFilled < HISTORY) {
            this.selfFilled++;
        }

        Set<Integer> alive = new HashSet<Integer>();
        for (Object object : mc.theWorld.loadedEntityList) {
            if (!(object instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) object;
            if (living == mc.thePlayer) {
                continue;
            }
            alive.add(living.getEntityId());
            Track track = this.tracks.get(living.getEntityId());
            if (track == null) {
                track = new Track();
                this.tracks.put(living.getEntityId(), track);
            }
            track.push(living, this.tickCounter);
        }

        resolveExpectations();
        resolveDue();

        Iterator<Map.Entry<Integer, Track>> it = this.tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Track> entry = it.next();
            if (!alive.contains(entry.getKey()) && !hasPendingFor(entry.getKey())) {
                it.remove();
            }
        }

        long now = System.currentTimeMillis();
        if (now >= this.nextSummaryAt) {
            this.nextSummaryAt = now + this.summarySeconds.getValue() * 1000L;

            if (this.swings > 0 || this.expected > 0) {
                writeLog("SUMMARY  " + plainSummary());
            }
            if (this.chatSummary.getValue()) {
                ChatUtil.sendFormatted("&7[&bHitCheck&7] " + summaryLine());
            }
        }
    }

    private boolean hasPendingFor(int entityId) {
        for (Pending shot : this.pending) {
            if (shot.entityId == entityId) {
                return true;
            }
        }
        return false;
    }

    private void resolveDue() {
        while (!this.pending.isEmpty() && this.pending.peekFirst().deadline <= this.tickCounter) {
            Pending shot = this.pending.pollFirst();

            resolve(shot);
        }
    }

    private void resolve(Pending shot) {
        Track track = this.tracks.get(shot.entityId);
        Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(shot.entityId);
        EntityLivingBase living = entity instanceof EntityLivingBase ? (EntityLivingBase) entity : null;

        boolean landed = track != null && track.damagedBetween(shot.tick, shot.deadline);
        if (!landed && living != null && HealthUtil.resolve(living) < shot.healthBefore - 0.01F) {
            landed = true;
        }

        if (landed) {
            this.hits++;
            reportToFlagDetector(false, shot.entityId);
            return;
        }
        if (living == null) {
            this.undetermined++;
            if (this.logEvents.getValue()) {
                writeLog("GONE  target=#" + shot.entityId + "  (killed or despawned)");
            }
            return;
        }
        this.misses++;
        String reason = explain(shot, track, living);
        if ("dropped".equals(reason)) {
            reportToFlagDetector(true, shot.entityId);
        }
        Integer seen = this.reasons.get(reason);
        this.reasons.put(reason, seen == null ? 1 : seen + 1);
        this.recent.addLast(new Miss(reason, System.currentTimeMillis()));
        while (this.recent.size() > 32) {
            this.recent.removeFirst();
        }
        if (this.logEvents.getValue()) {
            StringBuilder sb = new StringBuilder();
            sb.append("MISS  ").append(reason)
                    .append("  target=").append(nameOf(shot.entityId))
                    .append("  hurt=").append(shot.targetHurtTime)
                    .append("  ping=").append(this.ping()).append("ms")
                    .append("  window=").append(shot.deadline - shot.tick).append('t');
            if (this.lastClosest >= 0.0) {
                sb.append(String.format("  dist=%.2f", this.lastClosest));
            }
            if (shot.usingItem) {
                sb.append("  using-item");
            }
            writeLog(sb.toString());
        }
    }

    private void reportToFlagDetector(boolean dropped, int entityId) {
        atlas.module.Module module = atlas.Atlas.moduleManager == null ? null
                : atlas.Atlas.moduleManager.modules.get(FlagDetector.class);
        if (module instanceof FlagDetector) {
            ((FlagDetector) module).noteHitResult(dropped, nameOf(entityId), Math.max(0.0, this.lastClosest));
        }
    }

    private String nameOf(int entityId) {
        Entity entity = mc.theWorld == null ? null : mc.theWorld.getEntityByID(entityId);
        return entity == null ? ("#" + entityId) : entity.getName();
    }

    private String heldItem() {
        try {
            return mc.thePlayer.getHeldItem() == null
                    ? "empty" : mc.thePlayer.getHeldItem().getItem().getUnlocalizedName();
        } catch (Exception ignored) {
            return "?";
        }
    }

    private String explain(Pending shot, Track track, EntityLivingBase living) {
        this.lastClosest = -1.0;
        if (shot.usingItem) {
            return "using-item";
        }
        if (shot.targetHurtTime > this.invulnHurtTime.getValue()) {
            return "invuln";
        }
        if (track == null || living == null) {
            return "unknown";
        }
        int window = shot.deadline - shot.tick;
        double closest = Double.MAX_VALUE;
        boolean sawIt = false;
        for (int back = 0; back <= window; back++) {
            Vec3 mine = selfAt(back);
            Vec3 theirs = track.at(back);
            if (mine == null || theirs == null) {
                continue;
            }
            Vec3 eyes = new Vec3(mine.xCoord, mine.yCoord + mc.thePlayer.getEyeHeight(), mine.zCoord);
            double half = living.width / 2.0;
            AxisAlignedBB box = new AxisAlignedBB(
                    theirs.xCoord - half, theirs.yCoord, theirs.zCoord - half,
                    theirs.xCoord + half, theirs.yCoord + living.height, theirs.zCoord + half);
            double distance = distanceToBox(eyes, box);
            if (distance < closest) {
                closest = distance;
            }
            if (!sawIt) {
                Vec3 theirEyes = new Vec3(theirs.xCoord,
                        theirs.yCoord + living.getEyeHeight(), theirs.zCoord);
                if (mc.theWorld.rayTraceBlocks(eyes, theirEyes, false, true, false) == null) {
                    sawIt = true;
                }
            }
        }
        if (closest != Double.MAX_VALUE) {
            this.lastClosest = closest;
        }
        if (closest != Double.MAX_VALUE && closest > this.rangeThreshold.getValue()) {
            return "out-of-range";
        }
        if (!sawIt) {
            return "blocked";
        }

        Integer animated = this.hurtAnimations.get(shot.entityId);
        if (animated == null || animated < shot.tick || animated > shot.deadline) {
            return "dropped";
        }
        return "unknown";
    }

    private static double distanceToBox(Vec3 point, AxisAlignedBB box) {
        double dx = Math.max(Math.max(box.minX - point.xCoord, 0.0), point.xCoord - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.yCoord, 0.0), point.yCoord - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.zCoord, 0.0), point.zCoord - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private Vec3 selfAt(int back) {
        if (back >= this.selfFilled) {
            return null;
        }
        int at = ((this.selfIndex - 1 - back) % HISTORY + HISTORY) % HISTORY;
        return new Vec3(this.selfX[at], this.selfY[at], this.selfZ[at]);
    }

    private int judged() {
        return Math.max(0, this.swings - this.undetermined);
    }

    private int rate() {
        return judged() <= 0 ? 0 : Math.round(this.hits * 100.0F / judged());
    }

    private String summaryLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("&fhits ").append(this.hits).append('/').append(judged())
                .append(" &7(").append(rate()).append("%)");
        if (this.suppressed > 0) {
            sb.append(" &8| &c").append(this.suppressed).append('/').append(this.expected)
                    .append(" suppressed&7");
        }
        for (Map.Entry<String, Integer> entry : this.reasons.entrySet()) {
            sb.append(" &8| &7").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    public int judgedSwings() {
        return judged();
    }

    public int landedHits() {
        return this.hits;
    }

    public int suppressedClicks() {
        return this.suppressed;
    }

    public int missesFor(String reason) {
        Integer seen = this.reasons.get(reason);
        return seen == null ? 0 : seen;
    }

    public void resetCounters() {
        reset();
    }

    private String plainSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("hits ").append(this.hits).append('/').append(judged())
                .append(" (").append(rate()).append("%)")
                .append("  swings ").append(this.swings)
                .append("  gone ").append(this.undetermined)
                .append("  expected ").append(this.expected)
                .append("  suppressed ").append(this.suppressed)
                .append("  same-tick ").append(atlas.management.TickActions.refusals())
                .append("  clicks ").append(this.clicks)
                .append("  ping ").append(this.ping()).append("ms");
        double minutes = (System.currentTimeMillis() - this.sessionStart) / 60000.0;
        if (minutes >= 0.05) {
            sb.append(String.format("  %.1f min", minutes));
        }
        for (Map.Entry<String, Integer> entry : this.reasons.entrySet()) {
            sb.append("  | ").append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    private void writeLog(String line) {
        if (!this.logFile.getValue()) {
            return;
        }
        if (this.logTarget == null) {
            this.logTarget = new File(LOG_DIR, "hits-" + FILE_STAMP.format(new Date()) + ".txt");
        }

        AsyncLog.append(this.logTarget, LINE_STAMP.format(new Date()) + "  " + line);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || this.hud.getValue() == 0 || mc.fontRendererObj == null
                || mc.thePlayer == null || mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        boolean right = alignRight();
        int margin = Math.min(this.hudX.getValue(), Math.max(0, sr.getScaledWidth() - 120));
        int y = Math.min(this.hudY.getValue(), Math.max(0, sr.getScaledHeight() - 20));

        GL11.glPushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        String header = String.format("Hits %d/%d §7(%d%%)%s",
                this.hits, judged(), rate(),
                this.suppressed > 0 ? String.format("  §c-%d suppressed", this.suppressed) : "");
        mc.fontRendererObj.drawStringWithShadow(header, lineX(sr, header, margin, right), y, 0xFFFFFFFF);

        if (this.hud.getValue() == 2 && this.explainMisses.getValue()) {
            long now = System.currentTimeMillis();
            long fade = this.hudFade.getValue() * 1000L;
            int drawn = 0;
            Iterator<Miss> it = this.recent.descendingIterator();
            while (it.hasNext() && drawn < this.hudLines.getValue()) {
                Miss miss = it.next();
                long age = now - miss.at;
                if (age > fade) {
                    break;
                }
                int alpha = (int) (255 - 195 * ((double) age / fade));
                if (alpha < 60) {
                    alpha = 60;
                }
                String line = String.format("§c%s  §7%.1fs", miss.reason, age / 1000.0);
                mc.fontRendererObj.drawStringWithShadow(line,
                        lineX(sr, line, margin + 2, right), y + 11 + drawn * 10,
                        (alpha << 24) | 0xFFFFFF);
                drawn++;
            }
        }

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GL11.glPopMatrix();
    }

    private boolean alignRight() {
        HUD hud = (HUD) atlas.Atlas.moduleManager.modules.get(HUD.class);
        return hud != null && hud.isEnabled() && hud.posX.getValue() == 0;
    }

    private int lineX(ScaledResolution sr, String line, int margin, boolean right) {
        if (!right) {
            return margin;
        }
        return sr.getScaledWidth() - margin - mc.fontRendererObj.getStringWidth(line);
    }

    @Override
    public String[] getSuffix() {
        if (this.swings <= 0) {
            return new String[]{"--"};
        }
        return new String[]{rate() + "%"};
    }

    private static class Track {
        final double[] x = new double[HISTORY];
        final double[] y = new double[HISTORY];
        final double[] z = new double[HISTORY];
        int index;
        int filled;
        int lastHurtTime;
        int lastDamageTick = Integer.MIN_VALUE;

        void push(EntityLivingBase living, int tick) {
            this.x[this.index] = living.posX;
            this.y[this.index] = living.posY;
            this.z[this.index] = living.posZ;
            this.index = (this.index + 1) % HISTORY;
            if (this.filled < HISTORY) {
                this.filled++;
            }

            if (living.hurtTime > this.lastHurtTime) {
                this.lastDamageTick = tick;
            }
            this.lastHurtTime = living.hurtTime;
        }

        boolean damagedBetween(int from, int to) {
            return this.lastDamageTick >= from && this.lastDamageTick <= to;
        }

        Vec3 at(int back) {
            if (back >= this.filled) {
                return null;
            }
            int at = ((this.index - 1 - back) % HISTORY + HISTORY) % HISTORY;
            return new Vec3(this.x[at], this.y[at], this.z[at]);
        }
    }

    private static class Pending {
        int entityId;
        int tick;
        int deadline;
        int targetHurtTime;
        boolean usingItem;
        float healthBefore;
    }

    private static class Expectation {
        int entityId;
        int tick;
    }

    private static class Miss {
        final String reason;
        final long at;

        Miss(String reason, long at) {
            this.reason = reason;
            this.at = at;
        }
    }
}
