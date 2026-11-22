package atlas.management;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.event.types.Priority;
import atlas.events.KnockbackEvent;
import atlas.events.LivingUpdateEvent;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.UpdateEvent;
import atlas.module.Module;
import atlas.module.modules.*;
import atlas.sim.HeldKnockback;
import atlas.sim.Impulses;
import atlas.sim.MoveAudit;
import atlas.sim.PlayerSim;
import atlas.sim.SelfCheck;
import atlas.sim.SimPlayer;
import atlas.util.AsyncLog;
import atlas.util.render.DeviceProfile;
import atlas.util.render.MobileSafe;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.MathHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

public final class MoveCheck {
    public static final String SETTING = "move-check";
    public static final String[] MODES = {"Auto", "On", "Off"};
    private static volatile int setting = -1;
    private static volatile boolean running;

    private static final int LOW_FPS = 100;
    private static final int LOW_FPS_SECONDS = 5;
    private static final int HIGH_FPS = 115;
    private static final int HIGH_FPS_SECONDS = 10;
    private final FpsGate fps = new FpsGate(LOW_FPS, LOW_FPS_SECONDS, HIGH_FPS, HIGH_FPS_SECONDS);
    private long fpsSampledAt;
    private boolean wasActive;
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File LOG_DIR = new File("./config/Atlas/");
    private static final SimpleDateFormat FILE_STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss");
    private static final SimpleDateFormat LINE_STAMP = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int MISS_LINES_A_SECOND = 4;
    private static final long AFTER_CORRECTION_MS = 300L;
    private static final long AFTER_KNOCKBACK_MS = 100L;
    private static final float BIG_TURN = 30.0F;

    private static final float THRESHOLD = 0.02F;

    private static final int LOG_EVERY = 30;

    private File logFile;
    private volatile long correctedAt;
    private volatile long knockedAt;

    private double[] lookErrors;

    private String lookFlags = "";
    private boolean sampled;
    private float lastSentYaw = Float.NaN;
    private boolean lastSprinting;

    private String modulesLogged = "";
    private long summaryAt;
    private long lineSecond;
    private int linesThisSecond;
    private int ticks;
    private int skipped;
    private int lookMisses;
    private int cameraMisses;
    private int bigTurns;
    private int sprintChanges;
    private int plainTicks;
    private int plainMisses;

    private PlayerSim simPredicted;
    private int simTicks;
    private int simOff;
    private int simPlainTicks;
    private int simPlainOff;
    private double simWorst;
    private static final double SIM_OFF = 0.001;

    private final MoveAudit audit = new MoveAudit();

    private static volatile int playerId = Integer.MIN_VALUE;

    private static volatile String knockbackHeldBy;
    private int kbOffered;
    private int kbIgnored;

    private final HeldKnockback heldKnockback = new HeldKnockback();

    private final Map<String, int[]> selfBy = new TreeMap<String, int[]>();

    private volatile Boolean serverSprint;
    private volatile Boolean serverSneak;
    private int selfTicks;
    private int selfOff;
    private int selfPlainTicks;
    private int selfPlainOff;
    private double selfWorst;
    private long selfLineSecond;

    private float moveYaw = Float.NaN;
    private boolean moveSprinting;
    private boolean moveSneaking;
    private int moveTick = Integer.MIN_VALUE;
    private int selfLinesThisSecond;

    private final Map<String, int[]> byTag = new TreeMap<String, int[]>();

    public MoveCheck() {
        atlas.util.ActionWatch.beforeUpdatePost = this::endOfTick;
        reset();
    }

    public static void setSetting(int mode) {
        setting = Math.max(0, Math.min(MODES.length - 1, mode));
    }

    public static boolean running() {
        return running;
    }

    public static String describe() {
        int mode = setting < 0 ? MobileSafe.readMode(SETTING, MODES) : setting;
        return MODES[mode].toLowerCase() + (running ? " · running" : " · paused");
    }

    private boolean active() {
        int mode = setting;
        if (mode < 0) {
            mode = MobileSafe.readMode(SETTING, MODES);
            setting = mode;
        }
        boolean on;
        if (mode == 1) {
            on = true;
        } else if (mode == 2) {
            on = false;
        } else {
            sampleFps();
            on = !this.fps.slow() && !DeviceProfile.isMobile();
        }
        if (on && !this.wasActive) {
            reset();
        }
        this.wasActive = on;
        running = on;
        return on;
    }

    private void sampleFps() {
        long now = System.currentTimeMillis();
        if (now - this.fpsSampledAt < 1000L || mc.theWorld == null) {
            return;
        }
        this.fpsSampledAt = now;
        this.fps.sample(Minecraft.getDebugFPS());
    }

    public static final String CHAT_SETTING = "move-check-chat";
    public static final String[] CHAT_MODES = {"On", "Off"};
    private static volatile int chatSetting = -1;
    private static final long ALERT_EVERY_MS = 3000L;
    private final AlertLimiter alerts = new AlertLimiter(ALERT_EVERY_MS);

    public static void setChat(int mode) {
        chatSetting = Math.max(0, Math.min(CHAT_MODES.length - 1, mode));
    }

    private static boolean chatOn() {
        int mode = chatSetting;
        if (mode < 0) {
            mode = MobileSafe.readMode(CHAT_SETTING, CHAT_MODES);
            chatSetting = mode;
        }
        return mode == 0;
    }

    private void alert(String kind, String tag, String what, String why) {
        if (!chatOn() || mc.thePlayer == null) {
            return;
        }

        int held = this.alerts.pass(kind + "|" + tag, System.currentTimeMillis());
        if (held < 0) {
            return;
        }
        String who = !"plain".equals(tag) ? "&f" + tag
                : why.isEmpty() ? "&7no module &8(the prediction may be off)" : "&7no module seen";
        String more = held > 0 ? String.format(" &8+%d more", held) : "";
        atlas.util.ChatUtil.sendFormatted("&7[&cSim&7] " + who + " &7" + what + why + more);
    }

    private void reset() {
        this.alerts.clear();
        this.audit.reset();
        this.heldKnockback.clear();
        knockbackHeldBy = null;
        this.logFile = null;
        this.modulesLogged = "";
        this.sampled = false;
        this.lastSentYaw = Float.NaN;
        this.serverSprint = null;
        this.serverSneak = null;
        clearCounts();
        this.summaryAt = System.currentTimeMillis();
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        if (this.ticks + this.skipped > 0) {
            summary();
            this.summaryAt = System.currentTimeMillis();
        }
        this.sampled = false;
        this.lastSentYaw = Float.NaN;
        this.serverSprint = null;
        this.serverSneak = null;

        playerId = Integer.MIN_VALUE;
        knockbackHeldBy = null;
        this.heldKnockback.clear();
        this.audit.reset();
    }

    @EventTarget(Priority.HIGHEST)

    public void onPacket(PacketEvent event) {
        if (!running) {
            return;
        }
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            this.correctedAt = System.currentTimeMillis();
        } else if (event.getPacket() instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) event.getPacket()).getEntityID() == playerId) {
            knockbackHeldBy = null;
        } else if (event.getPacket() instanceof S27PacketExplosion) {
            this.knockedAt = System.currentTimeMillis();
            S27PacketExplosion explosion = (S27PacketExplosion) event.getPacket();
            this.audit.impulses().explosion(explosion.func_149149_c(), explosion.func_149144_d(),
                    explosion.func_149147_e());
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onPacketLast(PacketEvent event) {
        if (!running || event.getType() != EventType.RECEIVE || !(event.getPacket() instanceof S12PacketEntityVelocity)
                || ((S12PacketEntityVelocity) event.getPacket()).getEntityID() != playerId) {
            return;
        }
        S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
        double x = velocity.getMotionX() / 8000.0;
        double y = velocity.getMotionY() / 8000.0;
        double z = velocity.getMotionZ() / 8000.0;
        if (event.isCancelled()) {
            this.heldKnockback.held(x, y, z, knockbackHeldBy, System.currentTimeMillis());
            return;
        }

        this.knockedAt = System.currentTimeMillis();
        this.audit.impulses().velocity(x, y, z);
    }

    @EventTarget(Priority.HIGHEST)
    public void onKnockbackApplied(KnockbackEvent event) {
        if (!running) {
            return;
        }
        HeldKnockback.Held kb = this.heldKnockback.applied(event.getX(), event.getY(), event.getZ());
        if (kb == null) {
            return;
        }
        long now = System.currentTimeMillis();
        this.knockedAt = now;
        knockbackHeldBy = kb.by;
        this.audit.impulses().velocity(kb.x, kb.y, kb.z);
        write(String.format("KB applied late: %.3f %.3f %.3f after %d ms | %s:knockback", kb.x, kb.y, kb.z, now - kb.at,
                kb.by == null ? "?" : kb.by));
    }

    public static void packetHeld(String module, Packet<?> packet) {
        if (running && packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == playerId) {
            knockbackHeldBy = module;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onSend(PacketEvent event) {
        if (!running || event.getType() != EventType.SEND || event.isCancelled()) {
            return;
        }
        if (event.getPacket() instanceof C02PacketUseEntity
                && ((C02PacketUseEntity) event.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
            this.audit.impulses().attack();
            return;
        }
        if (!(event.getPacket() instanceof C0BPacketEntityAction)) {
            return;
        }
        switch (((C0BPacketEntityAction) event.getPacket()).getAction()) {
            case START_SPRINTING:
                this.serverSprint = Boolean.TRUE;
                break;
            case STOP_SPRINTING:
                this.serverSprint = Boolean.FALSE;
                break;
            case START_SNEAKING:
                this.serverSneak = Boolean.TRUE;
                break;
            case STOP_SNEAKING:
                this.serverSneak = Boolean.FALSE;
                break;
            default:
                break;
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onUpdate(UpdateEvent event) {
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (event.getType() == EventType.PRE) {
            this.sampled = true;
            return;
        }
        if (event.getType() == EventType.POST && this.sampled) {
            this.sampled = false;
            finish(event.getYaw());
        }
    }

    private void endOfTick(UpdateEvent event) {
        Map<String, java.util.TreeSet<String>> movers = atlas.util.ActionWatch.takeTickMovers();
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            this.audit.pause();
            return;
        }
        playerId = mc.thePlayer.getEntityId();
        long now = System.currentTimeMillis();
        for (HeldKnockback.Held kb : this.heldKnockback.expired(now)) {
            this.kbOffered++;
            this.kbIgnored++;
            String by = (kb.by == null ? "?" : kb.by) + ":knockback";
            write(String.format("KB dropped: %.3f %.3f %.3f, held %d ms and never applied | %s", kb.x, kb.y, kb.z,
                    now - kb.at, by));
            alert("kb", by, "&ckept no knockback", "");
        }
        boolean sprint = this.serverSprint != null ? this.serverSprint : mc.thePlayer.isSprinting();
        boolean sneak = this.serverSneak != null ? this.serverSneak : mc.thePlayer.isSneaking();
        PlayerSim state = SimPlayer.supported(mc.thePlayer) ? SimPlayer.state(mc.thePlayer) : null;
        MoveAudit.Verdict verdict = this.audit.endOfTick(mc.thePlayer.ticksExisted, state,
                SimPlayer.world(mc.theWorld, mc.thePlayer), event.getYaw(), sprint, sneak,
                now - this.correctedAt < AFTER_CORRECTION_MS);
        this.lookErrors = null;
        if (verdict.result != null) {
            report(verdict, state, changes(movers), event.getYaw(), sprint, sneak, now);
            this.lookErrors = SelfCheck.lookErrors(verdict.from, SimPlayer.world(mc.theWorld, mc.thePlayer),
                    verdict.result, event.getYaw(), mc.thePlayer.rotationYaw, sprint, sneak, state.x(), state.y(),
                    state.z(), verdict.extra);

            boolean jumped = verdict.from.onGround && mc.thePlayer.motionY > 0.2;
            this.lookFlags = (sprint ? "sprint " : "") + (verdict.from.onGround ? "ground " : "air ")
                    + (jumped ? "jump " : "") + (mc.thePlayer.isUsingItem() ? "use " : "");
        }
    }

    static java.util.List<String> movers(String tag) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        StringBuilder name = new StringBuilder();
        for (String part : tag.split(" ")) {
            int colon = part.indexOf(':');
            if (colon < 0) {
                name.append(part).append(' ');
                continue;
            }
            name.append(part, 0, colon);
            out.add(name.toString());
            name.setLength(0);
        }
        return out;
    }

        private static String changes(Map<String, java.util.TreeSet<String>> movers) {
        if (movers.isEmpty()) {
            return "plain";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, java.util.TreeSet<String>> entry : movers.entrySet()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(entry.getKey()).append(':');
            boolean first = true;
            for (String kind : entry.getValue()) {
                out.append(first ? "" : ",").append(kind);
                first = false;
            }
        }
        return out.toString();
    }

    private void report(MoveAudit.Verdict verdict, PlayerSim end, String tag, float sentYaw, boolean sprint,
                        boolean sneak, long now) {
        SelfCheck.Result result = verdict.result;
        if (verdict.knockback == Impulses.Knockback.TAKEN) {
            this.kbOffered++;
        } else if (verdict.knockback == Impulses.Knockback.IGNORED) {
            this.kbOffered++;
            this.kbIgnored++;
            String held = knockbackHeldBy;
            String kbTag = held == null ? tag : tag.equals("plain") ? held + ":knockback" : tag + " " + held + ":knockback";
            write(String.format("KB not taken: %.3f %.3f %.3f over two moves | %s", verdict.velocity[0],
                    verdict.velocity[1], verdict.velocity[2], kbTag));
            alert("kb", kbTag, "&ckept no knockback", "");
        }
        boolean off = result.offset > SelfCheck.OFF;
        boolean plain = tag.equals("plain");
        this.selfTicks++;
        this.selfOff += off ? 1 : 0;
        this.selfWorst = Math.max(this.selfWorst, result.offset);
        if (plain) {
            this.selfPlainTicks++;
            this.selfPlainOff += off ? 1 : 0;
        } else {
            for (String module : movers(tag)) {
                int[] counts = this.selfBy.get(module);
                if (counts == null) {
                    counts = new int[2];
                    this.selfBy.put(module, counts);
                }
                counts[0]++;
                counts[1] += off ? 1 : 0;
            }
        }
        if (!off) {
            return;
        }

        boolean seen = this.moveTick == mc.thePlayer.ticksExisted && !Float.isNaN(this.moveYaw);
        StringBuilder why = new StringBuilder();
        if (seen && Math.abs(MathHelper.wrapAngleTo180_float(this.moveYaw - sentYaw)) > 0.01F) {
            why.append(String.format(" &8(moved by yaw %.1f, sent %.1f)", MathHelper.wrapAngleTo180_float(this.moveYaw),
                    MathHelper.wrapAngleTo180_float(sentYaw)));
        }
        if (seen && this.moveSprinting != sprint) {
            why.append(sprint ? " &8(the server has a sprint the move did not)" : " &8(sprinted, the server not told)");
        }
        if (seen && this.moveSneaking != sneak) {
            why.append(sneak ? " &8(the server has a sneak the move did not)" : " &8(sneaked, the server not told)");
        }
        alert("off", tag, String.format("off &f%.4f &8(keys %s)", result.offset, result.keys()), why.toString());
        long second = now / 1000L;
        if (second != this.selfLineSecond) {
            this.selfLineSecond = second;
            this.selfLinesThisSecond = 0;
        }
        if (this.selfLinesThisSecond++ < MISS_LINES_A_SECOND) {
            write(String.format("SELF off %.4f (x %.4f y %.4f z %.4f) keys %s | yaw %.1f move-yaw %.1f %s%s%s| client %s"
                            + "| speed %.4f air %.4f | %s", result.offset,
                    end.x() - result.end.x(), end.y() - result.end.y(), end.z() - result.end.z(),
                    result.keys(), MathHelper.wrapAngleTo180_float(sentYaw),
                    seen ? MathHelper.wrapAngleTo180_float(this.moveYaw) : Float.NaN,
                    mc.thePlayer.onGround ? "ground " : "air ", sprint ? "sprint " : "", sneak ? "sneak " : "",
                    !seen ? "? " : this.moveSprinting ? "sprint " : "walk ", verdict.from.moveSpeed,
                    verdict.from.airSpeed, tag));
        }
    }

    @EventTarget(Priority.LOWEST)
    public void onLivingUpdate(LivingUpdateEvent event) {
        this.simPredicted = null;
        if (!this.active() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        this.moveYaw = SimPlayer.moveYaw(mc.thePlayer);
        this.moveSprinting = mc.thePlayer.isSprinting();
        this.moveSneaking = mc.thePlayer.isSneaking();
        this.moveTick = mc.thePlayer.ticksExisted;
        if (!SimPlayer.supported(mc.thePlayer)) {
            return;
        }

        SimPlayer.Snapshot now = SimPlayer.snapshot(mc.thePlayer, PlayerSim.Stage.MOVE);
        now.sim.tick(now.world, now.input);
        this.simPredicted = now.sim;
    }

    private void checkSim(long now) {
        PlayerSim predicted = this.simPredicted;
        this.simPredicted = null;
        if (predicted == null || now - this.correctedAt < AFTER_CORRECTION_MS || now - this.knockedAt < AFTER_KNOCKBACK_MS) {
            return;
        }
        double dx = mc.thePlayer.posX - predicted.x();
        double dy = mc.thePlayer.getEntityBoundingBox().minY - predicted.y();
        double dz = mc.thePlayer.posZ - predicted.z();
        double error = Math.sqrt(dx * dx + dy * dy + dz * dz);
        boolean off = error > SIM_OFF;
        boolean plain = tag().equals("plain");
        this.simTicks++;
        this.simOff += off ? 1 : 0;
        this.simWorst = Math.max(this.simWorst, error);
        if (plain) {
            this.simPlainTicks++;
            this.simPlainOff += off ? 1 : 0;
        }
        if (!off) {
            return;
        }
        long second = now / 1000L;
        if (second != this.lineSecond) {
            this.lineSecond = second;
            this.linesThisSecond = 0;
        }
        if (this.linesThisSecond++ < MISS_LINES_A_SECOND) {
            write(String.format("SIM off %.4f (x %.4f y %.4f z %.4f) | %s%s%s| %s", error, dx, dy, dz,
                    mc.thePlayer.onGround ? "ground " : "air ", mc.thePlayer.isSprinting() ? "sprint " : "",
                    mc.thePlayer.isCollidedHorizontally ? "wall " : "", tag()));
        }
    }

    private void finish(float sentYaw) {
        long now = System.currentTimeMillis();
        checkSim(now);
        if (mc.thePlayer.ticksExisted % 20 == 0) {
            logModulesIfChanged();
        }
        float turn = Float.isNaN(this.lastSentYaw) ? 0.0F
                : Math.abs(MathHelper.wrapAngleTo180_float(sentYaw - this.lastSentYaw));
        this.lastSentYaw = sentYaw;
        boolean sprinting = mc.thePlayer.isSprinting();
        if (sprinting != this.lastSprinting) {
            this.sprintChanges++;
            this.lastSprinting = sprinting;
        }
        if (turn >= BIG_TURN) {
            this.bigTurns++;
        }
        double[] errors = this.lookErrors;
        this.lookErrors = null;
        if (errors == null) {
            this.skipped++;
        } else {
            record(now, errors[0], errors[1], sentYaw, turn);
        }
        if (now - this.summaryAt >= LOG_EVERY * 1000L) {
            summary();
            this.summaryAt = now;
        }
    }

    private void record(long now, double lookError, double cameraError, float sentYaw, float turn) {
        float limit = THRESHOLD;
        boolean lookMiss = lookError > limit;
        boolean cameraMiss = cameraError > limit;
        boolean turned = Math.abs(MathHelper.wrapAngleTo180_float(sentYaw - mc.thePlayer.rotationYaw)) >= 1.0F;
        String tag = tag();
        this.ticks++;
        this.lookMisses += lookMiss ? 1 : 0;
        this.cameraMisses += cameraMiss ? 1 : 0;
        int[] counts = this.byTag.get(tag);
        if (counts == null) {
            counts = new int[2];
            this.byTag.put(tag, counts);
        }
        counts[0]++;
        counts[1] += lookMiss ? 1 : 0;
        if (!turned && tag.equals("plain")) {
            this.plainTicks++;
            this.plainMisses += lookMiss ? 1 : 0;
        }
        if (!lookMiss) {
            return;
        }
        long second = now / 1000L;
        if (second != this.lineSecond) {
            this.lineSecond = second;
            this.linesThisSecond = 0;
        }
        if (this.linesThisSecond++ < MISS_LINES_A_SECOND) {
            write(String.format("MISS %s look-err %.3f cam-err %.3f | yaw sent %.1f cam %.1f turn %.1f | %s| %s",
                    cameraMiss ? "SPEED" : "LOOK", lookError, cameraError, MathHelper.wrapAngleTo180_float(sentYaw),
                    MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw), turn,
                    this.lookFlags, tag));
        }
    }

    private String tag() {
        StringBuilder tag = new StringBuilder();
        if (Arbiter.catching()) {
            tag.append("catch ");
        }
        if (on(Scaffold.class)) {
            tag.append("scaffold ");
        }
        if (atlas.util.Auras.target() != null) {
            tag.append("aura ");
        }
        if (on(Hitflick.class)) {
            tag.append("hitflick ");
        }
        return tag.length() == 0 ? "plain" : tag.toString().trim();
    }

    private static boolean on(Class<? extends Module> type) {
        Module module = Atlas.moduleManager == null ? null : Atlas.moduleManager.modules.get(type);
        return module != null && module.isEnabled();
    }

    private void logModulesIfChanged() {
        if (Atlas.moduleManager == null) {
            return;
        }
        StringBuilder on = new StringBuilder();
        for (Module module : names().values()) {
            if (module.isEnabled()) {
                on.append(module.getName()).append(' ');
            }
        }
        String now = on.toString().trim();
        if (!now.equals(this.modulesLogged)) {
            this.modulesLogged = now;
            write("MODULES " + now);
        }
    }

    private static Map<String, Module> names() {
        Map<String, Module> out = new TreeMap<String, Module>();
        for (Module module : Atlas.moduleManager.modules.values()) {
            out.put(module.getName(), module);
        }
        return out;
    }

    private void summary() {
        StringBuilder tags = new StringBuilder();
        for (Map.Entry<String, int[]> entry : this.byTag.entrySet()) {
            tags.append(" | ").append(entry.getKey()).append(' ').append(entry.getValue()[1]).append('/')
                    .append(entry.getValue()[0]);
        }
        StringBuilder by = new StringBuilder();
        for (Map.Entry<String, int[]> entry : this.selfBy.entrySet()) {
            by.append(' ').append(entry.getKey()).append(' ').append(entry.getValue()[1]).append('/')
                    .append(entry.getValue()[0]);
        }
        write(String.format("SUM ticks %d skipped %d look-miss %d cam-miss %d plain-miss %d/%d big-turn %d sprint-change %d%s"
                        + " | sim %d/%d worst %.4f plain %d/%d | self %d/%d worst %.4f plain %d/%d | kb-missed %d/%d | by%s",
                this.ticks, this.skipped, this.lookMisses, this.cameraMisses, this.plainMisses, this.plainTicks,
                this.bigTurns, this.sprintChanges, tags, this.simOff, this.simTicks, this.simWorst, this.simPlainOff,
                this.simPlainTicks, this.selfOff, this.selfTicks, this.selfWorst, this.selfPlainOff,
                this.selfPlainTicks, this.kbIgnored, this.kbOffered, by.length() == 0 ? " -" : by));
        clearCounts();
    }

    private void clearCounts() {
        this.kbOffered = 0;
        this.kbIgnored = 0;
        this.ticks = 0;
        this.skipped = 0;
        this.lookMisses = 0;
        this.cameraMisses = 0;
        this.bigTurns = 0;
        this.sprintChanges = 0;
        this.plainTicks = 0;
        this.plainMisses = 0;
        this.simTicks = 0;
        this.simOff = 0;
        this.simPlainTicks = 0;
        this.simPlainOff = 0;
        this.simWorst = 0.0;
        this.selfTicks = 0;
        this.selfOff = 0;
        this.selfPlainTicks = 0;
        this.selfPlainOff = 0;
        this.selfWorst = 0.0;
        this.selfBy.clear();
        this.byTag.clear();
    }

    private void write(String line) {
        if (this.logFile == null) {
            this.logFile = new File(LOG_DIR, "moves-" + FILE_STAMP.format(new Date()) + ".txt");
        }
        AsyncLog.append(this.logFile, LINE_STAMP.format(new Date()) + " " + line);
    }
}
