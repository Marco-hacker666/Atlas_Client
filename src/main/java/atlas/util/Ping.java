package atlas.util;

import atlas.management.HitTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

public final class Ping {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int TAB_FLOOR = 2;

    private Ping() {
    }

    public static int own() {
        int tab = tab();
        if (tab >= TAB_FLOOR) {
            return tab;
        }
        HitTimer timer = HitTimer.get();
        long measured = timer == null ? -1L : timer.measured();
        return measured > 0L ? (int) measured : -1;
    }

    public static int tab() {
        try {
            NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
            return info != null ? info.getResponseTime() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
