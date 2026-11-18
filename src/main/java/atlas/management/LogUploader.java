package atlas.management;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.LoadWorldEvent;
import atlas.events.PacketEvent;
import atlas.events.TickEvent;
import atlas.util.ActionLedger;
import atlas.util.BugReport;
import atlas.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.IChatComponent;

import java.io.File;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;

public final class LogUploader {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final File THEME_FILE = new File("./config/Atlas/atlas-theme.json");
    private static final File NOTICE_FILE = new File("./config/Atlas/auto-send-logs-notice-2.txt");

    public static final String SETTING = "auto-send-logs-v2";

    static final int FLAG_LIMIT = 8;
    static final long FLAG_WINDOW_MS = 60_000L;
    private static final long FLAG_COOLDOWN_MS = 600_000L;
    private static final long KICK_COOLDOWN_MS = 120_000L;
    private static final long JOIN_GRACE_MS = 5_000L;
    private static final double TELEPORT_BLOCKS = 8.0;

    private static volatile Boolean enabled;
    private final Deque<Long> flags = new ArrayDeque<Long>();
    private volatile long worldAt;
    private long lastFlagLog;
    private long lastKickLog;
    private boolean noticeChecked;

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        Boolean value = enabled;
        if (value == null) {
            value = readSetting();
            enabled = value;
        }
        return value;
    }

    private static boolean readSetting() {
        if (!THEME_FILE.isFile()) {
            return false;
        }
        try (Reader reader = new FileReader(THEME_FILE)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                if (object.has(SETTING)) {
                    return object.get(SETTING).getAsBoolean();
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @EventTarget
    public void onWorld(LoadWorldEvent event) {
        this.worldAt = System.currentTimeMillis();
        synchronized (this.flags) {
            this.flags.clear();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.noticeChecked && event.getType() == EventType.PRE && mc.thePlayer != null
                && Atlas.moduleManager != null && isEnabled()) {
            this.noticeChecked = true;
            notice();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (event.getType() != EventType.RECEIVE || !isEnabled()) {
            return;
        }
        Object packet = event.getPacket();
        if (packet instanceof S40PacketDisconnect) {
            kicked("kicked", ((S40PacketDisconnect) packet).getReason());
        } else if (packet instanceof net.minecraft.network.login.server.S00PacketDisconnect) {
            kicked("kicked at login", ((net.minecraft.network.login.server.S00PacketDisconnect) packet).func_149603_c());
        } else if (packet instanceof S08PacketPlayerPosLook) {
            correction((S08PacketPlayerPosLook) packet);
        }
    }

    private void correction(S08PacketPlayerPosLook packet) {
        long now = System.currentTimeMillis();
        if (mc.thePlayer == null || now - this.worldAt < JOIN_GRACE_MS || !packet.func_179834_f().isEmpty()) {
            return;
        }
        double dx = packet.getX() - mc.thePlayer.posX;
        double dy = packet.getY() - mc.thePlayer.posY;
        double dz = packet.getZ() - mc.thePlayer.posZ;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > TELEPORT_BLOCKS) {
            return;
        }
        int count;
        synchronized (this.flags) {
            this.flags.addLast(now);
            while (!this.flags.isEmpty() && now - this.flags.peekFirst() > FLAG_WINDOW_MS) {
                this.flags.removeFirst();
            }
            count = this.flags.size();
        }
        if (count < FLAG_LIMIT || now - this.lastFlagLog < FLAG_COOLDOWN_MS) {
            return;
        }
        this.lastFlagLog = now;
        synchronized (this.flags) {
            this.flags.clear();
        }
        send("flagged " + count + " times in " + (FLAG_WINDOW_MS / 1000L) + "s");
    }

    private void kicked(String what, IChatComponent reason) {
        long now = System.currentTimeMillis();
        if (now - this.lastKickLog < KICK_COOLDOWN_MS) {
            return;
        }
        this.lastKickLog = now;
        String text = reason == null ? "" : reason.getUnformattedText().replaceAll("\\s+", " ").trim();
        send(what + (text.isEmpty() ? "" : ": " + text));
    }

    private static void send(String trigger) {
        String suspects = ActionLedger.summary(FLAG_WINDOW_MS, 3);
        String full = suspects.isEmpty() ? trigger : trigger + " | most active: " + suspects;
        mc.addScheduledTask(() -> BugReport.sendAuto(full));
    }

    private static void notice() {
        if (NOTICE_FILE.exists()) {
            return;
        }
        try {
            ChatUtil.sendFormatted(Atlas.clientName + "&7When a server kicks you, or you get flagged many times in a"
                    + " minute, Atlas sends its log and config to the developers to help fix it (your name,"
                    + " other players' names, tokens and IPs are removed). Turn it off in ClickGUI > Client Settings"
                    + " > Appearance > Privacy > auto-send-logs.");
            File dir = NOTICE_FILE.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            java.nio.file.Files.write(NOTICE_FILE.toPath(), "shown\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
