package atlas.nickresolver;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.function.Predicate;

public final class NametagRenderer {
    private static final float LINE = 9 * 1.15F * 0.016666668F * 1.6F;
    private static final double RANGE_SQ = 64.0 * 64.0;

    private final IdentityResolver resolver;
    private final NickSettings settings;
    private final Predicate<EntityPlayer> hostDraws;

    public NametagRenderer(IdentityResolver resolver, NickSettings settings) {
        this(resolver, settings, null);
    }

    public NametagRenderer(IdentityResolver resolver, NickSettings settings, Predicate<EntityPlayer> hostDraws) {
        this.resolver = resolver;
        this.settings = settings;
        this.hostDraws = hostDraws;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onSpecialsPre(RenderLivingEvent.Specials.Pre<?> event) {
        if (this.settings.nametagMode != NickSettings.NametagMode.INLINE || !(event.entity instanceof EntityOtherPlayerMP)) {
            return;
        }
        EntityOtherPlayerMP player = (EntityOtherPlayerMP) event.entity;
        String tag = this.tagFor(player);
        if (tag == null) {
            return;
        }
        event.setCanceled(true);
        double y = event.y + player.height + 0.5;
        String score = scoreLine(player);
        if (score != null) {
            drawLabel(score, event.x, y, event.z);
            y += LINE;
        }
        drawLabel(player.getDisplayName().getFormattedText() + " " + tag, event.x, y, event.z);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onSpecialsPost(RenderLivingEvent.Specials.Post<?> event) {
        if (this.settings.nametagMode != NickSettings.NametagMode.ABOVE || !(event.entity instanceof EntityOtherPlayerMP)) {
            return;
        }
        EntityOtherPlayerMP player = (EntityOtherPlayerMP) event.entity;
        String tag = this.tagFor(player);
        if (tag == null) {
            return;
        }
        double y = event.y + player.height + 0.5 + LINE;
        if (scoreLine(player) != null) {
            y += LINE;
        }
        drawLabel(tag, event.x, y, event.z);
    }

    private String tagFor(EntityOtherPlayerMP player) {
        if (!this.settings.enabled || !this.settings.showNametag || !shouldRender(player)) {
            return null;
        }
        if (this.hostDraws != null && this.hostDraws.test(player)) {
            return null;
        }
        return IdentityLabels.nametag(this.resolver.forEntity(player), this.settings);
    }

    private static boolean shouldRender(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer self = mc.thePlayer;
        if (self == null || mc.gameSettings.hideGUI || player == mc.getRenderViewEntity()
                || player.isInvisibleToPlayer(self) || player.riddenByEntity != null || player.isSneaking()) {
            return false;
        }
        if (player.getDistanceSqToEntity(self) > RANGE_SQ) {
            return false;
        }
        Team team = player.getTeam();
        Team ours = self.getTeam();
        if (team != null) {
            switch (team.getNameTagVisibility()) {
                case NEVER:
                    return false;
                case HIDE_FOR_OTHER_TEAMS:
                    return ours == null || team.isSameTeam(ours);
                case HIDE_FOR_OWN_TEAM:
                    return ours == null || !team.isSameTeam(ours);
                default:
                    return true;
            }
        }
        return true;
    }

    private static String scoreLine(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || player.getDistanceSqToEntity(mc.thePlayer) >= 100.0) {
            return null;
        }
        Scoreboard scoreboard = player.getWorldScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(2);
        if (objective == null) {
            return null;
        }
        Score score = scoreboard.getValueFromObjective(player.getName(), objective);
        return score.getScorePoints() + " " + objective.getDisplayName();
    }

    private static void drawLabel(String text, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        RenderManager rm = mc.getRenderManager();
        float scale = 0.016666668F * 1.6F;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        int half = font.getStringWidth(text) / 2;
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer wr = tessellator.getWorldRenderer();
        GlStateManager.disableTexture2D();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(-half - 1, -1, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        wr.pos(-half - 1, 8, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        wr.pos(half + 1, 8, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        wr.pos(half + 1, -1, 0.0).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();

        font.drawString(text, -half, 0, 0x20FFFFFF);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        font.drawString(text, -half, 0, 0xFFFFFFFF);

        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }
}
