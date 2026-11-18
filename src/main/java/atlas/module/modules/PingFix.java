package atlas.module.modules;

import atlas.module.Module;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.server.S00PacketKeepAlive;

public class PingFix extends Module {
    private static volatile boolean on;

    public PingFix() {
        super("PingFix", false);
    }

    public static boolean exempt(Packet<?> packet) {
        return on && (packet instanceof C00PacketKeepAlive || packet instanceof S00PacketKeepAlive);
    }

    @Override
    public void onEnabled() {
        on = true;
    }

    @Override
    public void onDisabled() {
        on = false;
    }
}
