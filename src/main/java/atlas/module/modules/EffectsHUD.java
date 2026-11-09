package atlas.module.modules;

import atlas.ui.impl.clickgui.atlas.HudPanel;
import atlas.ui.UiMode;
import atlas.ui.hud.HudLayout;

import atlas.event.EventTarget;
import atlas.events.Render2DEvent;
import atlas.module.Module;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** A compact, right-aligned list of active potion effects and timers. */
public class EffectsHUD extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final String[] AMPLIFIERS = {"", " II", " III", " IV", " V", " VI", " VII", " VIII"};

    public final IntProperty right = new IntProperty("right", 8, 0, 500);
    public final IntProperty y = new IntProperty("y", 32, 0, 400);
    public final BooleanProperty background = new BooleanProperty("background", true);
    /* 2026-11-09: every HUD element can be resized (here, or with the wheel in the HUD editor). */
    public final FloatProperty scale = new FloatProperty("scale", 1.0F, 0.5F, 2.0F);

    public EffectsHUD() {
        super("EffectsHUD", false, false, "Shows active potion effects and their remaining time.");
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) return;

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active.isEmpty()) {
            if (HudLayout.isEditing()) {
                /* Nothing to show, but still somewhere to put it. */
                int edge = new ScaledResolution(mc).getScaledWidth() - this.right.getValue();
                float[] area = HudLayout.scaledRect(edge, this.y.getValue(), this.scale.getValue(), edge - 93,
                        this.y.getValue() - 2, 96, mc.fontRendererObj.FONT_HEIGHT + 4);
                HudLayout.report("EffectsHUD", "EffectsHUD (no effects)", area[0], area[1], area[2], area[3],
                        HudLayout.ints(this.right, -1, this.y, 1), HudLayout.scale(this.scale));
            }
            return;
        }

        List<PotionEffect> effects = new ArrayList<PotionEffect>(active);
        effects.sort(Comparator.comparingInt(PotionEffect::getPotionID));
        ScaledResolution resolution = new ScaledResolution(mc);
        int rightEdge = resolution.getScaledWidth() - this.right.getValue();
        int drawY = this.y.getValue();
        int widest = 0;
        /* Resized about its top-right corner, the corner it hangs from. */
        float s = this.scale.getValue();
        HudLayout.beginScaled(rightEdge, drawY, s);
        try {
            boolean jello = HudPanel.active() && this.background.getValue();
            if (jello) {
                /* JelloBounce Effects.svelte: the rows sit in one card, 12px
                   corners at the top and bottom, no gaps. */
                int cardWidest = 0;
                int rows = 0;
                for (PotionEffect effect : effects) {
                    int id = effect.getPotionID();
                    if (id < 0 || id >= Potion.potionTypes.length || Potion.potionTypes[id] == null) continue;
                    int amplifier = effect.getAmplifier();
                    String level = amplifier >= 0 && amplifier < AMPLIFIERS.length
                            ? AMPLIFIERS[amplifier] : " " + (amplifier + 1);
                    cardWidest = Math.max(cardWidest, mc.fontRendererObj.getStringWidth(
                            I18n.format(Potion.potionTypes[id].getName()) + level + "  "
                                    + StringUtils.ticksToElapsedTime(effect.getDuration())));
                    rows++;
                }
                if (rows > 0) {
                    HudPanel.rounded(rightEdge - cardWidest - 5, drawY - 3, rightEdge + 5,
                            drawY + rows * (mc.fontRendererObj.FONT_HEIGHT + 3) + 1, HudPanel.cardRadius(), 1.0F, true);
                }
            }
            for (PotionEffect effect : effects) {
                int id = effect.getPotionID();
                if (id < 0 || id >= Potion.potionTypes.length || Potion.potionTypes[id] == null) continue;

                Potion potion = Potion.potionTypes[id];
                String name = I18n.format(potion.getName());
                int amplifier = effect.getAmplifier();
                String level = amplifier >= 0 && amplifier < AMPLIFIERS.length
                        ? AMPLIFIERS[amplifier] : " " + (amplifier + 1);
                String text = name + level + "  " + StringUtils.ticksToElapsedTime(effect.getDuration());
                int textWidth = mc.fontRendererObj.getStringWidth(text);
                widest = Math.max(widest, textWidth);
                int textX = rightEdge - textWidth;
                int color = 0xFF000000 | (potion.getLiquidColor() & 0xFFFFFF);
                if (this.background.getValue() && !jello) {
                    Gui.drawRect(textX - 3, drawY - 2, rightEdge + 3,
                            drawY + mc.fontRendererObj.FONT_HEIGHT + 2, UiMode.adapt(0x70000000));
                }
                mc.fontRendererObj.drawStringWithShadow(text, textX, drawY, color);
                drawY += mc.fontRendererObj.FONT_HEIGHT + 3;
            }
        } finally {
            HudLayout.endScaled();
        }
        if (widest > 0) {
            float[] area = HudLayout.scaledRect(rightEdge, this.y.getValue(), s, rightEdge - widest - 3,
                    this.y.getValue() - 2, widest + 6, drawY - this.y.getValue() + 1);
            HudLayout.report("EffectsHUD", "EffectsHUD", area[0], area[1], area[2], area[3],
                    HudLayout.ints(this.right, -1, this.y, 1), HudLayout.scale(this.scale));
        }
    }
}
