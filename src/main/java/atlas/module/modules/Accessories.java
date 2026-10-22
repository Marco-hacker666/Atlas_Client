package atlas.module.modules;

import atlas.event.EventTarget;
import atlas.events.Render3DEvent;
import atlas.mixin.IAccessorRenderManager;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.ModeProperty;
import atlas.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;

/**
 * Headwear and wings for the players you can see (adapted from CrewX's
 * Accessories, 2026-10-11).
 *
 * Everything is drawn from coloured geometry rather than textures, so the
 * module carries no assets of its own and the two colours are settings. Each
 * model is built in the wearer's own frame: local +X is their right and local
 * +Z is the way they are facing, which is the frame the body yaw rotation
 * below produces, so the pieces turn with the head and sit right whether the
 * player is standing or sneaking.
 *
 * This is a cosmetic only the wearer sees: nothing is sent to the server, and
 * with "all-players" off it is drawn on you alone.
 */
public class Accessories extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** Headwear styles, in menu order; index 0 is off. */
    private static final String[] HEADWEAR = {
            "OFF", "HALO", "CROWN", "BUNNY EARS", "CAT EARS", "DEMON HORNS", "STRAW HAT", "CAP"
    };
    private static final int HAT_SEGMENTS = 24;

    public final ModeProperty headwear = new ModeProperty("headwear", 0, HEADWEAR);
    public final ColorProperty headColor = new ColorProperty("head-color", 0xFFD24A, () -> this.headwear.getValue() != 0);
    public final BooleanProperty wings = new BooleanProperty("wings", false);
    public final ColorProperty wingColor = new ColorProperty("wing-color", 0xA8323E, () -> this.wings.getValue());
    public final BooleanProperty allPlayers = new BooleanProperty("all-players", false);

    public Accessories() {
        super("Accessories", false, false, "Draws headwear and wings on players.");
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        int style = this.headwear.getValue();
        boolean drawWings = this.wings.getValue();
        if (style == 0 && !drawWings) {
            return;
        }
        float partialTicks = event.getPartialTicks();
        double renderX = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosX();
        double renderY = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosY();
        double renderZ = ((IAccessorRenderManager) mc.getRenderManager()).getRenderPosZ();
        long now = System.currentTimeMillis();

        RenderUtil.enableRenderState();
        if (this.allPlayers.getValue()) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                this.draw(player, style, drawWings, partialTicks, now, renderX, renderY, renderZ);
            }
        } else {
            this.draw(mc.thePlayer, style, drawWings, partialTicks, now, renderX, renderY, renderZ);
        }
        RenderUtil.disableRenderState();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void draw(EntityPlayer player, int style, boolean drawWings, float partialTicks, long now,
                      double renderX, double renderY, double renderZ) {
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        float bodyYaw = player.prevRenderYawOffset
                + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
        double headTop = y + player.getEntityBoundingBox().maxY - player.getEntityBoundingBox().minY;

        GL11.glPushMatrix();
        GL11.glTranslated(x - renderX, y - renderY, z - renderZ);
        GL11.glRotatef(-bodyYaw, 0.0F, 1.0F, 0.0F);
        if (style != 0) {
            int rgb = this.headColor.getValue();
            this.headModel(style, headTop, (rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F);
        }
        if (drawWings) {
            int rgb = this.wingColor.getValue();
            this.wingsModel(headTop, (rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, now);
        }
        GL11.glPopMatrix();
    }

    /** One style of headwear, sitting on the head top the caller measured. */
    private void headModel(int style, double top, float red, float green, float blue) {
        switch (style) {
            case 1: // halo: a ring floating above the head
                this.ring(top + 0.14, 0.26, 0.34, red, green, blue, 0.95F);
                break;
            case 2: { // crown: a band with five points
                this.band(top + 0.02, top + 0.14, 0.30, red, green, blue);
                for (int i = 0; i < 5; i++) {
                    double angle = Math.PI * 2.0 * i / 5.0;
                    double px = Math.sin(angle) * 0.30;
                    double pz = Math.cos(angle) * 0.30;
                    this.box(px - 0.05, top + 0.14, pz - 0.05, px + 0.05, top + 0.28, pz + 0.05, red, green, blue, 1.0F);
                }
                break;
            }
            case 3: // bunny ears
                this.box(-0.17, top + 0.02, -0.03, -0.06, top + 0.44, 0.03, red, green, blue, 1.0F);
                this.box(0.06, top + 0.02, -0.03, 0.17, top + 0.44, 0.03, red, green, blue, 1.0F);
                break;
            case 4: // cat ears
                this.triangle(top + 0.02, -0.20, -0.02, 0.06, top + 0.26, red, green, blue);
                this.triangle(top + 0.02, 0.20, 0.02, 0.06, top + 0.26, red, green, blue);
                break;
            case 5: // demon horns, leaning out
                this.horn(-1.0, top, red, green, blue);
                this.horn(1.0, top, red, green, blue);
                break;
            case 6: // straw hat: a wide brim and a small cone
                this.ring(top + 0.02, 0.30, 0.62, red, green, blue, 1.0F);
                this.cone(top + 0.02, 0.30, 0.26, red, green, blue);
                break;
            case 7: // cap: a shallow dome and a brim over the face
                this.box(-0.31, top - 0.04, -0.31, 0.31, top + 0.14, 0.31, red, green, blue, 1.0F);
                this.quad(-0.31, top + 0.02, 0.31, 0.31, top + 0.02, 0.31,
                        0.31, top + 0.02, 0.62, -0.31, top + 0.02, 0.62, red, green, blue, 0.9F);
                break;
            default:
                break;
        }
    }

    /** Two wings that flap from the shoulders, behind the wearer. */
    private void wingsModel(double top, float red, float green, float blue, long now) {
        float flap = (float) Math.sin(now / 260.0) * 12.0F;
        for (int side = -1; side <= 1; side += 2) {
            GL11.glPushMatrix();
            GL11.glTranslated(side * 0.14, top - 0.55, -0.12);
            GL11.glRotatef(flap, 0.0F, 0.0F, 1.0F);
            GL11.glRotatef(side * 18.0F, 0.0F, 0.0F, 1.0F);
            float alpha = 0.85F;
            this.quad(0.0, 0.0, 0.0, side * 0.34, 0.34, -0.28, side * 0.62, 0.12, -0.50, side * 0.24, -0.12, -0.16,
                    red, green, blue, alpha);
            this.quad(side * 0.24, -0.12, -0.16, side * 0.62, 0.12, -0.50, side * 0.58, -0.26, -0.44,
                    side * 0.20, -0.34, -0.14, red * 0.8F, green * 0.8F, blue * 0.8F, alpha);
            this.quad(side * 0.20, -0.34, -0.14, side * 0.58, -0.26, -0.44, side * 0.34, -0.52, -0.30,
                    side * 0.08, -0.46, -0.08, red, green, blue, alpha);
            GL11.glPopMatrix();
        }
    }

    /** A box from two corners, every face drawn. */
    private void box(double x0, double y0, double z0, double x1, double y1, double z1,
                     float red, float green, float blue, float alpha) {
        this.quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, red, green, blue, alpha);
        this.quad(x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, red, green, blue, alpha);
        this.quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, red, green, blue, alpha);
        this.quad(x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1, red, green, blue, alpha);
        this.quad(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, red, green, blue, alpha);
        this.quad(x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, red, green, blue, alpha);
    }

    /** A flat ring in the XZ plane, for brims and halos. */
    private void ring(double y, double inner, double outer, float red, float green, float blue, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= HAT_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / HAT_SEGMENTS;
            double sin = Math.sin(angle);
            double cos = Math.cos(angle);
            GL11.glVertex3d(sin * inner, y, cos * inner);
            GL11.glVertex3d(sin * outer, y, cos * outer);
        }
        GL11.glEnd();
    }

    /** A cylinder wall between two heights. */
    private void band(double bottom, double top, double radius, float red, float green, float blue) {
        GL11.glColor4f(red, green, blue, 1.0F);
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        for (int i = 0; i <= HAT_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / HAT_SEGMENTS;
            double sin = Math.sin(angle) * radius;
            double cos = Math.cos(angle) * radius;
            GL11.glVertex3d(sin, bottom, cos);
            GL11.glVertex3d(sin, top, cos);
        }
        GL11.glEnd();
    }

    /** A cone with its point above a circular base. */
    private void cone(double base, double radius, double height, float red, float green, float blue) {
        GL11.glColor4f(red, green, blue, 1.0F);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex3d(0.0, base + height, 0.0);
        for (int i = 0; i <= HAT_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / HAT_SEGMENTS;
            GL11.glVertex3d(Math.sin(angle) * radius, base, Math.cos(angle) * radius);
        }
        GL11.glEnd();
    }

    /** An upright ear: a triangle standing on the head. */
    private void triangle(double base, double x, double halfWidth, double height, double top,
                          float red, float green, float blue) {
        this.quad(x - halfWidth, base, 0.0, x + halfWidth, base, 0.0, x, top, 0.0, x, top, 0.0,
                red, green, blue, 1.0F);
        this.quad(x + halfWidth, base, 0.0, x - halfWidth, base, 0.0, x, top, 0.0, x, top, 0.0,
                red, green, blue, 1.0F);
    }

    /** A horn: a box leaning outward, narrower at the tip. */
    private void horn(double side, double top, float red, float green, float blue) {
        GL11.glPushMatrix();
        GL11.glTranslated(side * 0.16, top + 0.02, 0.0);
        GL11.glRotatef((float) (side * 28.0), 0.0F, 0.0F, 1.0F);
        this.quad(-0.05, 0.0, -0.05, 0.05, 0.0, -0.05, 0.02, 0.30, -0.02, -0.02, 0.30, -0.02,
                red, green, blue, 1.0F);
        this.quad(-0.05, 0.0, 0.05, -0.02, 0.30, 0.02, 0.02, 0.30, 0.02, 0.05, 0.0, 0.05,
                red, green, blue, 1.0F);
        this.quad(-0.05, 0.0, -0.05, -0.02, 0.30, -0.02, -0.02, 0.30, 0.02, -0.05, 0.0, 0.05,
                red, green, blue, 1.0F);
        this.quad(0.05, 0.0, 0.05, 0.02, 0.30, 0.02, 0.02, 0.30, -0.02, 0.05, 0.0, -0.05,
                red, green, blue, 1.0F);
        GL11.glPopMatrix();
    }

    private void quad(double x1, double y1, double z1, double x2, double y2, double z2,
                      double x3, double y3, double z3, double x4, double y4, double z4,
                      float red, float green, float blue, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
        GL11.glVertex3d(x3, y3, z3);
        GL11.glVertex3d(x4, y4, z4);
        GL11.glEnd();
    }

    @Override
    public String[] getSuffix() {
        if (this.headwear.getValue() == 0) {
            return new String[]{this.wings.getValue() ? "Wings" : "Off"};
        }
        String style = this.headwear.getModeString();
        return this.wings.getValue() ? new String[]{style, "Wings"} : new String[]{style};
    }
}
