package atlas.module.modules;

import io.netty.buffer.Unpooled;
import atlas.event.EventTarget;
import atlas.events.PacketEvent;
import atlas.mixin.IAccessorC17PacketCustomPayload;
import atlas.module.Module;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.TextProperty;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;

public class ClientSpoofer extends Module {
    private static final String BRAND_CHANNEL = "MC|Brand";
    private static final String CUSTOM_MODE = "Custom";
    private static final String[] MODES = new String[]{
            "Vanilla", "OptiFine", "Fabric", "Feather", "LunarClient",
            "LabyMod", "CheatBreaker", "PvPLounge", "Minebuilders", "FML",
            "Geyser", "Log4j", "FDP", "Atlas", CUSTOM_MODE
    };
    private static final String[] BRAND_VALUES = new String[]{
            "vanilla", "optifine", "fabric", "Feather Forge", "lunarclient",
            "LMC", "CB", "PLC18", "minebuilders", "fml,forge",
            "Geyser", "${jndi:ldap://127.0.0.1/a}", "FDPClient", "Atlas", ""
    };

    public final ModeProperty mode = new ModeProperty("mode", 0, MODES);
    public final TextProperty customBrand = new TextProperty("custom-brand", "Atlas", this::isCustomMode);

    public ClientSpoofer() {
        super("ClientSpoofer", false);
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!isEnabled() || !(event.getPacket() instanceof C17PacketCustomPayload)) return;

        C17PacketCustomPayload packet = (C17PacketCustomPayload) event.getPacket();
        if (BRAND_CHANNEL.equals(packet.getChannelName())) {
            ((IAccessorC17PacketCustomPayload) packet).setData(createBrandBuffer(getBrand()));
        }
    }

    private PacketBuffer createBrandBuffer(String brand) {
        return new PacketBuffer(Unpooled.buffer()).writeString(brand);
    }

    private String getBrand() {
        if (isCustomMode()) {
            return customBrand.getValue();
        }
        int index = mode.getValue();
        return index >= 0 && index < BRAND_VALUES.length ? BRAND_VALUES[index] : BRAND_VALUES[0];
    }

    private boolean isCustomMode() {
        return CUSTOM_MODE.equalsIgnoreCase(mode.getModeString());
    }

    @Override
    public String[] getSuffix() {
        return new String[]{mode.getModeString()};
    }

    /* Merged modules (2026-10-11, ModuleBundle). */
    public final atlas.module.ModuleBundle modSpooferMerged = atlas.module.ModuleBundle.withSwitch(this, new atlas.module.modules.ModSpoofer(), "mod-spoofer", "Mod list spoof", false);
}