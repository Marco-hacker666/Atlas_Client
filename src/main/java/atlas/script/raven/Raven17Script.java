package atlas.script.raven;

import atlas.Atlas;
import atlas.mixin.IAccessorMinecraft;
import atlas.mixin.IAccessorTimer;
import atlas.module.Module;
import atlas.property.Property;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.script.ScriptManager;
import atlas.script.ScriptSettings;
import atlas.script.raven.model.Entity;
import atlas.script.raven.model.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The base of a Raven bS 17 script (2026-11-03): bS 16's API (RavenScript) and what bS 17 adds -- 71 methods by
 * the two versions' sources side by side: nanoTime, clickBlock, swingReset, setTabName, client-only mobs, titles,
 * the slider's bounds and option name, texture and colour calls with ints or doubles, beams, the hovered slot ...
 * Each API class extends bS 16's, so a bS 17 script reaches both through the same name (gl.color(255, 0, 0, 60)
 * takes the int one, as bS 17 does). Written for Atlas; Raven's names and behaviour are matched, not its code.
 */
public class Raven17Script extends RavenScript {
    private static final Minecraft mc = Minecraft.getMinecraft();

    public static class client extends RavenScript.client {
        public static long nanoTime() {
            return System.nanoTime();
        }

        /** Starts breaking the block, as a left click on that face does. */
        public static void clickBlock(Vec3 blockpos, String side) {
            mc.playerController.clickBlock(Vec3.getBlockPos(blockpos), facing(side));
        }

        /**
         * Raven draws the held item as in use (its block animation) with this; Atlas's own animations draw
         * blocking themselves, so there is nothing to switch here.
         */
        public static void setItemInUse(boolean inUse) {
        }

        public static void setRidingEntity(Entity entity) {
            mc.thePlayer.ridingEntity = entity == null ? null : entity.entity;
        }

        /** The name shown for a player in the tab list (here only). */
        public static void setTabName(String name, String displayName) {
            if (name == null || displayName == null || mc.getNetHandler() == null) {
                return;
            }
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info.getGameProfile() != null && name.equalsIgnoreCase(info.getGameProfile().getName())) {
                    info.setDisplayName(new ChatComponentText(displayName));
                    return;
                }
            }
        }

        public static void setTimer(double timer) {
            ((IAccessorTimer) ((IAccessorMinecraft) mc).getTimer()).setTimerSpeed((float) timer);
        }

        /** A swing that also restarts the held item's equip animation. */
        public static void swingReset() {
            mc.thePlayer.swingItem();
            mc.getItemRenderer().resetEquippedProgress();
        }
    }

    public static class world extends RavenScript.world {
        public static boolean exists() {
            return mc.theWorld != null;
        }

        /** A mob only this client sees ("Zombie", "Villager" ...); null when the type is unknown. */
        public static Entity spawnMob(String type, double x, double y, double z) {
            return ClientMobs.spawn(type, x, y, z);
        }

        public static Entity spawnMob(String type, Vec3 position) {
            return position == null ? null : spawnMob(type, position.x, position.y, position.z);
        }

        public static boolean removeClientEntity(Entity entity) {
            return ClientMobs.remove(entity);
        }

        public static void clearClientEntities() {
            ClientMobs.clear();
        }

        public static void playSound(String name, float volume, float pitch, double x, double y, double z) {
            mc.theWorld.playSound(x, y, z, name, volume, pitch, false);
        }

        /** Still in the world. */
        public static boolean isValidEntity(Entity entity) {
            return mc.theWorld != null && entity != null && mc.theWorld.loadedEntityList.contains(entity.entity);
        }

        public static String getTitleText() {
            return client.getTitle();
        }

        public static void setTitleText(String title, String subTitle, int timeFadeIn, int displayTime, int timeFadeOut) {
            mc.ingameGUI.displayTitle(title, subTitle, timeFadeIn, displayTime, timeFadeOut);
        }

        public static void clearTitleText() {
            mc.ingameGUI.displayTitle(null, null, -1, -1, -1);
        }
    }

    public static class modules extends RavenScript.modules {
        public modules(String scriptName) {
            super(scriptName);
        }

        /** AimAssist keeps no target between ticks in Atlas: null. */
        public Entity getAimAssistTarget() {
            return null;
        }

        /** LagRange keeps no target between ticks in Atlas: null. */
        public Entity getLagRangeTarget() {
            return null;
        }

        public Vec3 getLagRangePosition() {
            return null;
        }

        public Entity getBacktrackTarget() {
            atlas.module.modules.BackTrack backtrack = backtrack();
            return backtrack == null ? null : Entity.convert(backtrack.getBacktrackTarget());
        }

        /** Where the server has the backtracked target. */
        public Vec3 getBacktrackPosition() {
            atlas.module.modules.BackTrack backtrack = backtrack();
            net.minecraft.util.Vec3 position = backtrack == null ? null : backtrack.getBacktrackPosition();
            return position == null ? null : new Vec3(position);
        }

        private static atlas.module.modules.BackTrack backtrack() {
            Module module = Atlas.moduleManager.getModule(atlas.module.modules.BackTrack.class);
            return module != null && module.isEnabled() ? (atlas.module.modules.BackTrack) module : null;
        }

        private static Property<?> slider(String moduleName, String name) {
            return ScriptSettings.find(find(moduleName), name);
        }

        public double getSliderMin(String moduleName, String name) {
            return bound(slider(moduleName, name), true);
        }

        public double getSliderMax(String moduleName, String name) {
            return bound(slider(moduleName, name), false);
        }

        private static double bound(Property<?> property, boolean min) {
            if (property instanceof FloatProperty) {
                FloatProperty slider = (FloatProperty) property;
                return min ? slider.getMinimum() : slider.getMaximum();
            }
            if (property instanceof IntProperty) {
                IntProperty slider = (IntProperty) property;
                return min ? slider.getMinimum() : slider.getMaximum();
            }
            if (property instanceof ModeProperty) {
                return min ? 0.0 : ((ModeProperty) property).visibleModes().length - 1;
            }
            return 0.0;
        }

        /** A choice slider's chosen option; "" for any other setting. */
        public String getSliderString(String moduleName, String name) {
            Property<?> property = slider(moduleName, name);
            return property instanceof ModeProperty ? ((ModeProperty) property).getModeString() : "";
        }
    }

    public static class gl extends RavenScript.gl {
        public static int getBoundTexture() {
            return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        public static void bindTexture(String resource) {
            mc.getTextureManager().bindTexture(new ResourceLocation(resource));
        }

        public static void bindTextureId(int textureId) {
            GlStateManager.bindTexture(textureId);
        }

        public static void color(double r, double g, double b, double a) {
            GlStateManager.color((float) r, (float) g, (float) b, (float) a);
        }

        /** 0..255 each -- unless all four are 0 or 1, which are taken as they are. */
        public static void color(int r, int g, int b, int a) {
            if (r <= 1 && g <= 1 && b <= 1 && a <= 1) {
                GlStateManager.color(r, g, b, a);
                return;
            }
            GlStateManager.color(r / 255.0F, g / 255.0F, b / 255.0F, a / 255.0F);
        }

        public static void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {
            GlStateManager.colorMask(red, green, blue, alpha);
        }

        public static void depthFunc(int func) {
            GL11.glDepthFunc(func);
        }

        public static void lineWidth(double width) {
            GL11.glLineWidth((float) width);
        }

        public static void normal(double x, double y, double z) {
            GL11.glNormal3d(x, y, z);
        }

        public static void polygonOffset(boolean enabled) {
            if (enabled) {
                GlStateManager.enablePolygonOffset();
            } else {
                GlStateManager.disablePolygonOffset();
            }
        }

        public static void polygonOffset(float factor, float units) {
            GL11.glPolygonOffset(factor, units);
        }

        public static void rotate(double angle, double x, double y, double z) {
            GL11.glRotated(angle, x, y, z);
        }

        public static void texCoord2(double u, double v) {
            GL11.glTexCoord2d(u, v);
        }

        public static void texCoord2(float u, float v) {
            GL11.glTexCoord2f(u, v);
        }

        public static void translate(double x, double y, double z) {
            GL11.glTranslated(x, y, z);
        }

        public static void vertex3(double x, double y, double z) {
            GL11.glVertex3d(x, y, z);
        }

        /** bS 17 also sets the colour back to white. */
        public static void resetColor() {
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    public static class render extends RavenScript.render {
        private static final Map<String, Color[]> THEMES = new HashMap<String, Color[]>();

        static {
            THEMES.put("aurora", new Color[]{new Color(120, 0, 200), new Color(0, 255, 180)});
            THEMES.put("cherry", new Color[]{new Color(255, 200, 200), new Color(243, 58, 106)});
            THEMES.put("cotton_candy", new Color[]{new Color(99, 249, 255), new Color(255, 104, 204)});
            THEMES.put("flare", new Color[]{new Color(241, 39, 17), new Color(245, 175, 25)});
            THEMES.put("flower", new Color[]{new Color(215, 166, 231), new Color(211, 90, 232)});
            THEMES.put("forest", new Color[]{new Color(34, 139, 34), new Color(154, 205, 50)});
            THEMES.put("frost", new Color[]{new Color(253, 253, 253), new Color(210, 225, 230)});
            THEMES.put("gold", new Color[]{new Color(255, 250, 0), new Color(255, 253, 195)});
            THEMES.put("grayscale", new Color[]{new Color(240, 240, 240), new Color(110, 110, 110)});
            THEMES.put("inferno", new Color[]{new Color(255, 51, 0), new Color(50, 0, 0)});
            THEMES.put("royal", new Color[]{new Color(125, 204, 241), new Color(30, 71, 170)});
            THEMES.put("sandstorm", new Color[]{new Color(194, 178, 128), new Color(255, 236, 179)});
            THEMES.put("sky", new Color[]{new Color(140, 254, 250), new Color(14, 210, 247)});
            THEMES.put("vine", new Color[]{new Color(0, 255, 25), new Color(180, 255, 180)});
        }

        /**
         * A Raven theme's colour now (RGB): its two colours swinging into each other, Rainbow round the hues,
         * Default (or an unknown name) Atlas's own HUD colour.
         */
        public static int getThemeRGB(String theme) {
            String key = theme == null ? "" : theme.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            long now = System.currentTimeMillis();
            if ("rainbow".equals(key)) {
                return Color.HSBtoRGB((now % 3000L) / 3000.0F, 0.8F, 1.0F) & 0xFFFFFF;
            }
            Color[] pair = THEMES.get(key);
            if (pair == null) {
                Module hud = Atlas.moduleManager == null ? null : Atlas.moduleManager.getModule(atlas.module.modules.HUD.class);
                return hud == null ? 0xFFFFFF : ((atlas.module.modules.HUD) hud).getColor(now).getRGB() & 0xFFFFFF;
            }
            double t = (Math.sin(now / 1000.0 * Math.PI) + 1.0) / 2.0;
            int r = (int) (pair[0].getRed() + (pair[1].getRed() - pair[0].getRed()) * t);
            int g = (int) (pair[0].getGreen() + (pair[1].getGreen() - pair[0].getGreen()) * t);
            int b = (int) (pair[0].getBlue() + (pair[1].getBlue() - pair[0].getBlue()) * t);
            return (r << 16) | (g << 8) | b;
        }

        public static void beam(Vec3 position, double height, double width, int color, float alpha) {
            beam(position.x, position.y, position.z, height, width, color, alpha);
        }

        /** A see-through column standing on the block at x, y, z (in onRenderWorld). */
        public static void beam(double x, double y, double z, double height, double width, int color, float alpha) {
            x -= mc.getRenderManager().viewerPosX;
            y -= mc.getRenderManager().viewerPosY;
            z -= mc.getRenderManager().viewerPosZ;
            float red = (color >> 16 & 0xFF) / 255.0F;
            float green = (color >> 8 & 0xFF) / 255.0F;
            float blue = (color & 0xFF) / 255.0F;
            double minX = x + 0.5 - width;
            double maxX = x + 0.5 + width;
            double minZ = z + 0.5 - width;
            double maxZ = z + 0.5 + width;
            double top = y + height;
            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.disableCull();
            GlStateManager.depthMask(false);
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer buffer = tessellator.getWorldRenderer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            double[][] sides = {{minX, minZ, maxX, minZ}, {maxX, maxZ, minX, maxZ}, {minX, maxZ, minX, minZ},
                    {maxX, minZ, maxX, maxZ}};
            for (double[] side : sides) {
                buffer.pos(side[0], top, side[1]).color(red, green, blue, alpha).endVertex();
                buffer.pos(side[0], y, side[1]).color(red, green, blue, alpha).endVertex();
                buffer.pos(side[2], y, side[3]).color(red, green, blue, alpha).endVertex();
                buffer.pos(side[2], top, side[3]).color(red, green, blue, alpha).endVertex();
            }
            tessellator.draw();
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            GlStateManager.disableBlend();
            GlStateManager.enableTexture2D();
        }

        public static void text(String text, float x, float y, float scale, int color, boolean shadow) {
            text2d(text, x, y, scale, color, shadow);
        }
    }

    public static class inventory extends RavenScript.inventory {
        /** The slot number under the mouse in an open window, -1 for none. */
        public static int getHoveringSlot() {
            if (!(mc.currentScreen instanceof GuiContainer)) {
                return -1;
            }
            Slot slot = ((GuiContainer) mc.currentScreen).getSlotUnderMouse();
            return slot == null ? -1 : slot.slotNumber;
        }
    }

    public static class keybinds extends RavenScript.keybinds {
        public static int getKeycode(final String key) {
            return getKeyCode(key);
        }
    }

    public static class util extends RavenScript.util {
    }

    public static class config extends RavenScript.config {
    }

    /** bS 17's client-only mobs: spawned with ids no server entity uses, gone with the world or on request. */
    static final class ClientMobs {
        private static final Map<net.minecraft.entity.Entity, Integer> SPAWNED = new HashMap<net.minecraft.entity.Entity, Integer>();
        private static int nextId = -1000000;

        static synchronized Entity spawn(String type, double x, double y, double z) {
            if (mc.theWorld == null || type == null) {
                return null;
            }
            String name = null;
            for (String known : EntityList.getEntityNameList()) {
                if (known.equalsIgnoreCase(type) || known.equalsIgnoreCase(type.replace(" ", ""))) {
                    name = known;
                    break;
                }
            }
            net.minecraft.entity.Entity entity = name == null ? null : EntityList.createEntityByName(name, mc.theWorld);
            if (!(entity instanceof EntityLivingBase)) {
                return null;
            }
            while (mc.theWorld.getEntityByID(nextId) != null) {
                nextId--;
            }
            int id = nextId--;
            entity.setPositionAndRotation(x, y, z, 0.0F, 0.0F);
            mc.theWorld.addEntityToWorld(id, entity);
            SPAWNED.put(entity, id);
            return Entity.convert(entity);
        }

        static synchronized boolean remove(Entity entity) {
            if (entity == null || entity.entity == null) {
                return false;
            }
            Integer id = SPAWNED.remove(entity.entity);
            if (id == null) {
                return false;
            }
            if (mc.theWorld != null) {
                mc.theWorld.removeEntityFromWorld(id);
            }
            return true;
        }

        static synchronized void clear() {
            if (mc.theWorld != null) {
                for (Integer id : SPAWNED.values()) {
                    mc.theWorld.removeEntityFromWorld(id);
                }
            }
            SPAWNED.clear();
        }
    }
}
