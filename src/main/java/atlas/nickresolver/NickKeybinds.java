package atlas.nickresolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public final class NickKeybinds {
    private final NickSettings settings;
    private final KeyBinding toggle = new KeyBinding("切換真名顯示", Keyboard.KEY_NONE, "暱稱解析");

    public NickKeybinds(NickSettings settings) {
        this.settings = settings;
    }

    public void register() {
        ClientRegistry.registerKeyBinding(this.toggle);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        while (this.toggle.isPressed()) {
            boolean show = !(this.settings.showNametag || this.settings.showChat);
            this.settings.setToggle("nametag", show);
            this.settings.setToggle("chat", show);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        NickCommand.PREFIX + (show ? "§a已顯示真名" : "§c已隱藏真名")));
            }
        }
    }
}
