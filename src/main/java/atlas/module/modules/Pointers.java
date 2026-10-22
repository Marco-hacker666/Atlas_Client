package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.Render2DEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Arrowheads around the crosshair pointing at other players (2026-10-19,
 * Slinky's Pointers rebuilt).
 *
 * Each player within range gets a dart on a ring around the screen centre,
 * turned toward where they stand. 3D tilts the ring with your pitch, as if
 * it lay on the ground. Colours: by distance (near colour to far colour
 * across the near-far band), by name tag (team colour), or manual (enemies
 * and friendlies). The farthest are drawn first so the nearest sit on top.
 */
public class Pointers extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** The dart: tip, left wing, notch, right wing (unit size, pointing up). */
    private static final float[][] DART = {{0.0F, -1.0F}, {-1.0F, 1.0F}, {0.0F, 0.5F}, {1.0F, 1.0F}};

    public final IntProperty range = new IntProperty("range", 100, 10, 1000);
    public final IntProperty ignoreFov = new IntProperty("ignore-within-fov", 0, 0, 180);
    public final BooleanProperty hideFriendlies = new BooleanProperty("hide-friendlies", false);
    public final ModeProperty style = new ModeProperty("style", 0, new String[]{"2D", "3D"});
    public final ModeProperty colors = new ModeProperty("colors", 0, new String[]{"Distance", "Name tag", "Manual"});
    public final FloatProperty near = new FloatProperty("near", 5.0F, 0.0F, 100.0F, () -> this.colors.getValue() == 0);
    public final FloatProperty far = new FloatProperty("far", 30.0F, 0.0F, 100.0F, () -> this.colors.getValue() == 0);
    public final ColorProperty nearColor = new ColorProperty("near-color", 0xFF0000, () -> this.colors.getValue() == 0);
    public final ColorProperty farColor = new ColorProperty("far-color", 0x00FF00, () -> this.colors.getValue() == 0);
    public final ColorProperty enemyColor = new ColorProperty("enemy-color", 0xFF0000, () -> this.colors.getValue() == 2);
    public final ColorProperty friendlyColor = new ColorProperty("friendly-color", 0x00FF00,
            () -> this.colors.getValue() == 2);
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.75F, 2.0F);
    public final FloatProperty radius = new FloatProperty("radius", 10.0F, 5.0F, 20.0F);
    public final BooleanProperty distanceRadius = new BooleanProperty("distance-radius", false);

    public Pointers() {
        super("Pointers", false);
    }

    private static double lerp(double now, double before, float partial) {
        return before + (now - before) * partial;
    }

    private boolean friendly(EntityPlayer player) {
        return TeamUtil.isFriend(player) || TeamUtil.isSameTeam(player);
    }

    /** Degrees from the view direction to the point, -180..180. */
    private static float angle(double dx, double dz, float yaw) {
        return MathHelper.wrapAngleTo180_float((float) (Math.atan2(dz, dx) * 180.0 / Math.PI - 90.0) - yaw);
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        Entity view = mc.getRenderViewEntity();
        if (mc.thePlayer == null || mc.theWorld == null || view == null || mc.gameSettings.showDebugInfo) {
            return;
        }
        float partial = event.getPartialTicks();
        double selfX = lerp(view.posX, view.lastTickPosX, partial);
        double selfZ = lerp(view.posZ, view.lastTickPosZ, partial);
        float yaw = view.prevRotationYaw + (view.rotationYaw - view.prevRotationYaw) * partial;
        float pitch = view.prevRotationPitch + (view.rotationPitch - view.prevRotationPitch) * partial;
        double maxRange = this.range.getValue();
        List<EntityPlayer> players = new ArrayList<EntityPlayer>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player == view || player.isDead || TeamUtil.isBot(player)) {
                continue;
            }
            if (this.hideFriendlies.getValue() && friendly(player)) {
                continue;
            }
            double dx = player.posX - selfX;
            double dz = player.posZ - selfZ;
            if (dx * dx + dz * dz > maxRange * maxRange) {
                continue;
            }
            if (this.ignoreFov.getValue() > Math.abs(angle(dx, dz, yaw))) {
                continue;
            }
            players.add(player);
            if (players.size() == 100) {
                break;
            }
        }
        if (players.isEmpty()) {
            return;
        }
        Collections.sort(players, new Comparator<EntityPlayer>() {
            @Override
            public int compare(EntityPlayer a, EntityPlayer b) {
                return Double.compare(mc.thePlayer.getDistanceSqToEntity(b), mc.thePlayer.getDistanceSqToEntity(a));
            }
        });

        ScaledResolution sr = new ScaledResolution(mc);
        GlStateManager.pushMatrix();
        GlStateManager.translate(sr.getScaledWidth() / 2.0F, sr.getScaledHeight() / 2.0F, 0.0F);
        if (this.style.getValue() == 1) {
            GlStateManager.rotate(90.0F - Math.max(30.0F, pitch), 1.0F, 0.0F, 0.0F);
        }
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.0F);
        float span = this.far.getValue() - this.near.getValue();
        if (span == 0.0F) {
            span = 1.0E-5F;
        }
        float size = this.scale.getValue() * 10.0F;
        for (EntityPlayer player : players) {
            double x = lerp(player.posX, player.lastTickPosX, partial) - selfX;
            double z = lerp(player.posZ, player.lastTickPosZ, partial) - selfZ;
            float distance = (float) Math.sqrt(x * x + z * z);
            float factor = Math.max(0.0F, Math.min(1.0F, (distance - this.near.getValue()) / span));
            float ring = this.radius.getValue();
            if (this.distanceRadius.getValue()) {
                ring *= factor + 1.0F;
            }
            int color;
            switch (this.colors.getValue()) {
                case 1:
                    color = TeamUtil.getTeamColor(player, 1.0F).getRGB() & 0xFFFFFF;
                    break;
                case 2:
                    color = (friendly(player) ? this.friendlyColor : this.enemyColor).getValue() & 0xFFFFFF;
                    break;
                default:
                    color = blend(this.nearColor.getValue(), this.farColor.getValue(), factor);
                    break;
            }
            GlStateManager.pushMatrix();
            GlStateManager.rotate(angle(x, z, yaw), 0.0F, 0.0F, 1.0F);
            GlStateManager.scale(size, size, size);
            GlStateManager.translate(0.0F, -ring, 0.0F);
            draw(GL11.GL_TRIANGLE_FAN, color);
            draw(GL11.GL_LINE_LOOP, 0x000000);
            GlStateManager.popMatrix();
        }
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private static void draw(int mode, int rgb) {
        GlStateManager.color((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F, (rgb & 255) / 255.0F, 1.0F);
        GL11.glBegin(mode);
        for (float[] point : DART) {
            GL11.glVertex2f(point[0], point[1]);
        }
        GL11.glEnd();
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * t);
        int g = Math.round((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * t);
        int b = Math.round((from & 255) + ((to & 255) - (from & 255)) * t);
        return r << 16 | g << 8 | b;
    }
}
