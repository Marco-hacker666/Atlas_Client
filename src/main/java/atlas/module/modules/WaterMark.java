package atlas.module.modules;

import atlas.ui.impl.clickgui.atlas.HudPanel;
import atlas.ui.UiMode;
import atlas.ui.hud.HudLayout;
import atlas.property.properties.IntProperty;

import atlas.Atlas;
import atlas.event.EventTarget;
import atlas.events.Render2DEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.TextProperty;
import atlas.util.RenderUtil;
import atlas.util.RenderUtils;
import atlas.util.font.FontManager;
import atlas.util.font.impl.FontRenderer;
import atlas.font.CFontRenderer;
import atlas.font.CFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.util.Color;


import static net.minecraft.init.Items.string;

public class WaterMark extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /* 2026-10-26: the "Vape" mode (another client's wordmark drawn from two
       PNGs) is gone; a config that names it falls back to Jello. */
    public final ModeProperty mode = new ModeProperty("Mode", 3,
            new String[] { "Exhibition", "Modern", "WeedHack", "Jello" }).alias("Vape", "Jello");

    public final TextProperty modernText = new TextProperty("Text", "Atlas", () -> mode.getValue() == 1);
    public final BooleanProperty shadow = new BooleanProperty("Shadow", true, () -> mode.getValue() == 1);
    public final BooleanProperty enableGlow = new BooleanProperty("Glow", true);
    /* Added with the HUD editor (2026-10-05): drag it there, or set it here. 0 keeps the original place. */
    public final IntProperty offsetX = new IntProperty("offset-x", 0, -1000, 1000);
    public final IntProperty offsetY = new IntProperty("offset-y", 0, -1000, 1000);

    public WaterMark() {
        super("WaterMark", false, false);
    }

    private FontRenderer getCustomFont() {
        HUD hud = (HUD) Atlas.moduleManager.getModule("HUD");
        if (hud != null) {
            switch (hud.fontMode.getValue()) {
                case 1:
                    if (FontManager.productSans20 != null)
                        return FontManager.productSans20;
                    break;
                case 2:
                    if (FontManager.regular22 != null)
                        return FontManager.regular22;
                    break;
                case 3:
                    if (FontManager.tenacity20 != null)
                        return FontManager.tenacity20;
                    break;
                case 4:
                    if (FontManager.vision20 != null)
                        return FontManager.vision20;
                    break;
                case 5:
                    if (FontManager.nbpInforma20 != null)
                        return FontManager.nbpInforma20;
                    break;
                case 6:
                    if (FontManager.tahomaBold20 != null)
                        return FontManager.tahomaBold20;
                    break;
            }
        }
        return null;
    }

    private float getStringWidth(String text) {
        FontRenderer fr = getCustomFont();
        if (fr != null) {
            return (float) fr.getStringWidth(text);
        }
        return mc.fontRendererObj.getStringWidth(text);
    }

    private void drawStringWithShadow(String text, float x, float y, int color) {
        FontRenderer fr = getCustomFont();
        if (fr != null) {
            fr.drawStringWithShadow(text, x, y, color);
        } else {
            mc.fontRendererObj.drawStringWithShadow(text, x, y, color);
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled())
            return;

        float[] area = watermarkArea();
        if (area != null) {
            HudLayout.report("WaterMark", "WaterMark", area[0] + this.offsetX.getValue(),
                    area[1] + this.offsetY.getValue(), area[2], area[3],
                    HudLayout.ints(this.offsetX, 1, this.offsetY, 1));
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) this.offsetX.getValue(), (float) this.offsetY.getValue(), 0.0F);
        try {
        switch (mode.getValue()) {
            case 0:
                renderExhibition();
                break;
            case 1:
                renderModern();
                break;
            case 2:
                renderWeedhackWatermark(4, 4);
                break;
            case 3:
                renderJello(4.0f, 4.0f);
                break;
        }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Where the current style draws, before the offset: {x, y, width, height}; null if not known yet. */
    private float[] watermarkArea() {
        switch (mode.getValue()) {
            case 0: {
                float top = getCustomFont() != null ? 3.0f : 2.0f;
                String sample = "Atlas [" + Minecraft.getDebugFPS() + "FPS] [000ms]";
                return new float[]{2.0f, top, getStringWidth(sample), 10.0f};
            }
            case 1: {
                FontRenderer fr = FontManager.nunitoBold48;
                String text = modernText.getValue();
                float width = fr != null ? (float) fr.getStringWidth(text) : mc.fontRendererObj.getStringWidth(text);
                float height = fr != null ? (float) fr.getHeight() : mc.fontRendererObj.FONT_HEIGHT;
                return new float[]{4.0f, 4.0f, width, height};
            }
            case 2:
                return new float[]{4.0f, 4.0f, mc.fontRendererObj.getStringWidth("weedhack premium beta") + 12.0f, 20.0f};
            case 3: {
                float[] size = jelloSize();
                return new float[]{4.0f, 4.0f, size[0], size[1]};
            }
            default:
                return null;
        }
    }

    /** A grey (this file's Color is LWJGL's), swapped to its light-mode counterpart when light mode is on. */
    private static Color neutral(int r, int g, int b) {
        int argb = UiMode.adapt(0xFF000000 | (r << 16) | (g << 8) | b);
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    private void renderModern() {
        FontRenderer fr = FontManager.nunitoBold48;
        boolean customFont = fr != null;

        HUD hud = (HUD) Atlas.moduleManager.getModule("HUD");

        String text = modernText.getValue();
        float x = 4.0f;
        float y = 4.0f;
        long time = System.currentTimeMillis();

        GlStateManager.pushMatrix();

        char[] characters = text.toCharArray();
        float currentX = x;

        for (int i = 0; i < characters.length; i++) {
            String charStr = String.valueOf(characters[i]);

            int color = UiMode.adapt(0xFFFFFFFF);
            if (hud != null) {
                long offset = (long) (i * hud.colorDistance.getValue());
                color = hud.getColor(time, offset).getRGB();
            }

            if (customFont) {
                if (shadow.getValue()) {
                    fr.drawStringWithShadow(charStr, currentX, y, color);
                } else {
                    fr.drawString(charStr, currentX, y, color);
                }
                currentX += (float) fr.getStringWidth(charStr);
            } else {
                mc.fontRendererObj.drawString(charStr, currentX, y, color, shadow.getValue());
                currentX += mc.fontRendererObj.getStringWidth(charStr);
            }
        }

        GlStateManager.popMatrix();
    }

    private void renderExhibition() {
        int fps = Minecraft.getDebugFPS();
        int ping = 0;

        if (mc.thePlayer != null && mc.theWorld != null) {
            if (mc.thePlayer.sendQueue != null
                    && mc.thePlayer.sendQueue.getPlayerInfo(mc.thePlayer.getUniqueID()) != null) {
                ping = mc.thePlayer.sendQueue.getPlayerInfo(mc.thePlayer.getUniqueID()).getResponseTime();
            }
        }

        String exhibitionText = "A";
        String restText = "tlas ";
        String fpsValue = fps + "FPS";
        String pingValue = ping + "ms";

        HUD hud = (HUD) Atlas.moduleManager.modules.get(HUD.class);

        float x = 2.0f;
        float y = 2.0f;

        if (getCustomFont() != null) {
            y += 1.0f;
        }

        GlStateManager.pushMatrix();

        long time = System.currentTimeMillis();
        int rainbowColor = hud != null ? hud.getColor(time).getRGB() : 0xFFFFFFFF;

        drawStringWithShadow(exhibitionText, x, y, rainbowColor);
        float currentX = x + getStringWidth(exhibitionText);

        int whiteColor = UiMode.adapt(0xFFFFFFFF);
        drawStringWithShadow(restText, currentX, y, whiteColor);
        currentX += getStringWidth(restText);

        int grayColor = UiMode.adapt(0xFFAAAAAA);
        drawStringWithShadow("[", currentX, y, grayColor);
        currentX += getStringWidth("[");

        drawStringWithShadow(fpsValue, currentX, y, whiteColor);
        currentX += getStringWidth(fpsValue);

        drawStringWithShadow("]", currentX, y, grayColor);
        currentX += getStringWidth("]");

        String space = " ";
        drawStringWithShadow(space, currentX, y, whiteColor);
        currentX += getStringWidth(space);

        drawStringWithShadow("[", currentX, y, grayColor);
        currentX += getStringWidth("[");

        drawStringWithShadow(pingValue, currentX, y, whiteColor);
        currentX += getStringWidth(pingValue);

        drawStringWithShadow("]", currentX, y, grayColor);

        GlStateManager.popMatrix();
    }

    /*
     * JelloBounce Watermark.svelte: "jello" at 40px with "bounce" beside it at
     * 27px, a shade darker, both at 80% opacity, no background. Here "atlas"
     * alone at the big size, in the thin Product Sans, at GUI scale (half the CSS px).
     */
    private static final float JELLO_BIG = 20.0f;

    private float jelloScale(FontRenderer font, float pixels) {
        double height = font != null ? font.getHeight() : mc.fontRendererObj.FONT_HEIGHT;
        return height <= 0.0 ? 1.0f : (float) (pixels / height);
    }

    private float jelloWidth(FontRenderer font, String text) {
        return font != null ? (float) font.getStringWidth(text) : mc.fontRendererObj.getStringWidth(text);
    }

    /** {width, height} of the wordmark. */
    private float[] jelloSize() {
        FontRenderer font = FontManager.productSansLight;
        float big = jelloScale(font, JELLO_BIG);
        return new float[]{7.0f + jelloWidth(font, "atlas") * big, JELLO_BIG};
    }

    private void jelloText(FontRenderer font, String text, float x, float y, float scale, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(scale, scale, 1.0f);
        if (font != null) {
            font.drawString(text, 0.0, 0.0, color);
        } else {
            mc.fontRendererObj.drawString(text, 0, 0, color);
        }
        GlStateManager.popMatrix();
    }

    private void renderJello(float x, float y) {
        FontRenderer font = FontManager.productSansLight;
        float big = jelloScale(font, JELLO_BIG);
        GlStateManager.enableBlend();
        jelloText(font, "atlas", x + 3.5f, y, big, 0xCCFFFFFF);
    }

    private void renderWeedhackWatermark(float x, float y) {
        String text = "weedhack premium beta";
        float textWidth = mc.fontRendererObj.getStringWidth(text);
        float boxWidth = textWidth + 4;
        float boxHeight = 12;

        RenderUtils.drawRect(x, y, boxWidth + 8, boxHeight + 8, neutral(60, 60, 60));
        RenderUtils.drawRect(x + 1, y + 1, boxWidth + 6, boxHeight + 6, neutral(40, 40, 40));
        RenderUtils.drawRect(x + 2, y + 2, boxWidth + 4, boxHeight + 4, neutral(60, 60, 60));
        RenderUtils.drawRect(x + 3, y + 3, boxWidth + 2, boxHeight + 2, neutral(22, 22, 22));

        float textY = mc.fontRendererObj.FONT_HEIGHT > 12 ? y + (boxHeight - 12) / 2f + 1
                : y + (boxHeight - mc.fontRendererObj.FONT_HEIGHT) / 2f + 3;
        mc.fontRendererObj.drawStringWithShadow(text, x + 5, textY, UiMode.adapt(0xFFFFFFFF));

        float gradient = boxWidth + 2;
        for (int i = 0; i < gradient; i++) {
            float ratio = i / gradient;
            int r = (int) (255 + (255 - 255) * ratio);
            int g = (int) (255 + (0 - 255) * ratio);
            int b = (int) (0 + (255 - 0) * ratio);
            RenderUtils.drawRect(x + 3 + i, y + 3, 1, 1, new Color(r, g, b));
        }
    }

}
