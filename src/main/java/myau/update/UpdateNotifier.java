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
            ChatUtil.sendFormatted(Myau.clientName + (ModuleDocs.isEnglish() ? m[1] : m[0]));
        }
    }
}
