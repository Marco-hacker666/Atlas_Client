package atlas.script.raven.model;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;

public class Message {
    public ChatComponentText component;

    public Message(String message) {
        this.component = new ChatComponentText(message == null ? "" : message);
    }

    public void appendStyle(String style, String action, String styleMessage, String message) {
        ChatStyle chatStyle = new ChatStyle();
        if ("HOVER".equalsIgnoreCase(style)) {
            HoverEvent.Action hover = enumOf(HoverEvent.Action.class, action, HoverEvent.Action.SHOW_TEXT);
            chatStyle.setChatHoverEvent(new HoverEvent(hover, new ChatComponentText(styleMessage)));
        } else if ("CLICK".equalsIgnoreCase(style)) {
            ClickEvent.Action click = enumOf(ClickEvent.Action.class, action, ClickEvent.Action.RUN_COMMAND);
            chatStyle.setChatClickEvent(new ClickEvent(click, styleMessage));
        }
        ChatComponentText part = new ChatComponentText(message == null ? "" : message);
        part.setChatStyle(chatStyle);
        this.component.appendSibling(part);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return fallback;
        }
    }

    public void append(String append) {
        this.component.appendSibling(new ChatComponentText(append == null ? "" : append));
    }

    public List<Message> getSiblings() {
        List<Message> out = new ArrayList<Message>();
        for (IChatComponent sibling : this.component.getSiblings()) {
            out.add(new Message(sibling.getUnformattedTextForChat()));
        }
        return out;
    }

    public String getStyle() {
        return this.component.getChatStyle().toString();
    }

    public String getText() {
        return this.component.getUnformattedTextForChat();
    }

    @Override
    public String toString() {
        return this.component.toString();
    }
}
