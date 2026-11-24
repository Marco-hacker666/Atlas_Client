package atlas.nickresolver;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatAnnotator {
    private static final Pattern NAME_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])");

    private final IdentityResolver resolver;
    private final NickSettings settings;

    public ChatAnnotator(IdentityResolver resolver, NickSettings settings) {
        this.resolver = resolver;
        this.settings = settings;
    }

    @SubscribeEvent(priority = EventPriority.LOW, receiveCanceled = false)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || event.message == null || !this.settings.enabled || !this.settings.showChat) {
            return;
        }
        String plain = IdentityResolver.strip(event.message.getUnformattedText());
        if (plain == null || plain.isEmpty()) {
            return;
        }
        Matcher m = NAME_TOKEN.matcher(plain);
        int checked = 0;
        while (m.find() && checked++ < 24) {
            ResolvedIdentity identity = this.resolver.forShownName(m.group());
            String tag = IdentityLabels.tag(identity, this.settings);
            if (tag == null) {
                continue;
            }
            IChatComponent prefix = new ChatComponentText(tag + " ");
            prefix.setChatStyle(new ChatStyle().setChatHoverEvent(
                    new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(IdentityLabels.details(identity)))));
            IChatComponent root = new ChatComponentText("");
            root.appendSibling(prefix);
            root.appendSibling(event.message);
            event.message = root;
            return;
        }
    }
}
