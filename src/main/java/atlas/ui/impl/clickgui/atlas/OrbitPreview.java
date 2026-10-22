package atlas.ui.impl.clickgui.atlas;

import atlas.module.modules.TargetOrbit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * TargetOrbit, previewed beside the menu (2026-10-18).
 *
 * The owner: no extra screen and no extra button ("the menu is crowded
 * already"); there is empty screen beside the window, so draw the preview
 * there and let what is being adjusted show at once. So while TargetOrbit is
 * the selected module, the wider of the two gaps beside the window holds this
 * player's own model wearing the mark and the body glow, drawn by the same
 * code as in the world, so every setting shows as it is changed.
 *
 * Drag turns it (it spins slowly until first dragged), the wheel zooms, a
 * click plays a hit (and takes some health, for Lock), a right-click a kill
 * (crystals shatter, then the mark comes back in at full health). Changing the pattern replays the arrival. Nothing is drawn when
 * the gap is too narrow; dragging the window to one side makes room.
 */
final class OrbitPreview {
    private static final Minecraft mc = Minecraft.getMinecraft();
    /** Narrower than this and there is no room to show anything worth seeing. */
    private static final int MIN_GAP = 110;
    private static final float RESPAWN_SECONDS = 0.7F;

    private final TargetOrbit.Fx fx = new TargetOrbit.Fx();
    private final List<TargetOrbit.Burst> bursts = new ArrayList<TargetOrbit.Burst>();
    private float yaw = -28.0F;
    private float pitch = 14.0F;
    private float zoom = 1.0F;
    private boolean everDragged;
    private boolean dragging;
    private int lastX;
    private int lastY;
    private float moved;
    private float respawnIn;
    private int lastPattern = -1;

    private boolean visible;
    private float x1, y1, x2, y2;

    boolean contains(float mx, float my) {
        return this.visible && mx >= this.x1 && mx <= this.x2 && my >= this.y1 && my <= this.y2;
    }

    /** Where the preview goes: the wider gap beside the window, if wide enough. */
    private boolean place(int screenW, int winX, int winY, int winW, int winH) {
        int leftGap = winX;
        int rightGap = screenW - (winX + winW);
        int gap = Math.max(leftGap, rightGap);
        if (gap < MIN_GAP) {
            return false;
        }
        if (rightGap >= leftGap) {
            this.x1 = winX + winW + 6;
            this.x2 = screenW - 6;
        } else {
            this.x1 = 6;
            this.x2 = winX - 6;
        }
        /* As tall as the window and level with it (2026-10-18: the full screen height made the model
           huge on a wide screen, and the title ran into the module list in the corner). */
        this.y1 = winY;
        this.y2 = winY + winH;
        return true;
    }

    void draw(boolean wanted, int screenW, int winX, int winY, int winW, int winH, int mouseX, int mouseY, float dt,
              boolean english, int accent) {
        TargetOrbit orbit = TargetOrbit.instance();
        EntityPlayerSP player = mc.thePlayer;
        this.visible = wanted && orbit != null && player != null && mc.getRenderManager() != null
                && place(screenW, winX, winY, winW, winH);
        if (!this.visible) {
            this.dragging = false;
            return;
        }
        followDrag(mouseX, mouseY);
        if (!this.everDragged) {
            this.yaw += dt * 14.0F;
        }

        int pattern = orbit.style.getValue();
        if (pattern != this.lastPattern) {
            this.lastPattern = pattern;
            this.fx.reset();
            this.bursts.clear();
        }
        if (this.respawnIn > 0.0F) {
            this.respawnIn -= dt;
            this.fx.clock += dt * orbit.speed.getValue();
        } else {
            orbit.step(this.fx, null, dt);
        }

        float w = this.x2 - this.x1;
        float h = this.y2 - this.y1;
        float cx = (this.x1 + this.x2) / 2.0F;
        /* Pixels a block: the model about 45 % of the window's height, at most 100. */
        float scale = Math.min(100.0F, Math.min(w / 3.6F, h / 4.0F)) * this.zoom;
        float feetY = this.y1 + h * 0.5F + scale * 0.95F;

        LiquidFont title = LiquidFont.of(9.0F, true);
        LiquidFont small = LiquidFont.of(7.5F, false);

        title.drawCentred("TargetOrbit · " + orbit.style.getModeString(),
                cx, this.y1 + 14, 0xE6FFFFFF);
        small.drawCentred(english ? "Drag to turn · Click: hit · Right-click: kill · Wheel: zoom"
                : "拖曳旋轉 · 左鍵：打中 · 右鍵：擊殺 · 滾輪：縮放", cx, this.y2 - 12, 0x99FFFFFF);

        int color = orbit.colorFor(player);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(cx, feetY, 300.0F);
            GlStateManager.scale(-scale, scale, scale);
            GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);
            /* From here: x right, y up, z toward the viewer, a block a unit. */
            GlStateManager.rotate(this.pitch, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(this.yaw, 0.0F, 1.0F, 0.0F);

            floor(accent);
            drawPlayer(orbit, player);

            /* Flakes face this view: the scene is turned by pitch, then yaw. */
            orbit.setView(this.yaw, -this.pitch);
            TargetOrbit.begin();
            try {
                orbit.renderEffect(player.width, player.height, color, this.fx, 0.0F);
                orbit.drawBursts(this.bursts, dt, 0.0, 0.0, 0.0);
            } finally {
                TargetOrbit.end();
            }
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
            GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** This player's model facing +z, the way the inventory draws it, glowing if body glow is on. */
    private void drawPlayer(TargetOrbit orbit, final EntityPlayerSP player) {
        float bodyYaw = player.renderYawOffset;
        float prevBodyYaw = player.prevRenderYawOffset;
        float yawNow = player.rotationYaw;
        float prevYaw = player.prevRotationYaw;
        float pitchNow = player.rotationPitch;
        float prevPitch = player.prevRotationPitch;
        float head = player.rotationYawHead;
        float prevHead = player.prevRotationYawHead;
        player.renderYawOffset = player.prevRenderYawOffset = 0.0F;
        player.rotationYaw = player.prevRotationYaw = 0.0F;
        player.rotationPitch = player.prevRotationPitch = 0.0F;
        player.rotationYawHead = player.prevRotationYawHead = 0.0F;
        final RenderManager rm = mc.getRenderManager();
        float viewY = rm.playerViewY;
        try {
            GlStateManager.enableColorMaterial();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableCull();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.pushMatrix();
            GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
            RenderHelper.enableStandardItemLighting();
            GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);
            rm.setPlayerViewY(180.0F);
            rm.setRenderShadow(false);
            Runnable render = new Runnable() {
                @Override
                public void run() {
                    rm.renderEntityWithPosYaw(player, 0.0, 0.0, 0.0, 0.0F, 1.0F);
                }
            };
            if (orbit.bodyGlow.getValue()) {
                int base = orbit.colorFor(player);
                TargetOrbit.withGlow(player, orbit.glowColorFor(base, this.fx), orbit.glowAmountFor(this.fx), render);
            } else {
                TargetOrbit.withGlow(null, 0, 0.0F, render);
            }
            GlStateManager.popMatrix();
        } finally {
            rm.setRenderShadow(true);
            rm.setPlayerViewY(viewY);
            player.renderYawOffset = bodyYaw;
            player.prevRenderYawOffset = prevBodyYaw;
            player.rotationYaw = yawNow;
            player.prevRotationYaw = prevYaw;
            player.rotationPitch = pitchNow;
            player.prevRotationPitch = prevPitch;
            player.rotationYawHead = head;
            player.prevRotationYawHead = prevHead;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.disableColorMaterial();
            GlStateManager.disableLighting();
        }
    }

    /** A soft pool of the accent colour under the feet, so the model stands on something. */
    private static void floor(int accent) {
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        float r = ((accent >> 16) & 0xFF) / 255.0F;
        float g = ((accent >> 8) & 0xFF) / 255.0F;
        float b = (accent & 0xFF) / 255.0F;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r * 0.3F, g * 0.3F, b * 0.3F, 0.45F);
        GL11.glVertex3f(0.0F, 0.0F, 0.0F);
        GL11.glColor4f(r, g, b, 0.0F);
        for (int i = 0; i <= 40; i++) {
            double a = i * Math.PI * 2.0 / 40;
            GL11.glVertex3d(Math.cos(a) * 1.1, 0.0, Math.sin(a) * 1.1);
        }
        GL11.glEnd();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.depthMask(true);
        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ---- input --------------------------------------------------------------

    /** Takes a click inside the preview; false leaves it to the menu. */
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        if (button == 0) {
            this.dragging = true;
            this.lastX = mouseX;
            this.lastY = mouseY;
            this.moved = 0.0F;
        } else if (button == 1) {
            kill();
        }
        return true;
    }

    void mouseReleased() {
        if (this.dragging && this.moved < 3.0F) {
            /* A click, not a drag: play a hit, and take some health for Lock's arc. */
            this.fx.flash = 1.0F;
            this.fx.health = Math.max(0.1F, this.fx.health - 0.15F);
        }
        this.dragging = false;
    }

    /** The wheel over the preview zooms it; false leaves the wheel to the menu. */
    boolean scroll(float mouseX, float mouseY, int wheel) {
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        this.zoom = Math.max(0.6F, Math.min(1.4F, this.zoom * (wheel > 0 ? 1.1F : 1.0F / 1.1F)));
        return true;
    }

    /* Read every frame, like the menu's own drags: mouse events arrive only twenty times a second. */
    private void followDrag(int mouseX, int mouseY) {
        if (!this.dragging) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            mouseReleased();
            return;
        }
        int dx = mouseX - this.lastX;
        int dy = mouseY - this.lastY;
        this.moved += Math.abs(dx) + Math.abs(dy);
        if (this.moved >= 3.0F) {
            this.everDragged = true;
            /* 2026-10-18: 1.2 and 0.8 degrees a pixel turned too fast. */
            this.yaw += dx * 0.45F;
            this.pitch = Math.max(-25.0F, Math.min(70.0F, this.pitch + dy * 0.3F));
        }
        this.lastX = mouseX;
        this.lastY = mouseY;
    }

    private void kill() {
        TargetOrbit orbit = TargetOrbit.instance();
        EntityPlayerSP player = mc.thePlayer;
        if (orbit == null || player == null || this.respawnIn > 0.0F) {
            return;
        }
        orbit.shatterAt(this.bursts, player.width, player.height, orbit.colorFor(player), this.fx, 0.0F);
        double clock = this.fx.clock;
        this.fx.reset();
        this.fx.clock = clock;
        this.respawnIn = RESPAWN_SECONDS;
    }
}
