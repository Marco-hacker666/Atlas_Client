package atlas.management;

import atlas.Atlas;
import atlas.combat.RunRecorder;
import atlas.combat.RunTracker;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.TickEvent;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Anonymous movement data for SpeedBlocker's prediction (2026-11-01, owner: "let every player give us their data,
 * with a click to agree in game, for the SpeedBlocker AI model").
 *
 * Asked once, in chat, the first time the player is in a world: [Agree] / [No]; changed any time with
 * .rundata on|off (status shows it). Nothing is recorded or sent without "on".
 *
 * With it on, every moving player within RANGE is predicted and later checked against where it went (RunRecorder,
 * velocity by RunTracker), whether SpeedBlocker is on or not. A row is only numbers: the horizon, distance, speed,
 * approach, key offset, chasing, ground, sprint and both errors. No name -- the player column is "e" plus the
 * entity id, which means nothing outside that session -- no server, no coordinates, no account. Rows are sent in
 * batches through the report relay (kind "runs", its own Discord channel): UPLOAD_ROWS at a time, or what there is
 * (at least MIN_ROWS) on leaving a world, never more often than UPLOAD_GAP_MS.
 */
public final class RunCollector {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File CONSENT_FILE = new File("./config/Atlas/run-data.txt");
    static final double RANGE = 12.0;
    static final int UPLOAD_ROWS = 3000;
    static final int MIN_ROWS = 300;
    /** Kept in memory at most; older rows are dropped if uploads keep failing. */
    static final int MAX_BUFFER = 6000;
    static final long UPLOAD_GAP_MS = 10L * 60L * 1000L;

    /** null: not asked yet. */
    private static volatile Boolean consent;
    private static boolean consentRead;

    private final RunTracker tracker = new RunTracker();
    private final StringBuilder rows = new StringBuilder();
    private int rowCount;
    private final RunRecorder recorder = new RunRecorder(line -> {
        if (this.rowCount >= MAX_BUFFER) {
            return;
        }
        this.rows.append(line).append('\n');
        this.rowCount++;
    });
    private int tick;
    private boolean askedThisSession;
    private long lastUpload;

    public static synchronized Boolean consent() {
        if (!consentRead) {
            consentRead = true;
            try {
                if (CONSENT_FILE.isFile()) {
                    String value = new String(Files.readAllBytes(CONSENT_FILE.toPath()), StandardCharsets.UTF_8).trim();
                    consent = "on".equalsIgnoreCase(value) ? Boolean.TRUE : "off".equalsIgnoreCase(value) ? Boolean.FALSE : null;
                }
            } catch (Exception ignored) {
                consent = null;
            }
        }
        return consent;
    }

    public static synchronized void setConsent(boolean on) {
        consent = on;
        consentRead = true;
        try {
            File dir = CONSENT_FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.write(CONSENT_FILE.toPath(), (on ? "on\n" : "off\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Atlas] Could not save the run-data choice: " + e);
        }
    }

    private static boolean on() {
        return Boolean.TRUE.equals(consent());
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.askedThisSession) {
            this.askedThisSession = true;
            if (consent() == null) {
                ask();
            }
        }
        if (!on()) {
            return;
        }
        this.tick++;
        this.recorder.prune(this.tick);
        Set<Integer> alive = new HashSet<Integer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            double x = player.serverPosX / 32.0;
            double z = player.serverPosZ / 32.0;
            double dx = x - mc.thePlayer.posX;
            double dz = z - mc.thePlayer.posZ;
            if (dx * dx + dz * dz > RANGE * RANGE) {
                continue;
            }
            int id = player.getEntityId();
            alive.add(id);
            this.tracker.observe(this.tick, id, x, z);
            double[] motion = this.tracker.velocity(id);
            boolean sprinting = player.isSprinting()
                    || motion != null && Math.hypot(motion[0], motion[1]) >= 0.25;
            this.recorder.observe(this.tick, id, "e" + id, x, z,
                    motion == null ? Double.NaN : motion[0], motion == null ? Double.NaN : motion[1],
                    player.rotationYawHead, mc.thePlayer.posX, mc.thePlayer.posZ, player.onGround, sprinting);
        }
        this.tracker.retain(alive);
        if (this.rowCount >= UPLOAD_ROWS) {
            this.upload("batch");
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        if (on() && this.rowCount >= MIN_ROWS) {
            this.upload("world change");
        }
        this.tracker.clear();
        this.recorder.clearOpen();
    }

    /** Sends what is buffered, at most once every UPLOAD_GAP_MS. */
    private void upload(String why) {
        long now = System.currentTimeMillis();
        if (now - this.lastUpload < UPLOAD_GAP_MS) {
            return;
        }
        this.lastUpload = now;
        String version = Atlas.version == null ? "dev" : Atlas.version;
        String body = "Atlas runs v1\nversion=" + version + "\nrows=" + this.rowCount + "\n" + RunRecorder.HEADER + "\n"
                + this.rows;
        String title = "[" + version + "] runs " + this.rowCount;
        String summary = "SpeedBlocker run data (" + why + "): " + this.recorder.summary();
        atlas.util.BugReport.sendRuns(title, summary, body);
        this.rows.setLength(0);
        this.rowCount = 0;
    }

    /** The question, once: what is sent and what is not, and two buttons. */
    private static void ask() {
        IChatComponent line = new ChatComponentText("");
        line.appendSibling(text("[Atlas] ", EnumChatFormatting.AQUA));
        line.appendSibling(text("Help train SpeedBlocker's prediction? Atlas would send anonymous movement numbers "
                + "(distance, speed, direction keys, how far the prediction was off) of players near you. "
                + "No names, no server, no coordinates, no account. ", EnumChatFormatting.GRAY));
        line.appendSibling(button("[Agree]", ".rundata on", EnumChatFormatting.GREEN,
                "Send anonymous movement data. Turn off any time: .rundata off"));
        line.appendSibling(text(" ", EnumChatFormatting.GRAY));
        line.appendSibling(button("[No]", ".rundata off", EnumChatFormatting.RED, "Send nothing."));
        ChatUtil.send(line);
    }

    private static IChatComponent text(String s, EnumChatFormatting color) {
        IChatComponent c = new ChatComponentText(s);
        c.setChatStyle(new ChatStyle().setColor(color));
        return c;
    }

    private static IChatComponent button(String label, String command, EnumChatFormatting color, String hover) {
        IChatComponent c = new ChatComponentText(label);
        ClickEvent click = atlas.command.CommandManager.ownClick(command);
        c.setChatStyle(new ChatStyle().setColor(color).setBold(true).setChatClickEvent(click)
                .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hover))));
        return c;
    }
}
