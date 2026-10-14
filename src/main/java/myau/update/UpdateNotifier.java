package myau.update;

import me.ksyz.accountmanager.utils.Nan0EventRegister;
import myau.Myau;
import myau.module.ModuleDocs;
import myau.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shows Updater messages in chat. The check runs at startup, before any
 * world is loaded and there is no chat to write to, so messages wait here
 * until the player is in a world. Chinese or English follows the menu
 * language (Client Settings).
 */
public final class UpdateNotifier implements Updater.Listener {

    private final ConcurrentLinkedQueue<String[]> queue = new ConcurrentLinkedQueue<String[]>();

    public static UpdateNotifier install() {
        UpdateNotifier n = new UpdateNotifier();
        Nan0EventRegister.register(MinecraftForge.EVENT_BUS, n);
        return n;
    }

    @Override
    public void message(String zh, String en) {
        queue.add(new String[]{zh, en});
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || queue.isEmpty() || Minecraft.getMinecraft().thePlayer == null) {
            return;
        }
        String[] m;
        while ((m = queue.poll()) != null) {
            ChatUtil.sendFormatted(Myau.clientName + (english() ? m[1] : m[0]));
        }
    }

    /**
     * The menu language. ModuleDocs only learns it once the ClickGUI has been
     * drawn, which has usually not happened yet when the startup check
     * reports, so the saved menu settings are read as well.
     */
    public static boolean english() {
        if (ModuleDocs.isEnglish()) {
            return true;
        }
        java.io.File file = new java.io.File("./config/Atlas/atlas-theme.json");
        if (!file.isFile()) {
            return false;
        }
        java.io.Reader reader = null;
        try {
            reader = new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonElement json = new com.google.gson.JsonParser().parse(reader);
            return json != null && json.isJsonObject() && json.getAsJsonObject().has("language")
                    && "English".equals(json.getAsJsonObject().get("language").getAsString());
        } catch (Exception e) {
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }
}
