package atlas.util.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.settings.GameSettings;

import java.lang.reflect.Field;

public final class FramebufferCompat {
    private static Field fastRender;
    private static boolean looked;

    private FramebufferCompat() {
    }

    public static boolean available() {
        return !MobileSafe.active()
                && OpenGlHelper.shadersSupported && OpenGlHelper.isFramebufferEnabled() && !optifineFastRender();
    }

    public static boolean optifineFastRender() {
        if (!looked) {
            looked = true;
            try {
                fastRender = GameSettings.class.getDeclaredField("ofFastRender");
                fastRender.setAccessible(true);
            } catch (Throwable ignored) {
                fastRender = null;
            }
        }
        if (fastRender == null) {
            return false;
        }
        try {
            GameSettings settings = Minecraft.getMinecraft().gameSettings;
            return settings != null && fastRender.getBoolean(settings);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
