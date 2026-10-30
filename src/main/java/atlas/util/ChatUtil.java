package atlas.util;

import atlas.enums.ChatColors;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public class ChatUtil {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static void send(IChatComponent iChatComponent) {
        /* 2026-10-30 (audit): eleven packet handlers write chat from the network thread, and the chat's line list
           is drawn on the client thread at the same time (ConcurrentModificationException in the render). Off the
           client thread the line is queued for it instead. */
        if (!ChatUtil.mc.isCallingFromMinecraftThread()) {
            ChatUtil.mc.addScheduledTask(() -> send(iChatComponent));
            return;
        }
        if (ChatUtil.mc.thePlayer != null) {
            ChatUtil.mc.thePlayer.addChatMessage(iChatComponent);
        }
    }

    public static void sendFormatted(String string) {
        ChatUtil.send(new ChatComponentText(ChatColors.formatColor(string)));
    }

    public static void sendRaw(String string) {
        ChatUtil.send(new ChatComponentText(string));
    }

    public static void sendMessage(String string) {
        if (ChatUtil.mc.thePlayer != null) {
            ChatUtil.mc.thePlayer.sendChatMessage(string);
        }
    }
}
