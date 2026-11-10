package atlas.module.modules;

// Ported from OpenSkid (GPL-3.0): spoofs the client brand on outgoing
// MC|Brand payloads and can drop FML/FORGE mod channels.
import io.netty.buffer.Unpooled;

import atlas.event.EventTarget;
import atlas.event.types.EventType;
import atlas.events.PacketEvent;
import atlas.mixin.IAccessorC17PacketCustomPayload;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.TextProperty;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;

public class ModSpoofer extends Module {
    private static final String BRAND_CHANNEL = "MC|Brand";

    /* 2026-11-10: merged into ClientSpoofer, both rewrote MC|Brand and whichever ran last won (Vanilla there, Forge
       here: "fml,forge" could go out). ClientSpoofer owns the brand while it is on; these two only apply without it. */
    public final ModeProperty mode = new ModeProperty("mode", 0, new String[]{"Forge", "Vanilla", "Custom"},
            () -> !clientSpooferOn());
    public final TextProperty customBrand = new TextProperty("custom-brand", "fml,forge",
            () -> !clientSpooferOn() && mode.getValue() == 2);
    public final BooleanProperty hideModChannels = new BooleanProperty("hide-mod-channels", false);

    public ModSpoofer() {
        super("ModSpoofer", false, false, "Spoofs your client brand and hides mod channels.");
    }

    @Override
    public void onEnabled() {
    }

    @Override
    public void onDisabled() {
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || event.getType() != EventType.SEND) return;
        if (!(event.getPacket() instanceof C17PacketCustomPayload)) return;

        C17PacketCustomPayload packet = (C17PacketCustomPayload) event.getPacket();
        String channel = packet.getChannelName();
        if (channel == null) return;

        if (BRAND_CHANNEL.equals(channel)) {
            if (clientSpooferOn()) {
                return;
            }
            ((IAccessorC17PacketCustomPayload) packet).setData(createBrandBuffer(getBrand()));
        } else if (hideModChannels.getValue() && isModChannel(channel)) {
            event.setCancelled(true);
        }
    }

    private static boolean clientSpooferOn() {
        if (atlas.Atlas.moduleManager == null) {
            return false;
        }
        atlas.module.Module host = atlas.Atlas.moduleManager.modules.get(ClientSpoofer.class);
        return host != null && host.isEnabled();
    }

    private boolean isModChannel(String channel) {
        return channel.startsWith("FML") || channel.startsWith("FORGE");
    }

    private PacketBuffer createBrandBuffer(String brand) {
        return new PacketBuffer(Unpooled.buffer()).writeString(brand);
    }

    private String getBrand() {
        if (mode.getValue() == 2) {
            String custom = customBrand.getValue();
            return custom == null || custom.isEmpty() ? "vanilla" : custom;
        }
        return mode.getValue() == 1 ? "vanilla" : "fml,forge";
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }
}
