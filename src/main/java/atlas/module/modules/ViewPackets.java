package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0), which adapted the MiauMinus misc/ViewPackets
// direction toggles down to a name filter.
import java.util.Locale;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.TextProperty;
import atlas.util.ChatUtil;

public class ViewPackets extends Module {
    private static final int MAX_LOG = 20;
    public final BooleanProperty inbound = new BooleanProperty("inbound", true);
    public final BooleanProperty outbound = new BooleanProperty("outbound", true);
    public final TextProperty filter = new TextProperty("filter", "");

    private int logged;

    public ViewPackets() {
        super("ViewPackets", false, false, "Logs incoming and outgoing packets to chat.");
    }

    @Override
    public void onEnabled() {
        this.logged = 0;
    }

    @Override
    public void onDisabled() {
        this.logged = 0;
    }

    @Override
    public String[] getSuffix() {
        return new String[]{String.valueOf(this.logged)};
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        boolean sent = event.getType() == EventType.SEND;
        if (sent && !this.outbound.getValue()) {
            return;
        }
        if (!sent && !this.inbound.getValue()) {
            return;
        }
        if (this.logged >= MAX_LOG) {
            return;
        }
        String name = event.getPacket().getClass().getSimpleName();
        String wanted = this.filter.getValue() == null ? "" : this.filter.getValue().trim().toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(wanted)) {
            return;
        }
        ChatUtil.sendFormatted(String.format("%s%s: &7%s &b%s&r", Atlas.clientName, this.getName(), sent ? "S" : "R", name));
        this.logged++;
    }
}
