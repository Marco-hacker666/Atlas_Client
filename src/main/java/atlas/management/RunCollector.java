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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Anonymous movement data for SpeedBlocker's prediction (2026-11-01, owner: "let every player give us their data,
 * with a click to agree in game, for the SpeedBlocker AI model").
 *
 * Asked once, in chat, the first time the player is in a world: [Agree] / [No]. Changed any time in Client
 * Settings > Privacy (share-run-data) or with .rundata on|off. Nothing is recorded or sent without "on".
 *
 * With it on, every moving player within RANGE is predicted and later checked against where it went (RunRecorder,
 * velocity and position by RunTracker), whether SpeedBlocker is on or not. A row is only numbers: the horizon,
 * distance, speed, approach, key offset, chasing, ground, sprint and the errors (the model's, the straight line's
 * and, since 2026-11-13, the movement simulation's). No name -- the player column is
 * "e" plus the entity id, which means nothing outside that session -- no server, no coordinates, no account. The
 * relay sees the sender's IP, as any website does, and uses it only for its hourly limit.
 *
 * 2026-11-02 (review): the rows of a window of WINDOW_MS are a uniform sample of it (a reservoir of CAPACITY):
 * a full buffer used to drop everything after its first minute or two, the busiest fights first. Each window is
 * sent once it has passed (at least MIN_ROWS). A send that fails keeps its rows for the next one (one batch, then
 * they go); rows not sent when the game closes are saved (config/Atlas/run-data-pending.csv) and sent next time;
 * switching it off drops them. Asked only once: an unanswered question is not asked again at every launch.
 */
public final class RunCollector {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File CONSENT_FILE = new File("./config/Atlas/run-data.txt");
    private static final File PENDING_FILE = new File("./config/Atlas/run-data-pending.csv");
    static final double RANGE = 12.0;
    /** Rows kept per window: a uniform sample of everything recorded in it. */
    static final int CAPACITY = 3000;
    static final int MIN_ROWS = 300;
    static final long WINDOW_MS = 10L * 60L * 1000L;

    /** "on" / "off" / "asked" (shown, not answered) / null (never shown). */
    private static volatile String state;
    private static boolean stateRead;

    private final RunTracker tracker = new RunTracker();
    private final Random random = new Random();
    /** This window's sample, and how many rows the window produced. */
    private final List<String> sample = new ArrayList<String>();
    private long seen;
    /** A batch whose send failed (or was left at the last exit), sent along with the next window. */
    private volatile List<String> retry = new ArrayList<String>();
    private final RunRecorder recorder = new RunRecorder(this::offer);
    private int tick;
    private boolean askedThisSession;
    private long windowStart = System.currentTimeMillis();

    public RunCollector() {
        this.retry = loadPending();
        Runtime.getRuntime().addShutdownHook(new Thread(this::savePending, "Atlas run data"));
    }

    // ---- the choice ----------------------------------------------------

    private static synchronized String state() {
        if (!stateRead) {
            stateRead = true;
            try {
                if (CONSENT_FILE.isFile()) {
                    state = new String(Files.readAllBytes(CONSENT_FILE.toPath()), StandardCharsets.UTF_8).trim()
                            .toLowerCase();
                }
            } catch (Exception ignored) {
                state = null;
            }
        }
        return state;
    }

    /** TRUE on, FALSE off, null when the player has not chosen. */
    public static Boolean consent() {
        String s = state();
        return "on".equals(s) ? Boolean.TRUE : "off".equals(s) ? Boolean.FALSE : null;
    }

    public static void setConsent(boolean on) {
        write(on ? "on" : "off");
        if (!on) {
            PENDING_FILE.delete();
        }
    }

    private static synchronized void write(String value) {
        state = value;
        stateRead = true;
        try {
            File dir = CONSENT_FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            Files.write(CONSENT_FILE.toPath(), (value + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Atlas] Could not save the run-data choice: " + e);
        }
    }

    private static boolean on() {
        return Boolean.TRUE.equals(consent());
    }

    // ---- recording -------------------------------------------------------

    /** A row for this window's sample: kept outright until it is full, then by reservoir sampling. */
    private void offer(String row) {
        this.seen++;
        if (this.sample.size() < CAPACITY) {
            this.sample.add(row);
            return;
        }
        long slot = (long) (this.random.nextDouble() * this.seen);
        if (slot < CAPACITY) {
            this.sample.set((int) slot, row);
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (event.getType() != EventType.PRE || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.askedThisSession) {
            this.askedThisSession = true;
            if (state() == null) {
                ask();
                write("asked");
            }
        }
        if (!on()) {
            if (!this.sample.isEmpty() || !this.retry.isEmpty()) {
                this.sample.clear();
                this.retry = new ArrayList<String>();
                this.seen = 0;
            }
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
            double[] now = this.tracker.now(this.tick, id, x, z);
            boolean sprinting = player.isSprinting()
                    || motion != null && Math.hypot(motion[0], motion[1]) >= 0.25;
            this.recorder.observe(this.tick, id, "e" + id, now[0], now[1], x, z, this.tracker.fresh(this.tick, id),
                    motion == null ? Double.NaN : motion[0], motion == null ? Double.NaN : motion[1],
                    player.rotationYawHead, mc.thePlayer.posX, mc.thePlayer.posZ, player.onGround, sprinting,
                    this.recorder.samples(this.tick)
                            ? atlas.sim.SimTargets.path(player, RunRecorder.LENGTH) : null);
        }
        this.tracker.retain(alive);
        if (System.currentTimeMillis() - this.windowStart >= WINDOW_MS) {
            this.endWindow("window");
        }
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.tracker.clear();
        this.recorder.clearOpen();
    }

    /** The window is over: its sample (and any batch waiting for a retry) goes, if there is enough of it. */
    private void endWindow(String why) {
        this.windowStart = System.currentTimeMillis();
        final List<String> fresh = new ArrayList<String>(this.sample);
        List<String> batch = new ArrayList<String>(this.retry);
        batch.addAll(fresh);
        long windowRows = this.seen;
        this.sample.clear();
        this.seen = 0;
        this.retry = new ArrayList<String>();
        if (batch.size() < MIN_ROWS) {
            /* Too little to be worth a post: kept for the next window. */
            this.retry = batch;
            return;
        }
        String version = Atlas.version == null ? "dev" : Atlas.version;
        StringBuilder body = new StringBuilder("Atlas runs v1\nversion=" + version + "\nrows=" + batch.size()
                + "\nwindowRows=" + windowRows + "\n" + RunRecorder.HEADER + "\n");
        for (String row : batch) {
            body.append(row).append('\n');
        }
        String title = "[" + version + "] runs " + batch.size();
        String summary = "SpeedBlocker run data (" + why + ", " + batch.size() + " of " + windowRows + " rows): "
                + this.recorder.summary();
        atlas.util.BugReport.sendRuns(title, summary, body.toString(), ok -> {
            if (!ok && on()) {
                /* This window's rows get one more try with the next window; rows that were already a retry go. */
                List<String> keep = new ArrayList<String>(fresh);
                keep.addAll(this.retry);
                this.retry = keep.size() > CAPACITY * 2 ? new ArrayList<String>(keep.subList(0, CAPACITY * 2)) : keep;
            }
        });
    }

    // ---- across launches -------------------------------------------------

    private void savePending() {
        try {
            if (!on()) {
                PENDING_FILE.delete();
                return;
            }
            List<String> rows = new ArrayList<String>(this.retry);
            rows.addAll(this.sample);
            if (rows.isEmpty()) {
                PENDING_FILE.delete();
                return;
            }
            Files.write(PENDING_FILE.toPath(), String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Only data for training; nothing to lose sleep over.
        }
    }

    private static List<String> loadPending() {
        try {
            if (PENDING_FILE.isFile()) {
                List<String> rows = new ArrayList<String>(Arrays.asList(
                        new String(Files.readAllBytes(PENDING_FILE.toPath()), StandardCharsets.UTF_8).split("\n")));
                rows.removeIf(String::isEmpty);
                PENDING_FILE.delete();
                return rows.size() > CAPACITY ? new ArrayList<String>(rows.subList(0, CAPACITY)) : rows;
            }
        } catch (Exception ignored) {
            // Unreadable: start empty.
        }
        return new ArrayList<String>();
    }

    // ---- the question --------------------------------------------------

    /** The question, once: what is sent and what is not, and two buttons. */
    private static void ask() {
        IChatComponent line = new ChatComponentText("");
        line.appendSibling(text("[Atlas] ", EnumChatFormatting.AQUA));
        line.appendSibling(text("Help train SpeedBlocker's prediction? Atlas would send anonymous movement numbers "
                + "(distance, speed, direction keys, how far the prediction was off) of players near you. "
                + "No names, no server, no coordinates, no account. The relay sees your IP, as any website does, and "
                + "uses it only to limit how often data is sent. ", EnumChatFormatting.GRAY));
        line.appendSibling(button("[Agree]", ".rundata on", EnumChatFormatting.GREEN,
                "Send anonymous movement data. Turn off any time: Client Settings > Privacy, or .rundata off"));
        line.appendSibling(text(" ", EnumChatFormatting.GRAY));
        line.appendSibling(button("[No]", ".rundata off", EnumChatFormatting.RED,
                "Send nothing. You will not be asked again."));
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
